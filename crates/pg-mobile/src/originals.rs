//! Full-size originals of Proton media, decrypted into a directory the host
//! owns (its cache), for viewing, playing, sharing and handing to other apps.
use super::*;
use std::io::Write;

/// A file name that is safe on any file system and keeps the extension.
fn safe_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| {
            if c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|') {
                '_'
            } else {
                c
            }
        })
        .collect();
    let trimmed = cleaned.trim().trim_start_matches('.');
    let mut out: String = if trimmed.is_empty() {
        "photo".into()
    } else {
        trimmed.into()
    };
    while out.len() > 120 {
        // Keep the end, which holds the extension.
        let cut = out.char_indices().nth(1).map_or(out.len(), |(i, _)| i);
        out.drain(..cut);
    }
    out
}

/// A folder per item, so two photos with one name never collide.
fn item_folder(key: &AssetKey) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(format!(
        "{}/{}/{}",
        key.account.as_str(),
        key.volume.as_str(),
        key.node.as_str()
    ));
    digest[..12].iter().map(|b| format!("{b:02x}")).collect()
}

#[uniffi::export]
impl GalleryEngine {
    /// The large preview (about screen size) Proton keeps beside a photo, or
    /// `None` when there is none and only the original can be shown.
    pub fn photo_preview(&self, key: String) -> Result<Option<Vec<u8>>, GalleryError> {
        let key: AssetKey = serde_json::from_str(&key).map_err(|_| GalleryError::Operation {
            detail: "Invalid photo identity".into(),
        })?;
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            if account.id != key.account {
                return Err(pg_core::Error::NotFound.into());
            }
            let photos_volume = self
                .catalog
                .account_sources(SourceKind::Photos, account.id.clone())
                .await?
                .first()
                .map(|s| s.volume.clone());
            let uid = pg_drive::node_uid(&key);
            let preview = proton_drive_rs::ThumbnailType::Preview;
            let download = async {
                if photos_volume.as_ref() == Some(&key.volume) {
                    proton_drive_rs::ProtonPhotosClient::from_drive_client(account.drive.clone())
                        .download_thumbnail(&uid, preview)
                        .await
                } else {
                    account.drive.download_thumbnail(&uid, preview).await
                }
            };
            let bytes = tokio::select! { biased;
                _ = account.cancel.cancelled() => return Err(pg_core::Error::Cancelled.into()),
                result = download => result.map_err(|e| {
                    tracing::warn!("photo preview failed: {}", pg_drive::auth::cause_chain(&e));
                    GalleryError::Operation { detail: "Couldn’t load this photo.".into() }
                })?,
            };
            if bytes.as_ref().is_some_and(|b| b.len() > 16 * 1024 * 1024) {
                return Err(pg_core::Error::Invalid("preview exceeds budget".into()).into());
            }
            Ok(bytes)
        })
    }

    /// Decrypt a Proton photo's or video's original into `directory` and
    /// return its path. A finished earlier download is reused.
    pub fn download_original(
        &self,
        key: String,
        name: String,
        directory: String,
    ) -> Result<String, GalleryError> {
        let key: AssetKey = serde_json::from_str(&key).map_err(|_| GalleryError::Operation {
            detail: "Invalid photo identity".into(),
        })?;
        let folder = PathBuf::from(directory).join(item_folder(&key));
        let path = folder.join(safe_name(&name));
        if std::fs::metadata(&path).is_ok_and(|m| m.is_file() && m.len() > 0) {
            return Ok(path.to_string_lossy().into_owned());
        }
        self.runtime.block_on(async {
            let account = self.auth.account().await?;
            if account.id != key.account {
                return Err(pg_core::Error::NotFound.into());
            }
            let photos_volume = self
                .catalog
                .account_sources(SourceKind::Photos, account.id.clone())
                .await?
                .first()
                .map(|s| s.volume.clone());
            std::fs::create_dir_all(&folder).map_err(|_| GalleryError::Operation {
                detail: "There isn’t room on this phone to open the original.".into(),
            })?;
            let part = folder.join(".download");
            let mut out = std::io::BufWriter::new(std::fs::File::create(&part).map_err(|_| GalleryError::Operation {
                detail: "There isn’t room on this phone to open the original.".into(),
            })?);
            let uid = pg_drive::node_uid(&key);
            let download = async {
                if photos_volume.as_ref() == Some(&key.volume) {
                    proton_drive_rs::ProtonPhotosClient::from_drive_client(account.drive.clone())
                        .download_photo_to(&uid, &mut out)
                        .await
                } else {
                    account.drive.download_file_to(&uid, &mut out).await
                }
            };
            let result = tokio::select! { biased;
                _ = account.cancel.cancelled() => Err(GalleryError::from(pg_core::Error::Cancelled)),
                result = download => result.map_err(|e| {
                    tracing::warn!("original download failed: {}", pg_drive::auth::cause_chain(&e));
                    GalleryError::Operation { detail: "Couldn’t download the original. Check your connection and try again.".into() }
                }),
            };
            let finished = result.and_then(|()| {
                out.flush().map_err(|_| GalleryError::Operation {
                    detail: "There isn’t room on this phone to open the original.".into(),
                })
            });
            drop(out);
            if let Err(error) = finished {
                let _ = std::fs::remove_file(&part);
                return Err(error);
            }
            std::fs::rename(&part, &path).map_err(|_| GalleryError::Operation {
                detail: "There isn’t room on this phone to open the original.".into(),
            })?;
            Ok(path.to_string_lossy().into_owned())
        })
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn names_are_safe_and_keep_their_extension() {
        assert_eq!(super::safe_name("IMG_1.jpg"), "IMG_1.jpg");
        assert_eq!(super::safe_name("../a/b:c.png"), "_a_b_c.png");
        assert_eq!(super::safe_name("..."), "photo");
        let long = format!("{}.heic", "x".repeat(300));
        let safe = super::safe_name(&long);
        assert!(safe.len() <= 120 && safe.ends_with(".heic"));
    }
}
