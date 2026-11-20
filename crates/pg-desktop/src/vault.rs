//! Where the session is kept: the Linux Secret Service or the Windows
//! Credential Manager. When neither works, the app says so and signs in only
//! for this session if the user agrees; it never falls back to a file.
use proton_auth_rs::{KeyringStore, SecretStore};
use proton_gallery_drive_rs::auth::{AuthError, CredentialVault, Result};
use std::sync::{Mutex, OnceLock};

const SERVICE: &str = "io.narl.protongallery";
const USER: &str = "proton-session";

/// The system keyring, connected once per process. It reconnects when the
/// Secret Service restarts, and on Linux a changed session replaces its item
/// instead of editing it, which oo7 before 0.7.0 kept only in memory.
fn keyring() -> &'static KeyringStore {
    static STORE: OnceLock<KeyringStore> = OnceLock::new();
    STORE.get_or_init(|| KeyringStore::new(SERVICE))
}

/// Whether the system keyring can be used here.
pub fn keyring_available() -> bool {
    match keyring().get_bytes(USER) {
        Ok(_) => true,
        Err(error) => {
            tracing::warn!("system keyring unavailable: {error}");
            false
        }
    }
}

/// The session in the system keyring.
pub struct KeyringVault;

impl CredentialVault for KeyringVault {
    fn load(&self) -> Result<Option<Vec<u8>>> {
        match keyring().get_bytes(USER) {
            Ok(bytes) => Ok(bytes.map(|bytes| bytes.to_vec())),
            Err(error) => {
                tracing::warn!("keyring read failed: {error}");
                Err(AuthError(
                    "The saved sign-in couldn’t be read from the system keyring.",
                ))
            }
        }
    }

    fn save(&self, bytes: &[u8]) -> Result<()> {
        keyring().set_bytes(USER, bytes).map_err(|error| {
            tracing::warn!("keyring write failed: {error}");
            AuthError("The sign-in couldn’t be saved in the system keyring.")
        })
    }

    fn clear(&self) -> Result<()> {
        keyring().delete(USER).map_err(|error| {
            tracing::warn!("keyring delete failed: {error}");
            AuthError("The saved sign-in couldn’t be removed from the system keyring.")
        })
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
