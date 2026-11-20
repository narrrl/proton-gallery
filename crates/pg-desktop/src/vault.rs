//! Where the session is kept: the Linux Secret Service or the Windows
//! Credential Manager. When neither works, the app says so and signs in only
//! for this session if the user agrees; it never falls back to a file.
use proton_gallery_drive_rs::auth::{AuthError, CredentialVault, Result};
use std::sync::Mutex;

const SERVICE: &str = "io.narl.protongallery";
const USER: &str = "proton-session";

fn entry() -> Result<keyring::Entry> {
    keyring::Entry::new(SERVICE, USER).map_err(|_| AuthError("The system keyring is unavailable."))
}

/// Whether the system keyring can be used here.
pub fn keyring_available() -> bool {
    match entry().map(|e| e.get_secret()) {
        Ok(Ok(_)) | Ok(Err(keyring::Error::NoEntry)) => true,
        Ok(Err(error)) => {
            tracing::warn!("system keyring unavailable: {error}");
            false
        }
        Err(_) => false,
    }
}

/// The session in the system keyring.
pub struct KeyringVault;

impl CredentialVault for KeyringVault {
    fn load(&self) -> Result<Option<Vec<u8>>> {
        match entry()?.get_secret() {
            Ok(bytes) => Ok(Some(bytes)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(error) => {
                tracing::warn!("keyring read failed: {error}");
                Err(AuthError(
                    "The saved sign-in couldn’t be read from the system keyring.",
                ))
            }
        }
    }

    fn save(&self, bytes: &[u8]) -> Result<()> {
        entry()?.set_secret(bytes).map_err(|error| {
            tracing::warn!("keyring write failed: {error}");
            AuthError("The sign-in couldn’t be saved in the system keyring.")
        })
    }

    fn clear(&self) -> Result<()> {
        match entry()?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(error) => {
                tracing::warn!("keyring delete failed: {error}");
                Err(AuthError(
                    "The saved sign-in couldn’t be removed from the system keyring.",
                ))
            }
        }
    }
}

/// A session that ends with the app. Only used after the user chose it.
#[derive(Default)]
pub struct TemporaryVault(Mutex<Option<zeroize::Zeroizing<Vec<u8>>>>);

impl CredentialVault for TemporaryVault {
    fn load(&self) -> Result<Option<Vec<u8>>> {
        Ok(self
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .map(|b| b.to_vec()))
    }
    fn save(&self, bytes: &[u8]) -> Result<()> {
        *self.0.lock().unwrap_or_else(|e| e.into_inner()) =
            Some(zeroize::Zeroizing::new(bytes.to_vec()));
        Ok(())
    }
    fn clear(&self) -> Result<()> {
        *self.0.lock().unwrap_or_else(|e| e.into_inner()) = None;
        Ok(())
    }
}
