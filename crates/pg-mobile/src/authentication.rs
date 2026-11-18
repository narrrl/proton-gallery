use super::*;

#[uniffi::export(callback_interface)]
pub trait SessionVault: Send + Sync {
    fn load(&self) -> Result<Option<Vec<u8>>, GalleryError>;
    fn save(&self, bytes: Vec<u8>) -> Result<(), GalleryError>;
    fn clear(&self) -> Result<(), GalleryError>;
}
impl From<uniffi::UnexpectedUniFFICallbackError> for GalleryError {
    fn from(_: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Operation {
            detail: "Secure session storage is unavailable.".into(),
        }
    }
}
pub struct PlatformVault(pub Box<dyn SessionVault>);
impl CredentialVault for PlatformVault {
    fn load(&self) -> pg_drive::auth::Result<Option<Vec<u8>>> {
        self.0
            .load()
            .map_err(|_| AuthError("Your saved session couldn’t be read securely. Sign in again."))
    }
    fn save(&self, bytes: &[u8]) -> pg_drive::auth::Result<()> {
        self.0.save(bytes.to_vec()).map_err(|_|AuthError("Your session couldn’t be saved securely. Check available device storage and try again."))
    }
    fn clear(&self) -> pg_drive::auth::Result<()> {
        self.0
            .clear()
            .map_err(|_| AuthError("The saved session couldn’t be removed. Try signing out again."))
    }
}
pub struct TransientVault;
impl CredentialVault for TransientVault {
    fn load(&self) -> pg_drive::auth::Result<Option<Vec<u8>>> {
        Ok(None)
    }
    fn save(&self, _: &[u8]) -> pg_drive::auth::Result<()> {
        Err(AuthError("A secure platform vault is required to sign in."))
    }
    fn clear(&self) -> pg_drive::auth::Result<()> {
        Ok(())
    }
}
impl From<AuthError> for GalleryError {
    fn from(e: AuthError) -> Self {
        Self::Operation {
            detail: e.to_string(),
        }
    }
}
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum SignInStage {
    SignedOut,
    SecondFactor,
    MailboxPassword,
    Verification,
    Connected,
}
#[derive(uniffi::Record)]
pub struct AccountStatus {
    pub stage: SignInStage,
    pub username: String,
    pub verification_url: Option<String>,
    pub persistence_failed: bool,
}

impl From<pg_app::AccountState> for AccountStatus {
    fn from(state: pg_app::AccountState) -> Self {
        Self {
            stage: match state.stage {
                pg_app::SignInStage::SignedOut => SignInStage::SignedOut,
                pg_app::SignInStage::SecondFactor => SignInStage::SecondFactor,
                pg_app::SignInStage::MailboxPassword => SignInStage::MailboxPassword,
                pg_app::SignInStage::Verification => SignInStage::Verification,
                pg_app::SignInStage::Connected => SignInStage::Connected,
            },
            username: state.username,
            verification_url: state.verification_url,
            persistence_failed: state.persistence_failed,
        }
    }
}

#[uniffi::export]
impl GalleryEngine {
    pub fn restore_session(&self) -> Result<(), GalleryError> {
        self.runtime.block_on(self.app.restore_session())?;
        Ok(())
    }
    pub fn account_status(&self) -> AccountStatus {
        self.app.account_state().into()
    }
    pub fn sign_in(&self, username: String, password: String) -> Result<(), GalleryError> {
        let password = zeroize::Zeroizing::new(password);
        self.runtime
            .block_on(self.app.sign_in(username, password))?;
        Ok(())
    }
    pub fn submit_second_factor(&self, code: String) -> Result<(), GalleryError> {
        self.runtime.block_on(self.app.submit_second_factor(code))?;
        Ok(())
    }
    pub fn submit_mailbox_password(&self, password: String) -> Result<(), GalleryError> {
        let password = zeroize::Zeroizing::new(password);
        self.runtime
            .block_on(self.app.submit_mailbox_password(password))?;
        Ok(())
    }
    pub fn submit_verification(&self, token: String) -> Result<(), GalleryError> {
        self.runtime.block_on(self.app.submit_verification(token))?;
        Ok(())
    }
    pub fn cancel_sign_in(&self) -> Result<(), GalleryError> {
        self.runtime.block_on(self.app.cancel_sign_in())?;
        Ok(())
    }
    /// Sign out and forget what the catalog knew of the account.
    pub fn sign_out(&self) -> Result<(), GalleryError> {
        Ok(self.runtime.block_on(self.app.sign_out())?)
    }
    /// Media indexed so far by the running Photos sync.
    pub fn sync_progress(&self) -> u64 {
        self.indexed.load(Ordering::Relaxed)
    }
    /// Media indexed so far by the running Drive gallery add or refresh.
    pub fn drive_progress(&self) -> u64 {
        self.drive_indexed.load(Ordering::Relaxed)
    }
    pub fn sync_photos(&self) -> Result<u64, GalleryError> {
        Ok(self.runtime.block_on(self.app.sync_photos(|_| {}))?)
    }
    /// Small decrypted previews stay in the caller's bounded memory cache.
    pub fn photo_thumbnail(&self, key: String) -> Result<Option<Vec<u8>>, GalleryError> {
        let key = parse_key(&key)?;
        Ok(self.runtime.block_on(self.app.thumbnail(&key))?)
    }
    pub fn remember_photo_shape(
        &self,
        key: String,
        width: u32,
        height: u32,
    ) -> Result<(), GalleryError> {
        let key = parse_key(&key)?;
        Ok(self
            .runtime
            .block_on(self.app.remember_shape(key, width, height))?)
    }
}

/// An asset identity sent by the app as JSON.
pub(crate) fn parse_key(key: &str) -> Result<AssetKey, GalleryError> {
    serde_json::from_str(key).map_err(|_| GalleryError::Operation {
        detail: "Invalid photo identity".into(),
    })
}
