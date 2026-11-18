//! Full-size originals of Proton media, decrypted into a directory the host
//! owns (its cache), for viewing, playing, sharing and handing to other apps.
use super::*;
use authentication::parse_key;

#[uniffi::export]
impl GalleryEngine {
    /// The large preview (about screen size) Proton keeps beside a photo, or
    /// `None` when there is none and only the original can be shown.
    pub fn photo_preview(&self, key: String) -> Result<Option<Vec<u8>>, GalleryError> {
        let key = parse_key(&key)?;
        Ok(self.runtime.block_on(self.app.preview(&key))?)
    }

    /// Decrypt a Proton photo's or video's original into `directory` and
    /// return its path. A finished earlier download is reused.
    pub fn download_original(
        &self,
        key: String,
        name: String,
        directory: String,
    ) -> Result<String, GalleryError> {
        let key = parse_key(&key)?;
        let path = self.runtime.block_on(self.app.download_original(
            &key,
            &name,
            std::path::Path::new(&directory),
        ))?;
        Ok(path.to_string_lossy().into_owned())
    }
}
