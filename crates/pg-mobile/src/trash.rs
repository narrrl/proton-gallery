//! Deleting Proton media: it moves to the Proton trash, where it can be
//! restored from Proton's own apps, and leaves this app's catalog at once.
use super::*;

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TrashOutcome {
    pub trashed: u64,
    pub failed: u64,
}

#[uniffi::export]
impl GalleryEngine {
    /// Move Proton items (their `asset_key`s) to the Proton trash.
    pub fn trash_items(&self, asset_keys: Vec<String>) -> Result<TrashOutcome, GalleryError> {
        if asset_keys.len() > 5_000 {
            return Err(pg_core::Error::Invalid("too many items to delete at once".into()).into());
        }
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            let mut keys = Vec::with_capacity(asset_keys.len());
            for key in &asset_keys {
                let key: AssetKey = serde_json::from_str(key).map_err(|_| GalleryError::Operation {
                    detail: "Invalid photo identity".into(),
                })?;
                if key.account != account.id {
                    return Err(pg_core::Error::NotFound.into());
                }
                keys.push(key);
            }
            let uids: Vec<_> = keys.iter().map(pg_drive::node_uid).collect();
            let work = async {
                let mut trashed = Vec::new();
                let mut failed = 0;
                for (batch, uids) in keys.chunks(100).zip(uids.chunks(100)) {
                    let outcomes = account.drive.trash_nodes(uids).await.map_err(|e| {
                        tracing::warn!("trash failed: {}", pg_drive::auth::cause_chain(&e));
                        GalleryError::Operation {
                            detail: "Couldn’t move to the Proton trash. Check your connection and try again.".into(),
                        }
                    })?;
                    for (key, uid) in batch.iter().zip(uids) {
                        match outcomes.iter().find(|(done, _)| done == uid) {
                            Some((_, Ok(()))) => trashed.push(key.clone()),
                            Some((_, Err(e))) => {
                                tracing::warn!("trash of one item failed: {}", pg_drive::auth::cause_chain(e));
                                failed += 1;
                            }
                            None => failed += 1,
                        }
                    }
                }
                Ok::<_, GalleryError>((trashed, failed))
            };
            let (trashed, failed) = tokio::select! { biased;
                _ = account.cancel.cancelled() => return Err(pg_core::Error::Cancelled.into()),
                result = work => result?,
            };
            let count = trashed.len() as u64;
            self.catalog.remove_assets(account.id.clone(), trashed).await?;
            Ok(TrashOutcome { trashed: count, failed })
        })
    }

    /// Photo picker identifiers removed after generation `after`, oldest
    /// first, with the generation to pass back for the next page.
    pub fn picker_deleted(
        &self,
        after: u64,
        limit: u32,
    ) -> Result<picker::PickerDeletions, GalleryError> {
        self.runtime.block_on(async {
            let Some(account) = self.auth.account_id() else {
                return Ok(picker::PickerDeletions {
                    ids: vec![],
                    next: None,
                });
            };
            let Some(source) = self
                .catalog
                .account_sources(SourceKind::Photos, account)
                .await?
                .into_iter()
                .next()
            else {
                return Ok(picker::PickerDeletions {
                    ids: vec![],
                    next: None,
                });
            };
            let limit = limit.clamp(1, pg_core::picker::PICKER_PAGE_MAX);
            let rows = self.catalog.picker_deletions(source, after, limit).await?;
            let next = (rows.len() == limit as usize)
                .then(|| rows.last().map(|(_, g)| *g))
                .flatten();
            Ok(picker::PickerDeletions {
                ids: rows.into_iter().map(|(node, _)| node.to_string()).collect(),
                next,
            })
        })
    }
}
