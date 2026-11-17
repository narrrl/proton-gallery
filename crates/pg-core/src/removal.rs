//! Media leaving the catalog: trashed from this app, or gone from Proton.
//!
//! Only memberships are removed. The asset row stays while anything else
//! refers to it (another source, a phone backup link, the picker's change
//! log), which keeps every foreign key intact; it simply stops appearing.
use rusqlite::params;

use crate::catalog::Catalog;
use crate::model::*;
use crate::{Error, Result};

impl Catalog {
    /// Take `keys` out of every source of `account` that lists them.
    /// Returns how many memberships were removed.
    pub async fn remove_assets(&self, account: AccountId, keys: Vec<AssetKey>) -> Result<u64> {
        if keys.len() > 10_000 {
            return Err(Error::Invalid("too many items to remove at once".into()));
        }
        if keys.iter().any(|key| key.account != account) {
            return Err(Error::Invalid("item belongs to another account".into()));
        }
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let mut removed = 0;
            {
                let mut delete = tx.prepare_cached(
                    "DELETE FROM memberships WHERE account=?1 AND volume=?2 AND node=?3",
                )?;
                for key in &keys {
                    removed += delete.execute(params![
                        key.account.as_str(),
                        key.volume.as_str(),
                        key.node.as_str()
                    ])?;
                }
            }
            tx.commit()?;
            Ok(removed as u64)
        })
        .await
    }

    /// After a complete listing of `source`: remove members it no longer
    /// lists. Returns how many were removed.
    pub async fn prune_source(&self, source: SourceId, listed: Vec<NodeId>) -> Result<u64> {
        if listed.len() > 2_000_000 {
            return Err(Error::Invalid("listing exceeds bounds".into()));
        }
        self.run(move |conn| {
            let tx = conn.transaction()?;
            tx.execute_batch(
                "CREATE TEMP TABLE IF NOT EXISTS listed(node TEXT PRIMARY KEY NOT NULL);
                 DELETE FROM temp.listed;",
            )?;
            {
                let mut insert =
                    tx.prepare_cached("INSERT OR IGNORE INTO temp.listed(node) VALUES (?1)")?;
                for node in &listed {
                    insert.execute([node.as_str()])?;
                }
            }
            let removed = tx.execute(
                "DELETE FROM memberships WHERE source=?1 AND node NOT IN (SELECT node FROM temp.listed)",
                [source.as_str()],
            )?;
            tx.execute("DELETE FROM temp.listed", [])?;
            tx.commit()?;
            Ok(removed as u64)
        })
        .await
    }
}
