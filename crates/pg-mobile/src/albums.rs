//! Drive galleries (a chosen folder; each folder below it is an album) and
//! local presentation settings for albums and timelines.
use super::*;
use pg_core::library::{AlbumSettings, Grouping};
use pg_core::model::{AccountId, SortOrder};
use proton_drive_rs::Node;
use proton_sdk::ids::NodeUid;

/// How a grid is divided into headed sections.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AlbumGrouping {
    Day,
    Month,
    Year,
    None,
}
impl From<Grouping> for AlbumGrouping {
    fn from(value: Grouping) -> Self {
        match value {
            Grouping::Day => Self::Day,
            Grouping::Month => Self::Month,
            Grouping::Year => Self::Year,
            Grouping::None => Self::None,
        }
    }
}
impl From<AlbumGrouping> for Grouping {
    fn from(value: AlbumGrouping) -> Self {
        match value {
            AlbumGrouping::Day => Self::Day,
            AlbumGrouping::Month => Self::Month,
            AlbumGrouping::Year => Self::Year,
            AlbumGrouping::None => Self::None,
        }
    }
}

/// Order of a grid. Name order ignores case and has no date headings.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AlbumOrder {
    Newest,
    Oldest,
    Name,
    NameDesc,
}
impl From<SortOrder> for AlbumOrder {
    fn from(value: SortOrder) -> Self {
        match value {
            SortOrder::Newest => Self::Newest,
            SortOrder::Oldest => Self::Oldest,
            SortOrder::Name => Self::Name,
            SortOrder::NameDesc => Self::NameDesc,
        }
    }
}
impl From<AlbumOrder> for SortOrder {
    fn from(value: AlbumOrder) -> Self {
        match value {
            AlbumOrder::Newest => Self::Newest,
            AlbumOrder::Oldest => Self::Oldest,
            AlbumOrder::Name => Self::Name,
            AlbumOrder::NameDesc => Self::NameDesc,
        }
    }
}

/// Local presentation choices. Nothing here renames or changes Drive.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AlbumOptions {
    /// `None` shows the folder name.
    pub title: Option<String>,
    pub description: Option<String>,
    /// `GalleryItem.asset_key` of the chosen cover; `None` uses the first item
    /// in the album's order.
    pub cover_key: Option<String>,
    pub grouping: AlbumGrouping,
    pub order: AlbumOrder,
    pub pinned: bool,
    pub hidden: bool,
    pub tags: Vec<String>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct AlbumTag {
    pub name: String,
    pub albums: u64,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct DriveFolder {
    /// `volume~node`.
    pub id: String,
    pub name: String,
    /// Already added as a gallery.
    pub is_gallery: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct DriveFolderListing {
    pub folder: DriveFolder,
    pub children: Vec<DriveFolder>,
}

const MAX_LISTED_FOLDERS: usize = 2000;

fn options(settings: AlbumSettings) -> Result<AlbumOptions, GalleryError> {
    Ok(AlbumOptions {
        title: settings.title,
        description: settings.description,
        cover_key: settings
            .cover
            .map(|key| serde_json::to_string(&key))
            .transpose()
            .map_err(|_| invalid("Invalid album cover"))?,
        grouping: settings.grouping.into(),
        order: settings.order.into(),
        pinned: settings.pinned,
        hidden: settings.hidden,
        tags: settings.tags,
    })
}

fn invalid(detail: &str) -> GalleryError {
    GalleryError::Operation {
        detail: detail.into(),
    }
}

fn folder_uid(id: &str) -> Result<NodeUid, GalleryError> {
    match id.split_once('~') {
        Some((volume, node)) if !volume.is_empty() && !node.is_empty() && !node.contains('~') => {
            Ok(NodeUid::new(volume.into(), node.into()))
        }
        _ => Err(invalid("Invalid Drive folder")),
    }
}

fn remote(what: &str, error: &dyn std::error::Error) -> GalleryError {
    tracing::warn!("{what}: {}", pg_drive::auth::cause_chain(error));
    GalleryError::Operation {
        detail: format!("{what}. Check your connection and try again."),
    }
}

#[uniffi::export]
impl GalleryEngine {
    /// Presentation options of the source behind `view`; defaults when unset.
    pub fn view_options(&self, view: LibraryView) -> Result<AlbumOptions, GalleryError> {
        self.runtime.block_on(async {
            match self.view_source(view).await? {
                Some(source) => options(self.catalog.album_settings(source).await?),
                None => options(AlbumSettings::default()),
            }
        })
    }

    /// Replace the options of the source behind `view`; returns them normalized.
    pub fn set_view_options(
        &self,
        view: LibraryView,
        options: AlbumOptions,
    ) -> Result<AlbumOptions, GalleryError> {
        let cover = options
            .cover_key
            .as_deref()
            .map(serde_json::from_str::<AssetKey>)
            .transpose()
            .map_err(|_| invalid("Invalid album cover"))?;
        let settings = AlbumSettings {
            title: options.title,
            description: options.description,
            cover,
            grouping: options.grouping.into(),
            order: options.order.into(),
            pinned: options.pinned,
            hidden: options.hidden,
            tags: options.tags,
        };
        self.runtime.block_on(async {
            let source = self
                .view_source(view)
                .await?
                .ok_or(pg_core::Error::NotFound)?;
            self::options(self.catalog.set_album_settings(source, settings).await?)
        })
    }

    pub fn album_tags(&self) -> Result<Vec<AlbumTag>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(vec![]);
        };
        Ok(self
            .runtime
            .block_on(self.catalog.album_tags(account))?
            .into_iter()
            .map(|(name, albums)| AlbumTag { name, albums })
            .collect())
    }

    /// Albums with `tag` at any depth of any gallery.
    pub fn tagged_albums(&self, tag: String) -> Result<Vec<FolderAlbum>, GalleryError> {
        let Some(account) = self.auth.account_id() else {
            return Ok(vec![]);
        };
        self.runtime.block_on(async {
            let albums = self.catalog.tagged_albums(tag, account).await?;
            self.folder_albums(albums).await
        })
    }

    /// Subfolders of `parent`, or of My files when `None`.
    pub fn drive_folders(
        &self,
        parent: Option<String>,
    ) -> Result<DriveFolderListing, GalleryError> {
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            let drive = account.drive.clone();
            let work = async {
                let folder = match &parent {
                    None => drive
                        .get_my_files_folder()
                        .await
                        .map_err(|e| remote("Couldn’t open My files", &e))?,
                    Some(id) => drive
                        .get_node(&folder_uid(id)?)
                        .await
                        .map_err(|e| remote("Couldn’t open this folder", &e))?
                        .ok_or(pg_core::Error::NotFound)?,
                };
                if !folder.is_folder() || folder.trashed {
                    return Err(invalid("Choose an active folder"));
                }
                let ids = drive
                    .enumerate_folder_children_node_uids(&folder.uid)
                    .await
                    .map_err(|e| remote("Couldn’t list this folder", &e))?;
                let mut children = Vec::new();
                for batch in ids.chunks(PageSize::MAX) {
                    let nodes = drive
                        .enumerate_nodes(batch)
                        .await
                        .map_err(|e| remote("Couldn’t list this folder", &e))?;
                    children.extend(nodes.into_iter().filter(|n| n.is_folder() && !n.trashed));
                    if children.len() >= MAX_LISTED_FOLDERS {
                        children.truncate(MAX_LISTED_FOLDERS);
                        break;
                    }
                }
                children.sort_by_key(|n| n.name.to_lowercase());
                let mut listed = Vec::with_capacity(children.len());
                for node in &children {
                    listed.push(self.drive_folder(&account.id, node).await?);
                }
                Ok(DriveFolderListing {
                    folder: self.drive_folder(&account.id, &folder).await?,
                    children: listed,
                })
            };
            tokio::select! {
                biased;
                _ = account.cancel.cancelled() => Err(pg_core::Error::Cancelled.into()),
                result = tokio::time::timeout(std::time::Duration::from_secs(90), work) =>
                    result.map_err(|_| invalid("Proton took too long to respond. Try again."))?,
            }
        })
    }

    /// Add `folder` (`volume~node`) as a gallery: every folder below it
    /// becomes an album. Returns the gallery id. Progress: `drive_progress`.
    /// Runs alongside a Photos sync.
    pub fn add_drive_gallery(&self, folder: String) -> Result<String, GalleryError> {
        let uid = folder_uid(&folder)?;
        self.scan_drive_gallery(Some(uid), None)
    }

    /// Fetch new and changed media for a gallery and its albums.
    pub fn refresh_drive_gallery(&self, gallery: String) -> Result<(), GalleryError> {
        self.scan_drive_gallery(None, Some(SourceId::new(gallery)?))
            .map(|_| ())
    }

    /// Remove a gallery from this app. Nothing is deleted from Drive.
    pub fn remove_drive_gallery(&self, gallery: String) -> Result<(), GalleryError> {
        let account = self.auth.account_id().ok_or(pg_core::Error::NotFound)?;
        self.runtime.block_on(
            self.catalog
                .remove_gallery(SourceId::new(gallery)?, account),
        )?;
        Ok(())
    }
}

impl GalleryEngine {
    async fn drive_folder(
        &self,
        account: &AccountId,
        node: &Node,
    ) -> Result<DriveFolder, GalleryError> {
        let id = pg_drive::folder_source_id(account, node)?;
        Ok(DriveFolder {
            id: format!(
                "{}~{}",
                node.uid.volume_id.as_str(),
                node.uid.link_id.as_str()
            ),
            name: node.name.clone(),
            is_gallery: self.catalog.album_exists(id.clone(), id).await?,
        })
    }

    fn scan_drive_gallery(
        &self,
        folder: Option<NodeUid>,
        gallery: Option<SourceId>,
    ) -> Result<String, GalleryError> {
        self.runtime.block_on(async {
            let _guard = self.drive_sync.lock().await;
            let account = self.auth.account().await?;
            self.drive_indexed.store(0, Ordering::Relaxed);
            let uid = match (folder, gallery) {
                (Some(uid), _) => uid,
                (None, Some(gallery)) => {
                    if !self
                        .catalog
                        .album_exists(gallery.clone(), gallery.clone())
                        .await?
                        || !self
                            .catalog
                            .source_belongs_to(gallery.clone(), account.id.clone())
                            .await?
                    {
                        return Err(pg_core::Error::NotFound.into());
                    }
                    let spec = self
                        .catalog
                        .sources()
                        .await?
                        .into_iter()
                        .find(|s| s.id == gallery)
                        .ok_or(pg_core::Error::NotFound)?;
                    NodeUid::new(spec.volume.as_str().into(), spec.root.as_str().into())
                }
                (None, None) => return Err(pg_core::Error::NotFound.into()),
            };
            let root = tokio::select! {
                biased;
                _ = account.cancel.cancelled() => return Err(pg_core::Error::Cancelled.into()),
                result = account.drive.get_node(&uid) => result
                    .map_err(|e| remote("Couldn’t open this folder", &e))?
                    .ok_or(pg_core::Error::NotFound)?,
            };
            let indexed = &self.drive_indexed;
            // Media found so far across albums: finished albums plus the current one.
            let (mut done, mut last, mut current) = (0u64, 0u64, None);
            let gallery = pg_drive::galleries::scan_gallery(
                &self.catalog,
                account.drive.clone(),
                account.id.clone(),
                root,
                true,
                &account.cancel,
                |source, checkpoint| {
                    if current.as_ref() != Some(&source.id) {
                        current = Some(source.id.clone());
                        done += last;
                    }
                    last = checkpoint.indexed;
                    indexed.store(done + last, Ordering::Relaxed);
                },
            )
            .await
            .map_err(|e| match e {
                pg_core::Error::Remote(_) => remote("Couldn’t read this Drive folder", &e),
                other => other.into(),
            })?;
            Ok(gallery.to_string())
        })
    }
}
