//! UniFFI adapter for the seekable Proton video reader.
use super::*;

#[derive(uniffi::Object)]
pub struct VideoStream {
    stream: Arc<pg_app::video::VideoStream>,
    runtime: tokio::runtime::Handle,
}

#[uniffi::export]
impl VideoStream {
    pub fn length(&self) -> u64 {
        self.stream.length()
    }

    /// Runs the fetch on the Rust runtime so Media3's loading thread only waits.
    pub fn read(self: Arc<Self>, offset: u64, length: u32) -> Result<Vec<u8>, GalleryError> {
        let stream = self.stream.clone();
        let task = self
            .runtime
            .spawn(async move { stream.read(offset, length).await });
        Ok(self
            .runtime
            .block_on(task)
            .map_err(|_| pg_core::Error::Cancelled)??)
    }

    pub fn stop(&self) {
        self.stream.stop();
    }
}

impl Drop for VideoStream {
    fn drop(&mut self) {
        self.stream.stop();
    }
}

#[uniffi::export]
impl GalleryEngine {
    pub fn open_video(&self, key: String) -> Result<Arc<VideoStream>, GalleryError> {
        let key: AssetKey = serde_json::from_str(&key).map_err(|_| GalleryError::Operation {
            detail: "Invalid video identity".into(),
        })?;
        let stream = self.runtime.block_on(self.stream_video(&key))?;
        Ok(Arc::new(VideoStream {
            stream,
            runtime: self.runtime.handle().clone(),
        }))
    }
}
