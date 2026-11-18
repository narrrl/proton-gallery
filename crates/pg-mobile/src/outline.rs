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
        if asset_keys.len() > pg_app::favorites::MAX_FAVORITE_ITEMS {
            return Err(pg_core::Error::Invalid("too many items to change at once".into()).into());
        }
        let keys = asset_keys
            .iter()
            .map(|key| {
                serde_json::from_str(key).map_err(|_| GalleryError::Operation {
                    detail: "Invalid photo identity".into(),
                })
            })
            .collect::<Result<Vec<AssetKey>, _>>()?;
        let outcome = self
            .runtime
            .block_on(self.app.set_favorite(keys, favorite))?;
        Ok(FavoriteOutcome {
            changed: outcome.changed,
            failed: outcome.failed,
        })
    }
}
