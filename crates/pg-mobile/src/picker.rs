//! Proton Photos inside Android's system photo picker. The host's
//! `CloudMediaProvider` answers the picker with these calls; thumbnails and
//! originals come from `photo_thumbnail`, `photo_preview` and
//! `download_original`, keyed by the item's `remote_key`.
use super::*;
use pg_core::picker::{PICKER_PAGE_MAX, picker_mime};

/// What the picker should hold. The identifier changes with the account and
/// when the catalog is rebuilt; then the picker reads everything again.
#[derive(uniffi::Record)]
pub struct PickerCollection {
    pub id: String,
    pub generation: u64,
    pub account_name: String,
}

#[derive(uniffi::Record)]
pub struct PickerMedia {
    /// The Proton node: unique within the account's Photos.
    pub id: String,
    pub mime: String,
    pub is_video: bool,
    pub taken_millis: i64,
    pub size_bytes: u64,
    pub generation: u64,
    pub width: u32,
    pub height: u32,
    /// The phone's media identifier when the same file is on this phone.
    pub phone_id: Option<String>,
}

#[derive(uniffi::Record)]
pub struct PickerPage {
    pub items: Vec<PickerMedia>,
    /// Pass back as `after` for the next page; `None` on the last page.
    pub next: Option<u64>,
}

#[derive(uniffi::Record)]
pub struct PickerDeletions {
    pub ids: Vec<String>,
    /// Pass back as `after` for the next page; `None` on the last page.
    pub next: Option<u64>,
}

impl GalleryEngine {
    async fn picker_source(&self) -> Result<Option<SourceSpec>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(None);
        };
        Ok(self
            .catalog
            .account_sources(SourceKind::Photos, account)
            .await?
            .into_iter()
            .next())
    }
}

#[uniffi::export]
impl GalleryEngine {
    /// `None` while signed out or before Photos was first synced.
    pub fn picker_collection(&self) -> Result<Option<PickerCollection>, GalleryError> {
        self.runtime.block_on(async {
            let Some(source) = self.picker_source().await? else {
                return Ok(None);
            };
            let state = self.catalog.picker_state().await?;
            Ok(Some(PickerCollection {
                id: format!("{}:{}", state.collection, source.id.as_str()),
                generation: state.generation,
                account_name: self.auth.status().username,
            }))
        })
    }

    /// Photos and videos changed after generation `after`, oldest change first.
    pub fn picker_media(&self, after: u64, limit: u32) -> Result<PickerPage, GalleryError> {
        self.runtime.block_on(async {
            let Some(source) = self.picker_source().await? else {
                return Ok(PickerPage {
                    items: Vec::new(),
                    next: None,
                });
            };
            let limit = limit.clamp(1, PICKER_PAGE_MAX);
            let entries = self.catalog.picker_changes(source.id, after, limit).await?;
            let next = (entries.len() == limit as usize)
                .then(|| entries.last().map(|entry| entry.generation))
                .flatten();
            let items = entries
                .into_iter()
                .map(|entry| PickerMedia {
                    id: entry.asset.key.node.to_string(),
                    mime: picker_mime(&entry.asset),
                    is_video: entry.asset.kind == MediaKind::Video,
                    taken_millis: entry.asset.sort_time.saturating_mul(1000),
                    size_bytes: entry.asset.size_bytes.unwrap_or(0),
                    generation: entry.generation,
                    width: entry.width.unwrap_or(0),
                    height: entry.height.unwrap_or(0),
                    phone_id: entry.phone,
                })
                .collect();
            Ok(PickerPage { items, next })
        })
    }

    /// The gallery item behind a picker identifier, for its thumbnail and file.
    pub fn picker_item(&self, id: String) -> Result<Option<GalleryItem>, GalleryError> {
        self.runtime.block_on(async {
            let Some(source) = self.picker_source().await? else {
                return Ok(None);
            };
            let Ok(node) = NodeId::new(id) else {
                return Ok(None);
            };
            let Some(asset) = self.catalog.picker_asset(source, node).await? else {
                return Ok(None);
            };
            Ok(self.items(vec![asset]).await?.into_iter().next())
        })
    }
}
