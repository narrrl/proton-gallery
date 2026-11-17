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

#[uniffi::export]
impl GalleryEngine {
    pub fn restore_session(&self) -> Result<(), GalleryError> {
        self.runtime.block_on(self.auth.restore())?;
        Ok(())
    }
    pub fn account_status(&self) -> AccountStatus {
        let s = self.auth.status();
        AccountStatus {
            stage: match s.stage {
                Stage::SignedOut => SignInStage::SignedOut,
                Stage::SecondFactor => SignInStage::SecondFactor,
                Stage::MailboxPassword => SignInStage::MailboxPassword,
                Stage::Verification => SignInStage::Verification,
                Stage::Connected => SignInStage::Connected,
            },
            username: s.username,
            verification_url: s.verification_url,
            persistence_failed: s.persistence_failed,
        }
    }
    pub fn sign_in(&self, username: String, password: String) -> Result<(), GalleryError> {
        let password = zeroize::Zeroizing::new(password);
        self.runtime.block_on(
            self.auth
                .begin(username, password.as_bytes().to_vec(), None),
        )?;
        Ok(())
    }
    pub fn submit_second_factor(&self, code: String) -> Result<(), GalleryError> {
        self.runtime.block_on(self.auth.second_factor(code))?;
        Ok(())
    }
    pub fn submit_mailbox_password(&self, password: String) -> Result<(), GalleryError> {
        let password = zeroize::Zeroizing::new(password);
        self.runtime
            .block_on(self.auth.mailbox_password(password.as_bytes().to_vec()))?;
        Ok(())
    }
    pub fn submit_verification(&self, token: String) -> Result<(), GalleryError> {
        self.runtime
            .block_on(self.auth.begin(String::new(), vec![], Some(token)))?;
        Ok(())
    }
    pub fn cancel_sign_in(&self) -> Result<(), GalleryError> {
        self.runtime.block_on(self.auth.reset(false))?;
        Ok(())
    }
    pub fn sign_out(&self) -> Result<(), GalleryError> {
        self.runtime.block_on(self.auth.reset(true))?;
        *self.photos.write().unwrap() = None;
        Ok(())
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
        self.runtime.block_on(async {
        let _guard=self.photos_sync.lock().await;
        let account=self.auth.account().await?;
        self.indexed.store(0,Ordering::Relaxed);
        let photos=proton_drive_rs::ProtonPhotosClient::from_drive_client(account.drive);
        let root=tokio::select! {biased;
            _=account.cancel.cancelled()=>return Err(pg_core::Error::Cancelled.into()),
            result=tokio::time::timeout(std::time::Duration::from_secs(60),photos.get_photos_root())=>result.map_err(|_|GalleryError::Operation{detail:"Proton took too long to respond. Try refreshing again.".into()})?.map_err(|e|{tracing::warn!("Photos root failed: {}",pg_drive::auth::cause_chain(&e));GalleryError::Operation{detail:"Couldn’t open Proton Photos. Check your connection, or sign in again if your session has expired.".into()}})?,
        };
        let Some(root)=root else {return Ok(0);};
        let albums=photos.clone();
        let source=Arc::new(pg_drive::photos::ProtonPhotosSource::new(photos,account.id.clone(),&root)?);
        {
            let mut cache=self.photos.write().unwrap();
            if account.cancel.is_cancelled() {return Err(pg_core::Error::Cancelled.into());}
            *cache=Some(source.clone());
        }
        let checkpoint=pg_core::indexer::scan(&self.catalog,source.as_ref(),true,&account.cancel,|p|{self.indexed.store(p.indexed,Ordering::Relaxed);}).await?;
        // A complete listing is the truth: photos it no longer has were
        // deleted elsewhere (or restored items return through the scan).
        use pg_core::source::MediaSource;
        if checkpoint.complete && let Some(listed)=source.listed_nodes() {
            let removed=self.catalog.prune_source(source.spec().id.clone(),listed).await?;
            if removed>0 { tracing::info!("{removed} photos are no longer in Proton Photos"); }
        }
        tokio::select!{biased; _=account.cancel.cancelled()=>{}, _=self.sync_albums(&albums,&account.id)=>{}}
        Ok(checkpoint.indexed)
    })
    }
    /// Small decrypted previews stay in the caller's bounded memory cache.
    pub fn photo_thumbnail(&self, key: String) -> Result<Option<Vec<u8>>, GalleryError> {
        self.runtime.block_on(async {
            let key: AssetKey =
                serde_json::from_str(&key).map_err(|_| GalleryError::Operation {
                    detail: "Invalid photo identity".into(),
                })?;
            let account = self.auth.account().await?;
            if account.id != key.account {
                return Err(pg_core::Error::NotFound.into());
            }
            use pg_core::source::MediaSource;
            let cached = self.photos.read().unwrap().as_ref().filter(|source|source.spec().account==account.id).cloned();
            let photos_volume = match &cached {
                Some(source) => Some(source.spec().volume.clone()),
                None => self.catalog.account_sources(SourceKind::Photos, account.id.clone()).await?.first().map(|s| s.volume.clone()),
            };
            if photos_volume.as_ref() != Some(&key.volume) {
                // Drive gallery media lives outside the Photos volume.
                let uid = pg_drive::node_uid(&key);
                return tokio::select! {biased;
                    _=account.cancel.cancelled()=>Err(pg_core::Error::Cancelled.into()),
                    result=account.drive.download_thumbnail(&uid,proton_drive_rs::ThumbnailType::Thumbnail)=>{
                        let bytes=result.map_err(|e|{tracing::warn!("Drive thumbnail failed: {}",pg_drive::auth::cause_chain(&e));GalleryError::Operation{detail:"Couldn’t load this preview.".into()}})?;
                        if bytes.as_ref().is_some_and(|b|b.len()>4*1024*1024) { return Err(pg_core::Error::Invalid("thumbnail exceeds budget".into()).into()); }
                        Ok(bytes)
                    }
                };
            }
            let source = match cached {
                Some(source) => source,
                None => {
                    let photos=proton_drive_rs::ProtonPhotosClient::from_drive_client(account.drive.clone());
                    let root=tokio::select! {biased;
                        _=account.cancel.cancelled()=>return Err(pg_core::Error::Cancelled.into()),
                        result=photos.get_photos_root()=>result.map_err(|_|GalleryError::Operation{detail:"Couldn’t load Proton Photos previews.".into()})?.ok_or(pg_core::Error::NotFound)?,
                    };
                    let source=Arc::new(pg_drive::photos::ProtonPhotosSource::new(photos,account.id.clone(),&root)?);
                    {
                        let mut cache=self.photos.write().unwrap();
                        if account.cancel.is_cancelled() {return Err(pg_core::Error::Cancelled.into());}
                        *cache=Some(source.clone());
                    }
                    source
                }
            };
            Ok(source.thumbnail(&key, &account.cancel).await?)
        })
    }
    pub fn remember_photo_shape(
        &self,
        key: String,
        width: u32,
        height: u32,
    ) -> Result<(), GalleryError> {
        self.runtime.block_on(async {
            let key: AssetKey =
                serde_json::from_str(&key).map_err(|_| GalleryError::Operation {
                    detail: "Invalid photo identity".into(),
                })?;
            if self.auth.account_id().as_ref() != Some(&key.account) {
                return Err(pg_core::Error::NotFound.into());
            }
            self.catalog.remember_shape(key, width, height).await?;
            Ok(())
        })
    }
}
