//! The desktop app: sign-in, the Photos timeline, and the photo viewer.
//!
//! Presentation state (navigation, scroll position, focus, the open photo,
//! forms) lives here; the library, account and media come from `pg-app`.
//! Every asynchronous result carries the generation it started under and
//! the request it answers, and is dropped when either is out of date.
use crate::grid::{self, Layout, RowKind};
use crate::thumbs::{self, Decoded};
use crate::vault;
use iced::keyboard::{self, Key, key::Named};
use iced::widget::{
    Space, button, center, checkbox, column, container, image, operation, row, scrollable, text,
    text_input,
};
use iced::{
    Alignment, Color, ContentFit, Element, Length, Size, Subscription, Task, Theme, window,
};
use pg_app::fixtures::{FixtureMedia, MediaProvider};
use pg_app::{AccountState, App, Generation, MediaItem, Outline, SignInStage, View};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use zeroize::Zeroizing;

/// Items per request for records.
const PAGE: usize = 100;
/// Record pages kept around the viewport.
const KEPT_PAGES: usize = 24;
const PAGES_IN_FLIGHT: usize = 4;
const ORIGINAL_LIMIT: u64 = 32 * 1024 * 1024;
/// Thumbnail requests running at once.
const THUMBS_IN_FLIGHT: usize = 12;
/// Target row height of the timeline.
const ROW_TARGET: u32 = 200;
const SIDEBAR: f32 = 220.0;
const TOOLBAR: f32 = 56.0;
/// Room for the vertical scrollbar beside the grid.
const SCROLLBAR: f32 = 14.0;

fn grid_id() -> iced::widget::Id {
    iced::widget::Id::new("timeline")
}

#[derive(Clone, Debug)]
pub struct Options {
    /// Browse a synthetic library of this many items instead of an account.
    pub demo: Option<usize>,
    pub data_dir: Option<PathBuf>,
}

enum Phase {
    Starting,
    Failed(String),
    Ready(Arc<App>),
}

#[derive(Default)]
struct SignInForm {
    username: String,
    password: Zeroizing<String>,
    code: String,
    mailbox: Zeroizing<String>,
    busy: bool,
    error: Option<String>,
    /// The user agreed to a sign-in that ends with the app.
    temporary: bool,
    verification: String,
}

#[derive(Default)]
struct SyncState {
    running: bool,
    indexed: u64,
    error: Option<String>,
}

/// The photo open in the viewer.
struct Viewer {
    index: usize,
    item: Option<MediaItem>,
    image: Option<Decoded>,
    loading: bool,
    message: Option<String>,
}

pub struct Gallery {
    options: Options,
    phase: Phase,
    keyring: bool,
    account: AccountState,
    form: SignInForm,
    sync: SyncState,
    view: View,
    /// Answers to older outline requests are ignored.
    outline_request: u64,
    outline: Option<Arc<Outline>>,
    /// Shapes known here, which may be newer than the outline's.
    ratios: Vec<f32>,
    layout: Layout,
    relayout: bool,
    window: Size,
    /// The grid's width as the scrollable reported it, once it has.
    measured_width: Option<f32>,
    scroll_top: f32,
    viewport_height: f32,
    pages: BTreeMap<usize, Vec<Option<MediaItem>>>,
    library_request: u64,
    outline_work: Option<iced::task::Handle>,
    pages_loading: BTreeMap<usize, iced::task::Handle>,
    pages_failed: HashSet<usize>,
    thumbs: thumbs::Cache,
    thumbs_loading: HashMap<String, iced::task::Handle>,
    thumbs_failed: HashSet<String>,
    focus: Option<usize>,
    viewer: Option<Viewer>,
    viewer_request: u64,
    originals: PathBuf,
    viewer_work: Option<iced::task::Handle>,
    cleanup_failed: bool,
    account_request: u64,
    captcha_page: Option<Arc<tempfile::TempPath>>,
    last_frame: Option<std::time::Instant>,
}

#[derive(Debug, Clone)]
pub enum Message {
    Started(Result<Started, String>),
    RetryStart,
    Frame(std::time::Instant),
    Restore,
    Verification(String),
    SubmitVerification,
    CopyVerificationUrl,
    OpenVerification,
    VerificationOpened(u64, Result<Arc<tempfile::TempPath>, String>),
    WindowSize(Size),
    Username(String),
    Password(String),
    Code(String),
    Mailbox(String),
    Temporary(bool),
    SignIn,
    SubmitCode,
    SubmitMailbox,
    CancelSignIn,
    Account(u64, Result<AccountState, String>),
    SignOut,
    SignedOut(Result<(), String>),
    Sync,
    SyncTick,
    Synced(Generation, Result<u64, pg_app::Error>),
    DemoReady(Result<usize, String>),
    OutlineLoaded(u64, Generation, Result<Arc<Outline>, String>),
    Relayout,
    Scrolled(scrollable::Viewport),
    PageLoaded(
        u64,
        Generation,
        usize,
        Result<Vec<Option<MediaItem>>, String>,
    ),
    ThumbLoaded(
        u64,
        Generation,
        String,
        usize,
        Result<Option<Decoded>, String>,
    ),
    Open(usize),
    Close,
    RetryViewer,
    Step(i32),
    ViewerLoaded(u64, Generation, Result<Box<Loaded>, String>),
    Key(keyboard::Event),
    Ignored,
}

/// What the viewer shows: the item, its picture, and a note about it.
/// Boxed in [`Message`], which stays small.
type Started = (Arc<App>, bool, AccountState, Option<String>);

type Loaded = (MediaItem, Option<Decoded>, Option<String>);

/// Run `f` with the app on the runtime and map its answer to a message.
fn spawn<T, F>(
    app: &Arc<App>,
    f: impl FnOnce(Arc<App>) -> F + Send + 'static,
    done: impl Fn(T) -> Message + Send + 'static,
) -> Task<Message>
where
    F: Future<Output = T> + Send + 'static,
    T: Send + 'static,
{
    let app = app.clone();
    Task::perform(async move { f(app).await }, done)
}

fn shown(error: pg_app::Error) -> String {
    error.to_string()
}

impl Gallery {
    pub fn boot(options: Options) -> (Self, Task<Message>) {
        let data_dir = options.data_dir.clone().unwrap_or_else(default_data_dir);
        let originals = data_dir.join("cache").join("originals");
        let demo = options.demo.is_some();
        let gallery = Self {
            options,
            phase: Phase::Starting,
            keyring: true,
            account: AccountState {
                stage: SignInStage::SignedOut,
                username: String::new(),
                verification_url: None,
                persistence_failed: false,
            },
            form: SignInForm::default(),
            sync: SyncState::default(),
            view: if demo {
                View::Source("demo".into())
            } else {
                View::Photos
            },
            outline_request: 0,
            outline: None,
            ratios: Vec::new(),
            layout: Layout::default(),
            relayout: false,
            window: Size::new(1200.0, 800.0),
            measured_width: None,
            scroll_top: 0.0,
            viewport_height: 800.0,
            pages: BTreeMap::new(),
            library_request: 0,
            outline_work: None,
            pages_loading: BTreeMap::new(),
            pages_failed: HashSet::new(),
            thumbs: thumbs::Cache::default(),
            thumbs_loading: HashMap::new(),
            thumbs_failed: HashSet::new(),
            focus: None,
            viewer: None,
            viewer_request: 0,
            originals: originals.clone(),
            viewer_work: None,
            cleanup_failed: false,
            account_request: 0,
            captcha_page: None,
            last_frame: None,
        };
        let start = Task::perform(
            async move {
                // Decrypted originals from an earlier run are not kept.
                let _ = std::fs::remove_dir_all(&originals);
                if demo {
                    let app = App::open(&data_dir, Arc::new(vault::TemporaryVault::default()))
                        .await
                        .map_err(shown)?;
                    let state = app.account_state();
                    return Ok((Arc::new(app), true, state, None));
                }
                let keyring = tokio::task::spawn_blocking(vault::keyring_available)
                    .await
                    .unwrap_or(false);
                let vault: Arc<dyn pg_drive::auth::CredentialVault> = if keyring {
                    Arc::new(vault::KeyringVault)
                } else {
                    Arc::new(vault::TemporaryVault::default())
                };
                let app = App::open(&data_dir, vault).await.map_err(shown)?;
                let restore_error = app.restore_session().await.err().map(shown);
                let state = app.account_state();
                Ok((Arc::new(app), keyring, state, restore_error))
            },
            Message::Started,
        );
        let size = window::latest()
            .and_then(window::size)
            .map(Message::WindowSize);
        (gallery, Task::batch([start, size]))
    }

    pub fn scale_factor(&self) -> f32 {
        std::env::var("PG_SCALE_FACTOR")
            .ok()
            .and_then(|s| s.parse::<f32>().ok())
            .filter(|scale| scale.is_finite() && (0.5..=3.0).contains(scale))
            .unwrap_or(1.0)
    }

    pub fn title(&self) -> String {
        match &self.viewer {
            Some(viewer) => match &viewer.item {
                Some(item) => format!("{} – Proton Gallery", item.name),
                None => "Proton Gallery".into(),
            },
            None => "Proton Gallery".into(),
        }
    }

    pub fn theme(&self) -> Option<Theme> {
        // Follow the system's light or dark setting.
        None
    }

    pub fn subscription(&self) -> Subscription<Message> {
        let mut subscriptions = vec![
            window::resize_events().map(|(_, size)| Message::WindowSize(size)),
            keyboard::listen().map(Message::Key),
        ];
        if std::env::var_os("PG_METRICS").is_some() {
            subscriptions.push(window::frames().map(Message::Frame));
        }
        if self.sync.running {
            subscriptions
                .push(iced::time::every(Duration::from_millis(700)).map(|_| Message::SyncTick));
        }
        if self.relayout {
            subscriptions
                .push(iced::time::every(Duration::from_millis(250)).map(|_| Message::Relayout));
        }
        Subscription::batch(subscriptions)
    }

    fn app(&self) -> Option<&Arc<App>> {
        match &self.phase {
            Phase::Ready(app) => Some(app),
            _ => None,
        }
    }

    fn demo(&self) -> bool {
        self.options.demo.is_some()
    }

    fn browsing(&self) -> bool {
        self.demo() || self.account.stage == SignInStage::Connected
    }

    pub fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::Frame(now) => {
                if let Some(last) = self.last_frame.replace(now) {
                    eprintln!(
                        "PG_METRICS frame_us={} pages={} thumbnails={}",
                        now.duration_since(last).as_micros(),
                        self.pages_loading.len(),
                        self.thumbs_loading.len()
                    );
                }
                Task::none()
            }
            Message::Started(Ok((app, keyring, state, restore_error))) => {
                self.form.error = restore_error;
                self.keyring = keyring;
                self.cleanup_failed = app.cleanup_pending();
                self.account = state;
                self.phase = Phase::Ready(app.clone());
                if let Some(count) = self.options.demo {
                    self.sync.running = true;
                    return spawn(
                        &app,
                        move |app| async move {
                            let source = pg_core::synthetic::SyntheticSource::new(count)
                                .map_err(|e| e.to_string())?;
                            let cancel = pg_core::CancellationToken::new();
                            let done = app
                                .index_source(&source, app.generation(), &cancel, |_| {})
                                .await
                                .map_err(shown)?;
                            Ok(done.indexed as usize)
                        },
                        Message::DemoReady,
                    );
                }
                if self.browsing() {
                    return Task::batch([self.load_outline(), self.start_sync()]);
                }
                Task::none()
            }
            Message::Started(Err(error)) => {
                self.phase = Phase::Failed(error);
                Task::none()
            }
            Message::DemoReady(result) => {
                self.sync.running = false;
                if let Err(error) = result {
                    self.sync.error = Some(error);
                }
                self.load_outline()
            }
            Message::RetryStart => {
                let (next, task) = Self::boot(self.options.clone());
                *self = next;
                task
            }
            Message::Restore => {
                let Some(app) = self.app().cloned() else {
                    return Task::none();
                };
                if self.form.busy {
                    return Task::none();
                }
                self.account_request += 1;
                let request = self.account_request;
                self.form.busy = true;
                spawn(
                    &app,
                    |app| async move { app.restore_session().await.map_err(shown) },
                    move |result| Message::Account(request, result),
                )
            }
            Message::Verification(value) => {
                self.form.verification = value;
                Task::none()
            }
            Message::OpenVerification => {
                let Some(url) = self.account.verification_url.clone() else {
                    return Task::none();
                };
                let request = self.account_request;
                Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || crate::captcha::open(&url))
                            .await
                            .map_err(|e| e.to_string())?
                            .map(Arc::new)
                    },
                    move |result| Message::VerificationOpened(request, result),
                )
            }
            Message::VerificationOpened(request, result) => {
                if request == self.account_request
                    && self.account.stage == SignInStage::Verification
                {
                    match result {
                        Ok(page) => self.captcha_page = Some(page),
                        Err(error) => self.form.error = Some(error),
                    }
                }
                Task::none()
            }
            Message::CopyVerificationUrl => self
                .account
                .verification_url
                .clone()
                .map(iced::clipboard::write)
                .unwrap_or_else(Task::none),
            Message::SubmitVerification => {
                let Some(app) = self.app().cloned() else {
                    return Task::none();
                };
                if self.form.busy {
                    return Task::none();
                }
                self.account_request += 1;
                let request = self.account_request;
                self.form.busy = true;
                self.form.error = None;
                let token = std::mem::take(&mut self.form.verification)
                    .trim()
                    .to_owned();
                spawn(
                    &app,
                    move |app| async move { app.submit_verification(token).await.map_err(shown) },
                    move |result| Message::Account(request, result),
                )
            }
            Message::WindowSize(size) => {
                self.window = size;
                // The next scroll event measures the grid again.
                self.measured_width = None;
                self.viewport_height = (size.height - TOOLBAR).max(1.0);
                self.replan()
            }
            Message::Username(value) => {
                self.form.username = value;
                Task::none()
            }
            Message::Password(value) => {
                self.form.password = Zeroizing::new(value);
                Task::none()
            }
            Message::Code(value) => {
                self.form.code = value;
                Task::none()
            }
            Message::Mailbox(value) => {
                self.form.mailbox = Zeroizing::new(value);
                Task::none()
            }
            Message::Temporary(value) => {
                self.form.temporary = value;
                Task::none()
            }
            Message::SignIn => {
                let Some(app) = self.app().cloned() else {
                    return Task::none();
                };
                if self.form.busy || (!self.keyring && !self.form.temporary) {
                    return Task::none();
                }
                self.account_request += 1;
                let request = self.account_request;
                self.form.busy = true;
                self.form.error = None;
                let username = self.form.username.trim().to_owned();
                let password = std::mem::take(&mut self.form.password);
                spawn(
                    &app,
                    move |app| async move { app.sign_in(username, password).await.map_err(shown) },
                    move |result| Message::Account(request, result),
                )
            }
            Message::SubmitCode => {
                let Some(app) = self.app().cloned() else {
                    return Task::none();
                };
                if self.form.busy {
                    return Task::none();
                }
                self.account_request += 1;
                let request = self.account_request;
                self.form.busy = true;
                self.form.error = None;
                let code = std::mem::take(&mut self.form.code).trim().to_owned();
                spawn(
                    &app,
                    move |app| async move { app.submit_second_factor(code).await.map_err(shown) },
                    move |result| Message::Account(request, result),
                )
            }
            Message::SubmitMailbox => {
                let Some(app) = self.app().cloned() else {
                    return Task::none();
                };
                if self.form.busy {
                    return Task::none();
                }
                self.account_request += 1;
                let request = self.account_request;
                self.form.busy = true;
                self.form.error = None;
                let password = std::mem::take(&mut self.form.mailbox);
                spawn(
                    &app,
                    move |app| async move { app.submit_mailbox_password(password).await.map_err(shown) },
                    move |result| Message::Account(request, result),
                )
            }
            Message::CancelSignIn => {
                let Some(app) = self.app().cloned() else {
                    return Task::none();
                };
                self.account_request += 1;
                let request = self.account_request;
                self.form = SignInForm {
                    busy: true,
                    temporary: self.form.temporary,
                    ..SignInForm::default()
                };
                spawn(
                    &app,
                    |app| async move { app.cancel_sign_in().await.map_err(shown) },
                    move |result| Message::Account(request, result),
                )
            }
            Message::Account(request, result) => {
                if request != self.account_request {
                    return Task::none();
                }
                self.form.busy = false;
                self.captcha_page = None;
                match result {
                    Ok(state) => {
                        let connected = state.stage == SignInStage::Connected
                            && self.account.stage != SignInStage::Connected;
                        self.account = state;
                        if connected {
                            self.form = SignInForm {
                                temporary: self.form.temporary,
                                ..SignInForm::default()
                            };
                            return Task::batch([self.load_outline(), self.start_sync()]);
                        }
                    }
                    Err(error) => {
                        self.form.error = Some(error);
                        if let Some(app) = self.app() {
                            self.account = app.account_state();
                        }
                    }
                }
                Task::none()
            }
            Message::SignOut => {
                if self.form.busy {
                    return Task::none();
                }
                let Some(app) = self.app().cloned() else {
                    return Task::none();
                };
                // Nothing of the account stays on screen, even if signing out fails.
                self.account_request += 1;
                self.captcha_page = None;
                self.forget_library();
                self.account.stage = SignInStage::SignedOut;
                self.form.busy = true;
                self.sync = SyncState::default();
                spawn(
                    &app,
                    move |app| async move { app.sign_out().await.map_err(shown) },
                    Message::SignedOut,
                )
            }
            Message::SignedOut(result) => {
                self.forget_library();
                self.cleanup_failed = result.is_err();
                if let Some(app) = self.app() {
                    self.account = app.account_state();
                }
                self.sync = SyncState::default();
                self.form = SignInForm {
                    error: result.err(),
                    ..SignInForm::default()
                };
                Task::none()
            }
            Message::Sync => {
                self.pages_failed.clear();
                self.thumbs_failed.clear();
                if self.demo() {
                    self.load_outline()
                } else {
                    self.start_sync()
                }
            }
            Message::SyncTick => {
                if !self.browsing() {
                    return Task::none();
                }
                if let Some(app) = self.app() {
                    let indexed = app.indexed.load(std::sync::atomic::Ordering::Relaxed);
                    let grew = indexed > self.sync.indexed;
                    self.sync.indexed = indexed;
                    // Show what has arrived so far.
                    if grew {
                        return self.load_outline();
                    }
                }
                Task::none()
            }
            Message::Synced(generation, result) => {
                let Some(app) = self.app() else {
                    return Task::none();
                };
                if app.ensure_current(generation).is_err() {
                    return Task::none();
                }
                self.sync.running = false;
                match result {
                    Ok(_) => self.sync.error = None,
                    Err(pg_app::Error::Cancelled | pg_app::Error::Stale) => {}
                    Err(error) => self.sync.error = Some(error.to_string()),
                }
                self.load_outline()
            }
            Message::OutlineLoaded(request, generation, result) => {
                if request != self.outline_request || !self.current(generation) {
                    return Task::none();
                }
                self.outline_work = None;
                match result {
                    Ok(outline) => self.show_outline(outline),
                    Err(error) => {
                        self.sync.error = Some(error);
                        Task::none()
                    }
                }
            }
            Message::Relayout => {
                self.relayout = false;
                self.replan()
            }
            Message::Scrolled(viewport) => {
                self.scroll_top = viewport.absolute_offset().y;
                self.viewport_height = viewport.bounds().height;
                // The content is padded by one gap on each side.
                let width = viewport.bounds().width - 2.0 * grid::GAP as f32;
                if width > 0.0 && self.measured_width != Some(width) {
                    self.measured_width = Some(width);
                    if (width - self.layout.width as f32).abs() >= 1.0 {
                        return self.replan_to(width);
                    }
                }
                self.request_visible()
            }
            Message::PageLoaded(request, generation, page, result) => {
                if request != self.library_request || !self.current(generation) {
                    return Task::none();
                }
                self.pages_loading.remove(&page);
                match result {
                    Ok(items) => {
                        self.pages.insert(page, items);
                    }
                    Err(error) => {
                        self.pages_failed.insert(page);
                        self.sync.error = Some(format!(
                            "Couldn’t load a page: {error}. Use Refresh to retry."
                        ));
                    }
                }
                self.request_visible()
            }
            Message::ThumbLoaded(request, generation, id, index, result) => {
                if request != self.library_request || !self.current(generation) {
                    return Task::none();
                }
                self.thumbs_loading.remove(&id);
                match result {
                    Ok(Some(decoded)) => {
                        let task = self.learn_shape(index, &decoded);
                        self.thumbs.insert(id, decoded, thumbs::BUDGET);
                        return Task::batch([task, self.request_visible()]);
                    }
                    Ok(None) => {
                        self.thumbs_failed.insert(id);
                    }
                    Err(error) => {
                        tracing::debug!("thumbnail failed: {error}");
                        self.thumbs_failed.insert(id);
                    }
                }
                self.request_visible()
            }
            Message::Open(index) => {
                self.focus = Some(index);
                self.open(index)
            }
            Message::RetryViewer => {
                if let Some(index) = self.viewer.as_ref().map(|v| v.index) {
                    self.open(index)
                } else {
                    Task::none()
                }
            }
            Message::Close => {
                if let Some(work) = self.viewer_work.take() {
                    work.abort();
                }
                self.viewer = None;
                self.viewer_request += 1;
                self.scroll_to_focus()
            }
            Message::Step(delta) => {
                let Some(viewer) = &self.viewer else {
                    return Task::none();
                };
                let count = self.ratios.len();
                let next = viewer.index as i64 + i64::from(delta);
                if next < 0 || next as usize >= count {
                    return Task::none();
                }
                self.focus = Some(next as usize);
                self.open(next as usize)
            }
            Message::ViewerLoaded(request, generation, result) => {
                if request != self.viewer_request || !self.current(generation) {
                    return Task::none();
                }
                if let Some(work) = self.viewer_work.take() {
                    work.abort();
                }
                if let Some(viewer) = &mut self.viewer {
                    viewer.loading = false;
                    match result {
                        Ok(loaded) => {
                            let (item, image, message) = *loaded;
                            viewer.item = Some(item);
                            if image.is_some() {
                                viewer.image = image;
                            }
                            viewer.message = message;
                        }
                        Err(error) => viewer.message = Some(error),
                    }
                }
                Task::none()
            }
            Message::Key(event) => self.key(event),
            Message::Ignored => Task::none(),
        }
    }

    fn current(&self, generation: Generation) -> bool {
        self.browsing()
            && self
                .app()
                .is_some_and(|app| app.ensure_current(generation).is_ok())
    }

    fn start_sync(&mut self) -> Task<Message> {
        if !self.browsing() {
            return Task::none();
        }
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        if self.sync.running || self.demo() {
            return Task::none();
        }
        self.sync.running = true;
        self.sync.error = None;
        self.sync.indexed = 0;
        let generation = app.generation();
        spawn(
            &app,
            |app| async move { app.sync_photos(|_| {}).await },
            move |result| Message::Synced(generation, result),
        )
    }

    fn load_outline(&mut self) -> Task<Message> {
        if !self.browsing() {
            return Task::none();
        }
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        self.outline_work = None;
        self.outline_request += 1;
        let request = self.outline_request;
        let generation = app.generation();
        let view = self.view.clone();
        let (task, handle) = spawn(
            &app,
            move |app| async move { app.outline(view).await.map(Arc::new).map_err(shown) },
            move |result| Message::OutlineLoaded(request, generation, result),
        )
        .abortable();
        self.outline_work = Some(handle.abort_on_drop());
        task
    }

    /// Show a new outline, keeping the item at the top of the viewport in place.
    fn show_outline(&mut self, outline: Arc<Outline>) -> Task<Message> {
        if std::env::var_os("PG_METRICS").is_some() {
            eprintln!("PG_METRICS outline_items={}", outline.len());
        }
        let anchor_id = self
            .layout
            .anchor(self.scroll_top)
            .and_then(|(item, offset)| {
                self.outline
                    .as_ref()
                    .and_then(|old| old.ids.get(item))
                    .map(|id| (id.clone(), offset))
            });
        let focus_id = self
            .focus
            .and_then(|i| self.outline.as_ref()?.ids.get(i).cloned());
        // Preserve learned shapes across incremental sync snapshots.
        let learned: HashMap<&str, f32> = self
            .outline
            .as_ref()
            .map(|old| {
                old.ids
                    .iter()
                    .map(String::as_str)
                    .zip(self.ratios.iter().copied())
                    .collect()
            })
            .unwrap_or_default();
        self.ratios = outline
            .ids
            .iter()
            .zip(&outline.ratios)
            .map(|(id, &ratio)| {
                learned
                    .get(id.as_str())
                    .copied()
                    .filter(|r| *r > 0.0)
                    .unwrap_or(ratio)
            })
            .collect();
        self.library_request += 1;
        self.pages.clear();
        self.pages_loading.clear();
        self.pages_failed.clear();
        self.thumbs_loading.clear();
        let find = |id: &str| outline.ids.iter().position(|other| other == id);
        let anchor = anchor_id.and_then(|(id, offset)| Some((find(&id)?, offset)));
        self.focus = focus_id.and_then(|id| find(&id));
        let viewer_index = self
            .viewer
            .as_ref()
            .and_then(|v| self.outline.as_ref()?.ids.get(v.index))
            .and_then(|id| find(id));
        if let Some(viewer) = &mut self.viewer {
            if let Some(index) = viewer_index {
                viewer.index = index;
            } else {
                self.viewer_request += 1;
                self.viewer = None;
                if let Some(work) = self.viewer_work.take() {
                    work.abort();
                }
            }
        }
        self.outline = Some(outline);
        self.layout = Layout::plan(
            &self.ratios,
            &self.outline.as_ref().map_or(vec![], |o| o.times.clone()),
            self.grid_width() as u32,
            ROW_TARGET,
        );
        let scroll = self.restore(anchor);
        Task::batch([scroll, self.request_visible()])
    }

    /// The width rows fill: measured by the scrollable, or estimated from
    /// the window before the first measurement.
    fn grid_width(&self) -> f32 {
        self.measured_width
            .unwrap_or(
                self.window.width
                    - (if self.window.width < 760.0 {
                        0.0
                    } else {
                        SIDEBAR
                    })
                    - SCROLLBAR
                    - 2.0 * grid::GAP as f32,
            )
            .max(200.0)
    }

    fn replan(&mut self) -> Task<Message> {
        self.replan_to(self.grid_width())
    }

    fn replan_to(&mut self, width: f32) -> Task<Message> {
        let Some(outline) = &self.outline else {
            return Task::none();
        };
        let anchor = self.layout.anchor(self.scroll_top);
        self.layout = Layout::plan(&self.ratios, &outline.times, width as u32, ROW_TARGET);
        let scroll = self.restore(anchor);
        Task::batch([scroll, self.request_visible()])
    }

    fn restore(&mut self, anchor: Option<(usize, f32)>) -> Task<Message> {
        let top = anchor.and_then(|a| self.layout.restore(a)).unwrap_or(0.0);
        self.scroll_top = top;
        operation::scroll_to(
            grid_id(),
            scrollable::AbsoluteOffset {
                x: None,
                y: Some(top),
            },
        )
    }

    /// A decoded thumbnail shows the item's real shape: keep it, and lay
    /// the grid out again soon, all learned shapes at once.
    fn learn_shape(&mut self, index: usize, decoded: &Decoded) -> Task<Message> {
        if decoded.height == 0 {
            return Task::none();
        }
        let ratio = decoded.width as f32 / decoded.height as f32;
        let Some(known) = self.ratios.get_mut(index) else {
            return Task::none();
        };
        if *known > 0.0 && (*known - ratio).abs() < 0.01 {
            return Task::none();
        }
        *known = ratio;
        self.relayout = true;
        let (Some(app), Some(item)) = (self.app().cloned(), self.item(index).cloned()) else {
            return Task::none();
        };
        if self.demo() {
            return Task::none();
        }
        let (width, height) = (decoded.width, decoded.height);
        Task::perform(
            async move { app.remember_shape(item.key, width, height).await },
            |_| Message::Ignored,
        )
    }

    fn item(&self, index: usize) -> Option<&MediaItem> {
        self.pages.get(&(index / PAGE))?.get(index % PAGE)?.as_ref()
    }

    /// Ask for the records and thumbnails of what is near the viewport, and
    /// forget records far from it.
    fn request_visible(&mut self) -> Task<Message> {
        if !self.browsing() {
            return Task::none();
        }
        let (Some(app), Some(outline)) = (self.app().cloned(), self.outline.clone()) else {
            return Task::none();
        };
        let rows = self.layout.visible(self.scroll_top, self.viewport_height);
        let items = self.layout.items_in(rows);
        if items.is_empty() {
            return Task::none();
        }
        let generation = app.generation();
        let request = self.library_request;
        let mut tasks = Vec::new();
        let first_page = items.start / PAGE;
        let last_page = (items.end - 1) / PAGE;
        let keep = first_page.saturating_sub(KEPT_PAGES / 2)..=last_page + KEPT_PAGES / 2;
        self.pages.retain(|page, _| keep.contains(page));
        for page in first_page..=last_page {
            if self.pages_loading.len() >= PAGES_IN_FLIGHT {
                break;
            }
            if self.pages.contains_key(&page)
                || self.pages_failed.contains(&page)
                || self.pages_loading.contains_key(&page)
            {
                continue;
            }
            let outline = outline.clone();
            let (task, handle) = spawn(
                &app,
                move |app| async move {
                    app.outline_items(&outline, page * PAGE, PAGE)
                        .await
                        .map_err(shown)
                },
                move |result| Message::PageLoaded(request, generation, page, result),
            )
            .abortable();
            self.pages_loading.insert(page, handle.abort_on_drop());
            tasks.push(task);
        }
        let visible_ids: Vec<String> = items
            .clone()
            .filter_map(|i| outline.ids.get(i).cloned())
            .collect();
        self.thumbs.touch(visible_ids.iter().map(String::as_str));
        for index in items {
            if self.thumbs_loading.len() >= THUMBS_IN_FLIGHT {
                break;
            }
            let Some(id) = outline.ids.get(index).cloned() else {
                continue;
            };
            if self.thumbs.contains(&id)
                || self.thumbs_loading.contains_key(&id)
                || self.thumbs_failed.contains(&id)
            {
                continue;
            }
            let done = {
                let id = id.clone();
                move |result| Message::ThumbLoaded(request, generation, id.clone(), index, result)
            };
            let Some(item) = self.item(index).cloned() else {
                continue;
            };
            if !item.remote {
                continue;
            }
            let provider = self.provider(&app);
            let (task, handle) = Task::perform(load_thumbnail(provider, item), done).abortable();
            self.thumbs_loading.insert(id, handle.abort_on_drop());
            tasks.push(task);
        }
        Task::batch(tasks)
    }

    fn open(&mut self, index: usize) -> Task<Message> {
        if !self.browsing() {
            return Task::none();
        }
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        let Some(outline) = self.outline.clone() else {
            return Task::none();
        };
        self.viewer_request += 1;
        let request = self.viewer_request;
        let generation = app.generation();
        let placeholder = outline
            .ids
            .get(index)
            .and_then(|id| self.thumbs.get(id))
            .cloned();
        self.viewer = Some(Viewer {
            index,
            item: self.item(index).cloned(),
            image: placeholder,
            loading: true,
            message: None,
        });
        if let Some(work) = self.viewer_work.take() {
            work.abort();
        }
        let originals = self.originals.clone();
        let provider = self.provider(&app);
        let (task, handle) = spawn(
            &app,
            move |app| load_full(app, provider, outline, index, originals),
            move |result: Result<Loaded, String>| {
                Message::ViewerLoaded(request, generation, result.map(Box::new))
            },
        )
        .abortable();
        self.viewer_work = Some(handle.abort_on_drop());
        task
    }

    fn provider(&self, app: &Arc<App>) -> Arc<dyn MediaProvider> {
        if self.demo() {
            Arc::new(FixtureMedia)
        } else {
            app.clone()
        }
    }

    fn scroll_to_focus(&mut self) -> Task<Message> {
        let Some(focus) = self.focus else {
            return Task::none();
        };
        let Some((y, row_height)) = self.layout.item_bounds(focus) else {
            return Task::none();
        };
        let top = if y < self.scroll_top {
            y - grid::HEADING
        } else if y + row_height > self.scroll_top + self.viewport_height {
            y + row_height - self.viewport_height + grid::GAP as f32
        } else {
            return Task::none();
        };
        self.scroll_top = top.max(0.0);
        Task::batch([
            operation::scroll_to(
                grid_id(),
                scrollable::AbsoluteOffset {
                    x: None,
                    y: Some(self.scroll_top),
                },
            ),
            self.request_visible(),
        ])
    }

    fn key(&mut self, event: keyboard::Event) -> Task<Message> {
        let keyboard::Event::KeyPressed { key, modifiers, .. } = event else {
            return Task::none();
        };
        if self.viewer.is_some() {
            return match key {
                Key::Named(Named::Escape) => self.update(Message::Close),
                Key::Named(Named::ArrowLeft) => self.update(Message::Step(-1)),
                Key::Named(Named::ArrowRight) => self.update(Message::Step(1)),
                Key::Character(value) if value.eq_ignore_ascii_case("r") => {
                    self.update(Message::RetryViewer)
                }
                Key::Named(Named::F5) => self.update(Message::RetryViewer),
                _ => Task::none(),
            };
        }
        if self.browsing() && key == Key::Named(Named::F5) {
            return self.update(Message::Sync);
        }
        let count = self.ratios.len();
        if count == 0 || !self.browsing() {
            return Task::none();
        }
        let focus = self.focus.unwrap_or(0).min(count - 1);
        let page_rows = (self.viewport_height / ROW_TARGET as f32).max(1.0) as i32;
        let next = match key {
            Key::Named(Named::Enter | Named::Space) => return self.update(Message::Open(focus)),
            Key::Named(Named::Tab) => {
                if self.focus.is_none() {
                    0
                } else if modifiers.shift() {
                    focus.saturating_sub(1)
                } else {
                    (focus + 1).min(count - 1)
                }
            }
            Key::Named(Named::ArrowLeft) => focus.saturating_sub(1),
            Key::Named(Named::ArrowRight) => (focus + 1).min(count - 1),
            Key::Named(Named::ArrowUp) => self.layout.vertical_neighbor(focus, -1).unwrap_or(focus),
            Key::Named(Named::ArrowDown) => {
                self.layout.vertical_neighbor(focus, 1).unwrap_or(focus)
            }
            Key::Named(Named::PageUp) => (0..page_rows).fold(focus, |at, _| {
                self.layout.vertical_neighbor(at, -1).unwrap_or(at)
            }),
            Key::Named(Named::PageDown) => (0..page_rows).fold(focus, |at, _| {
                self.layout.vertical_neighbor(at, 1).unwrap_or(at)
            }),
            Key::Named(Named::Home) => 0,
            Key::Named(Named::End) => count - 1,
            _ => return Task::none(),
        };
        self.focus = Some(next);
        self.scroll_to_focus()
    }

    /// Drop everything shown of the library.
    fn forget_library(&mut self) {
        self.outline_request += 1;
        self.library_request += 1;
        self.outline_work = None;
        if let Some(work) = self.viewer_work.take() {
            work.abort();
        }
        self.pages_failed.clear();
        self.thumbs_failed.clear();
        self.viewer_request += 1;
        self.outline = None;
        self.ratios.clear();
        self.layout = Layout::default();
        self.pages.clear();
        self.pages_loading.clear();
        self.thumbs.clear();
        self.thumbs_loading.clear();
        self.focus = None;
        self.viewer = None;
        self.scroll_top = 0.0;
    }

    pub fn view(&self) -> Element<'_, Message> {
        let _timing = crate::metrics::ViewTiming::start();
        match &self.phase {
            Phase::Starting => center(text("Opening your library…")).into(),
            Phase::Failed(error) => center(
                column![
                    text("Proton Gallery couldn’t open its library").size(20),
                    text(error.as_str()),
                    button("Retry").on_press(Message::RetryStart)
                ]
                .spacing(8)
                .align_x(Alignment::Center),
            )
            .into(),
            Phase::Ready(_) if !self.browsing() => self.sign_in_view(),
            Phase::Ready(_) => match &self.viewer {
                Some(viewer) => self.viewer_view(viewer),
                None if self.window.width < 760.0 => column![self.toolbar(), self.content()].into(),
                None => row![self.sidebar(), column![self.toolbar(), self.content()]].into(),
            },
        }
    }

    fn sign_in_view(&self) -> Element<'_, Message> {
        let busy = self.form.busy;
        if self.cleanup_failed {
            return center(
                container(
                    column![
                        text("Sign-out cleanup needs another try").size(22),
                        text(
                            self.form
                                .error
                                .as_deref()
                                .unwrap_or("The system keyring or cache could not be cleared.")
                        ),
                        button("Retry cleanup").on_press_maybe((!busy).then_some(Message::SignOut)),
                    ]
                    .spacing(14),
                )
                .padding(24),
            )
            .into();
        }
        let mut card = column![text("Sign in to Proton").size(24)]
            .spacing(14)
            .width(Length::Fill)
            .max_width(360);
        match self.account.stage {
            SignInStage::SecondFactor => {
                card = card.push(text("Enter the code from your authenticator app."));
                card = card.push(
                    text_input("Two-factor code", &self.form.code)
                        .on_input(Message::Code)
                        .on_submit_maybe((!busy).then_some(Message::SubmitCode))
                        .padding(10),
                );
                card = card.push(row![
                    button("Cancel")
                        .on_press(Message::CancelSignIn)
                        .style(button::secondary),
                    Space::new().width(Length::Fill),
                    button("Continue").on_press_maybe((!busy).then_some(Message::SubmitCode)),
                ]);
            }
            SignInStage::MailboxPassword => {
                card = card.push(text("Enter your mailbox password."));
                card = card.push(
                    text_input("Mailbox password", &self.form.mailbox)
                        .secure(true)
                        .on_input(Message::Mailbox)
                        .on_submit_maybe((!busy).then_some(Message::SubmitMailbox))
                        .padding(10),
                );
                card = card.push(row![
                    button("Cancel")
                        .on_press(Message::CancelSignIn)
                        .style(button::secondary),
                    Space::new().width(Length::Fill),
                    button("Continue").on_press_maybe((!busy).then_some(Message::SubmitMailbox)),
                ]);
            }
            SignInStage::Verification => {
                card = card.push(text("Open the verification page, complete the CAPTCHA, then copy the displayed token and paste it here."));
                if let Some(url) = &self.account.verification_url {
                    card = card.push(text(url.as_str()).size(12));
                    card = card
                        .push(button("Open verification page").on_press(Message::OpenVerification));
                    card = card.push(
                        button("Copy verification page URL").on_press(Message::CopyVerificationUrl),
                    );
                }
                card = card.push(
                    text_input("Verification token", &self.form.verification)
                        .on_input(Message::Verification)
                        .on_submit_maybe((!busy).then_some(Message::SubmitVerification))
                        .padding(10),
                );
                card = card.push(
                    row![
                        button("Cancel").on_press(Message::CancelSignIn),
                        button("Continue").on_press_maybe(
                            (!busy && !self.form.verification.trim().is_empty())
                                .then_some(Message::SubmitVerification)
                        )
                    ]
                    .spacing(12),
                );
            }
            SignInStage::SignedOut | SignInStage::Connected => {
                if !self.keyring {
                    card = card.push(
                        container(
                            column![
                                text("Your system keyring isn’t available, so this sign-in can’t be saved."),
                                checkbox(self.form.temporary)
                                    .label("Sign in until I close the app")
                                    .on_toggle(Message::Temporary),
                            ]
                            .spacing(8),
                        )
                        .padding(12)
                        .style(container::bordered_box),
                    );
                }
                let ready = !busy
                    && (self.keyring || self.form.temporary)
                    && !self.form.username.trim().is_empty()
                    && !self.form.password.is_empty();
                card = card.push(
                    text_input("Email or username", &self.form.username)
                        .on_input(Message::Username)
                        .padding(10),
                );
                card = card.push(
                    text_input("Password", &self.form.password)
                        .secure(true)
                        .on_input(Message::Password)
                        .on_submit_maybe(ready.then_some(Message::SignIn))
                        .padding(10),
                );
                card = card.push(
                    button(if busy { "Signing in…" } else { "Sign in" })
                        .on_press_maybe(ready.then_some(Message::SignIn)),
                );
            }
        }
        if busy && self.account.stage == SignInStage::SignedOut {
            card = card.push(button("Cancel sign-in").on_press(Message::CancelSignIn));
        }
        if let Some(error) = &self.form.error {
            card = card.push(text(error.as_str()).style(text::danger));
            if self.account.stage == SignInStage::SignedOut {
                card = card.push(
                    button("Retry saved sign-in")
                        .on_press_maybe((!busy).then_some(Message::Restore)),
                );
            }
        }
        center(scrollable(
            container(card).padding(24).style(container::rounded_box),
        ))
        .into()
    }

    fn sidebar(&self) -> Element<'_, Message> {
        let label = if self.demo() {
            "Demo library"
        } else {
            "Photos"
        };
        let mut side = column![
            text("Proton Gallery").size(18),
            Space::new().height(12),
            button(text(label))
                .width(Length::Fill)
                .style(button::primary),
        ]
        .spacing(6)
        .padding(14)
        .width(SIDEBAR)
        .height(Length::Fill);
        side = side.push(Space::new().height(Length::Fill));
        if !self.demo() {
            side = side.push(text(self.account.username.as_str()).size(13));
            if self.account.persistence_failed || !self.keyring {
                side = side.push(text("Signed in until the app closes").size(12));
            }
            side = side.push(
                button("Sign out")
                    .on_press(Message::SignOut)
                    .style(button::secondary),
            );
        }
        container(side).style(container::secondary).into()
    }

    fn toolbar(&self) -> Element<'_, Message> {
        let count = self.ratios.len();
        let status: String = if self.sync.running {
            if self.demo() {
                "Building the demo library…".into()
            } else {
                format!("Syncing… {} indexed", self.sync.indexed)
            }
        } else if let Some(error) = &self.sync.error {
            error.clone()
        } else {
            format!("{count} items")
        };
        let mut bar = row![
            text(if self.demo() {
                "Demo library"
            } else {
                "Photos"
            })
            .size(20),
            Space::new().width(Length::Fill),
            text(status).size(13).width(Length::Fill),
        ]
        .spacing(12)
        .align_y(Alignment::Center)
        .padding([0, 16])
        .height(TOOLBAR);
        {
            let label = if self.sync.error.is_some() {
                "Retry"
            } else {
                "Refresh"
            };
            bar = bar.push(
                button(label)
                    .on_press_maybe((!self.sync.running).then_some(Message::Sync))
                    .style(button::secondary),
            );
        }
        if self.window.width < 760.0 && !self.demo() {
            bar = bar.push(button("Sign out").on_press(Message::SignOut));
        }
        bar.into()
    }

    fn content(&self) -> Element<'_, Message> {
        if self.outline.as_ref().is_none_or(|o| o.is_empty()) {
            let message = if self.sync.running {
                "Your photos are on their way…"
            } else {
                "No photos yet."
            };
            return center(text(message)).into();
        }
        let rows = self.layout.visible(self.scroll_top, self.viewport_height);
        let mut grid = column![].width(Length::Fill);
        let mut cursor = 0.0;
        for row in &self.layout.rows[rows] {
            if row.y > cursor {
                grid = grid.push(Space::new().height(row.y - cursor));
            }
            grid = grid.push(match &row.kind {
                RowKind::Heading(label) => container(text(label.as_str()).size(16))
                    .height(row.height)
                    .align_y(Alignment::End)
                    .padding([8, 4])
                    .into(),
                RowKind::Tiles(range) => self.tile_row(range.clone(), row.height),
            });
            cursor = row.y + row.height;
        }
        if self.layout.height > cursor {
            grid = grid.push(Space::new().height(self.layout.height - cursor));
        }
        scrollable(container(grid).padding([0, grid::GAP as u16]))
            .id(grid_id())
            .on_scroll(Message::Scrolled)
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
    }

    fn tile_row(&self, items: std::ops::Range<usize>, height: f32) -> Element<'_, Message> {
        let ids = self.outline.as_ref().map(|o| &o.ids);
        let mut tiles = row![].spacing(grid::GAP as f32).height(height);
        for index in items {
            let width = self.layout.widths.get(index).copied().unwrap_or(1) as f32;
            let thumb = ids
                .and_then(|ids| ids.get(index))
                .and_then(|id| self.thumbs.get(id));
            let focused = self.focus == Some(index);
            let video = self.item(index).is_some_and(|item| item.is_video);
            let body: Element<'_, Message> = match thumb {
                Some(decoded) => image(decoded.handle.clone())
                    .content_fit(ContentFit::Cover)
                    .width(Length::Fill)
                    .height(Length::Fill)
                    .into(),
                None if ids
                    .and_then(|ids| ids.get(index))
                    .is_some_and(|id| self.thumbs_failed.contains(id)) =>
                {
                    center(text("Preview unavailable").size(12)).into()
                }
                None => Space::new().width(Length::Fill).height(Length::Fill).into(),
            };
            let mut layers = iced::widget::stack![body]
                .width(Length::Fill)
                .height(Length::Fill);
            if video {
                layers = layers.push(
                    container(text("▶").size(14).color(Color::WHITE))
                        .padding(6)
                        .align_right(Length::Fill),
                );
            }
            let tile = container(layers)
                .padding(3)
                .width(width)
                .height(height)
                .style(move |theme: &Theme| {
                    let palette = theme.extended_palette();
                    container::Style {
                        background: Some(palette.background.weak.color.into()),
                        border: iced::Border {
                            color: if focused {
                                palette.primary.strong.color
                            } else {
                                Color::TRANSPARENT
                            },
                            width: if focused { 3.0 } else { 0.0 },
                            radius: 2.0.into(),
                        },
                        ..container::Style::default()
                    }
                });
            tiles = tiles.push(
                button(tile)
                    .padding(0)
                    .on_press(Message::Open(index))
                    .style(button::text),
            );
        }
        tiles.into()
    }

    fn viewer_view<'a>(&'a self, viewer: &'a Viewer) -> Element<'a, Message> {
        let count = self.ratios.len();
        let name = viewer
            .item
            .as_ref()
            .map_or("Loading photo…", |item| item.name.as_str());
        let bar = row![
            button("Back").on_press(Message::Close).style(viewer_button),
            Space::new().width(Length::Fill),
            text(if viewer.loading {
                "Loading…".to_string()
            } else {
                format!("{} of {count}", viewer.index + 1)
            })
            .size(13),
            button("Previous")
                .on_press_maybe((viewer.index > 0).then_some(Message::Step(-1)))
                .style(viewer_button),
            button("Next")
                .on_press_maybe((viewer.index + 1 < count).then_some(Message::Step(1)))
                .style(viewer_button),
        ]
        .spacing(8)
        .align_y(Alignment::Center)
        .padding([8, 12]);
        let body: Element<'_, Message> = match &viewer.image {
            Some(decoded) => image::viewer(decoded.handle.clone())
                .min_scale(1.0)
                .max_scale(8.0)
                .content_fit(ContentFit::Contain)
                .width(Length::Fill)
                .height(Length::Fill)
                .into(),
            None => Space::new().width(Length::Fill).height(Length::Fill).into(),
        };
        let body = iced::widget::keyed_column([(
            self.viewer_request,
            // Keyed columns diff children directly. Keep the child's widget type
            // stable while its decoded image replaces the stateless placeholder.
            column![body]
                .width(Length::Fill)
                .height(Length::Fill)
                .into(),
        )])
        .height(Length::Fill);
        let mut page = column![
            bar,
            container(text(name).size(16)).padding([0, 12]),
            body,
            text("Scroll to zoom • Drag to pan • ← / → to navigate • Esc to close").size(12)
        ];
        if let Some(message) = &viewer.message {
            page = page.push(
                container(
                    row![
                        text(message.as_str()),
                        button("Retry")
                            .on_press_maybe((!viewer.loading).then_some(Message::RetryViewer))
                            .style(viewer_button)
                    ]
                    .spacing(12),
                )
                .padding(12)
                .center_x(Length::Fill),
            );
        }
        container(page)
            .width(Length::Fill)
            .height(Length::Fill)
            .style(|_| container::Style {
                background: Some(Color::from_rgb8(16, 14, 22).into()),
                text_color: Some(Color::WHITE),
                ..container::Style::default()
            })
            .into()
    }
}

fn viewer_button(theme: &Theme, status: button::Status) -> button::Style {
    let mut style = button::text(theme, status);
    style.text_color = if status == button::Status::Disabled {
        Color::from_rgb8(130, 130, 140)
    } else {
        Color::WHITE
    };
    style
}

/// A Proton thumbnail, decoded at grid size.
async fn load_thumbnail(
    provider: Arc<dyn MediaProvider>,
    item: MediaItem,
) -> Result<Option<Decoded>, String> {
    let Some(bytes) = provider.thumbnail(&item.key).await.map_err(shown)? else {
        return Ok(None);
    };
    thumbs::decode_async(bytes, thumbs::THUMB_SIDE)
        .await
        .map(Some)
}

/// The viewer's photo: Proton's screen-size preview, or the decrypted
/// original when there is none. Videos show their thumbnail only.
async fn load_full(
    app: Arc<App>,
    provider: Arc<dyn MediaProvider>,
    outline: Arc<Outline>,
    index: usize,
    originals: PathBuf,
) -> Result<(MediaItem, Option<Decoded>, Option<String>), String> {
    let item = app
        .outline_items(&outline, index, 1)
        .await
        .map_err(shown)?
        .into_iter()
        .flatten()
        .next()
        .ok_or("This item is no longer in the library.")?;
    if item.is_video {
        let thumb = load_thumbnail(provider, item.clone()).await.ok().flatten();
        return Ok((
            item,
            thumb,
            Some("Video playback isn’t available on desktop yet.".into()),
        ));
    }
    let bytes = match provider.preview(&item.key).await.map_err(shown)? {
        Some(bytes) => bytes,
        None => {
            let path = provider
                .original(&item.key, &item.name, &originals, ORIGINAL_LIMIT)
                .await
                .map_err(shown)?;
            use tokio::io::AsyncReadExt;
            let file = tokio::fs::File::open(&path)
                .await
                .map_err(|_| "Couldn’t read the original.".to_string())?;
            let mut bytes = Vec::new();
            file.take(ORIGINAL_LIMIT + 1)
                .read_to_end(&mut bytes)
                .await
                .map_err(|_| "Couldn’t read the original.".to_string())?;
            if bytes.len() as u64 > ORIGINAL_LIMIT {
                return Err("Original exceeds viewing budget.".into());
            }
            bytes
        }
    };
    let decoded = thumbs::decode_async(bytes, thumbs::VIEWER_SIDE).await;
    match decoded {
        Ok(decoded) => Ok((item, Some(decoded), None)),
        Err(message) => Ok((item, None, Some(message))),
    }
}

/// ProjectDirs data-local directory, with a `desktop` subdirectory on both platforms.
pub fn default_data_dir() -> PathBuf {
    directories::ProjectDirs::from("io", "narl", "Proton Gallery")
        .map(|dirs| dirs.data_local_dir().join("desktop"))
        .unwrap_or_else(|| std::env::temp_dir().join("proton-gallery"))
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn gallery(count: usize) -> (tempfile::TempDir, Gallery) {
        let dir = tempfile::tempdir().unwrap();
        let app = Arc::new(
            App::open(dir.path(), Arc::new(vault::TemporaryVault::default()))
                .await
                .unwrap(),
        );
        let source = pg_core::synthetic::SyntheticSource::new(count).unwrap();
        app.index_source(
            &source,
            app.generation(),
            &pg_core::CancellationToken::new(),
            |_| {},
        )
        .await
        .unwrap();
        let outline = Arc::new(app.outline(View::Source("demo".into())).await.unwrap());
        let (mut gallery, _) = Gallery::boot(Options {
            demo: Some(count),
            data_dir: Some(dir.path().into()),
        });
        gallery.phase = Phase::Ready(app);
        let _ = gallery.show_outline(outline);
        (dir, gallery)
    }

    #[tokio::test]
    async fn stale_completions_do_not_remove_new_request_bookkeeping() {
        let (_dir, mut gallery) = gallery(300).await;
        let generation = gallery.app().unwrap().generation();
        let request = gallery.library_request;
        let id = gallery.outline.as_ref().unwrap().ids[0].clone();
        let (_, handle) = Task::done(Message::Ignored).abortable();
        gallery.thumbs_loading.insert(id.clone(), handle);
        let _ = gallery.update(Message::ThumbLoaded(
            request - 1,
            generation,
            id.clone(),
            0,
            Ok(None),
        ));
        assert!(gallery.thumbs_loading.contains_key(&id));
        let page = *gallery.pages_loading.keys().next().unwrap();
        let _ = gallery.update(Message::PageLoaded(
            request - 1,
            generation,
            page,
            Ok(vec![]),
        ));
        assert!(gallery.pages_loading.contains_key(&page));
    }

    #[tokio::test]
    async fn missing_and_failed_media_are_suppressed_until_refresh() {
        let (_dir, mut gallery) = gallery(300).await;
        let generation = gallery.app().unwrap().generation();
        let request = gallery.library_request;
        let id = gallery.outline.as_ref().unwrap().ids[1].clone();
        let _ = gallery.update(Message::ThumbLoaded(
            request,
            generation,
            id.clone(),
            1,
            Ok(None),
        ));
        assert!(gallery.thumbs_failed.contains(&id));
        let _ = gallery.update(Message::PageLoaded(
            request,
            generation,
            0,
            Err("injected".into()),
        ));
        for _ in 0..20 {
            let _ = gallery.request_visible();
        }
        assert!(!gallery.pages_loading.contains_key(&0));
        assert!(!gallery.thumbs_loading.contains_key(&id));
        let _ = gallery.update(Message::Sync);
        assert!(gallery.pages_failed.is_empty());
        assert!(gallery.thumbs_failed.is_empty());
    }

    #[tokio::test]
    async fn rapid_scrolling_and_viewer_navigation_keep_work_bounded_for_100000_items() {
        let (_dir, mut gallery) = gallery(100_000).await;
        let generation = gallery.app().unwrap().generation();
        for step in 0..500 {
            gallery.scroll_top = gallery.layout.height * ((step * 73 % 499) as f32 / 499.0);
            let _ = gallery.request_visible();
            assert!(gallery.pages_loading.len() <= PAGES_IN_FLIGHT);
            assert!(gallery.thumbs_loading.len() <= THUMBS_IN_FLIGHT);
            assert!(gallery.pages.len() <= KEPT_PAGES + 4);
            let _ = gallery.open(step);
            let old = gallery.viewer_work.as_ref().unwrap().clone();
            let request = gallery.viewer_request;
            let _ = gallery.open(step + 1);
            assert!(old.is_aborted());
            let _ = gallery.update(Message::ViewerLoaded(
                request,
                generation,
                Err("stale".into()),
            ));
            assert!(gallery.viewer.as_ref().unwrap().loading);
            assert!(gallery.viewer_work.is_some());
        }
        let _ = gallery.update(Message::Close);
        assert!(gallery.viewer_work.is_none());
    }

    #[tokio::test]
    async fn sync_insertion_keeps_the_focused_asset_and_viewport_anchor() {
        let (_dir, mut gallery) = gallery(2_000).await;
        gallery.scroll_top = 12_000.0;
        gallery.focus = Some(300);
        let outline = gallery.outline.clone().unwrap();
        let anchor = gallery.layout.anchor(gallery.scroll_top).unwrap();
        let anchor_id = outline.ids[anchor.0].clone();
        let focus_id = outline.ids[300].clone();
        let mut next = Outline {
            keys: outline.keys.clone(),
            ids: outline.ids.clone(),
            times: outline.times.clone(),
            ratios: outline.ratios.clone(),
        };
        next.ids.insert(0, "new-photo".into());
        next.keys.insert(0, outline.keys[0].clone());
        next.times.insert(0, outline.times[0]);
        next.ratios.insert(0, 1.5);
        let _ = gallery.show_outline(Arc::new(next));
        let current = gallery.outline.as_ref().unwrap();
        assert_eq!(current.ids[gallery.focus.unwrap()], focus_id);
        let new_index = current.ids.iter().position(|id| id == &anchor_id).unwrap();
        assert!(
            (gallery.layout.item_y(new_index).unwrap() - gallery.scroll_top - anchor.1).abs() < 0.5
        );
        gallery.ratios[new_index] = 0.5;
        let _ = gallery.replan_to(600.0);
        assert_eq!(current_id(&gallery, gallery.focus.unwrap()), focus_id);
    }

    #[tokio::test]
    async fn viewer_placeholder_and_decoded_images_keep_valid_widget_state_during_navigation() {
        use iced::advanced::widget::Tree;
        fn same_tags(actual: &Tree, expected: &Tree) {
            assert_eq!(actual.tag, expected.tag);
            assert_eq!(actual.children.len(), expected.children.len());
            for (a, e) in actual.children.iter().zip(&expected.children) {
                same_tags(a, e);
            }
        }
        let (_dir, mut gallery) = gallery(20).await;
        let _ = gallery.open(1);
        let mut tree = Tree::new(gallery.view().as_widget());
        for step in 0..30 {
            gallery.viewer.as_mut().unwrap().image =
                (step % 2 == 0).then(|| thumbs::synthetic(step, 1.5));
            let view = gallery.view();
            tree.diff(view.as_widget());
            same_tags(&tree, &Tree::new(view.as_widget()));
            drop(view);
            if step % 3 == 0 {
                let _ = gallery.open(step % 20);
            }
        }
    }

    #[tokio::test]
    async fn fixtures_use_the_same_thumbnail_and_full_viewer_loading_pipeline() {
        let (_dir, gallery) = gallery(20).await;
        let app = gallery.app().unwrap().clone();
        let outline = gallery.outline.clone().unwrap();
        let provider = gallery.provider(&app);
        let at = |number: usize| {
            outline
                .keys
                .iter()
                .position(|key| key.node.as_str() == format!("asset-{number:08}"))
                .unwrap()
        };
        let item = app
            .outline_items(&outline, at(4), 1)
            .await
            .unwrap()
            .pop()
            .unwrap()
            .unwrap();
        assert!(
            load_thumbnail(provider.clone(), item)
                .await
                .unwrap()
                .is_some()
        );
        let video = load_full(
            app.clone(),
            provider.clone(),
            outline.clone(),
            at(0),
            gallery.originals.clone(),
        )
        .await
        .unwrap();
        assert!(video.0.is_video && video.2.unwrap().contains("Video playback"));
        assert!(
            load_full(
                app.clone(),
                provider.clone(),
                outline.clone(),
                at(1),
                gallery.originals.clone()
            )
            .await
            .unwrap_err()
            .contains("missing")
        );
        let unsupported = load_full(
            app.clone(),
            provider.clone(),
            outline.clone(),
            at(2),
            gallery.originals.clone(),
        )
        .await
        .unwrap();
        assert!(unsupported.1.is_none() && unsupported.2.is_some());
        assert!(
            load_full(
                app,
                provider,
                outline.clone(),
                at(3),
                gallery.originals.clone()
            )
            .await
            .is_err()
        );
    }

    #[tokio::test]
    async fn queued_sync_ticks_and_results_cannot_repopulate_the_library_during_signout() {
        let (_dir, mut gallery) = gallery(20).await;
        gallery.options.demo = None;
        gallery.account.stage = SignInStage::Connected;
        let app = gallery.app().unwrap().clone();
        let generation = app.generation();
        let outline = gallery.outline.clone().unwrap();
        // Do not poll the sign-out task: model the interval before the shared
        // generation advances, when queued UI results can still be current.
        let _ = gallery.update(Message::SignOut);
        assert_eq!(app.generation(), generation);
        let request = gallery.outline_request;
        app.indexed.store(10, std::sync::atomic::Ordering::Relaxed);
        let _ = gallery.update(Message::SyncTick);
        assert_eq!(gallery.outline_request, request);
        let _ = gallery.update(Message::OutlineLoaded(request, generation, Ok(outline)));
        let _ = gallery.request_visible();
        assert!(gallery.outline.is_none());
        assert!(gallery.pages_loading.is_empty());
        assert!(gallery.thumbs_loading.is_empty());
        assert!(gallery.viewer.is_none());
    }

    fn current_id(gallery: &Gallery, index: usize) -> String {
        gallery.outline.as_ref().unwrap().ids[index].clone()
    }
}
