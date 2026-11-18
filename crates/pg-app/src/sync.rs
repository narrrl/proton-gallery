//! Proton Photos synchronization into the local catalog.
use super::*;
use pg_core::CancellationToken;
use pg_core::model::ScanCheckpoint;
use pg_core::source::MediaSource;

/// Progress of a running sync.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct SyncProgress {
    /// Media indexed so far.
    pub indexed: u64,
}

impl App {
    /// Index `source` into the catalog under `generation`. A sign-out while
    /// it runs cancels it; a session change before it finishes makes its
    /// result stale.
    pub async fn index_source(
        &self,
        source: &dyn MediaSource,
        generation: Generation,
        cancel: &CancellationToken,
        progress: impl Fn(SyncProgress),
    ) -> Result<ScanCheckpoint> {
        let _operations = self.operations.read().await;
        self.scan_source(source, generation, cancel, progress).await
    }

    async fn scan_source(
        &self,
        source: &dyn MediaSource,
        generation: Generation,
        cancel: &CancellationToken,
        progress: impl Fn(SyncProgress),
    ) -> Result<ScanCheckpoint> {
        self.ensure_current(generation)?;
        let checkpoint = pg_core::indexer::scan(&self.catalog, source, true, cancel, |p| {
            progress(SyncProgress { indexed: p.indexed })
        })
        .await?;
        self.ensure_current(generation)?;
        Ok(checkpoint)
    }

    /// Bring the catalog in line with the account's Proton Photos, then its
    /// albums. Returns how many photos were indexed. One sync runs at a time;
    /// a second call waits for the first.
    pub async fn sync_photos(&self, progress: impl Fn(SyncProgress)) -> Result<u64> {
        let _operations = self.operations.read().await;
        let _guard = self.photos_sync.lock().await;
        let generation = self.generation();
        let account = self.auth.account().await?;
        self.indexed.store(0, Ordering::Relaxed);
        let photos = proton_drive_rs::ProtonPhotosClient::from_drive_client(account.drive);
        let root = tokio::select! { biased;
            _ = account.cancel.cancelled() => return Err(Error::Cancelled),
            result = tokio::time::timeout(std::time::Duration::from_secs(60), photos.get_photos_root()) => result
                .map_err(|_| Error::message("Proton took too long to respond. Try refreshing again."))?
                .map_err(|e| {
                    tracing::warn!("Photos root failed: {}", pg_drive::auth::cause_chain(&e));
                    Error::message("Couldn’t open Proton Photos. Check your connection, or sign in again if your session has expired.")
                })?,
        };
        let Some(root) = root else { return Ok(0) };
        let albums = photos.clone();
        let source = Arc::new(pg_drive::photos::ProtonPhotosSource::new(
            photos,
            account.id.clone(),
            &root,
        )?);
        {
            let mut cache = self.photos.write().unwrap_or_else(|e| e.into_inner());
            if account.cancel.is_cancelled() {
                return Err(Error::Cancelled);
            }
            self.ensure_current(generation)?;
            *cache = Some(source.clone());
        }
        let checkpoint = self
            .scan_source(source.as_ref(), generation, &account.cancel, |p| {
                self.indexed.store(p.indexed, Ordering::Relaxed);
                progress(p);
            })
            .await?;
        // A complete listing is the truth: photos it no longer has were
        // deleted elsewhere (or restored items return through the scan).
        if checkpoint.complete
            && let Some(listed) = source.listed_nodes()
        {
            let removed = self
                .catalog
                .prune_source(source.spec().id.clone(), listed)
                .await?;
            if removed > 0 {
                tracing::info!("{removed} photos are no longer in Proton Photos");
            }
        }
        tokio::select! { biased;
            _ = account.cancel.cancelled() => {},
            _ = self.sync_albums(&albums, &account.id) => {},
        }
        self.ensure_current(generation)?;
        Ok(checkpoint.indexed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::*;
    use pg_core::synthetic::SyntheticSource;
    use std::sync::Mutex;
    use std::time::Duration;

    #[tokio::test]
    async fn a_failed_sync_can_be_retried_to_completion() {
        let (_dir, app) = app(Arc::default()).await;
        let cancel = CancellationToken::new();
        let generation = app.generation();
        let failing = SyntheticSource::new(600).unwrap().fail_at(250);
        let error = app
            .index_source(&failing, generation, &cancel, |_| {})
            .await
            .unwrap_err();
        assert!(matches!(error, Error::Message(_)));
        let source = SyntheticSource::new(600).unwrap();
        let seen = Mutex::new(Vec::new());
        let done = app
            .index_source(&source, generation, &cancel, |p| {
                seen.lock().unwrap().push(p.indexed)
            })
            .await
            .unwrap();
        assert!(done.complete);
        assert_eq!(
            app.catalog
                .source_count(source.spec().id.clone())
                .await
                .unwrap(),
            600
        );
        assert!(
            seen.lock()
                .unwrap()
                .last()
                .is_some_and(|&n| n == done.indexed)
        );
    }

    #[tokio::test]
    async fn cancelling_stops_a_sync() {
        let (_dir, app) = app(Arc::default()).await;
        let cancel = CancellationToken::new();
        let source = SyntheticSource::new(10_000)
            .unwrap()
            .with_delay(Duration::from_millis(20));
        let generation = app.generation();
        let run = app.index_source(&source, generation, &cancel, |_| {});
        let stop = async {
            tokio::time::sleep(Duration::from_millis(30)).await;
            cancel.cancel();
        };
        let (result, ()) = tokio::join!(run, stop);
        assert_eq!(result.unwrap_err(), Error::Cancelled);
    }

    #[tokio::test]
    async fn a_sync_that_outlives_its_account_returns_no_result() {
        let vault = Arc::new(MemoryVault::default());
        *vault.saved.lock().unwrap() = Some(saved_session("user-1"));
        let (_dir, app) = app(vault).await;
        app.restore_session().await.unwrap();
        let generation = app.generation();
        let cancel = CancellationToken::new();
        let source = SyntheticSource::new(5_000)
            .unwrap()
            .with_delay(Duration::from_millis(10));
        let run = app.index_source(&source, generation, &cancel, |_| {});
        let switch = async {
            tokio::time::sleep(Duration::from_millis(15)).await;
            app.advance();
        };
        let (result, ()) = tokio::join!(run, switch);
        assert_eq!(result.unwrap_err(), Error::Stale);
        // Work started after the change is current again.
        assert!(
            app.index_source(&source, app.generation(), &cancel, |_| {})
                .await
                .is_ok()
        );
    }

    #[tokio::test]
    async fn syncing_photos_needs_an_account() {
        let (_dir, app) = app(Arc::default()).await;
        let error = app.sync_photos(|_| {}).await.unwrap_err();
        assert!(matches!(error, Error::Message(text) if text.contains("Sign in")));
    }
}
