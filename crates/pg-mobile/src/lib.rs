//! Thin synchronous ABI. Android calls it on Dispatchers.IO; Rust owns all
//! catalog/file operations. Only bounded thumbnails cross the media ABI.
use pg_core::model::AssetKey;
use pg_drive::auth::{AuthError, CredentialVault};
use std::path::PathBuf;
use std::sync::{Arc, atomic::Ordering};

mod albums;
#[cfg(target_os = "android")]
#[allow(unsafe_code)] // The JVM requires one exported JNI symbol. No unsafe memory access.
mod android_tls;
mod authentication;
mod backup_albums;
mod diagnostics;
mod originals;
mod outline;
mod phone;
mod photo_albums;
mod picker;
mod trash;
mod video;

use pg_core::library::SourceKind;
use pg_core::model::{MediaKind, NodeId, PageSize, SourceId, SourceSpec};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum GalleryError {
    #[error("{detail}")]
    Operation { detail: String },
}

impl From<pg_app::Error> for GalleryError {
    fn from(error: pg_app::Error) -> Self {
        Self::Operation {
            detail: error.to_string(),
        }
    }
}

impl From<pg_core::Error> for GalleryError {
    fn from(error: pg_core::Error) -> Self {
        Self::Operation {
            detail: error.to_string(),
        }
    }
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct GalleryItem {
    pub id: String,
    pub name: String,
    pub mime: String,
    pub is_video: bool,
    pub size_bytes: u64,
    pub sort_time: i64,
    pub local_path: String,
    #[uniffi(default = None)]
    pub aspect_ratio: Option<f64>,
    #[uniffi(default = None)]
    pub remote_key: Option<String>,
    #[uniffi(default = None)]
    pub revision: Option<String>,
    /// Account/volume/node identity as JSON, for choosing an album cover.
    #[uniffi(default = "")]
    pub asset_key: String,
    /// The phone's media identifier when the file is on this phone.
    #[uniffi(default = None)]
    pub phone_id: Option<String>,
    /// Whether Proton holds a copy; false only for phone media.
    #[uniffi(default = true)]
    pub backed_up: bool,
    /// A Proton photo or Drive file, which can be moved to the Proton trash.
    #[uniffi(default = false)]
    pub in_proton: bool,
    /// A Proton favorite, or a favorite in the phone's own gallery.
    #[uniffi(default = false)]
    pub favorite: bool,
}

#[derive(Clone, uniffi::Enum)]
pub enum LibraryView {
    Photos,
    Device,
    DriveAlbum { gallery: String, album: String },
}

#[derive(Clone, uniffi::Record)]
pub struct FolderAlbum {
    pub gallery: String,
    pub id: String,
    pub parent: Option<String>,
    /// Display name: the chosen title, otherwise the folder name.
    pub name: String,
    pub direct_items: u64,
    pub child_albums: u64,
    #[uniffi(default = "")]
    pub folder_name: String,
    #[uniffi(default = None)]
    pub description: Option<String>,
    #[uniffi(default)]
    pub tags: Vec<String>,
    #[uniffi(default = None)]
    pub cover: Option<GalleryItem>,
    #[uniffi(default = false)]
    pub pinned: bool,
    #[uniffi(default = false)]
    pub hidden: bool,
}

#[derive(uniffi::Record)]
pub struct SmartTile {
    pub width: u32,
    pub height: u32,
}
#[derive(uniffi::Record)]
pub struct SmartRow {
    pub start: u32,
    pub tiles: Vec<SmartTile>,
}

#[uniffi::export]
pub fn plan_smart_grid(
    ratios: Vec<f64>,
    width: u32,
    target: u32,
    gap: u32,
) -> Result<Vec<SmartRow>, GalleryError> {
    Ok(pg_core::grid::plan_justified(&ratios, width, target, gap)?
        .into_iter()
        .map(|row| SmartRow {
            start: row.start as u32,
            tiles: row
                .tiles
                .into_iter()
                .map(|t| SmartTile {
                    width: t.width,
                    height: t.height,
                })
                .collect(),
        })
        .collect())
}

/// Every row of a view: see [`plan_grid`].
#[derive(uniffi::Record)]
pub struct GridPlan {
    /// The first item of each row.
    pub row_starts: Vec<u32>,
    /// The height of each row in pixels.
    pub row_heights: Vec<u32>,
    /// The width of each item in pixels, in item order.
    pub widths: Vec<u32>,
}

/// Plan the justified rows of a whole view in one call. `ratios` are the
/// items' width divided by height (0 when unknown, planned square);
/// `sections` are the items where a new heading starts.
#[uniffi::export]
pub fn plan_grid(
    ratios: Vec<f32>,
    sections: Vec<u32>,
    width: u32,
    target: u32,
    gap: u32,
) -> Result<GridPlan, GalleryError> {
    let ratios: Vec<f64> = ratios.into_iter().map(f64::from).collect();
    let grid = pg_core::grid::plan_sections(&ratios, &sections, width, target, gap)?;
    Ok(GridPlan {
        row_starts: grid.row_starts,
        row_heights: grid.row_heights,
        widths: grid.widths,
    })
}

#[derive(uniffi::Record)]
pub struct GalleryPage {
    pub items: Vec<GalleryItem>,
    pub next: Option<String>,
    pub previous: Option<String>,
}

#[derive(uniffi::Record)]
pub struct DeviceTotals {
    pub count: u64,
    pub bytes: u64,
}

/// The Android-facing adapter over the shared application services. It
/// blocks the calling thread (Android calls it on `Dispatchers.IO`) and
/// converts records for UniFFI; the services themselves live in `pg-app`.
#[derive(uniffi::Object)]
pub struct GalleryEngine {
    runtime: tokio::runtime::Runtime,
    app: pg_app::App,
}

/// The adapter's modules reach the catalog, account and locks of the app.
impl std::ops::Deref for GalleryEngine {
    type Target = pg_app::App;
    fn deref(&self) -> &pg_app::App {
        &self.app
    }
}

impl From<LibraryView> for pg_app::View {
    fn from(view: LibraryView) -> Self {
        match view {
            LibraryView::Photos => Self::Photos,
            LibraryView::Device => Self::Device,
            LibraryView::DriveAlbum { gallery, album } => Self::DriveAlbum { gallery, album },
        }
    }
}

impl GalleryItem {
    fn from_media(item: pg_app::MediaItem) -> Result<Self, GalleryError> {
        let key = serde_json::to_string(&item.key).map_err(|_| GalleryError::Operation {
            detail: "Invalid asset identity".into(),
        })?;
        Ok(Self {
            id: item.id,
            remote_key: item.remote.then(|| key.clone()),
            asset_key: key,
            revision: item.revision,
            name: item.name,
            mime: item.mime,
            is_video: item.is_video,
            size_bytes: item.size_bytes,
            sort_time: item.sort_time,
            local_path: item
                .local_path
                .map(|p| p.to_string_lossy().into_owned())
                .unwrap_or_default(),
            aspect_ratio: item.aspect_ratio,
            phone_id: item.phone_id,
            backed_up: item.backed_up,
            in_proton: item.in_proton,
            favorite: item.favorite,
        })
    }
}

#[uniffi::export]
impl GalleryEngine {
    #[uniffi::constructor]
    pub fn open(data_dir: String) -> Result<Arc<Self>, GalleryError> {
        Self::create(data_dir, Arc::new(authentication::TransientVault))
    }

    #[uniffi::constructor]
    pub fn open_with_vault(
        data_dir: String,
        vault: Box<dyn authentication::SessionVault>,
    ) -> Result<Arc<Self>, GalleryError> {
        Self::create(data_dir, Arc::new(authentication::PlatformVault(vault)))
    }
}

impl GalleryEngine {
    fn create(
        data_dir: String,
        vault: Arc<dyn CredentialVault>,
    ) -> Result<Arc<Self>, GalleryError> {
        diagnostics::install();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(pg_core::Error::Io)?;
        let root = PathBuf::from(data_dir);
        diagnostics::set_panic_file(root.join("last-panic.txt"));
        let app = runtime.block_on(pg_app::App::open(root, vault))?;
        Ok(Arc::new(Self { runtime, app }))
    }

    /// The catalog source behind a view; `None` before Photos is first synced.
    async fn view_source(&self, view: LibraryView) -> Result<Option<SourceId>, GalleryError> {
        Ok(self.app.view_source(view.into()).await?)
    }

    /// Bridge records for catalog assets, with local files and known shapes.
    async fn items(
        &self,
        assets: Vec<pg_core::model::Asset>,
    ) -> Result<Vec<GalleryItem>, GalleryError> {
        self.app
            .media_items(assets)
            .await?
            .into_iter()
            .map(GalleryItem::from_media)
            .collect()
    }

    async fn folder_albums(
        &self,
        albums: Vec<pg_core::library::Album>,
    ) -> Result<Vec<FolderAlbum>, GalleryError> {
        let covers = self
            .items(albums.iter().filter_map(|a| a.cover.clone()).collect())
            .await?;
        let mut covers = covers.into_iter();
        Ok(albums
            .into_iter()
            .map(|a| FolderAlbum {
                gallery: a.gallery.to_string(),
                id: a.source.id.to_string(),
                parent: a.parent.map(|p| p.to_string()),
                name: a
                    .settings
                    .title
                    .clone()
                    .unwrap_or_else(|| a.source.label.clone()),
                direct_items: a.direct_items,
                child_albums: a.child_albums,
                folder_name: a.source.label,
                description: a.settings.description,
                tags: a.settings.tags,
                cover: a.cover.and_then(|_| covers.next()),
                pinned: a.settings.pinned,
                hidden: a.settings.hidden,
            })
            .collect())
    }
}

#[uniffi::export]
impl GalleryEngine {
    pub fn incoming_directory(&self) -> String {
        self.local.incoming().to_string_lossy().into_owned()
    }

    pub fn import_file(
        &self,
        staged_path: String,
        name: String,
        mime: String,
        modified_at: i64,
    ) -> Result<(), GalleryError> {
        self.runtime.block_on(self.local.import_staged(
            PathBuf::from(staged_path),
            name,
            mime,
            modified_at,
        ))?;
        Ok(())
    }

    pub fn page(
        &self,
        cursor: Option<String>,
        backwards: bool,
        limit: u32,
    ) -> Result<GalleryPage, GalleryError> {
        self.page_at(LibraryView::Device, cursor, backwards, limit)
    }

    pub fn page_at(
        &self,
        view: LibraryView,
        cursor: Option<String>,
        backwards: bool,
        limit: u32,
    ) -> Result<GalleryPage, GalleryError> {
        self.runtime.block_on(async {
            let page = self
                .app
                .page(view.into(), cursor.as_deref(), backwards, limit as usize)
                .await?;
            Ok(GalleryPage {
                items: page
                    .items
                    .into_iter()
                    .map(GalleryItem::from_media)
                    .collect::<Result<_, _>>()?,
                next: page.next,
                previous: page.previous,
            })
        })
    }

    pub fn albums(
        &self,
        gallery: Option<String>,
        parent: Option<String>,
    ) -> Result<Vec<FolderAlbum>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(vec![]);
        };
        self.runtime.block_on(async {
            if !self.app.gallery_visibility_cached().await? {
                return Ok(vec![]);
            }
            let albums = self
                .catalog
                .albums_scoped(
                    gallery.map(SourceId::new).transpose()?,
                    parent.map(SourceId::new).transpose()?,
                    Some(account),
                )
                .await?;
            self.folder_albums(albums).await
        })
    }

    pub fn import_file_with_shape(
        &self,
        staged_path: String,
        name: String,
        mime: String,
        modified_at: i64,
        width: u32,
        height: u32,
    ) -> Result<(), GalleryError> {
        if width > 1_000_000 || height > 1_000_000 || (width == 0) != (height == 0) {
            return Err(pg_core::Error::Invalid("invalid media dimensions".into()).into());
        }
        self.runtime.block_on(async {
            let key = self
                .local
                .import_staged(PathBuf::from(staged_path), name, mime, modified_at)
                .await?;
            if width > 0 {
                // Optional presentation metadata must not turn a committed
                // original into a reported failed import (e.g. low disk space).
                let _ = self.catalog.remember_shape(key, width, height).await;
            }
            pg_core::Result::Ok(())
        })?;
        Ok(())
    }

    pub fn totals(&self) -> Result<DeviceTotals, GalleryError> {
        let (count, bytes) = self.runtime.block_on(self.catalog.local_totals())?;
        Ok(DeviceTotals { count, bytes })
    }

    pub fn count_at(&self, view: LibraryView) -> Result<u64, GalleryError> {
        Ok(self.runtime.block_on(self.app.count(view.into()))?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_mobile_surface_reopens_real_imports_and_rejects_bad_requests() {
        let dir = tempfile::tempdir().unwrap();
        let engine = GalleryEngine::open(dir.path().to_str().unwrap().into()).unwrap();
        let staged = PathBuf::from(engine.incoming_directory()).join("selected");
        std::fs::write(&staged, b"local image fixture").unwrap();
        engine
            .import_file(
                staged.to_str().unwrap().into(),
                "lake.jpg".into(),
                "image/jpeg".into(),
                42,
            )
            .unwrap();
        assert_eq!(engine.totals().unwrap().count, 1);
        assert!(engine.page(None, false, 0).is_err());
        assert!(engine.page(Some("broken".into()), false, 90).is_err());
        drop(engine);
        let reopened = GalleryEngine::open(dir.path().to_str().unwrap().into()).unwrap();
        let page = reopened.page(None, false, 90).unwrap();
        assert_eq!(page.items[0].name, "lake.jpg");
        assert_eq!(
            std::fs::read(&page.items[0].local_path).unwrap(),
            b"local image fixture"
        );
        let outline = reopened.outline_at(LibraryView::Device).unwrap();
        assert_eq!(outline.ids(), vec![page.items[0].id.clone()]);
        assert_eq!(outline.ratios(), vec![0.0]);
        let items = reopened.outline_items(outline.clone(), 0, 90).unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(
            items[0].as_ref().unwrap().local_path,
            page.items[0].local_path
        );
        assert!(reopened.outline_items(outline, 5, 90).unwrap().is_empty());
    }

    #[test]
    fn view_options_persist_and_reorder_the_timeline() {
        use albums::{AlbumGrouping, AlbumOptions, AlbumOrder};
        let dir = tempfile::tempdir().unwrap();
        let engine = GalleryEngine::open(dir.path().to_str().unwrap().into()).unwrap();
        for (name, time) in [("old.jpg", 10), ("new.jpg", 20)] {
            let staged = PathBuf::from(engine.incoming_directory()).join(name);
            std::fs::write(&staged, name.as_bytes()).unwrap();
            engine
                .import_file(
                    staged.to_str().unwrap().into(),
                    name.into(),
                    "image/jpeg".into(),
                    time,
                )
                .unwrap();
        }
        let first = |engine: &GalleryEngine| {
            engine
                .page_at(LibraryView::Device, None, false, 90)
                .unwrap()
                .items[0]
                .name
                .clone()
        };
        assert_eq!(first(&engine), "new.jpg");
        let defaults = engine.view_options(LibraryView::Device).unwrap();
        assert_eq!(defaults.grouping, AlbumGrouping::Day);
        let page = engine
            .page_at(LibraryView::Device, None, false, 90)
            .unwrap();
        let saved = engine
            .set_view_options(
                LibraryView::Device,
                AlbumOptions {
                    grouping: AlbumGrouping::None,
                    order: AlbumOrder::Oldest,
                    cover_key: Some(page.items[1].asset_key.clone()),
                    ..defaults
                },
            )
            .unwrap();
        assert_eq!(saved.grouping, AlbumGrouping::None);
        assert_eq!(first(&engine), "old.jpg");
        drop(engine);
        let reopened = GalleryEngine::open(dir.path().to_str().unwrap().into()).unwrap();
        assert_eq!(reopened.view_options(LibraryView::Device).unwrap(), saved);
        assert!(
            reopened
                .set_view_options(
                    LibraryView::Device,
                    AlbumOptions {
                        cover_key: Some("{}".into()),
                        ..saved
                    }
                )
                .is_err()
        );
    }
}
