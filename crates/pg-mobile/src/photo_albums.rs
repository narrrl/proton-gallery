//! Proton Photos albums: listed after each Photos sync, shown on the Photos
//! tab, and changed here (create, rename, add, remove, delete) with the
//! catalog updated at once so the screen never waits for the next sync.
use super::*;
use pg_core::model::{AccountId, Asset, VolumeId};
use proton_drive_rs::ProtonPhotosClient;
use proton_sdk::ids::NodeUid;
use sha2::{Digest, Sha256};

/// The catalog source of a Proton album.
fn album_spec(account: &AccountId, uid: &NodeUid, name: &str) -> Result<SourceSpec, GalleryError> {
    let identity = format!(
        "{}\n{}\n{}",
        account.as_str(),
        uid.volume_id.as_str(),
        uid.link_id.as_str()
    );
    Ok(SourceSpec {
        id: SourceId::new(format!(
            "album-{}",
            hex::encode(Sha256::digest(identity.as_bytes()))
        ))?,
        account: account.clone(),
        volume: VolumeId::new(uid.volume_id.as_str())?,
        root: NodeId::new(uid.link_id.as_str())?,
        label: name.to_owned(),
    })
}

fn album_uid(spec: &SourceSpec) -> NodeUid {
    NodeUid::new(spec.volume.as_str().into(), spec.root.as_str().into())
}

fn album_failure(what: &'static str) -> impl Fn(proton_sdk::error::ProtonError) -> GalleryError {
    move |e| {
        tracing::warn!("{what} failed: {}", pg_drive::auth::cause_chain(&e));
        GalleryError::Operation {
            detail: format!("Couldn’t {what}. Check your connection and try again."),
        }
    }
}

impl GalleryEngine {
    /// Bring every album and its photos in line with Proton. Best effort:
    /// a failure leaves the albums as they were.
    pub(crate) async fn sync_albums(&self, photos: &ProtonPhotosClient, account: &AccountId) {
        if let Err(error) = self.try_sync_albums(photos, account).await {
            tracing::warn!("album sync failed: {error}");
        }
    }

    async fn try_sync_albums(
        &self,
        photos: &ProtonPhotosClient,
        account: &AccountId,
    ) -> Result<(), GalleryError> {
        let uids = photos
            .enumerate_album_node_uids()
            .await
            .map_err(album_failure("list your albums"))?;
        let mut keep = Vec::with_capacity(uids.len());
        for batch in uids.chunks(100) {
            let nodes = photos
                .enumerate_nodes(batch)
                .await
                .map_err(album_failure("read your albums"))?;
            for node in nodes.into_iter().filter(|n| !n.trashed) {
                let spec = album_spec(account, &node.uid, &node.name)?;
                let items = photos
                    .enumerate_album(&node.uid)
                    .await
                    .map_err(album_failure("read an album"))?;
                let assets = self.album_assets(photos, account, items).await?;
                self.catalog.sync_photo_album(spec.clone(), assets).await?;
                keep.push(spec.id);
            }
        }
        self.catalog
            .retain_photo_albums(account.clone(), keep)
            .await?;
        Ok(())
    }

    /// Catalog records for album photos; photos the timeline does not hold
    /// (kept only in the album) are read from Proton.
    async fn album_assets(
        &self,
        photos: &ProtonPhotosClient,
        account: &AccountId,
        items: Vec<proton_drive_rs::AlbumItem>,
    ) -> Result<Vec<Asset>, GalleryError> {
        let keys = items
            .iter()
            .map(|item| {
                Ok(AssetKey {
                    account: account.clone(),
                    volume: VolumeId::new(item.uid.volume_id.as_str())?,
                    node: NodeId::new(item.uid.link_id.as_str())?,
                })
            })
            .collect::<Result<Vec<_>, pg_core::Error>>()?;
        let known = self.catalog.assets_by_key(keys).await?;
        let mut assets = Vec::with_capacity(items.len());
        let mut missing = Vec::new();
        for (item, asset) in items.iter().zip(known) {
            match asset {
                Some(asset) => assets.push(asset),
                None => missing.push(item),
            }
        }
        for batch in missing.chunks(100) {
            let uids: Vec<NodeUid> = batch.iter().map(|item| item.uid.clone()).collect();
            let nodes = photos
                .enumerate_nodes(&uids)
                .await
                .map_err(album_failure("read an album"))?;
            for node in nodes {
                let Some(item) = batch.iter().find(|item| item.uid == node.uid) else {
                    continue;
                };
                if let Some(asset) =
                    pg_drive::photos::photo_asset(account, node, item.capture_time)?
                {
                    assets.push(asset);
                }
            }
        }
        Ok(assets)
    }

    async fn album_for(
        &self,
        id: &str,
    ) -> Result<(pg_drive::auth::Account, SourceSpec), GalleryError> {
        let account = self.auth.account().await?;
        let spec = self
            .catalog
            .photo_album(account.id.clone(), SourceId::new(id)?)
            .await?
            .ok_or(pg_core::Error::NotFound)?;
        Ok((account, spec))
    }

    fn keys_of(account: &AccountId, asset_keys: &[String]) -> Result<Vec<AssetKey>, GalleryError> {
        asset_keys
            .iter()
            .map(|key| {
                let key: AssetKey =
                    serde_json::from_str(key).map_err(|_| GalleryError::Operation {
                        detail: "Invalid photo identity".into(),
                    })?;
                if &key.account != account {
                    return Err(pg_core::Error::NotFound.into());
                }
                Ok(key)
            })
            .collect()
    }
}

/// What adding to an album did.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AlbumChange {
    pub changed: u64,
    pub failed: u64,
}

#[uniffi::export]
impl GalleryEngine {
    /// The account's Proton Photos albums, pinned first, then by name.
    pub fn photo_albums(&self) -> Result<Vec<FolderAlbum>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(vec![]);
        };
        self.runtime.block_on(async {
            let albums = self.catalog.photo_albums(account).await?;
            self.folder_albums(albums).await
        })
    }

    /// Create an empty album named `name`.
    pub fn create_album(&self, name: String) -> Result<FolderAlbum, GalleryError> {
        let name = name.trim().to_owned();
        if name.is_empty() || name.chars().count() > 200 {
            return Err(
                pg_core::Error::Invalid("An album name needs 1 to 200 characters.".into()).into(),
            );
        }
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            let uid = photos
                .create_album(&name)
                .await
                .map_err(album_failure("create the album"))?;
            let spec = album_spec(&account.id, &uid, &name)?;
            self.catalog.sync_photo_album(spec.clone(), vec![]).await?;
            let albums = self.catalog.photo_albums(account.id.clone()).await?;
            let album = albums
                .into_iter()
                .filter(|a| a.source.id == spec.id)
                .collect();
            self.folder_albums(album)
                .await?
                .into_iter()
                .next()
                .ok_or(pg_core::Error::NotFound.into())
        })
    }

    /// Add Proton photos (their `asset_key`s) to an album.
    pub fn add_to_album(
        &self,
        album: String,
        asset_keys: Vec<String>,
    ) -> Result<AlbumChange, GalleryError> {
        self.runtime.block_on(async {
            let (account, spec) = self.album_for(&album).await?;
            let keys = Self::keys_of(&account.id, &asset_keys)?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            let uids: Vec<NodeUid> = keys.iter().map(pg_drive::node_uid).collect();
            let outcomes = photos
                .add_photos_to_album(&album_uid(&spec), &uids)
                .await
                .map_err(album_failure("add to the album"))?;
            let mut added = Vec::new();
            let mut failed = 0;
            for (key, uid) in keys.iter().zip(&uids) {
                match outcomes.iter().find(|(done, _)| done == uid) {
                    Some((_, Ok(()))) => added.push(key.clone()),
                    Some((_, Err(e))) => {
                        // Already in the album is not a failure.
                        if matches!(e, proton_sdk::error::ProtonError::Api(api) if api.code == proton_sdk::api::ResponseCode::AlreadyExists) {
                            added.push(key.clone());
                        } else {
                            tracing::warn!("adding one photo failed: {}", pg_drive::auth::cause_chain(e));
                            failed += 1;
                        }
                    }
                    None => failed += 1,
                }
            }
            let changed = added.len() as u64;
            self.catalog.add_album_members(spec.id, added).await?;
            Ok(AlbumChange { changed, failed })
        })
    }

    /// Take photos out of an album. They stay in the timeline.
    pub fn remove_from_album(
        &self,
        album: String,
        asset_keys: Vec<String>,
    ) -> Result<AlbumChange, GalleryError> {
        self.runtime.block_on(async {
            let (account, spec) = self.album_for(&album).await?;
            let keys = Self::keys_of(&account.id, &asset_keys)?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            let uids: Vec<NodeUid> = keys.iter().map(pg_drive::node_uid).collect();
            let outcomes = photos
                .remove_photos_from_album(&album_uid(&spec), &uids)
                .await
                .map_err(album_failure("remove from the album"))?;
            let mut removed = Vec::new();
            let mut failed = 0;
            for (key, uid) in keys.iter().zip(&uids) {
                match outcomes.iter().find(|(done, _)| done == uid) {
                    Some((_, Ok(()))) => removed.push(key.clone()),
                    _ => failed += 1,
                }
            }
            let changed = removed.len() as u64;
            self.catalog.remove_album_members(spec.id, removed).await?;
            Ok(AlbumChange { changed, failed })
        })
    }

    pub fn rename_album(&self, album: String, name: String) -> Result<(), GalleryError> {
        let name = name.trim().to_owned();
        if name.is_empty() || name.chars().count() > 200 {
            return Err(
                pg_core::Error::Invalid("An album name needs 1 to 200 characters.".into()).into(),
            );
        }
        self.runtime.block_on(async {
            let (account, spec) = self.album_for(&album).await?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            photos
                .rename_album(&album_uid(&spec), &name)
                .await
                .map_err(album_failure("rename the album"))?;
            self.catalog
                .register_source(SourceSpec {
                    label: name,
                    ..spec
                })
                .await?;
            Ok(())
        })
    }

    /// Delete an album. Its photos stay: any kept only in the album are
    /// saved to the timeline first.
    pub fn delete_album(&self, album: String) -> Result<(), GalleryError> {
        self.runtime.block_on(async {
            let (account, spec) = self.album_for(&album).await?;
            let photos = ProtonPhotosClient::from_drive_client(account.drive.clone());
            photos
                .delete_album_saving_photos(&album_uid(&spec))
                .await
                .map_err(album_failure("delete the album"))?;
            self.catalog.forget_photo_album(spec.id).await?;
            Ok(())
        })
    }
}
