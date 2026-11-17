//! Thin synchronous ABI. Android calls it on Dispatchers.IO; Rust owns all
//! catalog/file operations. Only bounded thumbnails cross the media ABI.
use pg_core::model::AssetKey;
use pg_drive::auth::{AuthError, AuthService, CredentialVault, Stage};
use std::path::PathBuf;
use std::sync::{
    Arc, RwLock,
    atomic::{AtomicU64, Ordering},
};

mod albums;
#[cfg(target_os = "android")]
#[allow(unsafe_code)] // The JVM requires one exported JNI symbol. No unsafe memory access.
mod android_tls;
mod authentication;
mod diagnostics;
mod phone;

use pg_core::catalog::Catalog;
use pg_core::library::SourceKind;
use pg_core::local::LocalLibrary;
use pg_core::model::{MediaKind, PageSize, SourceId, TimelineCursor};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum GalleryError {
    #[error("{detail}")]
    Operation { detail: String },
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

#[derive(uniffi::Object)]
pub struct GalleryEngine {
    runtime: tokio::runtime::Runtime,
    catalog: Catalog,
    local: LocalLibrary,
    auth: AuthService,
    photos: RwLock<Option<Arc<pg_drive::photos::ProtonPhotosSource>>>,
    // Photos and Drive galleries sync independently; each has its own progress.
    photos_sync: tokio::sync::Mutex<()>,
    indexed: AtomicU64,
    drive_sync: tokio::sync::Mutex<()>,
    drive_indexed: AtomicU64,
    backup_check: tokio::sync::Mutex<()>,
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
        let (catalog, local) = runtime.block_on(async {
            let catalog = Catalog::open(root.join("catalog.db")).await?;
            let local = LocalLibrary::open(root.join("device"), catalog.clone()).await?;
            catalog.register_source(LocalLibrary::source()?).await?;
            catalog
                .set_source_kind(LocalLibrary::source()?.id, SourceKind::Device)
                .await?;
            catalog
                .register_source(pg_core::phone::phone_source()?)
                .await?;
            pg_core::Result::Ok((catalog, local))
        })?;
        Ok(Arc::new(Self {
            runtime,
            catalog,
            local,
            auth: AuthService::new(vault),
            photos: RwLock::new(None),
            photos_sync: tokio::sync::Mutex::new(()),
            indexed: AtomicU64::new(0),
            drive_sync: tokio::sync::Mutex::new(()),
            drive_indexed: AtomicU64::new(0),
            backup_check: tokio::sync::Mutex::new(()),
        }))
    }

    async fn photo_source(&self) -> Result<Option<SourceId>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(None);
        };
        Ok(self
            .catalog
            .account_sources(SourceKind::Photos, account)
            .await?
            .first()
            .map(|s| s.id.clone()))
    }
    /// The catalog source behind a view; `None` before Photos is first synced.
    async fn view_source(&self, view: LibraryView) -> Result<Option<SourceId>, GalleryError> {
        Ok(match view {
            LibraryView::Device => Some(LocalLibrary::source()?.id),
            LibraryView::Photos => self.photo_source().await?,
            LibraryView::DriveAlbum { gallery, album } => {
                Some(self.checked_album(gallery, album).await?)
            }
        })
    }

    /// Bridge records for catalog assets, with local files and known shapes.
    async fn items(
        &self,
        assets: Vec<pg_core::model::Asset>,
    ) -> Result<Vec<GalleryItem>, GalleryError> {
        let paths = self
            .local
            .files_for(assets.iter().map(|asset| asset.key.clone()).collect())
            .await?;
        let ratios = self
            .catalog
            .aspect_ratios(assets.iter().map(|a| a.key.clone()).collect())
            .await?;
        let links = self
            .catalog
            .phone_links(
                self.auth.account_id(),
                assets.iter().map(|a| a.key.clone()).collect(),
            )
            .await?;
        let mut items = Vec::with_capacity(assets.len());
        for (((asset, path), ratio), link) in assets.into_iter().zip(paths).zip(ratios).zip(links) {
            let phone = pg_core::phone::is_phone(&asset.key);
            let key = serde_json::to_string(&asset.key).map_err(|_| GalleryError::Operation {
                detail: "Invalid asset identity".into(),
            })?;
            items.push(GalleryItem {
                // Account/volume/node identity, so different sources cannot collide.
                id: if asset.key.account.as_str() == "local-device" {
                    asset.key.node.to_string()
                } else {
                    asset.key.stable_id()?
                },
                remote_key: (path.is_none() && !phone).then(|| key.clone()),
                asset_key: key,
                revision: asset.revision.map(|revision| revision.to_string()),
                name: asset.name,
                mime: asset.mime,
                is_video: asset.kind == MediaKind::Video,
                size_bytes: asset.size_bytes.unwrap_or(0),
                sort_time: asset.sort_time,
                local_path: path
                    .map(|p| p.to_string_lossy().into_owned())
                    .unwrap_or_default(),
                aspect_ratio: ratio,
                phone_id: link.phone,
                backed_up: link.backed_up,
            });
        }
        Ok(items)
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

    async fn checked_album(
        &self,
        gallery: String,
        album: String,
    ) -> Result<SourceId, GalleryError> {
        let account = self.auth.account_id().ok_or(pg_core::Error::NotFound)?;
        let gallery = SourceId::new(gallery)?;
        let album = SourceId::new(album)?;
        if !self
            .catalog
            .source_belongs_to(album.clone(), account)
            .await?
            || !self.catalog.album_exists(gallery, album.clone()).await?
        {
            return Err(pg_core::Error::NotFound.into());
        }
        Ok(album)
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
        let cursor = cursor.as_deref().map(TimelineCursor::decode).transpose()?;
        let size = PageSize::new(limit as usize)?;
        self.runtime.block_on(async {
            if matches!(view, LibraryView::Photos) {
                let page = self
                    .catalog
                    .timeline_window(
                        self.photo_source().await?,
                        self.auth.account_id(),
                        cursor,
                        backwards,
                        size,
                    )
                    .await?;
                return Ok(GalleryPage {
                    items: self.items(page.items).await?,
                    next: page.next.map(|c| c.encode()).transpose()?,
                    previous: page.previous.map(|c| c.encode()).transpose()?,
                });
            }
            let Some(source) = self.view_source(view).await? else {
                return Ok(GalleryPage {
                    items: vec![],
                    next: None,
                    previous: None,
                });
            };
            let order = self.catalog.album_settings(source.clone()).await?.order;
            let page = self
                .catalog
                .window_ordered(Some(source), cursor, backwards, order, size)
                .await?;
            Ok(GalleryPage {
                items: self.items(page.items).await?,
                next: page.next.map(|c| c.encode()).transpose()?,
                previous: page.previous.map(|c| c.encode()).transpose()?,
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
        self.runtime.block_on(async {
            if matches!(view, LibraryView::Photos) {
                let photos = self.photo_source().await?;
                return Ok(self
                    .catalog
                    .timeline_count(photos, self.auth.account_id())
                    .await?);
            }
            match self.view_source(view).await? {
                Some(source) => Ok(self.catalog.source_count(source).await?),
                None => Ok(0),
            }
        })
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
