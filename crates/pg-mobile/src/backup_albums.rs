//! Backups into Proton albums: each phone folder can go into albums of its
//! own, and every backup into another (say, one named after the phone).
//! Proton's upload names no album, so photos are queued after their upload
//! and added in batches; the queue survives failures and restarts.
use std::collections::BTreeMap;

use super::*;
use crate::photo_albums::{AlbumChange, album_failure, album_uid};
use proton_drive_rs::ProtonPhotosClient;
use proton_sdk::ids::NodeUid;

/// An album that backups go into.
#[derive(Clone, uniffi::Record)]
pub struct BackupAlbumTarget {
    /// The phone folder, or `None` for every backup.
    pub folder: Option<String>,
    pub album: FolderAlbum,
}

#[uniffi::export]
impl GalleryEngine {
    /// The albums backups go into.
    pub fn backup_albums(&self) -> Result<Vec<BackupAlbumTarget>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(vec![]);
        };
        self.runtime.block_on(async {
            let targets = self.catalog.backup_albums(account.clone()).await?;
            let albums = self
                .folder_albums(self.catalog.photo_albums(account).await?)
                .await?;
            Ok(targets
                .into_iter()
                .filter_map(|target| {
                    let album = albums.iter().find(|a| a.id == target.album.as_str())?;
                    Some(BackupAlbumTarget {
                        folder: target.folder,
                        album: album.clone(),
                    })
                })
                .collect())
        })
    }

    /// Back up `folder` (every backup, for `None`) into `album` too, or stop.
    /// With `include_backed_up`, photos backed up before go into it as well;
    /// returns how many were queued for that.
    pub fn set_backup_album(
        &self,
        folder: Option<String>,
        album: String,
        add: bool,
        include_backed_up: bool,
    ) -> Result<u64, GalleryError> {
        self.runtime.block_on(async {
            let (account, spec) = self.album_for(&album).await?;
            self.catalog
                .set_backup_album(account.id.clone(), folder.clone(), spec.id.clone(), add)
                .await?;
            if !(add && include_backed_up) {
                return Ok(0);
            }
            Ok(self
                .catalog
                .queue_backed_up(account.id, folder, spec.id)
                .await?)
        })
    }

    /// Put phone media into `album` once it is backed up: right after its
    /// upload, or with the next [`Self::flush_album_adds`] if it already is.
    pub fn queue_album_adds(
        &self,
        album: String,
        phone_ids: Vec<String>,
    ) -> Result<u64, GalleryError> {
        self.runtime.block_on(async {
            let (account, spec) = self.album_for(&album).await?;
            Ok(self
                .catalog
                .queue_album_adds(account.id, phone_ids, spec.id)
                .await?)
        })
    }

    /// Add the queued backed-up media to its albums. What fails stays queued
    /// for the next time.
    pub fn flush_album_adds(&self) -> Result<AlbumChange, GalleryError> {
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            let work = async {
                let mut total = AlbumChange {
                    changed: 0,
                    failed: 0,
                };
                // Each round reads what is still queued; a round that adds
                // nothing ends the flush, so failures are not retried at once.
                loop {
                    let adds = self
                        .catalog
                        .album_adds(
                            account.id.clone(),
                            proton_gallery_rs::backup_albums::ALBUM_ADDS_MAX,
                        )
                        .await?;
                    if adds.is_empty() {
                        break;
                    }
                    let mut by_album: BTreeMap<SourceId, Vec<_>> = BTreeMap::new();
                    for add in adds {
                        by_album.entry(add.album.clone()).or_default().push(add);
                    }
                    let mut progress = false;
                    for (album, adds) in by_album {
                        let Some(spec) = self
                            .catalog
                            .photo_album(account.id.clone(), album.clone())
                            .await?
                        else {
                            continue;
                        };
                        for batch in adds.chunks(100) {
                            let uids: Vec<NodeUid> = batch
                                .iter()
                                .map(|a| proton_gallery_drive_rs::node_uid(&a.remote))
                                .collect();
                            let outcomes = photos
                                .add_photos_to_album(&album_uid(&spec), &uids)
                                .await
                                .map_err(album_failure("add backups to their album"))?;
                            let mut done = Vec::new();
                            let mut added = Vec::new();
                            for (add, uid) in batch.iter().zip(&uids) {
                                let ok = match outcomes.iter().find(|(d, _)| d == uid) {
                                    Some((_, Ok(()))) => true,
                                    Some((_, Err(proton_sdk::error::ProtonError::Api(api))))
                                        if api.code
                                            == proton_sdk::api::ResponseCode::AlreadyExists =>
                                    {
                                        true
                                    }
                                    Some((_, Err(e))) => {
                                        tracing::warn!(
                                            "adding a backup to its album failed: {}",
                                            proton_gallery_drive_rs::auth::cause_chain(e)
                                        );
                                        false
                                    }
                                    None => false,
                                };
                                if ok {
                                    done.push((add.phone.clone(), album.clone()));
                                    added.push(add.remote.clone());
                                } else {
                                    total.failed += 1;
                                }
                            }
                            total.changed += added.len() as u64;
                            progress |= !done.is_empty();
                            self.catalog.add_album_members(album.clone(), added).await?;
                            self.catalog
                                .finish_album_adds(account.id.clone(), done)
                                .await?;
                        }
                    }
                    if !progress {
                        break;
                    }
                }
                Ok::<_, GalleryError>(total)
            };
            tokio::select! { biased;
                _ = account.cancel.cancelled() => Err(proton_gallery_rs::Error::Cancelled.into()),
                result = work => result,
            }
        })
    }
}
