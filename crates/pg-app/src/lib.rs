//! Application services shared by the gallery frontends: the account and its
//! session, Photos synchronization, library queries, and media retrieval.
//!
//! The Android app reaches these through `pg-mobile`'s UniFFI adapter; the
//! desktop app calls them directly. Everything here is asynchronous and runs
//! on the caller's Tokio runtime. Presentation state (navigation, selection,
//! scroll position, dialogs) stays in each frontend.
//!
//! Work that outlives a sign-out must not publish results for the old
//! account. Each change of session raises the [`Generation`]; long work takes
//! the generation at its start and checks it with [`App::ensure_current`]
//! before it writes or returns.
use pg_core::catalog::Catalog;
use pg_core::library::SourceKind;
use pg_core::local::LocalLibrary;
use pg_drive::auth::{AuthService, CredentialVault};
use std::path::{Path, PathBuf};
use std::sync::{
    Arc, RwLock,
    atomic::{AtomicU64, Ordering},
};

pub mod account;
pub mod albums;
pub mod config_sync;
pub mod drive_galleries;
pub mod favorites;
pub mod fixtures;
pub mod library;
pub mod media;
pub mod sharing;
pub mod sync;
pub mod video;

pub use account::{AccountState, SignInStage};
pub use library::{MediaItem, Outline, View};
pub use sync::SyncProgress;

/// A failure to show the user, or work that stopped on purpose.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum Error {
    /// Something went wrong; the text is written for the user.
    #[error("{0}")]
    Message(String),
    /// The work was cancelled, for example by signing out.
    #[error("cancelled")]
    Cancelled,
    /// The work belongs to an account that is no longer signed in.
    #[error("the account changed")]
    Stale,
}

impl Error {
    pub fn message(text: impl Into<String>) -> Self {
        Self::Message(text.into())
    }
}

impl From<pg_core::Error> for Error {
    fn from(error: pg_core::Error) -> Self {
        match error {
            pg_core::Error::Cancelled => Self::Cancelled,
            other => Self::Message(other.to_string()),
        }
    }
}

impl From<pg_drive::auth::AuthError> for Error {
    fn from(error: pg_drive::auth::AuthError) -> Self {
        Self::Message(error.to_string())
    }
}

pub type Result<T, E = Error> = std::result::Result<T, E>;

/// Which session a piece of work started under. Compare with
/// [`App::ensure_current`] before publishing its result.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Generation(u64);

/// The shared application state. One per data directory.
pub struct App {
    pub catalog: Catalog,
    pub local: LocalLibrary,
    pub auth: AuthService,
    /// The Photos source of the signed-in account, once opened.
    pub photos: RwLock<Option<Arc<pg_drive::photos::ProtonPhotosSource>>>,
    // Photos and Drive galleries sync independently; each has its own progress.
    pub photos_sync: tokio::sync::Mutex<()>,
    pub indexed: AtomicU64,
    pub drive_sync: tokio::sync::Mutex<()>,
    configuration_sync: tokio::sync::Mutex<()>,
    configuration_poll: tokio::sync::Mutex<()>,
    pub drive_indexed: AtomicU64,
    pub backup_check: tokio::sync::Mutex<()>,
    /// Serializes restore, challenge continuations and account cleanup.
    transition: tokio::sync::Mutex<()>,
    /// Cleanup waits for operations to finish before forgetting catalog/files.
    operations: tokio::sync::RwLock<()>,
    cleanup: RwLock<Option<Option<pg_core::model::AccountId>>>,
    generation: AtomicU64,
    root: PathBuf,
}

impl std::fmt::Debug for App {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("App")
            .field("data_dir", &self.root)
            .field("generation", &self.generation())
            .finish_non_exhaustive()
    }
}

impl App {
    /// Open (or create) the catalog in `data_dir`. Saved sessions are kept in
    /// `vault`; call [`App::restore_session`] to resume one.
    pub async fn open(
        data_dir: impl Into<PathBuf>,
        vault: Arc<dyn CredentialVault>,
    ) -> Result<Self> {
        let root = data_dir.into();
        let catalog = Catalog::open(root.join("catalog.db")).await?;
        let local = LocalLibrary::open(root.join("device"), catalog.clone()).await?;
        catalog.register_source(LocalLibrary::source()?).await?;
        catalog
            .set_source_kind(LocalLibrary::source()?.id, SourceKind::Device)
            .await?;
        catalog
            .register_source(pg_core::phone::phone_source()?)
            .await?;
        let cleanup = match std::fs::read(root.join("signout-pending.json")) {
            Ok(bytes) => Some(
                serde_json::from_slice(&bytes)
                    .map_err(|_| Error::message("Sign-out cleanup record is unreadable."))?,
            ),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
            Err(_) => return Err(Error::message("Sign-out cleanup record couldn’t be read.")),
        };
        Ok(Self {
            catalog,
            local,
            auth: AuthService::new(vault),
            photos: RwLock::new(None),
            photos_sync: tokio::sync::Mutex::new(()),
            indexed: AtomicU64::new(0),
            drive_sync: tokio::sync::Mutex::new(()),
            configuration_sync: tokio::sync::Mutex::new(()),
            configuration_poll: tokio::sync::Mutex::new(()),
            drive_indexed: AtomicU64::new(0),
            backup_check: tokio::sync::Mutex::new(()),
            transition: tokio::sync::Mutex::new(()),
            operations: tokio::sync::RwLock::new(()),
            cleanup: RwLock::new(cleanup),
            generation: AtomicU64::new(0),
            root,
        })
    }

    /// The directory this app's data lives in.
    pub fn data_dir(&self) -> &Path {
        &self.root
    }

    /// The current session's generation.
    pub fn generation(&self) -> Generation {
        Generation(self.generation.load(Ordering::SeqCst))
    }

    /// `Err(Stale)` when the session changed since `generation` was taken.
    pub fn ensure_current(&self, generation: Generation) -> Result<()> {
        if self.generation() == generation {
            Ok(())
        } else {
            Err(Error::Stale)
        }
    }

    /// Start a new generation: every result of the previous one is stale.
    fn advance(&self) -> Generation {
        Generation(self.generation.fetch_add(1, Ordering::SeqCst) + 1)
    }
}

#[cfg(test)]
pub(crate) mod testing {
    use super::*;
    use std::sync::Mutex;

    /// A vault in memory, which can be made to fail.
    #[derive(Default)]
    pub struct MemoryVault {
        pub saved: Mutex<Option<Vec<u8>>>,
        pub fail_clear: std::sync::atomic::AtomicBool,
    }

    impl CredentialVault for MemoryVault {
        fn load(&self) -> pg_drive::auth::Result<Option<Vec<u8>>> {
            Ok(self.saved.lock().unwrap().clone())
        }
        fn save(&self, bytes: &[u8]) -> pg_drive::auth::Result<()> {
            *self.saved.lock().unwrap() = Some(bytes.to_vec());
            Ok(())
        }
        fn clear(&self) -> pg_drive::auth::Result<()> {
            if self.fail_clear.load(Ordering::Relaxed) {
                return Err(pg_drive::auth::AuthError("Keyring locked. Retry cleanup."));
            }
            *self.saved.lock().unwrap() = None;
            Ok(())
        }
    }

    /// A saved session in the vault's format. Restoring it needs no network.
    pub fn saved_session(user: &str) -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({
            "version": 1,
            "session_id": "session",
            "username": "someone",
            "user_id": user,
            "access_token": "access",
            "refresh_token": "refresh",
            "scopes": ["full"],
            "password_mode": 1,
            "key_passphrases": { "key": "passphrase" }
        }))
        .unwrap()
    }

    pub async fn app(vault: Arc<MemoryVault>) -> (tempfile::TempDir, App) {
        let dir = tempfile::tempdir().unwrap();
        let app = App::open(dir.path(), vault).await.unwrap();
        (dir, app)
    }
}
