//! A view's outline for the grid, and favorites.
//!
//! The grid plans every row from the outline (identity, time and shape of each
//! item) and asks for the records of the rows it shows. The layout therefore
//! stays put while records load, and only a new outline, made after media was
//! added or removed, moves it.
use super::*;

/// Every item of a view in display order. Taken once; later changes to the
/// catalog need a new outline.
#[derive(uniffi::Object)]
pub struct ViewOutline(pg_app::Outline);

#[uniffi::export]
impl ViewOutline {
    pub fn count(&self) -> u32 {
        self.0.len() as u32
    }

    /// Each item's [`GalleryItem::id`].
    pub fn ids(&self) -> Vec<String> {
        self.0.ids.clone()
    }

    /// Each item's [`GalleryItem::sort_time`].
    pub fn times(&self) -> Vec<i64> {
        self.0.times.clone()
    }

    /// Each item's width divided by its height, or 0 when not known yet.
    pub fn ratios(&self) -> Vec<f32> {
        self.0.ratios.clone()
    }
}

/// How many favorites changed, and how many could not.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct FavoriteOutcome {
    pub changed: u64,
    pub failed: u64,
}

#[uniffi::export]
impl GalleryEngine {
    /// The outline of `view`.
    pub fn outline_at(&self, view: LibraryView) -> Result<Arc<ViewOutline>, GalleryError> {
        let outline = self.runtime.block_on(self.app.outline(view.into()))?;
        Ok(Arc::new(ViewOutline(outline)))
    }

    /// The records of `count` items from `start` of `outline`. An item removed
    /// since the outline was taken is `None`.
    pub fn outline_items(
        &self,
        outline: Arc<ViewOutline>,
        start: u32,
        count: u32,
    ) -> Result<Vec<Option<GalleryItem>>, GalleryError> {
        let items = self.runtime.block_on(self.app.outline_items(
            &outline.0,
            start as usize,
            count as usize,
        ))?;
        items
            .into_iter()
            .map(|item| item.map(GalleryItem::from_media).transpose())
            .collect()
    }

    /// Add Proton photos (their `asset_key`s) to the favorites, or take them
    /// out. Drive files and phone media have no Proton favorite and count as
    /// failed.
    pub fn set_favorite(
        &self,
        asset_keys: Vec<String>,
        favorite: bool,
    ) -> Result<FavoriteOutcome, GalleryError> {
        if asset_keys.len() > 5_000 {
            return Err(pg_core::Error::Invalid("too many items to change at once".into()).into());
        }
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            let photos_volume = self
                .catalog
                .account_sources(SourceKind::Photos, account.id.clone())
                .await?
                .first()
                .map(|s| s.volume.clone());
            let mut keys = Vec::with_capacity(asset_keys.len());
            let mut failed = 0;
            for key in &asset_keys {
                let key: AssetKey =
                    serde_json::from_str(key).map_err(|_| GalleryError::Operation {
                        detail: "Invalid photo identity".into(),
                    })?;
                if key.account != account.id {
                    return Err(pg_core::Error::NotFound.into());
                }
                if Some(&key.volume) == photos_volume.as_ref() {
                    keys.push(key);
                } else {
                    failed += 1;
                }
            }
            let photos =
                proton_drive_rs::ProtonPhotosClient::from_drive_client(account.drive.clone());
            let tag = vec![proton_drive_rs::PhotoTag::Favorite];
            let work = async {
                let mut changed = Vec::new();
                for batch in keys.chunks(50) {
                    let updates: Vec<_> = batch
                        .iter()
                        .map(|key| proton_drive_rs::PhotoTagsUpdate {
                            node_uid: pg_drive::node_uid(key),
                            tags_to_add: if favorite { tag.clone() } else { vec![] },
                            tags_to_remove: if favorite { vec![] } else { tag.clone() },
                        })
                        .collect();
                    let outcomes = photos.update_photos(&updates).await.map_err(|e| {
                        tracing::warn!("favorite failed: {}", pg_drive::auth::cause_chain(&e));
                        GalleryError::Operation {
                            detail: "Couldn’t change the favorites. Check your connection and try again.".into(),
                        }
                    })?;
                    for (key, update) in batch.iter().zip(&updates) {
                        match outcomes.iter().find(|(uid, _)| *uid == update.node_uid) {
                            Some((_, Ok(()))) => changed.push(key.clone()),
                            Some((_, Err(e))) => {
                                tracing::warn!("favorite of one photo failed: {}", pg_drive::auth::cause_chain(e));
                                failed += 1;
                            }
                            None => failed += 1,
                        }
                    }
                }
                Ok::<_, GalleryError>(changed)
            };
            let changed = tokio::select! { biased;
                _ = account.cancel.cancelled() => return Err(pg_core::Error::Cancelled.into()),
                result = work => result?,
            };
            let count = changed.len() as u64;
            self.catalog.set_favorite(changed, favorite).await?;
            Ok(FavoriteOutcome { changed: count, failed })
        })
    }
}
