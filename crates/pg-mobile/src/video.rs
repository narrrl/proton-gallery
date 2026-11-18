//! Proton videos that play while they download. The player asks for byte
//! ranges; each range comes from one block of the revision, decrypted in
//! memory. Nothing is written to disk, so videos in hidden albums stream the
//! same way. A few blocks around the play position stay cached, and the next
//! ones are fetched ahead of it.
//!
//! A range read cannot check the signature over the whole file's manifest,
//! which the full download does. Each block is still decrypted and checked
//! on its own.
use super::*;
use proton_drive_rs::RevisionReader;
use std::collections::HashMap;
use std::sync::Mutex;
use tokio::sync::OnceCell;

/// Decrypted blocks kept per stream: about 24 MiB at Proton's 4 MiB blocks.
const KEPT_BLOCKS: usize = 6;
/// Blocks fetched ahead of the one being read.
const READ_AHEAD: usize = 2;

type Block = Arc<OnceCell<Arc<Vec<u8>>>>;

#[derive(uniffi::Object)]
pub struct VideoStream {
    reader: Arc<RevisionReader>,
    /// Plaintext offset where each block starts, then the total length.
    starts: Vec<u64>,
    blocks: Mutex<HashMap<usize, Block>>,
    runtime: tokio::runtime::Handle,
    cancel: pg_core::CancellationToken,
}

/// Where each block starts, followed by the total length.
fn block_starts(sizes: &[u64]) -> Vec<u64> {
    let mut starts = Vec::with_capacity(sizes.len() + 1);
    let mut at = 0u64;
    for size in sizes {
        starts.push(at);
        at = at.saturating_add(*size);
    }
    starts.push(at);
    starts
}

/// The block holding `offset`, or `None` at or past the end.
fn block_at(starts: &[u64], offset: u64) -> Option<usize> {
    let total = *starts.last()?;
    if offset >= total {
        return None;
    }
    // The last block that starts at or before `offset`; skips empty blocks.
    Some(starts.partition_point(|&start| start <= offset) - 1)
}

/// The blocks to drop so that at most `keep` remain: those farthest from `near`.
fn evictions(cached: impl Iterator<Item = usize>, near: usize, keep: usize) -> Vec<usize> {
    let mut cached: Vec<usize> = cached.collect();
    if cached.len() <= keep {
        return Vec::new();
    }
    // Blocks behind the play position go before blocks ahead of it.
    cached.sort_by_key(|&index| {
        std::cmp::Reverse(if index < near {
            (near - index) * 2 + 1
        } else {
            index - near
        })
    });
    cached.truncate(cached.len() - keep);
    cached
}

impl VideoStream {
    fn cell(&self, index: usize) -> Block {
        let mut blocks = self.blocks.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(cell) = blocks.get(&index) {
            return cell.clone();
        }
        let cell = Block::default();
        blocks.insert(index, cell.clone());
        for old in evictions(blocks.keys().copied(), index, KEPT_BLOCKS) {
            blocks.remove(&old);
        }
        cell
    }

    async fn block(&self, index: usize) -> Result<Arc<Vec<u8>>, GalleryError> {
        let start = self.starts[index];
        let length = self.starts[index + 1] - start;
        let cell = self.cell(index);
        let bytes = cell
            .get_or_try_init(|| async {
                tokio::select! { biased;
                    _ = self.cancel.cancelled() => Err(GalleryError::from(pg_core::Error::Cancelled)),
                    bytes = self.reader.read_at(start, length) => {
                        let bytes = bytes.map_err(|e| {
                            tracing::warn!("video block failed: {}", pg_drive::auth::cause_chain(&e));
                            GalleryError::Operation { detail: "Couldn’t load the video. Check your connection and try again.".into() }
                        })?;
                        if bytes.len() as u64 != length {
                            return Err(pg_core::Error::RevisionMismatch.into());
                        }
                        Ok(Arc::new(bytes))
                    }
                }
            })
            .await?;
        Ok(bytes.clone())
    }

    fn read_ahead(self: &Arc<Self>, index: usize) {
        let last = self.starts.len() - 2;
        for next in index + 1..=(index + READ_AHEAD).min(last) {
            let present = self
                .blocks
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .contains_key(&next);
            if !present {
                let stream = self.clone();
                self.runtime.spawn(async move {
                    let _ = stream.block(next).await;
                });
            }
        }
    }
}

#[uniffi::export]
impl VideoStream {
    /// The video's length in bytes.
    pub fn length(&self) -> u64 {
        *self.starts.last().unwrap_or(&0)
    }

    /// Up to `length` bytes from `offset`, never past the end of one block;
    /// empty at the end of the video. Blocks the calling thread.
    pub fn read(self: Arc<Self>, offset: u64, length: u32) -> Result<Vec<u8>, GalleryError> {
        let Some(index) = block_at(&self.starts, offset) else {
            return Ok(Vec::new());
        };
        if length == 0 {
            return Ok(Vec::new());
        }
        self.read_ahead(index);
        // The fetch runs on the runtime: the caller is the player's loading
        // thread, whose stack is too small to poll the download itself.
        let stream = self.clone();
        let task = self.runtime.spawn(async move { stream.block(index).await });
        let bytes = self
            .runtime
            .block_on(task)
            .map_err(|_| GalleryError::from(pg_core::Error::Cancelled))??;
        let from = (offset - self.starts[index]) as usize;
        let to = from.saturating_add(length as usize).min(bytes.len());
        Ok(bytes[from..to].to_vec())
    }

    /// Stop fetching and forget the cached blocks; reads after this fail.
    /// Not `close`, which the Kotlin bindings use for releasing the object.
    pub fn stop(&self) {
        self.cancel.cancel();
        self.blocks
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
    }
}

impl Drop for VideoStream {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

#[uniffi::export]
impl GalleryEngine {
    /// Open a Proton video for streaming playback. Only the file's block
    /// table is fetched here; the blocks come as the player reads them.
    pub fn open_video(&self, key: String) -> Result<Arc<VideoStream>, GalleryError> {
        let key: AssetKey = serde_json::from_str(&key).map_err(|_| GalleryError::Operation {
            detail: "Invalid video identity".into(),
        })?;
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            if account.id != key.account {
                return Err(pg_core::Error::NotFound.into());
            }
            let uid = pg_drive::node_uid(&key);
            let reader = tokio::select! { biased;
                _ = account.cancel.cancelled() => return Err(pg_core::Error::Cancelled.into()),
                reader = account.drive.open_revision(&uid) => reader.map_err(|e| {
                    tracing::warn!("video open failed: {}", pg_drive::auth::cause_chain(&e));
                    GalleryError::Operation { detail: "Couldn’t open the video. Check your connection and try again.".into() }
                })?,
            };
            let starts = block_starts(reader.block_sizes());
            Ok(Arc::new(VideoStream {
                reader: Arc::new(reader),
                starts,
                blocks: Mutex::new(HashMap::new()),
                runtime: self.runtime.handle().clone(),
                cancel: account.cancel.child_token(),
            }))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offsets_map_to_their_block() {
        let starts = block_starts(&[4, 4, 2]);
        assert_eq!(starts, vec![0, 4, 8, 10]);
        assert_eq!(block_at(&starts, 0), Some(0));
        assert_eq!(block_at(&starts, 3), Some(0));
        assert_eq!(block_at(&starts, 4), Some(1));
        assert_eq!(block_at(&starts, 9), Some(2));
        assert_eq!(block_at(&starts, 10), None);
        assert_eq!(block_at(&block_starts(&[]), 0), None);
    }

    #[test]
    fn empty_blocks_are_skipped() {
        let starts = block_starts(&[4, 0, 4]);
        assert_eq!(block_at(&starts, 4), Some(2));
    }

    #[test]
    fn eviction_keeps_blocks_near_the_play_position() {
        let mut dropped = evictions([0, 1, 2, 3, 4, 5, 6, 7].into_iter(), 4, 6);
        dropped.sort();
        assert_eq!(dropped, vec![0, 1]);
        // Behind counts double: with 3 kept around 5, block 4 goes before 7.
        let mut dropped = evictions([3, 4, 5, 6, 7].into_iter(), 5, 3);
        dropped.sort();
        assert_eq!(dropped, vec![3, 4]);
        assert!(evictions([1, 2].into_iter(), 1, 6).is_empty());
    }
}
