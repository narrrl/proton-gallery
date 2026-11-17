//! Phone backup: the system media index in the Photos timeline, the check
//! against Proton Photos, and uploads. The host reads files; Rust never opens
//! a phone path itself.
use super::*;
use pg_core::phone::{self as core, BackupState};
use proton_drive_rs::{
    PhotoTag, PhotoTagsUpdate, PhotoUploadMetadata, ProtonPhotosClient, Thumbnail, ThumbnailType,
};
use proton_sdk::api::ResponseCode;
use proton_sdk::error::ProtonError;
use std::io::Read;

/// Names checked per name-collision request round.
const CHECK_BATCH: usize = 600;

#[derive(Clone, uniffi::Record)]
pub struct PhoneMedia {
    pub id: String,
    pub name: String,
    pub mime: String,
    pub folder: String,
    pub folder_name: String,
    /// Seconds since the epoch.
    pub taken: i64,
    pub modified: i64,
    pub size_bytes: u64,
    pub width: u32,
    pub height: u32,
    pub favorite: bool,
}

#[derive(Clone, uniffi::Record)]
pub struct PhoneFolder {
    pub id: String,
    pub name: String,
    pub count: u64,
    pub not_backed_up: u64,
    pub backup: bool,
}

#[derive(Clone, uniffi::Record)]
pub struct PhoneHash {
    pub id: String,
    /// Lowercase hex SHA-1 of the file's contents.
    pub sha1: String,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum UploadOutcome {
    Uploaded,
    /// Proton already had the same photo; nothing was sent.
    AlreadyBackedUp,
}

/// The file's contents, read by the host in chunks.
#[uniffi::export(callback_interface)]
pub trait MediaReader: Send + Sync {
    /// Up to `max` bytes; empty at the end of the file.
    fn read(&self, max: u32) -> Result<Vec<u8>, GalleryError>;
}

struct HostReader {
    source: Box<dyn MediaReader>,
    chunk: Vec<u8>,
    at: usize,
}

impl Read for HostReader {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if self.at == self.chunk.len() {
            self.chunk = self
                .source
                .read(256 * 1024)
                .map_err(|e| std::io::Error::other(e.to_string()))?;
            self.at = 0;
            if self.chunk.is_empty() {
                return Ok(0);
            }
        }
        let n = out.len().min(self.chunk.len() - self.at);
        out[..n].copy_from_slice(&self.chunk[self.at..self.at + n]);
        self.at += n;
        Ok(n)
    }
}

fn remote_failure(what: &'static str) -> impl Fn(ProtonError) -> GalleryError {
    move |e| {
        tracing::warn!("{what} failed: {}", pg_drive::auth::cause_chain(&e));
        GalleryError::Operation {
            detail: format!("Couldn’t {what}. Check your connection and try again."),
        }
    }
}

/// `IMG_1.jpg`, then `IMG_1 (1).jpg`, `IMG_1 (2).jpg`, …
fn numbered(name: &str, n: u32) -> String {
    if n == 0 {
        return name.to_owned();
    }
    match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() => format!("{stem} ({n}).{ext}"),
        _ => format!("{name} ({n})"),
    }
}

#[uniffi::export]
impl GalleryEngine {
    /// Record one batch of a scan of the phone's media.
    pub fn phone_index(&self, scan: i64, items: Vec<PhoneMedia>) -> Result<(), GalleryError> {
        let items = items
            .into_iter()
            .map(|m| core::PhoneMedia {
                id: m.id,
                name: m.name,
                mime: m.mime,
                folder: m.folder,
                folder_name: m.folder_name,
                taken: m.taken,
                modified: m.modified,
                size_bytes: m.size_bytes,
                width: m.width,
                height: m.height,
                favorite: m.favorite,
            })
            .collect();
        Ok(self
            .runtime
            .block_on(self.catalog.phone_upsert(scan, items))?)
    }

    /// End a complete scan; media it did not see is gone from the phone.
    pub fn phone_finish(&self, scan: i64) -> Result<u64, GalleryError> {
        Ok(self.runtime.block_on(self.catalog.phone_finish(scan))?)
    }

    pub fn phone_folders(&self) -> Result<Vec<PhoneFolder>, GalleryError> {
        let folders = self
            .runtime
            .block_on(self.catalog.phone_folders(self.auth.account_id()))?;
        Ok(folders
            .into_iter()
            .map(|f| PhoneFolder {
                id: f.id,
                name: f.name,
                count: f.count,
                not_backed_up: f.not_backed_up,
                backup: f.backup,
            })
            .collect())
    }

    pub fn set_backup_folder(&self, folder: String, enabled: bool) -> Result<(), GalleryError> {
        Ok(self
            .runtime
            .block_on(self.catalog.set_backup_folder(folder, enabled))?)
    }

    /// Media in backup folders that Proton does not hold yet, newest first.
    pub fn backup_queue(&self, limit: u32) -> Result<Vec<String>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(vec![]);
        };
        Ok(self.runtime.block_on(
            self.catalog
                .backup_queue(account, limit.min(10_000) as usize),
        )?)
    }

    pub fn set_phone_hashes(&self, hashes: Vec<PhoneHash>) -> Result<(), GalleryError> {
        let hashes = hashes.into_iter().map(|h| (h.id, h.sha1)).collect();
        Ok(self
            .runtime
            .block_on(self.catalog.phone_set_hashes(hashes))?)
    }

    /// One round of comparing phone media with Proton Photos. Names are
    /// compared first; only same-named files need their contents hashed.
    /// Returns the phone items to hash before the next round; empty when done.
    pub fn check_backup(&self) -> Result<Vec<String>, GalleryError> {
        self.runtime.block_on(async {
            let _guard = self.backup_check.lock().await;
            let account = self.auth.account().await?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            let id = account.id.clone();
            let work = async {
                loop {
                    let unchecked = self
                        .catalog
                        .phone_unchecked(id.clone(), CHECK_BATCH)
                        .await?;
                    if unchecked.is_empty() {
                        break;
                    }
                    let names: Vec<String> =
                        unchecked.iter().map(|(_, name)| name.clone()).collect();
                    let collisions = photos
                        .name_collisions(&names)
                        .await
                        .map_err(remote_failure("compare with Proton Photos"))?;
                    let marks = unchecked
                        .into_iter()
                        .enumerate()
                        .map(|(i, (node, _))| {
                            (
                                node,
                                if collisions.get(i).copied().unwrap_or(true) {
                                    BackupState::Maybe
                                } else {
                                    BackupState::Missing
                                },
                            )
                        })
                        .collect();
                    self.catalog.phone_mark(id.clone(), marks).await?;
                }
                loop {
                    let hashed = self.catalog.phone_hashed(id.clone(), 150).await?;
                    if hashed.is_empty() {
                        break;
                    }
                    let query: Vec<(String, String)> = hashed
                        .iter()
                        .map(|(_, name, sha1)| (name.clone(), sha1.clone()))
                        .collect();
                    let found = photos
                        .find_duplicates_many(&query)
                        .await
                        .map_err(remote_failure("compare with Proton Photos"))?;
                    let mut marks = Vec::with_capacity(hashed.len());
                    for (i, (node, ..)) in hashed.into_iter().enumerate() {
                        marks.push((
                            node,
                            match found.get(i).and_then(|matches| matches.first()) {
                                Some(uid) => BackupState::BackedUp {
                                    volume: pg_core::model::VolumeId::new(uid.volume_id.as_str())?,
                                    node: pg_core::model::NodeId::new(uid.link_id.as_str())?,
                                },
                                None => BackupState::Missing,
                            },
                        ));
                    }
                    self.catalog.phone_mark(id.clone(), marks).await?;
                }
                Ok::<_, GalleryError>(self.catalog.phone_unhashed(id.clone(), 200).await?)
            };
            tokio::select! { biased;
                _ = account.cancel.cancelled() => Err(pg_core::Error::Cancelled.into()),
                result = work => result,
            }
        })
    }

    /// Upload one phone item to Proton Photos, unless Proton already has it.
    /// `sha1` is the contents digest the host computed; `thumbnail` a small
    /// JPEG (about 512 px) that Proton's apps show in their grids.
    pub fn upload_phone(
        &self,
        id: String,
        sha1: String,
        reader: Box<dyn MediaReader>,
        thumbnail: Option<Vec<u8>>,
    ) -> Result<UploadOutcome, GalleryError> {
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            self.catalog
                .phone_set_hashes(vec![(id.clone(), sha1.clone())])
                .await?;
            let entry = self
                .catalog
                .phone_entry(id.clone(), Some(account.id.clone()))
                .await?
                .ok_or_else(|| GalleryError::Operation {
                    detail: "This item is no longer on the phone.".into(),
                })?;
            if matches!(entry.state, BackupState::BackedUp { .. }) {
                return Ok(UploadOutcome::AlreadyBackedUp);
            }
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            let work = async {
                let found = photos
                    .find_duplicates_many(&[(entry.name.clone(), sha1.clone())])
                    .await
                    .map_err(remote_failure("compare with Proton Photos"))?;
                if let Some(uid) = found.first().and_then(|m| m.first()).cloned() {
                    self.after_upload(&photos, &account.id, &id, uid, entry.taken)
                        .await?;
                    return Ok(UploadOutcome::AlreadyBackedUp);
                }
                // Proton keeps one photo per name. A different photo with this
                // name is already there (checked above), so pick a free name.
                let mut name = entry.name.clone();
                for n in 0..20 {
                    let candidate = numbered(&entry.name, n);
                    let taken = photos
                        .name_collisions(std::slice::from_ref(&candidate))
                        .await
                        .map_err(remote_failure("prepare the upload"))?;
                    name = candidate;
                    if !taken.first().copied().unwrap_or(false) {
                        break;
                    }
                }
                let mut tags = Vec::new();
                if entry.mime.starts_with("video/") {
                    tags.push(PhotoTag::Video);
                }
                if entry.folder_name.eq_ignore_ascii_case("screenshots") {
                    tags.push(PhotoTag::Screenshot);
                }
                let metadata = PhotoUploadMetadata {
                    capture_time: Some(entry.taken),
                    tags,
                    // A draft under this name is our own interrupted upload.
                    replace_existing_draft: true,
                    ..Default::default()
                };
                let thumbnails = thumbnail
                    .filter(|t| !t.is_empty() && t.len() <= 1024 * 1024)
                    .and_then(|t| Thumbnail::new(ThumbnailType::Thumbnail, t).ok())
                    .into_iter()
                    .collect();
                let reader = HostReader {
                    source: reader,
                    chunk: Vec::new(),
                    at: 0,
                };
                let uid = photos
                    .upload_photo_from(
                        &name,
                        &entry.mime,
                        reader,
                        entry.size_bytes as i64,
                        thumbnails,
                        metadata,
                        false,
                    )
                    .await
                    .map_err(|e| match &e {
                        ProtonError::Api(api) if api.code == ResponseCode::AlreadyExists => {
                            GalleryError::Operation {
                                detail:
                                    "Another upload of this photo is in progress. Try again later."
                                        .into(),
                            }
                        }
                        _ => remote_failure("upload this photo")(e),
                    })?;
                if entry.favorite {
                    let favorite = PhotoTagsUpdate {
                        node_uid: uid.clone(),
                        tags_to_add: vec![PhotoTag::Favorite],
                        tags_to_remove: vec![],
                    };
                    if let Err(e) = photos.update_photos(&[favorite]).await {
                        tracing::warn!(
                            "marking an uploaded favorite failed: {}",
                            pg_drive::auth::cause_chain(&e)
                        );
                    }
                }
                self.after_upload(&photos, &account.id, &id, uid, entry.taken)
                    .await?;
                Ok(UploadOutcome::Uploaded)
            };
            tokio::select! { biased;
                _ = account.cancel.cancelled() => Err(pg_core::Error::Cancelled.into()),
                result = work => result,
            }
        })
    }
}

impl GalleryEngine {
    /// Remember the Proton copy, and show it in the timeline right away.
    async fn after_upload(
        &self,
        photos: &ProtonPhotosClient,
        account: &pg_core::model::AccountId,
        phone: &str,
        uid: proton_sdk::ids::NodeUid,
        taken: i64,
    ) -> Result<(), GalleryError> {
        let volume = pg_core::model::VolumeId::new(uid.volume_id.as_str())?;
        let node = pg_core::model::NodeId::new(uid.link_id.as_str())?;
        self.catalog
            .phone_mark(
                account.clone(),
                vec![(phone.to_owned(), BackupState::BackedUp { volume, node })],
            )
            .await?;
        let Some(source) = self.photo_source().await? else {
            return Ok(());
        };
        // Best effort: the next Photos sync adds it otherwise.
        if let Ok(Some(remote)) = photos.get_node(&uid).await
            && let Ok(Some(asset)) = pg_drive::photos::photo_asset(account, remote, taken)
        {
            let key = asset.key.clone();
            self.catalog.add_to_source(source, asset).await?;
            let _ = self.catalog.copy_shape(core::phone_key(phone)?, key).await;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn phone_media_fills_the_photos_timeline_before_sign_in() {
        let dir = tempfile::tempdir().unwrap();
        let engine = GalleryEngine::open(dir.path().to_str().unwrap().into()).unwrap();
        let item = |id: &str, taken| PhoneMedia {
            id: id.into(),
            name: format!("{id}.jpg"),
            mime: "image/jpeg".into(),
            folder: "camera".into(),
            folder_name: "Camera".into(),
            taken,
            modified: taken,
            size_bytes: 10,
            width: 3,
            height: 2,
            favorite: false,
        };
        engine
            .phone_index(7, vec![item("41", 10), item("42", 20)])
            .unwrap();
        assert_eq!(engine.phone_finish(7).unwrap(), 0);
        let page = engine
            .page_at(LibraryView::Photos, None, false, 90)
            .unwrap();
        let ids: Vec<_> = page
            .items
            .iter()
            .map(|i| i.phone_id.clone().unwrap())
            .collect();
        assert_eq!(ids, ["42", "41"]);
        assert!(
            page.items
                .iter()
                .all(|i| !i.backed_up && i.remote_key.is_none())
        );
        assert_eq!(page.items[0].aspect_ratio, Some(1.5));
        assert_eq!(engine.count_at(LibraryView::Photos).unwrap(), 2);
        assert!(engine.backup_queue(10).unwrap().is_empty());
        assert!(engine.check_backup().is_err());
        engine.set_backup_folder("camera".into(), true).unwrap();
        assert!(engine.phone_folders().unwrap()[0].backup);
    }

    #[test]
    fn a_taken_name_gets_a_number_before_its_extension() {
        assert_eq!(super::numbered("IMG_1.jpg", 0), "IMG_1.jpg");
        assert_eq!(super::numbered("IMG_1.jpg", 2), "IMG_1 (2).jpg");
        assert_eq!(super::numbered("archive.tar.gz", 1), "archive.tar (1).gz");
        assert_eq!(super::numbered(".hidden", 1), ".hidden (1)");
    }
}
