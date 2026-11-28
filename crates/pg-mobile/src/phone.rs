//! Phone backup: the system media index in the Photos timeline, the check
//! against Proton Photos, and uploads. The host reads files; Rust never opens
//! a phone path itself.
use super::*;
use proton_drive_rs::{
    PhotoTag, PhotoTagsUpdate, PhotoUploadMetadata, ProtonPhotosClient, Thumbnail, ThumbnailType,
};
use proton_gallery_rs::phone::{self as core, BackupState};
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

/// The outcome of [`GalleryEngine::phone_reconcile`].
#[derive(Clone, Debug, uniffi::Record)]
pub struct PhoneReconcile {
    pub removed: u64,
    pub unknown: Vec<String>,
}

/// A phone item to read: its identifier, and whether it is a video.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PhoneRef {
    pub id: String,
    pub is_video: bool,
}

fn refs(items: Vec<(String, bool)>) -> Vec<PhoneRef> {
    items
        .into_iter()
        .map(|(id, is_video)| PhoneRef { id, is_video })
        .collect()
}

/// What freeing up space would remove from the phone.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct FreeUpSummary {
    pub count: u64,
    pub bytes: u64,
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
        tracing::warn!(
            "{what} failed: {}",
            proton_gallery_drive_rs::auth::cause_chain(&e)
        );
        GalleryError::Operation {
            detail: format!("Couldn’t {what}. Check your connection and try again."),
        }
    }
}

/// Why an upload failed, in words that suggest what to do about it.
fn upload_failure(error: ProtonError) -> GalleryError {
    tracing::warn!(
        "upload failed: {}",
        proton_gallery_drive_rs::auth::cause_chain(&error)
    );
    let detail = match &error {
        ProtonError::Transport(_) => {
            "Couldn’t reach Proton during the upload. It will try again.".to_owned()
        }
        ProtonError::Api(api) if api.code == ResponseCode::TooManyRequests => {
            "Proton asked to slow down. The upload will try again later.".to_owned()
        }
        ProtonError::Api(api) if api.http_status == 413 || api.http_status == 507 => {
            "There isn’t enough space in your Proton account for this file.".to_owned()
        }
        ProtonError::Api(api) if !api.message.trim().is_empty() => {
            format!("Proton didn’t accept this file: {}", api.message.trim())
        }
        ProtonError::Api(api) => format!(
            "Proton didn’t accept this file (error {}).",
            api.http_status
        ),
        ProtonError::Crypto(_) => "This file couldn’t be encrypted for upload.".to_owned(),
        ProtonError::KeysLocked => {
            "Your account keys are locked. Sign in again to back up.".to_owned()
        }
        ProtonError::Serialization(_) | ProtonError::InvalidOperation(_) => {
            format!("Couldn’t upload this file: {error}")
        }
    };
    GalleryError::Operation { detail }
}

/// Names one upload may take: the phone's own, then `(1)` to `(19)`.
const NAME_CANDIDATES: u32 = 20;

/// Where one phone item goes, from what Proton Photos already holds.
#[derive(Debug, PartialEq, Eq)]
enum Placement {
    /// A photo with these bytes is there already, perhaps our own upload
    /// whose reply was lost.
    Recover(proton_sdk::ids::NodeUid),
    /// Upload under this free name.
    Upload(String),
    /// Every candidate name holds different bytes.
    NoFreeName,
}

/// `occupied[n]` says whether `candidates[n]` is taken, `matches[n]` lists
/// the photos under that name with the item's bytes. A match wins over a
/// free name, the lowest number first.
fn place(
    candidates: &[String],
    occupied: &[bool],
    matches: Vec<Vec<proton_sdk::ids::NodeUid>>,
) -> Placement {
    if let Some(uid) = matches.into_iter().find_map(|m| m.into_iter().next()) {
        return Placement::Recover(uid);
    }
    candidates
        .iter()
        .zip(occupied.iter().copied().chain(std::iter::repeat(false)))
        .find(|(_, taken)| !taken)
        .map_or(Placement::NoFreeName, |(name, _)| {
            Placement::Upload(name.clone())
        })
}

/// Tag `uid` a favorite. Adding a tag twice is harmless, so a recovered
/// upload gets it too.
async fn mark_favorite(photos: &ProtonPhotosClient, uid: &proton_sdk::ids::NodeUid) {
    let favorite = PhotoTagsUpdate {
        node_uid: uid.clone(),
        tags_to_add: vec![PhotoTag::Favorite],
        tags_to_remove: vec![],
    };
    if let Err(e) = photos.update_photos(&[favorite]).await {
        tracing::warn!(
            "marking an uploaded favorite failed: {}",
            proton_gallery_drive_rs::auth::cause_chain(&e)
        );
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

    /// Compare the catalog with every identifier the phone shows now: items
    /// that are gone are forgotten, and identifiers the catalog does not know
    /// are returned for the host to read in full.
    pub fn phone_reconcile(&self, ids: Vec<String>) -> Result<PhoneReconcile, GalleryError> {
        let (removed, unknown) = self.runtime.block_on(self.catalog.phone_retain(ids))?;
        Ok(PhoneReconcile { removed, unknown })
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
    pub fn backup_queue(&self, limit: u32) -> Result<Vec<PhoneRef>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(vec![]);
        };
        Ok(refs(
            self.runtime.block_on(
                self.catalog
                    .backup_queue(account, limit.min(10_000) as usize),
            )?,
        ))
    }

    pub fn set_phone_hashes(&self, hashes: Vec<PhoneHash>) -> Result<(), GalleryError> {
        let hashes = hashes.into_iter().map(|h| (h.id, h.sha1)).collect();
        Ok(self
            .runtime
            .block_on(self.catalog.phone_set_hashes(hashes))?)
    }

    /// How many phone items still need comparing with Proton Photos.
    pub fn backup_remaining(&self) -> Result<u64, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(0);
        };
        Ok(self
            .runtime
            .block_on(self.catalog.phone_remaining(account))?)
    }

    /// Phone items older than `keep_days` whose Proton copy is known.
    /// Nothing is checked with Proton here; see [`Self::free_up_verified`].
    pub fn free_up_summary(&self, keep_days: u32) -> Result<FreeUpSummary, GalleryError> {
        self.runtime.block_on(async {
            let items = self.freeable(keep_days).await?;
            Ok(FreeUpSummary {
                count: items.len() as u64,
                bytes: items.iter().map(|i| i.size_bytes).sum(),
            })
        })
    }

    /// The phone items that may be deleted now: each one's exact contents
    /// (name and SHA-1) are confirmed in Proton Photos again, right before
    /// the host asks the user to delete them.
    pub fn free_up_verified(&self, keep_days: u32) -> Result<Vec<PhoneRef>, GalleryError> {
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            let items = self.freeable(keep_days).await?;
            let work = async {
                let mut confirmed = Vec::with_capacity(items.len());
                for batch in items.chunks(150) {
                    let query: Vec<(String, String)> = batch
                        .iter()
                        .map(|i| (i.name.clone(), i.sha1.clone()))
                        .collect();
                    let found = photos
                        .find_duplicates_many(&query)
                        .await
                        .map_err(remote_failure("confirm your backups with Proton Photos"))?;
                    if found.len() != batch.len() {
                        return Err(GalleryError::Operation {
                            detail: "Proton Photos gave an incomplete answer. Nothing was deleted."
                                .into(),
                        });
                    }
                    for (item, matches) in batch.iter().zip(found) {
                        if !matches.is_empty() {
                            confirmed.push(PhoneRef {
                                id: item.id.clone(),
                                is_video: item.is_video,
                            });
                        }
                    }
                }
                Ok(confirmed)
            };
            tokio::select! { biased;
                _ = account.cancel.cancelled() => Err(proton_gallery_rs::Error::Cancelled.into()),
                result = work => result,
            }
        })
    }

    /// One round of comparing phone media with Proton Photos. Names are
    /// compared first; only same-named files need their contents hashed.
    /// Returns the phone items to hash before the next round; empty when done.
    /// `skip` lists items the host could not read this time; they are not
    /// offered again, so they cannot hold up the rest of the check.
    pub fn check_backup(&self, skip: Vec<String>) -> Result<Vec<PhoneRef>, GalleryError> {
        self.runtime.block_on(async {
            let _guard = self.backup_check.lock().await;
            let account = self.auth.account().await?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            let id = account.id.clone();
            let work = async {
                if let Some(source) = self.photo_source().await? {
                    self.catalog
                        .phone_recheck_new_names(id.clone(), source)
                        .await?;
                }
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
                                    volume: proton_gallery_rs::model::VolumeId::new(
                                        uid.volume_id.as_str(),
                                    )?,
                                    node: proton_gallery_rs::model::NodeId::new(
                                        uid.link_id.as_str(),
                                    )?,
                                },
                                None => BackupState::Missing,
                            },
                        ));
                    }
                    self.catalog.phone_mark(id.clone(), marks).await?;
                }
                Ok::<_, GalleryError>(refs(
                    self.catalog.phone_unhashed(id.clone(), 200, skip).await?,
                ))
            };
            tokio::select! { biased;
                _ = account.cancel.cancelled() => Err(proton_gallery_rs::Error::Cancelled.into()),
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
                // One request for which of the names are taken, one for which of
                // those already hold these bytes. A reply lost after an earlier
                // upload under `IMG (1).jpg` is found here, not uploaded again.
                let candidates: Vec<String> = (0..NAME_CANDIDATES)
                    .map(|n| numbered(&entry.name, n))
                    .collect();
                let occupied = photos
                    .name_collisions(&candidates)
                    .await
                    .map_err(remote_failure("prepare the upload"))?;
                let taken: Vec<usize> = (0..candidates.len())
                    .filter(|&n| occupied.get(n).copied().unwrap_or(false))
                    .collect();
                let mut matches = vec![Vec::new(); candidates.len()];
                if !taken.is_empty() {
                    let lookups: Vec<(String, String)> = taken
                        .iter()
                        .map(|&n| (candidates[n].clone(), sha1.clone()))
                        .collect();
                    let found = photos
                        .find_duplicates_many(&lookups)
                        .await
                        .map_err(remote_failure("compare with Proton Photos"))?;
                    for (&n, uids) in taken.iter().zip(found) {
                        matches[n] = uids;
                    }
                }
                let name = match place(&candidates, &occupied, matches) {
                    Placement::Recover(uid) => {
                        if entry.favorite {
                            mark_favorite(&photos, &uid).await;
                        }
                        self.after_upload(&photos, &account.id, &id, uid, entry.taken)
                            .await?;
                        return Ok(UploadOutcome::AlreadyBackedUp);
                    }
                    Placement::Upload(name) => name,
                    Placement::NoFreeName => {
                        return Err(GalleryError::Operation {
                            detail: format!(
                                "Proton Photos already has {NAME_CANDIDATES} different photos named like “{}”. Rename it on the phone to back it up.",
                                entry.name
                            ),
                        });
                    }
                };
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
                        _ => upload_failure(e),
                    })?;
                if entry.favorite {
                    mark_favorite(&photos, &uid).await;
                }
                self.after_upload(&photos, &account.id, &id, uid, entry.taken)
                    .await?;
                Ok(UploadOutcome::Uploaded)
            };
            tokio::select! { biased;
                _ = account.cancel.cancelled() => Err(proton_gallery_rs::Error::Cancelled.into()),
                result = work => result,
            }
        })
    }
}

impl GalleryEngine {
    async fn freeable(&self, keep_days: u32) -> Result<Vec<core::FreeableMedia>, GalleryError> {
        let (Some(account), Some(source)) = (self.auth.account_id(), self.photo_source().await?)
        else {
            return Ok(vec![]);
        };
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_secs() as i64);
        let before = now - i64::from(keep_days.min(36_500)) * 86_400;
        Ok(self.catalog.freeable(account, source, before).await?)
    }

    /// Remember the Proton copy, and show it in the timeline right away.
    async fn after_upload(
        &self,
        photos: &ProtonPhotosClient,
        account: &proton_gallery_rs::model::AccountId,
        phone: &str,
        uid: proton_sdk::ids::NodeUid,
        taken: i64,
    ) -> Result<(), GalleryError> {
        let volume = proton_gallery_rs::model::VolumeId::new(uid.volume_id.as_str())?;
        let node = proton_gallery_rs::model::NodeId::new(uid.link_id.as_str())?;
        // Queued first: the queue waits for the backup, so an interruption
        // between the two leaves the item unmarked and the next attempt finds
        // the photo again, instead of a backed-up item that never reaches its
        // albums.
        self.catalog
            .queue_backup_albums(account.clone(), phone.to_owned())
            .await?;
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
            && let Ok(Some(asset)) =
                proton_gallery_drive_rs::photos::photo_asset(account, remote, taken)
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
        assert!(engine.check_backup(vec![]).is_err());
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

    fn uid(link: &str) -> proton_sdk::ids::NodeUid {
        proton_sdk::ids::NodeUid::new(
            proton_sdk::ids::VolumeId::new("v"),
            proton_sdk::ids::LinkId::new(link),
        )
    }

    fn names() -> Vec<String> {
        (0..3).map(|n| super::numbered("IMG.jpg", n)).collect()
    }

    #[test]
    fn a_lost_reply_is_recovered_under_the_number_it_took() {
        // Different bytes hold IMG.jpg; the earlier upload landed as IMG (1).jpg.
        let placed = super::place(
            &names(),
            &[true, true, false],
            vec![vec![], vec![uid("ours")], vec![]],
        );
        assert_eq!(placed, super::Placement::Recover(uid("ours")));
    }

    #[test]
    fn the_first_free_name_is_used_when_no_copy_matches() {
        let placed = super::place(&names(), &[true, false, false], vec![vec![]; 3]);
        assert_eq!(placed, super::Placement::Upload("IMG (1).jpg".into()));
        let placed = super::place(&names(), &[false, false, false], vec![vec![]; 3]);
        assert_eq!(placed, super::Placement::Upload("IMG.jpg".into()));
    }

    #[test]
    fn no_free_name_is_an_error_not_a_clash() {
        let placed = super::place(&names(), &[true, true, true], vec![vec![]; 3]);
        assert_eq!(placed, super::Placement::NoFreeName);
    }
}
