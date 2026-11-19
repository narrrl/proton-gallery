//! Where the session is kept: the Linux Secret Service or the Windows
//! Credential Manager. When neither works, the app says so and signs in only
//! for this session if the user agrees; it never falls back to a file.
use pg_drive::auth::{AuthError, CredentialVault, Result};
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

/// Check or set the machine-local passphrase protecting hidden Gallery views.
/// The keyring stores a salted Argon2id verifier scoped to this account.
pub fn unlock_hidden(username: &str, passphrase: &str) -> std::result::Result<(), String> {
    use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
    let entry = keyring::Entry::new(SERVICE, &format!("hidden-albums:{username}"))
        .map_err(|_| "The system keyring is unavailable.".to_owned())?;
    let argon = argon2::Argon2::default();
    match entry.get_password() {
        Ok(stored) => {
            let hash = PasswordHash::new(&stored)
                .map_err(|_| "The local hidden album passphrase couldn’t be read.".to_owned())?;
            argon
                .verify_password(passphrase.as_bytes(), &hash)
                .map_err(|_| "The Gallery passphrase is incorrect.".to_owned())
        }
        Err(keyring::Error::NoEntry) => {
            if passphrase.chars().count() < 12 {
                return Err("Choose a Gallery passphrase with at least 12 characters.".into());
            }
            let mut bytes = [0; 16];
            getrandom::fill(&mut bytes)
                .map_err(|_| "Couldn’t generate the passphrase salt.".to_owned())?;
            let salt = SaltString::encode_b64(&bytes)
                .map_err(|_| "Couldn’t generate the passphrase salt.".to_owned())?;
            let hash = argon
                .hash_password(passphrase.as_bytes(), &salt)
                .map_err(|_| "Couldn’t protect this passphrase.".to_owned())?;
            entry.set_password(&hash.to_string()).map_err(|_| {
                "Couldn’t save the passphrase verifier in the system keyring.".to_owned()
            })
        }
        Err(_) => Err("The system keyring is locked or unavailable.".into()),
    }
}
