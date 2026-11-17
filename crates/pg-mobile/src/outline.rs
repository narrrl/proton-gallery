//! A view's outline for the grid, and favorites.
//!
//! The grid plans every row from the outline (identity, time and shape of each
//! item) and asks for the records of the rows it shows. The layout therefore
//! stays put while records load, and only a new outline, made after media was
//! added or removed, moves it.
use super::*;
use pg_core::outline::OutlineEntry;

/// Every item of a view in display order. Taken once; later changes to the
/// catalog need a new outline.
#[derive(uniffi::Object)]
pub struct ViewOutline {
    keys: Vec<AssetKey>,
    ids: Vec<String>,
    times: Vec<i64>,
    ratios: Vec<f32>,
}

#[uniffi::export]
impl ViewOutline {
    pub fn count(&self) -> u32 {
        self.ids.len() as u32
    }

    /// Each item's [`GalleryItem::id`].
    pub fn ids(&self) -> Vec<String> {
        self.ids.clone()
    }

    /// Each item's [`GalleryItem::sort_time`].
    pub fn times(&self) -> Vec<i64> {
        self.times.clone()
    }

    /// Each item's width divided by its height, or 0 when not known yet.
    pub fn ratios(&self) -> Vec<f32> {
        self.ratios.clone()
    }
}

impl ViewOutline {
    fn new(entries: Vec<OutlineEntry>) -> Result<Self, GalleryError> {
        let mut outline = Self {
            keys: Vec::with_capacity(entries.len()),
            ids: Vec::with_capacity(entries.len()),
            times: Vec::with_capacity(entries.len()),
            ratios: Vec::with_capacity(entries.len()),
        };
        for entry in entries {
            outline.ids.push(item_id(&entry.key)?);
            outline.times.push(entry.sort_time);
            outline.ratios.push(if entry.height > 0 {
                entry.width as f32 / entry.height as f32
            } else {
                0.0
            });
            outline.keys.push(entry.key);
        }
        Ok(outline)
    }
}

/// The bridge id of an item: account, volume and node, so different sources
/// cannot collide. Media saved in the app keeps its plain node id.
pub(crate) fn item_id(key: &AssetKey) -> Result<String, GalleryError> {
    Ok(if key.account.as_str() == "local-device" {
        key.node.to_string()
    } else {
        key.stable_id()?
    })
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
        self.runtime.block_on(async {
            let entries = if matches!(view, LibraryView::Photos) {
                self.catalog
                    .timeline_outline(self.photo_source().await?, self.auth.account_id())
                    .await?
            } else {
                match self.view_source(view).await? {
                    Some(source) => {
                        let order = self.catalog.album_settings(source.clone()).await?.order;
                        self.catalog.source_outline(source, order).await?
                    }
                    None => vec![],
                }
            };
            Ok(Arc::new(ViewOutline::new(entries)?))
        })
    }

    /// The records of `count` items from `start` of `outline`. An item removed
    /// since the outline was taken is `None`.
    pub fn outline_items(
        &self,
        outline: Arc<ViewOutline>,
        start: u32,
        count: u32,
    ) -> Result<Vec<Option<GalleryItem>>, GalleryError> {
        let start = (start as usize).min(outline.keys.len());
        let end = start
            .saturating_add((count as usize).min(PageSize::MAX))
            .min(outline.keys.len());
        let keys = outline.keys[start..end].to_vec();
        self.runtime.block_on(async {
            let found = self.catalog.assets_by_key(keys).await?;
            let present: Vec<usize> = found
                .iter()
                .enumerate()
                .filter_map(|(index, asset)| asset.as_ref().map(|_| index))
                .collect();
            let mut items = self
                .items(found.iter().flatten().cloned().collect())
                .await?
                .into_iter();
            let mut result = vec![None; found.len()];
            for index in present {
                result[index] = items.next();
            }
            Ok(result)
        })
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
