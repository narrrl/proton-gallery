//! The desktop app: sign-in, the Photos timeline, and the photo viewer.
//!
//! Presentation state (navigation, scroll position, focus, the open photo,
//! forms) lives here; the library, account and media come from `pg-app`.
//! Every asynchronous result carries the generation it started under and
//! the request it answers, and is dropped when either is out of date.
use crate::design::{self, Icon};
use crate::grid::{self, Layout, RowKind};
use crate::places::{Camera, Places};
use crate::thumbs::{self, Decoded};
use crate::vault;
use iced::advanced::{
    Clipboard, Layout as WidgetLayout, Shell, Widget, layout, mouse, overlay, renderer,
    widget::{Operation, Tree},
};
use iced::keyboard::{self, Key, key::Named};
use iced::widget::{
    Space, button, center, checkbox, column, container, image, operation, pick_list, row,
    scrollable, slider, text, text_input,
};
use iced::{
    Alignment, Color, ContentFit, Element, Event, Length, Rectangle, Renderer, Size, Subscription,
    Task, Theme, Vector, window,
};
use proton_gallery_app_rs::drive_galleries::{FolderChoice, FolderListing};
use proton_gallery_app_rs::fixtures::{FixtureMedia, MediaProvider};
use proton_gallery_app_rs::sharing::{PublicLink, PublicLinkAction};
use proton_gallery_app_rs::{
    AccountState, App, Generation, MediaItem, Outline, PhotosSyncStatus, SignInStage, View,
};
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
const SIDEBAR: f32 = 208.0;
const WIDE_TOOLBAR: f32 = 98.0;
const NARROW_TOOLBAR: f32 = 126.0;

fn toolbar_height(width: f32) -> f32 {
    if width < 960.0 {
        NARROW_TOOLBAR
    } else {
        WIDE_TOOLBAR
    }
}
const GUTTER: f32 = 24.0;
const COVER_WIDTH: f32 = 238.0;
const COVER_HEIGHT: f32 = 210.0;
const COVER_GAP: f32 = 12.0;
const COVER_HEADING: f32 = 32.0;

struct CoverGroup {
    title: Option<&'static str>,
    visible: std::ops::Range<usize>,
    before: f32,
    after: f32,
}
/// Room for the vertical scrollbar beside the grid.
const SCROLLBAR: f32 = 14.0;

fn grid_id() -> iced::widget::Id {
    iced::widget::Id::new("timeline")
}

/// Intercept the gesture before the scrollable can move the viewport.
struct GridZoom<'a>(Element<'a, Message>);

impl Widget<Message, Theme, Renderer> for GridZoom<'_> {
    fn size(&self) -> Size<Length> {
        self.0.as_widget().size()
    }

    fn tag(&self) -> iced::advanced::widget::tree::Tag {
        iced::advanced::widget::tree::Tag::of::<keyboard::Modifiers>()
    }

    fn state(&self) -> iced::advanced::widget::tree::State {
        iced::advanced::widget::tree::State::new(keyboard::Modifiers::empty())
    }

    fn children(&self) -> Vec<Tree> {
        vec![Tree::new(&self.0)]
    }

    fn diff(&self, tree: &mut Tree) {
        tree.children[0].diff(&self.0);
    }

    fn layout(
        &mut self,
        tree: &mut Tree,
        renderer: &Renderer,
        limits: &layout::Limits,
    ) -> layout::Node {
        self.0
            .as_widget_mut()
            .layout(&mut tree.children[0], renderer, limits)
    }

    fn draw(
        &self,
        tree: &Tree,
        renderer: &mut Renderer,
        theme: &Theme,
        style: &renderer::Style,
        layout: WidgetLayout<'_>,
        cursor: mouse::Cursor,
        viewport: &Rectangle,
    ) {
        self.0.as_widget().draw(
            &tree.children[0],
            renderer,
            theme,
            style,
            layout,
            cursor,
            viewport,
        );
    }

    fn update(
        &mut self,
        tree: &mut Tree,
        event: &Event,
        layout: WidgetLayout<'_>,
        cursor: mouse::Cursor,
        renderer: &Renderer,
        clipboard: &mut dyn Clipboard,
        shell: &mut Shell<'_, Message>,
        viewport: &Rectangle,
    ) {
        let modifiers = tree.state.downcast_mut::<keyboard::Modifiers>();
        match event {
            Event::Keyboard(keyboard::Event::ModifiersChanged(value)) => *modifiers = *value,
            Event::Window(window::Event::Unfocused) => *modifiers = keyboard::Modifiers::empty(),
            Event::Mouse(mouse::Event::WheelScrolled { delta })
                if modifiers.control() && cursor.is_over(layout.bounds()) =>
            {
                let y = match delta {
                    mouse::ScrollDelta::Lines { y, .. } | mouse::ScrollDelta::Pixels { y, .. } => {
                        *y
                    }
                };
                if y != 0.0 {
                    shell.publish(Message::GridZoom(y > 0.0));
                    shell.capture_event();
                    return;
                }
            }
            _ => {}
        }
        self.0.as_widget_mut().update(
            &mut tree.children[0],
            event,
            layout,
            cursor,
            renderer,
            clipboard,
            shell,
            viewport,
        );
    }

    fn operate(
        &mut self,
        tree: &mut Tree,
        layout: WidgetLayout<'_>,
        renderer: &Renderer,
        operation: &mut dyn Operation,
    ) {
        self.0
            .as_widget_mut()
            .operate(&mut tree.children[0], layout, renderer, operation);
    }

    fn mouse_interaction(
        &self,
        tree: &Tree,
        layout: WidgetLayout<'_>,
        cursor: mouse::Cursor,
        viewport: &Rectangle,
        renderer: &Renderer,
    ) -> mouse::Interaction {
        self.0
            .as_widget()
            .mouse_interaction(&tree.children[0], layout, cursor, viewport, renderer)
    }

    fn overlay<'a>(
        &'a mut self,
        tree: &'a mut Tree,
        layout: WidgetLayout<'a>,
        renderer: &Renderer,
        viewport: &Rectangle,
        translation: Vector,
    ) -> Option<overlay::Element<'a, Message, Theme, Renderer>> {
        self.0.as_widget_mut().overlay(
            &mut tree.children[0],
            layout,
            renderer,
            viewport,
            translation,
        )
    }
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
    settings_busy: bool,
    status_busy: bool,
    revision: u64,
    last_success: Option<i64>,
    failures: u32,
    retry_at: Option<std::time::Instant>,
    expired: bool,
    visibility_stale: bool,
}

/// The photo open in the viewer.
struct Viewer {
    index: usize,
    item: Option<MediaItem>,
    image: Option<Decoded>,
    loading: bool,
    message: Option<String>,
    #[cfg(target_os = "linux")]
    player: Option<Arc<crate::video::Player>>,
    #[cfg(target_os = "linux")]
    playback: crate::video::Playback,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Section {
    Photos,
    Favorites,
    Albums,
    Drive,
    Hidden,
    Places,
}
impl std::fmt::Display for Section {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Photos => "Photos",
            Self::Favorites => "Favorites",
            Self::Albums => "Albums",
            Self::Drive => "Drive",
            Self::Hidden => "Hidden albums",
            Self::Places => "Places",
        })
    }
}

#[derive(Default)]
struct DriveNavigation {
    trail: Vec<proton_gallery_rs::library::Album>,
    parent_scroll: Vec<f32>,
    restore_scroll: Option<f32>,
    chooser: Option<Vec<FolderChoice>>,
    listing: Option<FolderListing>,
    request: u64,
    busy: bool,
}

#[derive(Default)]
struct HiddenAccess {
    unlocked: bool,
    origin: Option<Section>,
    auth_work: Option<iced::task::Handle>,
    private_album: bool,
    busy: bool,
    request: u64,
    verified: bool,
    error: Option<String>,
}

#[derive(Default)]
struct DriveLinkPanel {
    request: u64,
    open: bool,
    busy: bool,
    loaded: bool,
    confirming_revoke: bool,
    copied: bool,
    link: Option<PublicLink>,
    expiry: String,
    password: Zeroizing<String>,
    error: Option<String>,
}

pub struct Gallery {
    options: Options,
    mode: iced::theme::Mode,
    phase: Phase,
    keyring: bool,
    account: AccountState,
    form: SignInForm,
    sync: SyncState,
    view: View,
    section: Section,
    album_open: bool,
    albums: Vec<proton_gallery_rs::library::Album>,
    albums_request: u64,
    albums_loading: bool,
    drive: DriveNavigation,
    hidden: HiddenAccess,
    places: Places,
    window_focused: bool,
    drive_link: DriveLinkPanel,
    row_target: u32,
    favorite_request: u64,
    favorite_busy: bool,
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
    /// Grid thumbnails kept across runs; never hidden-album ones.
    disk_thumbs: Arc<thumbs::DiskThumbs>,
    thumbs_loading: HashMap<String, iced::task::Handle>,
    thumbs_failed: HashSet<String>,
    thumbs_retryable: HashSet<String>,
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
    SystemTheme(iced::theme::Mode),
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
    CheckPhotos,
    RetryPhotos,
    RepairPhotos,
    SettingsChecked(Generation, Result<Option<bool>, String>),
    PhotosStatus(
        Generation,
        Result<PhotosSyncStatus, proton_gallery_app_rs::Error>,
    ),
    Synced(
        Generation,
        Result<PhotosSyncStatus, proton_gallery_app_rs::Error>,
    ),
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
    CoverLoaded(u64, Generation, String, Result<Option<Decoded>, String>),
    Open(usize),
    Close,
    RetryViewer,
    Step(i32),
    ViewerLoaded(u64, Generation, Result<Box<Loaded>, String>),
    #[cfg(target_os = "linux")]
    VideoOpened(u64, Generation, Result<Arc<crate::video::Player>, String>),
    #[cfg(target_os = "linux")]
    VideoTick,
    #[cfg(target_os = "linux")]
    VideoToggle,
    #[cfg(target_os = "linux")]
    VideoSeek(f64),
    #[cfg(target_os = "linux")]
    VideoVolume(f64),
    Key(keyboard::Event),
    Navigate(Section),
    OpenAlbum(String),
    BackToAlbums,
    OpenHidden,
    WindowFocusChanged(bool),
    HiddenUnlocked(u64, Generation, Result<(), String>),
    LockHidden,
    StartPlaces,
    PlacesReady(
        u64,
        Generation,
        Result<Arc<proton_gallery_app_rs::places::PlacesScan>, String>,
    ),
    PlacesBatch(
        u64,
        Generation,
        Result<proton_gallery_app_rs::places::PlacesBatch, String>,
    ),
    PlacesCamera(Camera),
    PlacesSelect(Vec<usize>),
    PlacesListAll,
    PlacesListMore,
    PlacesFit,
    PlacesZoom(f64),
    OpenPlacePhoto(usize),
    PlacePhotoReady(u64, Generation, Option<(Arc<Outline>, usize)>),

    OpenDriveLink,
    CloseDriveLink,
    DriveLinkPassword(String),
    DriveLinkExpiry(String),
    CreateDriveLink,
    CopyDriveLink,
    ConfirmRevokeDriveLink,
    RevokeDriveLink,
    DriveLinkLoaded(u64, Generation, Result<Option<PublicLink>, String>),
    ChooseDriveFolder,
    CloseDriveChooser,
    BrowseDriveFolder(usize),
    BackDriveFolder,
    DriveFoldersLoaded(u64, Generation, Result<FolderListing, String>),
    AddDriveGallery,
    RemoveDriveGallery,
    DriveChanged(Generation, bool, Result<(), String>),
    AlbumsLoaded(
        u64,
        Generation,
        Result<Vec<proton_gallery_rs::library::Album>, String>,
    ),
    GridSize(u32),
    GridZoom(bool),
    ToggleFavorite,
    FavoriteDone(
        u64,
        Generation,
        proton_gallery_rs::model::AssetKey,
        bool,
        Result<proton_gallery_app_rs::favorites::FavoriteOutcome, String>,
    ),
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

fn shown(error: proton_gallery_app_rs::Error) -> String {
    error.to_string()
}

impl Gallery {
    pub fn boot(options: Options) -> (Self, Task<Message>) {
        let data_dir = options.data_dir.clone().unwrap_or_else(default_data_dir);
        let originals = data_dir.join("cache").join("originals");
        let disk_thumbs = Arc::new(thumbs::DiskThumbs::new(
            data_dir.join("cache").join("thumbnails"),
        ));
        let demo = options.demo.is_some();
        let gallery = Self {
            options,
            mode: iced::theme::Mode::Light,
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
            section: Section::Photos,
            album_open: false,
            albums: Vec::new(),
            albums_request: 0,
            albums_loading: false,
            drive: DriveNavigation::default(),
            hidden: HiddenAccess::default(),
            places: Places::default(),
            window_focused: true,
            drive_link: DriveLinkPanel::default(),
            row_target: ROW_TARGET,
            favorite_request: 0,
            favorite_busy: false,
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
            disk_thumbs,
            thumbs_loading: HashMap::new(),
            thumbs_failed: HashSet::new(),
            thumbs_retryable: HashSet::new(),
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
                let vault: Arc<dyn proton_gallery_drive_rs::auth::CredentialVault> = if keyring {
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
        (
            gallery,
            Task::batch([start, size, iced::system::theme().map(Message::SystemTheme)]),
        )
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
        Some(design::theme(self.mode))
    }

    pub fn subscription(&self) -> Subscription<Message> {
        let mut subscriptions = vec![
            iced::system::theme_changes().map(Message::SystemTheme),
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
        if self.browsing() && !self.demo() {
            subscriptions
                .push(iced::time::every(Duration::from_secs(120)).map(|_| Message::CheckPhotos));
            if self.sync.retry_at.is_some() {
                subscriptions
                    .push(iced::time::every(Duration::from_secs(1)).map(|_| Message::RetryPhotos));
            }
            subscriptions.push(iced::event::listen_with(|event, _, _| match event {
                iced::Event::Window(window::Event::Focused) => {
                    Some(Message::WindowFocusChanged(true))
                }
                iced::Event::Window(window::Event::Unfocused) => {
                    Some(Message::WindowFocusChanged(false))
                }
                _ => None,
            }));
        }
        if self.relayout {
            subscriptions
                .push(iced::time::every(Duration::from_millis(250)).map(|_| Message::Relayout));
        }
        #[cfg(target_os = "linux")]
        if self
            .viewer
            .as_ref()
            .is_some_and(|viewer| viewer.player.is_some())
        {
            // Every tick redraws the video surface, which shows the newest
            // frame; 16 ms keeps 60 fps video at its rate while playing.
            // Paused, a tick only has to notice a frame a seek brings and the
            // player's messages, so it slows down instead of redrawing an
            // unchanged frame 30 times a second.
            let (playing, buffering) = self.viewer.as_ref().map_or((false, false), |viewer| {
                (viewer.playback.playing, viewer.playback.buffering)
            });
            let tick = Duration::from_millis(match (playing, buffering) {
                (true, _) => 16,
                (false, true) => 33,
                (false, false) => 100,
            });
            subscriptions.push(iced::time::every(tick).map(|_| Message::VideoTick));
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
            Message::SystemTheme(mode) => {
                self.mode = mode;
                Task::none()
            }
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
                            let source = proton_gallery_rs::synthetic::SyntheticSource::new(count)
                                .map_err(|e| e.to_string())?;
                            let cancel = proton_gallery_rs::CancellationToken::new();
                            let done = app
                                .index_source(&source, app.generation(), &cancel, |_| {})
                                .await
                                .map_err(shown)?;
                            proton_gallery_app_rs::fixtures::seed_collections(&app)
                                .await
                                .map_err(shown)?;
                            Ok(done.indexed as usize)
                        },
                        Message::DemoReady,
                    );
                }
                if self.browsing() {
                    return self.open_library();
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
                Task::batch([self.load_outline(), self.load_albums()])
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
                self.viewport_height = (size.height - toolbar_height(size.width)).max(1.0);
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
                            return self.open_library();
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
                let expired = self.sync.expired;
                self.sync = SyncState {
                    expired,
                    ..SyncState::default()
                };
                // The next account must not find this one's pictures on disk.
                let disk_thumbs = self.disk_thumbs.clone();
                spawn(
                    &app,
                    move |app| async move {
                        disk_thumbs.clear().await;
                        app.sign_out().await.map_err(shown)
                    },
                    Message::SignedOut,
                )
            }
            Message::SignedOut(result) => {
                self.forget_library();
                self.cleanup_failed = result.is_err();
                if let Some(app) = self.app() {
                    self.account = app.account_state();
                }
                let expired = self.sync.expired;
                self.sync = SyncState::default();
                self.form = SignInForm {
                    error: result.err().or_else(|| {
                        expired.then(|| proton_gallery_app_rs::Error::SessionExpired.to_string())
                    }),
                    ..SignInForm::default()
                };
                Task::none()
            }
            Message::Sync => {
                self.pages_failed.clear();
                self.thumbs_failed.clear();
                self.thumbs_retryable.clear();
                let visible = self.request_visible();
                let refresh = if self.section == Section::Places {
                    self.start_places()
                } else if self.section == Section::Hidden {
                    self.load_albums()
                } else if self.section == Section::Drive {
                    self.refresh_drive()
                } else if self.demo() {
                    Task::batch([self.load_outline(), self.load_albums()])
                } else {
                    self.start_sync()
                };
                Task::batch([visible, refresh])
            }
            Message::CheckPhotos => {
                if self
                    .sync
                    .retry_at
                    .is_some_and(|at| at > std::time::Instant::now())
                {
                    Task::none()
                } else {
                    self.start_sync()
                }
            }
            Message::RetryPhotos => {
                if self
                    .sync
                    .retry_at
                    .is_some_and(|at| at <= std::time::Instant::now())
                {
                    self.index_photos(false)
                } else {
                    Task::none()
                }
            }
            Message::RepairPhotos => self.index_photos(true),
            Message::SyncTick => {
                if !self.browsing() {
                    return Task::none();
                }
                if let Some(app) = self.app() {
                    let indexed = app.indexed.load(std::sync::atomic::Ordering::Relaxed);
                    let grew = indexed > self.sync.indexed;
                    self.sync.indexed = indexed;
                    if self.demo() && grew {
                        return self.load_outline();
                    }
                }
                self.read_photos_status()
            }
            Message::SettingsChecked(generation, result) => {
                if !self.current(generation) {
                    return Task::none();
                }
                self.sync.settings_busy = false;
                match result {
                    Ok(None) => {
                        let retry_places =
                            self.sync.visibility_stale && self.section == Section::Places;
                        self.sync.visibility_stale = false;
                        if retry_places {
                            self.start_places()
                        } else {
                            Task::none()
                        }
                    }
                    Ok(Some(visibility_changed)) => {
                        self.sync.visibility_stale = false;
                        let mut drop_cached = Task::none();
                        if visibility_changed {
                            let was_hidden = self.hidden.unlocked;
                            let origin = self.hidden.origin;
                            self.close_hidden_access();
                            self.clear_media();
                            if was_hidden {
                                self.reset_drive_navigation();
                                self.section = origin.unwrap_or(Section::Albums);
                            }
                            if self.album_open {
                                self.album_open = false;
                                self.drive.trail.clear();
                                self.drive.parent_scroll.clear();
                                self.drive.restore_scroll = None;
                                self.view = View::Photos;
                            }
                            let originals = self.originals.clone();
                            drop_cached = Task::perform(
                                async move { tokio::fs::remove_dir_all(originals).await },
                                |_| Message::Ignored,
                            );
                        }
                        if self.section == Section::Places {
                            if visibility_changed || self.places.error.is_some() {
                                return Task::batch([self.start_places(), drop_cached]);
                            }
                            return drop_cached;
                        }
                        Task::batch([self.load_outline(), self.load_albums(), drop_cached])
                    }
                    Err(_) => {
                        self.sync.visibility_stale = true;
                        Task::none()
                    }
                }
            }
            Message::PhotosStatus(generation, result) => {
                if !self.current(generation) {
                    return Task::none();
                }
                self.sync.status_busy = false;
                match result {
                    Ok(status) => self.apply_photos_status(status),
                    Err(_) => Task::none(),
                }
            }
            Message::Synced(generation, result) => {
                if !self.current(generation) {
                    return Task::none();
                }
                self.sync.running = false;
                match result {
                    Ok(status) => {
                        let reload = self.apply_photos_status(status);
                        if status.more {
                            return Task::batch([reload, self.index_photos(false)]);
                        }
                        if status.retry {
                            self.photos_retry("Some Photos changes are still pending. Your cached library is available.".into());
                        } else {
                            self.sync.error = None;
                            self.sync.failures = 0;
                        }
                        reload
                    }
                    Err(
                        proton_gallery_app_rs::Error::Cancelled
                        | proton_gallery_app_rs::Error::Stale,
                    ) => Task::none(),
                    Err(proton_gallery_app_rs::Error::SessionExpired) => {
                        self.sync.expired = true;
                        self.sync.error =
                            Some(proton_gallery_app_rs::Error::SessionExpired.to_string());
                        self.read_photos_status()
                    }
                    Err(error) => {
                        self.photos_retry(error.to_string());
                        self.read_photos_status()
                    }
                }
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
                // Subtract the fixed outer gutter from the measured viewport.
                let width = viewport.bounds().width - 2.0 * GUTTER;
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
                        self.thumbs_retryable.insert(id.clone());
                        self.thumbs_failed.insert(id);
                    }
                }
                self.request_visible()
            }
            Message::CoverLoaded(request, generation, id, result) => {
                if request != self.albums_request || !self.current(generation) {
                    return Task::none();
                }
                self.thumbs_loading.remove(&id);
                if let Ok(Some(decoded)) = result {
                    self.thumbs.insert(id, decoded, thumbs::BUDGET);
                } else {
                    self.thumbs_failed.insert(id);
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
                            #[cfg(target_os = "linux")]
                            let video = item.is_video
                                && (item.remote || item.local_path.is_some())
                                && self.options.demo.is_none();
                            viewer.item = Some(item);
                            if image.is_some() {
                                viewer.image = image;
                            }
                            viewer.message = message;
                            #[cfg(target_os = "linux")]
                            if video {
                                let item = viewer.item.as_ref().unwrap().clone();
                                viewer.loading = true;
                                viewer.message = None;
                                let Some(app) = self.app().cloned() else {
                                    return Task::none();
                                };
                                let (task, handle) = spawn(
                                    &app,
                                    move |app| async move {
                                        let stream = if item.local_path.is_none() {
                                            Some(app.stream_video(&item.key).await.map_err(shown)?)
                                        } else {
                                            None
                                        };
                                        let runtime = tokio::runtime::Handle::current();
                                        tokio::task::spawn_blocking(move || {
                                            crate::video::Player::open(
                                                stream,
                                                item.local_path.as_deref(),
                                                runtime,
                                            )
                                            .map(Arc::new)
                                        })
                                        .await
                                        .map_err(
                                            |_| "Couldn’t start the video player.".to_owned(),
                                        )?
                                    },
                                    move |result| Message::VideoOpened(request, generation, result),
                                )
                                .abortable();
                                self.viewer_work = Some(handle.abort_on_drop());
                                return task;
                            }
                        }
                        Err(error) => viewer.message = Some(error),
                    }
                }
                Task::none()
            }
            #[cfg(target_os = "linux")]
            Message::VideoOpened(request, generation, result) => {
                if request == self.viewer_request
                    && self.current(generation)
                    && let Some(viewer) = &mut self.viewer
                {
                    self.viewer_work = None;
                    viewer.loading = false;
                    match result {
                        Ok(player) => {
                            viewer.player = Some(player);
                            viewer.playback.playing = true;
                            viewer.playback.volume = 1.0;
                        }
                        Err(error) => viewer.message = Some(error),
                    }
                }
                Task::none()
            }
            #[cfg(target_os = "linux")]
            Message::VideoTick
            | Message::VideoToggle
            | Message::VideoSeek(_)
            | Message::VideoVolume(_) => {
                let Some(viewer) = &mut self.viewer else {
                    return Task::none();
                };
                let Some(player) = &viewer.player else {
                    return Task::none();
                };
                let state = &mut viewer.playback;
                let result = match message {
                    Message::VideoToggle => {
                        let start = if state.ended {
                            player.seek(0.0)
                        } else {
                            Ok(())
                        };
                        state.ended = false;
                        state.playing = !state.playing;
                        start.and_then(|()| player.set_playing(state.playing && !state.buffering))
                    }
                    Message::VideoSeek(seconds) => {
                        state.ended = false;
                        player.seek(seconds)
                    }
                    Message::VideoVolume(volume) => {
                        state.volume = volume;
                        player.volume(volume);
                        Ok(())
                    }
                    _ => player.poll(state),
                };
                match result {
                    Ok(()) => {}
                    Err(error) => {
                        viewer.message = Some(error);
                        viewer.player = None;
                    }
                }
                Task::none()
            }
            Message::Navigate(section) => {
                if !self.browsing()
                    || (self.section == section && !self.album_open)
                    || section == Section::Hidden
                    || (section == Section::Drive && self.demo())
                {
                    return Task::none();
                }
                self.reset_drive_navigation();
                self.close_hidden_access();
                self.places.reset();
                self.clear_media();
                self.section = section;
                self.album_open = false;
                self.view = if self.demo() {
                    View::Source("demo".into())
                } else {
                    View::Photos
                };
                if section == Section::Places {
                    self.start_places()
                } else if matches!(section, Section::Albums | Section::Drive) {
                    self.load_albums()
                } else {
                    self.load_outline()
                }
            }
            Message::OpenAlbum(id) => {
                if self.section == Section::Hidden {
                    if !self.hidden.unlocked {
                        return Task::none();
                    }
                    let Some(album) = self
                        .albums
                        .iter()
                        .find(|album| album.source.id.as_str() == id)
                        .cloned()
                    else {
                        return Task::none();
                    };
                    if album.photos_album {
                        self.section = Section::Albums;
                        self.album_open = true;
                        self.hidden.private_album = true;
                        self.view = View::PhotoAlbum(id);
                        self.clear_media();
                        return self.load_outline();
                    }
                    self.section = Section::Drive;
                    self.drive.parent_scroll.push(self.scroll_top);
                    self.drive.trail.push(album);
                    return self.show_drive_place();
                }
                if self.section == Section::Drive {
                    if self.drive.busy {
                        return Task::none();
                    }
                    if let Some(album) = self.albums.iter().find(|a| {
                        a.source.id.as_str() == id && (self.hidden.unlocked || !a.settings.hidden)
                    }) {
                        self.drive.parent_scroll.push(self.scroll_top);
                        self.drive.trail.push(album.clone());
                        return self.show_drive_place();
                    }
                    return Task::none();
                }
                if !self.browsing()
                    || !self.albums.iter().any(|a| {
                        a.source.id.as_str() == id && (self.hidden.unlocked || !a.settings.hidden)
                    })
                {
                    return Task::none();
                }
                self.clear_media();
                self.section = Section::Albums;
                self.album_open = true;
                self.hidden.private_album = self
                    .albums
                    .iter()
                    .any(|album| album.source.id.as_str() == id && album.settings.hidden);
                self.view = if self.demo() {
                    View::Source(id)
                } else {
                    View::PhotoAlbum(id)
                };
                self.load_outline()
            }
            Message::BackToAlbums => {
                if self.hidden.unlocked && self.drive.trail.len() <= 1 {
                    return self.show_hidden_index();
                }
                if self.section == Section::Drive {
                    self.drive.trail.pop();
                    let task = self.show_drive_place();
                    self.drive.restore_scroll = self.drive.parent_scroll.pop();
                    task
                } else {
                    self.update(Message::Navigate(Section::Albums))
                }
            }
            Message::WindowFocusChanged(focused) => {
                self.window_focused = focused;
                if focused {
                    Task::batch([
                        self.reveal_verified_hidden(),
                        self.update(Message::CheckPhotos),
                    ])
                } else if self.hidden.unlocked {
                    self.update(Message::LockHidden)
                } else {
                    Task::none()
                }
            }
            Message::OpenHidden => {
                if !matches!(self.section, Section::Albums | Section::Drive)
                    || self.album_open
                    || self.demo()
                    || !self.browsing()
                    || self.hidden.busy
                    || self.hidden.verified
                {
                    return Task::none();
                }
                let Some(app) = self.app() else {
                    return Task::none();
                };
                let generation = app.generation();
                self.hidden.origin = Some(self.section);
                self.hidden.busy = true;
                self.hidden.error = None;
                self.hidden.request += 1;
                let (task, handle) =
                    crate::device_auth::authenticate(self.hidden.request, generation).abortable();
                self.hidden.auth_work = Some(handle.abort_on_drop());
                task
            }
            Message::HiddenUnlocked(request, generation, result) => {
                if request != self.hidden.request || !self.current(generation) {
                    return Task::none();
                }
                self.hidden.busy = false;
                self.hidden.auth_work = None;
                match result {
                    Ok(()) => {
                        self.hidden.verified = true;
                        self.reveal_verified_hidden()
                    }
                    Err(error) => {
                        self.hidden.error = Some(error);
                        Task::none()
                    }
                }
            }
            Message::LockHidden => {
                let origin = self.hidden.origin;
                let unlocked = self.hidden.unlocked;
                self.close_hidden_access();
                if !unlocked {
                    return Task::none();
                }
                self.reset_drive_navigation();
                self.clear_media();
                self.section = origin.unwrap_or(Section::Albums);
                self.album_open = false;
                self.view = View::Photos;
                self.load_albums()
            }
            Message::StartPlaces => self.start_places(),
            Message::PlacesReady(request, generation, result) => {
                if request != self.places.request || !self.current(generation) {
                    return Task::none();
                }
                self.places.work = None;
                match result {
                    Ok(scan) => {
                        self.places.scan = Some(scan);
                        self.scan_places_batch()
                    }
                    Err(error) => {
                        self.places.error = Some(error);
                        Task::none()
                    }
                }
            }
            Message::PlacesBatch(request, generation, result) => {
                if request != self.places.request || !self.current(generation) {
                    return Task::none();
                }
                self.places.work = None;
                match result {
                    Ok(batch) => {
                        self.places.next = batch.next;
                        self.places.failed += batch.failed;
                        self.places.photos.extend(batch.photos);
                        self.scan_places_batch()
                    }
                    Err(error) => {
                        self.places.error = Some(error);
                        Task::none()
                    }
                }
            }
            Message::PlacesCamera(camera) => {
                self.places.camera = Some(camera);
                self.places.selected.clear();
                Task::none()
            }
            Message::PlacesSelect(selection) => {
                self.places.select(selection, false);
                Task::none()
            }
            Message::PlacesListAll => {
                self.places
                    .select((0..self.places.photos.len()).collect(), true);
                Task::none()
            }
            Message::PlacesListMore => {
                self.places.list_limit += 60;
                Task::none()
            }
            Message::PlacesFit => {
                self.places.camera = None;
                self.places.fit_steps = 0;
                self.places.selected.clear();
                Task::none()
            }
            Message::PlacesZoom(factor) => {
                if let Some(camera) = self.places.camera {
                    self.places.camera = Some(crate::places::camera_zoom(camera, factor));
                } else {
                    self.places.fit_steps =
                        (self.places.fit_steps + if factor > 1.0 { 1 } else { -1 }).clamp(-6, 13);
                }
                self.places.selected.clear();
                Task::none()
            }
            Message::OpenPlacePhoto(index) => self.open_place_photo(index),
            Message::PlacePhotoReady(request, generation, result) => {
                if request != self.places.request
                    || !self.current(generation)
                    || self.section != Section::Places
                {
                    return Task::none();
                }
                if let Some((outline, index)) = result {
                    self.ratios.clone_from(&outline.ratios);
                    self.outline = Some(outline);
                    self.open(index)
                } else {
                    self.start_places()
                }
            }
            Message::OpenDriveLink => {
                self.drive_link.open = true;
                self.drive_link_task(PublicLinkAction::Read)
            }
            Message::CloseDriveLink => {
                self.close_drive_link();
                Task::none()
            }
            Message::DriveLinkPassword(value) => {
                self.drive_link.password = Zeroizing::new(value);
                Task::none()
            }
            Message::DriveLinkExpiry(value) => {
                self.drive_link.expiry = value;
                Task::none()
            }
            Message::CreateDriveLink => {
                let Some(expires) = self.drive_link_expiry() else {
                    return Task::none();
                };
                let password = std::mem::take(&mut self.drive_link.password);
                self.drive_link_task(PublicLinkAction::Create { password, expires })
            }
            Message::CopyDriveLink => {
                if let Some(url) = self
                    .drive_link
                    .link
                    .as_ref()
                    .and_then(|link| link.url.clone())
                {
                    self.drive_link.copied = true;
                    iced::clipboard::write(url)
                } else {
                    Task::none()
                }
            }
            Message::ConfirmRevokeDriveLink => {
                self.drive_link.confirming_revoke = true;
                Task::none()
            }
            Message::RevokeDriveLink => {
                self.drive_link.confirming_revoke = false;
                self.drive_link_task(PublicLinkAction::Revoke)
            }
            Message::DriveLinkLoaded(request, generation, result) => {
                if request == self.drive_link.request && self.current(generation) {
                    self.drive_link.busy = false;
                    match result {
                        Ok(link) => {
                            self.drive_link.loaded = true;
                            self.drive_link.link = link;
                            self.drive_link.copied = false;
                        }
                        Err(error) => self.drive_link.error = Some(error),
                    }
                }
                Task::none()
            }
            Message::ChooseDriveFolder => {
                if self.section != Section::Drive || self.drive.busy {
                    return Task::none();
                }
                self.drive.chooser = Some(vec![]);
                self.load_drive_folders()
            }
            Message::CloseDriveChooser => {
                self.drive.request += 1;
                self.drive.chooser = None;
                self.drive.listing = None;
                Task::none()
            }
            Message::BrowseDriveFolder(index) => {
                if let (Some(trail), Some(folder)) = (
                    &mut self.drive.chooser,
                    self.drive
                        .listing
                        .as_ref()
                        .and_then(|listing| listing.children.get(index)),
                ) {
                    trail.push(folder.clone());
                    return self.load_drive_folders();
                }
                Task::none()
            }
            Message::BackDriveFolder => {
                if let Some(trail) = &mut self.drive.chooser {
                    trail.pop();
                    return self.load_drive_folders();
                }
                Task::none()
            }
            Message::DriveFoldersLoaded(request, generation, result) => {
                if request == self.drive.request && self.current(generation) {
                    match result {
                        Ok(listing) => self.drive.listing = Some(listing),
                        Err(error) => self.sync.error = Some(error),
                    }
                }
                Task::none()
            }
            Message::AddDriveGallery => {
                let Some(uid) = self
                    .drive
                    .listing
                    .as_ref()
                    .map(|listing| listing.folder.uid.clone())
                else {
                    return Task::none();
                };
                let _ = self.update(Message::CloseDriveChooser);
                self.scan_drive_folders(vec![uid], true)
            }
            Message::RemoveDriveGallery => {
                let Some(app) = self.app().cloned() else {
                    return Task::none();
                };
                let Some(root) = self
                    .drive
                    .trail
                    .first()
                    .filter(|_| self.drive.trail.len() == 1 && !self.drive.busy)
                else {
                    return Task::none();
                };
                let id = root.gallery.clone();
                self.drive.busy = true;
                let generation = app.generation();
                spawn(
                    &app,
                    move |app| async move { app.forget_drive_gallery(id).await.map_err(shown) },
                    move |result| Message::DriveChanged(generation, true, result),
                )
            }
            Message::DriveChanged(generation, removed, result) => {
                if !self.current(generation) {
                    return Task::none();
                }
                self.drive.busy = false;
                if removed && result.is_ok() {
                    self.drive.trail.clear();
                    self.drive.parent_scroll.clear();
                    self.drive.restore_scroll = None;
                }
                self.sync.error = result.err();
                if self.section == Section::Drive {
                    return Task::batch([self.show_drive_place(), self.check_settings()]);
                }
                Task::none()
            }
            Message::AlbumsLoaded(request, generation, result) => {
                if request != self.albums_request || !self.current(generation) {
                    return Task::none();
                }
                self.albums_loading = false;
                self.thumbs_loading
                    .retain(|key, _| !key.starts_with("cover:"));
                match result {
                    Ok(albums) => {
                        self.albums = albums;
                        if let View::PhotoAlbum(id) = &self.view
                            && self.album_open
                            && !self
                                .albums
                                .iter()
                                .any(|album| album.source.id.as_str() == id)
                        {
                            return self.update(Message::BackToAlbums);
                        }
                    }
                    Err(error) => self.sync.error = Some(error),
                }
                Task::batch([self.restore_drive_scroll(), self.request_visible()])
            }
            Message::GridSize(target) => {
                let target = target.clamp(100, 320);
                if target == self.row_target {
                    return Task::none();
                }
                self.row_target = target;
                self.replan()
            }
            Message::GridZoom(inward) => self.update(Message::GridSize(if inward {
                self.row_target + 20
            } else {
                self.row_target.saturating_sub(20)
            })),
            Message::ToggleFavorite => self.toggle_favorite(),
            Message::FavoriteDone(request, generation, key, favorite, result) => {
                if request != self.favorite_request || !self.current(generation) {
                    return Task::none();
                }
                self.favorite_busy = false;
                let error = match result {
                    Ok(outcome) if outcome.failed == 0 => None,
                    Ok(_) => Some("Couldn’t change this favorite. Try again.".to_owned()),
                    Err(error) => Some(error),
                };
                if let Some(error) = error {
                    if let Some(viewer) = self
                        .viewer
                        .as_mut()
                        .filter(|viewer| viewer.item.as_ref().is_some_and(|item| item.key == key))
                    {
                        viewer.message = Some(error);
                    } else {
                        self.sync.error = Some(error);
                    }
                    return Task::none();
                }
                for item in self.pages.values_mut().flatten().flatten() {
                    if item.key == key {
                        item.favorite = favorite;
                    }
                }
                if let Some(item) = self
                    .viewer
                    .as_mut()
                    .and_then(|v| v.item.as_mut())
                    .filter(|item| item.key == key)
                {
                    item.favorite = favorite;
                }
                self.load_outline()
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

    fn open_library(&mut self) -> Task<Message> {
        Task::batch([self.load_outline(), self.load_albums(), self.start_sync()])
    }

    fn start_sync(&mut self) -> Task<Message> {
        Task::batch([self.check_settings(), self.index_photos(false)])
    }

    fn check_settings(&mut self) -> Task<Message> {
        if !self.browsing() || self.demo() || self.sync.settings_busy || self.sync.expired {
            return Task::none();
        }
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        self.sync.settings_busy = true;
        let generation = app.generation();
        spawn(
            &app,
            |app| async move {
                if app.gallery_visibility_cached().await.map_err(shown)? {
                    app.poll_gallery_configuration(false).await.map_err(shown)
                } else {
                    app.refresh_gallery_configuration(false)
                        .await
                        .map(Some)
                        .map_err(shown)
                }
            },
            move |result| Message::SettingsChecked(generation, result),
        )
    }

    fn index_photos(&mut self, repair: bool) -> Task<Message> {
        if !self.browsing() || self.sync.running || self.demo() || self.sync.expired {
            return Task::none();
        }
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        self.sync.running = true;
        self.sync.retry_at = None;
        self.sync.indexed = 0;
        let generation = app.generation();
        spawn(
            &app,
            move |app| async move { app.sync_photos_batch(repair, |_| {}).await },
            move |result| Message::Synced(generation, result),
        )
    }

    fn read_photos_status(&mut self) -> Task<Message> {
        if self.sync.status_busy || self.demo() {
            return Task::none();
        }
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        self.sync.status_busy = true;
        let generation = app.generation();
        spawn(
            &app,
            |app| async move { app.photos_sync_status().await },
            move |result| Message::PhotosStatus(generation, result),
        )
    }

    fn apply_photos_status(&mut self, status: PhotosSyncStatus) -> Task<Message> {
        self.sync.last_success = self.sync.last_success.max(status.last_success);
        if self.sync.revision >= status.revision {
            return Task::none();
        }
        self.sync.revision = status.revision;
        if self.section == Section::Drive {
            return Task::none();
        }
        if self.section == Section::Places {
            return self.start_places();
        }
        Task::batch([self.load_outline(), self.load_albums()])
    }

    fn photos_retry(&mut self, message: String) {
        self.sync.error = Some(message);
        let seconds = 5 * 2u64.pow(self.sync.failures.min(6));
        self.sync.failures = self.sync.failures.saturating_add(1);
        self.sync.retry_at = Some(std::time::Instant::now() + Duration::from_secs(seconds));
    }

    fn start_places(&mut self) -> Task<Message> {
        if self.section != Section::Places || !self.browsing() {
            return Task::none();
        }
        self.places.reset();
        self.clear_media();
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        let request = self.places.request;
        let generation = app.generation();
        let demo = self.demo();
        let (task, handle) = spawn(
            &app,
            move |app| async move {
                if demo {
                    return app.demo_places().await.map(Arc::new).map_err(shown);
                }
                app.places_scan().await.map(Arc::new).map_err(shown)
            },
            move |result| Message::PlacesReady(request, generation, result),
        )
        .abortable();
        self.places.work = Some(handle.abort_on_drop());
        task
    }

    fn scan_places_batch(&mut self) -> Task<Message> {
        let Some(scan) = self
            .places
            .scan
            .clone()
            .filter(|scan| self.places.next < scan.outline.len())
        else {
            return Task::none();
        };
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        let request = self.places.request;
        let generation = app.generation();
        let start = self.places.next;
        let (task, handle) = spawn(
            &app,
            move |app| async move { app.places_batch(&scan, start).await.map_err(shown) },
            move |result| Message::PlacesBatch(request, generation, result),
        )
        .abortable();
        self.places.work = Some(handle.abort_on_drop());
        task
    }

    fn open_place_photo(&self, index: usize) -> Task<Message> {
        let Some(id) = self
            .places
            .scan
            .as_ref()
            .and_then(|scan| scan.outline.ids.get(index))
            .cloned()
        else {
            return Task::none();
        };
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        let request = self.places.request;
        let generation = app.generation();
        let view = if self.demo() {
            View::Source("demo".into())
        } else {
            View::Photos
        };
        spawn(
            &app,
            move |app| async move {
                let outline = Arc::new(app.outline(view).await.ok()?);
                let index = outline.ids.iter().position(|item| item == &id)?;
                Some((outline, index))
            },
            move |result| Message::PlacePhotoReady(request, generation, result),
        )
    }

    fn load_albums(&mut self) -> Task<Message> {
        if !self.browsing() || self.section == Section::Places {
            return Task::none();
        }
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        self.albums_request += 1;
        self.albums_loading = true;
        let request = self.albums_request;
        let generation = app.generation();
        let demo = self.demo();
        let drive = self.section == Section::Drive;
        let hidden_index = self.section == Section::Hidden;
        let unlocked = self.hidden.unlocked;
        let folder = self
            .drive
            .trail
            .last()
            .map(|a| (a.gallery.to_string(), a.source.id.to_string()));
        spawn(
            &app,
            move |app| async move {
                if hidden_index {
                    let (photos, drive) = tokio::try_join!(
                        app.photo_albums_with_hidden(true),
                        app.hidden_drive_albums(),
                    )
                    .map_err(shown)?;
                    Ok(photos
                        .into_iter()
                        .filter(|album| album.settings.hidden)
                        .chain(drive)
                        .collect())
                } else if drive {
                    app.drive_albums(
                        folder
                            .as_ref()
                            .map(|(gallery, album)| (gallery.as_str(), album.as_str())),
                        unlocked,
                    )
                    .await
                    .map_err(shown)
                } else if demo {
                    app.catalog
                        .photo_albums(
                            proton_gallery_rs::model::AccountId::new("synthetic")
                                .map_err(|e| e.to_string())?,
                        )
                        .await
                        .map_err(|e| e.to_string())
                } else {
                    app.photo_albums_with_hidden(unlocked).await.map_err(shown)
                }
            },
            move |result| Message::AlbumsLoaded(request, generation, result),
        )
    }

    fn reset_drive_navigation(&mut self) {
        self.close_drive_link();
        self.drive.trail.clear();
        self.drive.parent_scroll.clear();
        self.drive.restore_scroll = None;
        self.drive.chooser = None;
        self.drive.listing = None;
        self.drive.request += 1;
        self.albums.clear();
        self.albums_request += 1;
    }

    fn reveal_verified_hidden(&mut self) -> Task<Message> {
        if !self.window_focused || !self.hidden.verified {
            return Task::none();
        }
        self.hidden.verified = false;
        self.hidden.unlocked = true;
        self.show_hidden_index()
    }

    fn show_hidden_index(&mut self) -> Task<Message> {
        self.reset_drive_navigation();
        self.clear_media();
        self.hidden.private_album = false;
        self.section = Section::Hidden;
        self.view = View::Photos;
        self.album_open = false;
        self.load_albums()
    }

    fn close_hidden_access(&mut self) {
        self.hidden = HiddenAccess {
            request: self.hidden.request + 1,
            ..HiddenAccess::default()
        };
    }

    fn show_drive_place(&mut self) -> Task<Message> {
        self.close_drive_link();
        self.clear_media();
        self.albums.clear();
        self.album_open = !self.drive.trail.is_empty();
        self.hidden.private_album = self.drive.trail.iter().any(|album| album.settings.hidden);
        if let Some(album) = self.drive.trail.last() {
            self.view = View::DriveAlbum {
                gallery: album.gallery.to_string(),
                album: album.source.id.to_string(),
            };
        }
        Task::batch([self.load_albums(), self.load_outline()])
    }

    fn close_drive_link(&mut self) {
        self.drive_link = DriveLinkPanel {
            request: self.drive_link.request + 1,
            ..DriveLinkPanel::default()
        };
    }

    fn drive_link_expiry(&self) -> Option<Option<i64>> {
        let days = self.drive_link.expiry.trim();
        if days.is_empty() {
            return Some(None);
        }
        let days = days
            .parse::<i64>()
            .ok()
            .filter(|days| (1..=3650).contains(days))?;
        Some(Some(chrono::Utc::now().timestamp() + days * 86_400))
    }

    fn drive_link_task(&mut self, action: PublicLinkAction) -> Task<Message> {
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        let Some(folder) = self
            .drive
            .trail
            .last()
            .filter(|_| self.drive_link.open && !self.drive_link.busy)
        else {
            return Task::none();
        };
        let gallery = folder.gallery.to_string();
        let album = folder.source.id.to_string();
        self.drive_link.busy = true;
        self.drive_link.error = None;
        self.drive_link.request += 1;
        let request = self.drive_link.request;
        let generation = app.generation();
        spawn(
            &app,
            move |app| async move {
                app.drive_public_link(gallery, album, action)
                    .await
                    .map_err(shown)
            },
            move |result| Message::DriveLinkLoaded(request, generation, result),
        )
    }

    fn load_drive_folders(&mut self) -> Task<Message> {
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        let folder = self
            .drive
            .chooser
            .as_ref()
            .and_then(|trail| trail.last())
            .map(|folder| folder.uid.clone());
        self.drive.listing = None;
        self.sync.error = None;
        self.drive.request += 1;
        let request = self.drive.request;
        let generation = app.generation();
        spawn(
            &app,
            move |app| async move { app.browse_drive_folders(folder).await.map_err(shown) },
            move |result| Message::DriveFoldersLoaded(request, generation, result),
        )
    }

    fn refresh_drive(&mut self) -> Task<Message> {
        if self.drive.chooser.is_some() {
            return self.load_drive_folders();
        }
        let folder_uid = |album: &proton_gallery_rs::library::Album| {
            proton_sdk::ids::NodeUid::new(
                album.source.volume.to_string().into(),
                album.source.root.to_string().into(),
            )
        };
        let folders = if let Some(root) = self.drive.trail.first() {
            vec![folder_uid(root)]
        } else {
            self.albums.iter().map(folder_uid).collect()
        };
        self.scan_drive_folders(folders, false)
    }

    fn scan_drive_folders(
        &mut self,
        folders: Vec<proton_sdk::ids::NodeUid>,
        add: bool,
    ) -> Task<Message> {
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        if self.drive.busy {
            return Task::none();
        }
        self.drive.busy = true;
        self.sync.error = None;
        let generation = app.generation();
        spawn(
            &app,
            move |app| async move {
                for folder in folders {
                    if add {
                        app.add_drive_folder(folder).await.map_err(shown)?;
                    } else {
                        app.scan_drive_folder(folder).await.map_err(shown)?;
                    }
                }
                Ok(())
            },
            move |result| Message::DriveChanged(generation, false, result),
        )
    }

    fn toggle_favorite(&mut self) -> Task<Message> {
        if !self.browsing() || self.favorite_busy {
            return Task::none();
        }
        let Some(item) = self
            .viewer
            .as_ref()
            .filter(|viewer| !viewer.loading)
            .and_then(|viewer| viewer.item.as_ref())
        else {
            return Task::none();
        };
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        if !item.in_proton {
            return Task::none();
        }
        self.favorite_busy = true;
        self.favorite_request += 1;
        let request = self.favorite_request;
        let generation = app.generation();
        let favorite = !item.favorite;
        let key = item.key.clone();
        let done_key = key.clone();
        let demo = self.demo();
        spawn(
            &app,
            move |app| async move {
                if demo {
                    app.catalog
                        .set_favorite(vec![key], favorite)
                        .await
                        .map_err(|e| e.to_string())?;
                    Ok(proton_gallery_app_rs::favorites::FavoriteOutcome {
                        changed: 1,
                        failed: 0,
                    })
                } else {
                    app.set_favorite(vec![key], favorite).await.map_err(shown)
                }
            },
            move |result| {
                Message::FavoriteDone(request, generation, done_key.clone(), favorite, result)
            },
        )
    }

    fn load_outline(&mut self) -> Task<Message> {
        if !self.browsing()
            || self.section == Section::Places
            || (matches!(
                self.section,
                Section::Albums | Section::Drive | Section::Hidden
            ) && !self.album_open)
        {
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
        let favorites = self.section == Section::Favorites;
        let unlocked = self.hidden.unlocked && self.hidden.private_album;
        let (task, handle) = spawn(
            &app,
            move |app| async move {
                app.outline_with_visibility(view, favorites, unlocked)
                    .await
                    .map(Arc::new)
                    .map_err(shown)
            },
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
            .anchor(self.media_top())
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
            self.row_target,
        );
        let scroll = if self.drive.restore_scroll.is_some() {
            self.restore_drive_scroll()
        } else {
            self.restore(anchor)
        };
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
                    - 2.0 * GUTTER,
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
        let anchor = self.layout.anchor(self.media_top());
        self.layout = Layout::plan(&self.ratios, &outline.times, width as u32, self.row_target);
        let scroll = self.restore(anchor);
        Task::batch([scroll, self.request_visible()])
    }

    fn restore(&mut self, anchor: Option<(usize, f32)>) -> Task<Message> {
        let prefix = self.cover_height();
        let top = anchor
            .and_then(|a| self.layout.restore(a))
            .map_or(self.scroll_top.min(prefix), |y| prefix + y);
        self.scroll_absolute(top)
    }

    fn restore_drive_scroll(&mut self) -> Task<Message> {
        if self.albums_loading || (self.album_open && self.outline_work.is_some()) {
            return Task::none();
        }
        self.drive
            .restore_scroll
            .take()
            .map_or_else(Task::none, |top| self.scroll_absolute(top))
    }

    fn scroll_absolute(&mut self, top: f32) -> Task<Message> {
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
        if !self.browsing() || self.section == Section::Places {
            return Task::none();
        }
        let Some(app) = self.app().cloned() else {
            return Task::none();
        };
        let generation = app.generation();
        let mut tasks = Vec::new();
        let provider = self.provider(&app);
        let covers: Vec<_> = self
            .visible_cover_groups()
            .flat_map(|group| group.visible)
            .collect();
        for index in covers {
            let album = &self.albums[index];
            if self.thumbs_loading.len() >= THUMBS_IN_FLIGHT {
                break;
            }
            let Some(cover) = &album.cover else { continue };
            let id = cover_id(cover);
            if self.thumbs.contains(&id)
                || self.thumbs_loading.contains_key(&id)
                || self.thumbs_failed.contains(&id)
            {
                continue;
            }
            let request = self.albums_request;
            let provider = provider.clone();
            let cover = cover.clone();
            let done = id.clone();
            let (task, handle) = Task::perform(
                async move {
                    load_grid_image(
                        provider.as_ref(),
                        &cover.key,
                        cover.kind == proton_gallery_rs::model::MediaKind::Video,
                        cover.size_bytes.unwrap_or(0),
                    )
                    .await
                },
                move |result| Message::CoverLoaded(request, generation, done.clone(), result),
            )
            .abortable();
            self.thumbs_loading.insert(id, handle.abort_on_drop());
            tasks.push(task);
        }
        let Some(outline) = self.outline.clone() else {
            return Task::batch(tasks);
        };
        let rows = self.layout.visible(self.media_top(), self.media_height());
        let items = self.layout.items_in(rows);
        if items.is_empty() {
            return Task::batch(tasks);
        }
        let request = self.library_request;
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
        let visible: HashSet<_> = visible_ids.iter().map(String::as_str).collect();
        self.thumbs_loading
            .retain(|id, _| id.starts_with("cover:") || visible.contains(id.as_str()));
        let center = self.media_top() + self.media_height() / 2.0;
        let mut nearby: Vec<_> = items.collect();
        nearby.sort_by_key(|&index| {
            self.layout
                .item_bounds(index)
                .map_or(u32::MAX, |(top, height)| {
                    (top + height / 2.0 - center).abs() as u32
                })
        });
        for index in nearby {
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
            // Hidden-album pictures stay in memory only.
            let disk = (!self.hidden.private_album).then(|| self.disk_thumbs.clone());
            let (task, handle) =
                Task::perform(load_thumbnail(provider, item, disk), done).abortable();
            self.thumbs_loading.insert(id, handle.abort_on_drop());
            tasks.push(task);
        }
        Task::batch(tasks)
    }

    fn open(&mut self, index: usize) -> Task<Message> {
        if (matches!(
            self.section,
            Section::Albums | Section::Drive | Section::Hidden
        ) && !self.album_open)
            || !self.browsing()
        {
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
            #[cfg(target_os = "linux")]
            player: None,
            #[cfg(target_os = "linux")]
            playback: crate::video::Playback::default(),
        });
        if let Some(work) = self.viewer_work.take() {
            work.abort();
        }
        let originals = self.originals.clone();
        let provider = self.provider(&app);
        let (task, handle) = spawn(
            &app,
            {
                let private = self.hidden.private_album;
                move |app| load_full_with_privacy(app, provider, outline, index, originals, private)
            },
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
        let y = y + self.cover_height();
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
                Key::Character(value) if value.eq_ignore_ascii_case("f") => {
                    self.update(Message::ToggleFavorite)
                }
                _ => Task::none(),
            };
        }
        if self.browsing() && key == Key::Named(Named::F5) {
            return self.update(Message::Sync);
        }
        if self.browsing() {
            match &key {
                Key::Named(Named::Escape) if self.drive_link.open => {
                    return self.update(Message::CloseDriveLink);
                }
                Key::Named(Named::Escape) if self.drive.chooser.is_some() => {
                    return self.update(Message::CloseDriveChooser);
                }
                Key::Named(Named::Escape)
                    if self.section == Section::Hidden
                        || self.hidden.busy
                        || self.hidden.verified =>
                {
                    return self.update(Message::LockHidden);
                }
                Key::Named(Named::Escape) if self.album_open => {
                    return self.update(Message::BackToAlbums);
                }
                Key::Character(value) if value == "+" || value == "=" => {
                    return self.update(Message::GridSize(self.row_target + 20));
                }
                Key::Character(value) if value == "-" => {
                    return self.update(Message::GridSize(self.row_target.saturating_sub(20)));
                }
                _ => {}
            }
        }
        let count = self.ratios.len();
        if count == 0 || !self.browsing() {
            return Task::none();
        }
        let focus = self.focus.unwrap_or(0).min(count - 1);
        let page_rows = (self.viewport_height / self.row_target as f32).max(1.0) as i32;
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
        self.close_hidden_access();
        self.reset_drive_navigation();
        self.drive.busy = false;
        self.albums.clear();
        self.albums_request += 1;
        self.albums_loading = false;
        self.favorite_request += 1;
        self.favorite_busy = false;
        self.section = Section::Photos;
        self.places.reset();
        self.album_open = false;
        self.view = if self.demo() {
            View::Source("demo".into())
        } else {
            View::Photos
        };
        self.clear_media();
    }

    fn clear_media(&mut self) {
        self.outline_request += 1;
        self.library_request += 1;
        self.outline_work = None;
        if let Some(work) = self.viewer_work.take() {
            work.abort();
        }
        self.pages_failed.clear();
        self.thumbs_failed.clear();
        self.thumbs_retryable.clear();
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
                None => {
                    let mut page = column![self.toolbar()];
                    if let Some(error) = self.hidden.error.as_ref().or(self.sync.error.as_ref()) {
                        page = page.push(
                            container(text(error.as_str()).size(13).style(text::danger))
                                .padding([8, GUTTER as u16]),
                        );
                    }
                    page = page.push(self.content());
                    if self.window.width < 760.0 {
                        page.into()
                    } else {
                        row![self.sidebar(), page].into()
                    }
                }
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
                        button("Retry cleanup")
                            .on_press_maybe((!busy).then_some(Message::SignOut))
                            .padding([10, 14])
                            .style(design::primary),
                    ]
                    .spacing(14),
                )
                .padding(24),
            )
            .into();
        }
        let mut card = column![
            text("Welcome back").font(design::MEDIUM).size(30),
            text("Sign in to browse your Proton Photos.")
                .size(14)
                .style(design::muted),
            Space::new().height(8),
        ]
        .spacing(14)
        .width(Length::Fill)
        .max_width(368);
        match self.account.stage {
            SignInStage::SecondFactor => {
                card = card.push(text("Enter the code from your authenticator app."));
                card = card.push(
                    text_input("Two-factor code", &self.form.code)
                        .on_input(Message::Code)
                        .on_submit_maybe((!busy).then_some(Message::SubmitCode))
                        .padding(14)
                        .size(14)
                        .style(design::input),
                );
                card = card.push(row![
                    button("Cancel")
                        .on_press(Message::CancelSignIn)
                        .padding([10, 14])
                        .style(design::secondary),
                    Space::new().width(Length::Fill),
                    button("Continue")
                        .on_press_maybe((!busy).then_some(Message::SubmitCode))
                        .padding([10, 14])
                        .style(design::primary),
                ]);
            }
            SignInStage::MailboxPassword => {
                card = card.push(text("Enter your mailbox password."));
                card = card.push(
                    text_input("Mailbox password", &self.form.mailbox)
                        .secure(true)
                        .on_input(Message::Mailbox)
                        .on_submit_maybe((!busy).then_some(Message::SubmitMailbox))
                        .padding(14)
                        .size(14)
                        .style(design::input),
                );
                card = card.push(row![
                    button("Cancel")
                        .on_press(Message::CancelSignIn)
                        .padding([10, 14])
                        .style(design::secondary),
                    Space::new().width(Length::Fill),
                    button("Continue")
                        .on_press_maybe((!busy).then_some(Message::SubmitMailbox))
                        .padding([10, 14])
                        .style(design::primary),
                ]);
            }
            SignInStage::Verification => {
                card = card.push(text("Open the verification page, complete the CAPTCHA, then copy the displayed token and paste it here."));
                if let Some(url) = &self.account.verification_url {
                    card = card.push(text(url.as_str()).size(12));
                    card = card.push(
                        button("Open verification page")
                            .on_press(Message::OpenVerification)
                            .padding([10, 14])
                            .style(design::secondary),
                    );
                    card = card.push(
                        button("Copy verification page URL")
                            .on_press(Message::CopyVerificationUrl)
                            .padding([10, 14])
                            .style(design::secondary),
                    );
                }
                card = card.push(
                    text_input("Verification token", &self.form.verification)
                        .on_input(Message::Verification)
                        .on_submit_maybe((!busy).then_some(Message::SubmitVerification))
                        .padding(14)
                        .size(14)
                        .style(design::input),
                );
                card = card.push(
                    row![
                        button("Cancel")
                            .on_press(Message::CancelSignIn)
                            .padding([10, 14])
                            .style(design::secondary),
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
                card = card.push(text("Email or username").font(design::MEDIUM).size(13));
                card = card.push(
                    text_input("Email or username", &self.form.username)
                        .on_input(Message::Username)
                        .padding(14)
                        .size(14)
                        .style(design::input),
                );
                card = card.push(text("Password").font(design::MEDIUM).size(13));
                card = card.push(
                    text_input("Password", &self.form.password)
                        .secure(true)
                        .on_input(Message::Password)
                        .on_submit_maybe(ready.then_some(Message::SignIn))
                        .padding(14)
                        .size(14)
                        .style(design::input),
                );
                card = card.push(
                    button(
                        text(if busy { "Signing in…" } else { "Sign in" })
                            .font(design::MEDIUM)
                            .size(14)
                            .width(Length::Fill)
                            .align_x(Alignment::Center),
                    )
                    .on_press_maybe(ready.then_some(Message::SignIn))
                    .width(Length::Fill)
                    .padding(14)
                    .style(design::primary),
                );
            }
        }
        if busy && self.account.stage == SignInStage::SignedOut {
            card = card.push(
                button("Cancel sign-in")
                    .on_press(Message::CancelSignIn)
                    .padding([10, 14])
                    .style(design::secondary),
            );
        }
        if let Some(error) = &self.form.error {
            card = card.push(text(error.as_str()).style(text::danger));
            if self.account.stage == SignInStage::SignedOut {
                card = card.push(
                    button("Retry saved sign-in")
                        .on_press_maybe((!busy).then_some(Message::Restore))
                        .padding([10, 14])
                        .style(design::primary),
                );
            }
        }
        let brand = row![
            design::logo(40.0),
            text("Proton Gallery").font(design::MEDIUM).size(21)
        ]
        .spacing(12)
        .align_y(Alignment::Center);
        let page = column![
            brand,
            Space::new().height(24),
            container(card)
                .padding(if self.window.width < 760.0 { 24 } else { 36 })
                .style(design::panel),
            container(
                row![
                    design::icon(Icon::Shield, 16.0),
                    text("Your photos stay yours.")
                        .size(12)
                        .style(design::muted)
                ]
                .spacing(8)
                .align_y(Alignment::Center)
            )
            .padding(20),
        ]
        .align_x(Alignment::Center)
        .max_width(480)
        .width(Length::Fill);
        container(center(scrollable(
            center(page).padding(24).height(Length::Fill),
        )))
        .style(design::sidebar)
        .into()
    }

    fn sidebar(&self) -> Element<'_, Message> {
        let brand = row![
            design::logo(36.0),
            text("Gallery").font(design::MEDIUM).size(22)
        ]
        .spacing(10)
        .align_y(Alignment::Center);
        let mut navigation = column![
            self.navigation(Section::Photos, Icon::Photos, "All photos"),
            self.navigation(Section::Favorites, Icon::Heart, "Favorites"),
            self.navigation(Section::Albums, Icon::Albums, "Albums"),
            self.navigation(Section::Places, Icon::Map, "Places"),
        ]
        .spacing(6);
        if !self.demo() {
            navigation = navigation.push(self.navigation(Section::Drive, Icon::Folder, "Drive"));
        }
        let mut side = column![
            container(brand).padding([12, 8]),
            Space::new().height(20),
            navigation,
            Space::new().height(Length::Fill),
        ]
        .width(SIDEBAR)
        .height(Length::Fill)
        .padding(16);
        if self.demo() {
            side = side.push(
                container(
                    column![
                        text("Demo library").font(design::MEDIUM).size(13),
                        text("Sample media. Your account is not connected.")
                            .size(12)
                            .style(design::muted),
                    ]
                    .spacing(6),
                )
                .padding(12)
                .style(design::soft),
            );
        } else {
            side = side.push(
                container(
                    text(self.account.username.as_str())
                        .size(13)
                        .font(design::MEDIUM),
                )
                .padding([8, 8]),
            );
            if self.account.persistence_failed || !self.keyring {
                side = side.push(
                    text("Signed in until the app closes")
                        .size(12)
                        .style(design::muted),
                );
            }
            side = side.push(
                action(Icon::Shield, "Repair library")
                    .width(Length::Fill)
                    .style(design::quiet)
                    .on_press_maybe(
                        (!self.sync.running && !self.sync.expired).then_some(Message::RepairPhotos),
                    ),
            );
            side = side.push(
                action(Icon::Exit, "Sign out")
                    .width(Length::Fill)
                    .on_press(Message::SignOut)
                    .style(design::quiet),
            );
        }
        container(side).style(design::sidebar).into()
    }

    fn navigation<'a>(&self, section: Section, icon: Icon, label: &'a str) -> Element<'a, Message> {
        button(
            row![
                design::icon(icon, 19.0),
                text(label).font(design::MEDIUM).size(14)
            ]
            .spacing(10)
            .align_y(Alignment::Center),
        )
        .width(Length::Fill)
        .padding([12, 14])
        .on_press(Message::Navigate(section))
        .style(if self.section == section {
            design::navigation
        } else {
            design::quiet
        })
        .into()
    }

    fn section_title(&self) -> &str {
        if let Some(album) = self.drive.trail.last() {
            return album
                .settings
                .title
                .as_deref()
                .unwrap_or(&album.source.label);
        }
        if self.album_open {
            let id = match &self.view {
                View::PhotoAlbum(id) | View::Source(id) => Some(id.as_str()),
                _ => None,
            };
            if let Some(album) = self
                .albums
                .iter()
                .find(|a| Some(a.source.id.as_str()) == id)
            {
                return album
                    .settings
                    .title
                    .as_deref()
                    .unwrap_or(&album.source.label);
            }
        }
        match self.section {
            Section::Photos => "Photos",
            Section::Favorites => "Favorites",
            Section::Albums => "Albums",
            Section::Drive => "Drive galleries",
            Section::Hidden => "Hidden albums",
            Section::Places => "Places",
        }
    }

    fn toolbar(&self) -> Element<'_, Message> {
        let at_albums = matches!(
            self.section,
            Section::Albums | Section::Drive | Section::Hidden
        ) && !self.album_open;
        let status = if self.sync.settings_busy {
            "Checking Gallery settings…".into()
        } else if self.sync.visibility_stale {
            "Visibility may be out of date".into()
        } else if self.hidden.busy || self.hidden.verified {
            "Waiting for system authentication…".into()
        } else if self.section == Section::Drive && self.drive.busy {
            "Updating Drive galleries…".into()
        } else if self.sync.running && self.section != Section::Drive {
            if self.demo() {
                "Preparing sample media…".into()
            } else {
                format!("Syncing your library… {} indexed", self.sync.indexed)
            }
        } else if self.section == Section::Places {
            "Proton Photos".into()
        } else if at_albums {
            count_label(self.albums.len(), "album", "albums")
        } else {
            count_label(self.ratios.len(), "item", "items")
        };
        let last_sync = self
            .sync
            .last_success
            .and_then(|time| chrono::DateTime::from_timestamp(time, 0))
            .map(|time| {
                format!(
                    "Last Photos sync {}",
                    time.with_timezone(&chrono::Local).format("%b %e, %H:%M")
                )
            });
        let mut title = column![
            text(self.section_title())
                .font(design::MEDIUM)
                .size(if self.window.width < 760.0 { 22 } else { 30 }),
            text(status).size(13).style(design::muted),
        ]
        .spacing(4);
        if self.section != Section::Drive
            && let Some(label) = last_sync
        {
            title = title.push(text(label).size(12).style(design::muted));
        }
        let refresh = button(
            row![
                design::icon(Icon::Refresh, 16.0),
                text(if self.sync.expired && self.section != Section::Drive {
                    "Sign in again"
                } else if self.sync.error.is_some() {
                    "Retry"
                } else {
                    "Refresh"
                })
                .size(13),
            ]
            .spacing(8)
            .align_y(Alignment::Center),
        )
        .height(32)
        .padding([7, 10])
        .on_press_maybe(
            (if self.section == Section::Drive {
                !self.drive.busy
            } else {
                !self.sync.running
            })
            .then_some(if self.sync.expired && self.section != Section::Drive {
                Message::SignOut
            } else {
                Message::Sync
            }),
        )
        .style(design::secondary);
        let mut controls = row![].spacing(12).align_y(Alignment::Center);
        if self.album_open {
            controls = controls.push(
                button(
                    row![
                        design::icon(Icon::Back, 16.0),
                        text(if self.section == Section::Drive || self.hidden.unlocked {
                            "Back"
                        } else {
                            "Albums"
                        })
                        .size(13)
                    ]
                    .spacing(6)
                    .align_y(Alignment::Center),
                )
                .height(32)
                .padding([7, 10])
                .on_press(Message::BackToAlbums)
                .style(design::secondary),
            );
        } else if self.section == Section::Hidden {
            controls = controls.push(
                action(Icon::Back, "Back")
                    .style(design::secondary)
                    .on_press(Message::LockHidden),
            );
        } else if self.window.width < 760.0 {
            let mut sections = vec![
                Section::Photos,
                Section::Favorites,
                Section::Albums,
                Section::Places,
            ];
            if !self.demo() {
                sections.push(Section::Drive);
            }
            controls = controls.push(
                pick_list(sections, Some(self.section), Message::Navigate)
                    .text_size(13)
                    .padding(8),
            );
        }
        if self.section == Section::Drive && self.drive.chooser.is_none() && !self.hidden.unlocked {
            if !self.album_open {
                controls = controls.push(
                    action(Icon::Add, "Add gallery")
                        .style(design::primary)
                        .on_press_maybe((!self.drive.busy).then_some(Message::ChooseDriveFolder)),
                );
            } else if self.drive.trail.len() == 1 {
                controls = controls.push(
                    button(text("Remove gallery").size(13))
                        .padding([10, 12])
                        .style(design::secondary)
                        .on_press_maybe((!self.drive.busy).then_some(Message::RemoveDriveGallery)),
                );
            }
            if self.album_open && !self.drive_link.open {
                controls = controls.push(
                    button(text("Public link").size(13))
                        .padding([7, 10])
                        .height(32)
                        .on_press(Message::OpenDriveLink)
                        .style(design::secondary),
                );
            }
        }
        if self.hidden.unlocked {
            controls = controls.push(
                action(Icon::Lock, "Lock")
                    .style(design::secondary)
                    .on_press(Message::LockHidden),
            );
        } else if at_albums && !self.demo() && self.drive.chooser.is_none() {
            controls = controls.push(
                action(Icon::Lock, "Hidden albums")
                    .style(design::secondary)
                    .on_press_maybe(
                        (!self.hidden.busy && !self.hidden.verified).then_some(Message::OpenHidden),
                    ),
            );
        }
        if self.window.width >= 950.0 && !at_albums && self.section != Section::Places {
            controls = controls
                .push(text("Size").size(12).style(design::muted))
                .push(
                    slider(100..=320, self.row_target, Message::GridSize)
                        .step(20u32)
                        .width(90),
                );
        }
        controls = controls.push(refresh);
        let narrow = self.window.width < 960.0;
        if self.window.width < 760.0 && !self.demo() && !self.hidden.unlocked {
            if self.section == Section::Photos && !self.sync.expired && self.window.width >= 620.0 {
                controls = controls.push(
                    action(Icon::Shield, "Repair library")
                        .style(design::quiet)
                        .on_press_maybe((!self.sync.running).then_some(Message::RepairPhotos)),
                );
            }
            controls = controls.push(
                action(Icon::Exit, "Sign out")
                    .on_press(Message::SignOut)
                    .style(design::quiet),
            );
        }
        let bar: Element<'_, Message> = if narrow {
            column![
                title,
                scrollable(controls).direction(scrollable::Direction::Horizontal(
                    scrollable::Scrollbar::new().width(0).scroller_width(0)
                ))
            ]
            .spacing(6)
            .into()
        } else {
            row![container(title).width(Length::Fill), controls]
                .align_y(Alignment::Center)
                .into()
        };
        container(bar)
            .width(Length::Fill)
            .height(toolbar_height(self.window.width))
            .padding([10, GUTTER as u16])
            .align_y(Alignment::Center)
            .style(design::toolbar)
            .into()
    }

    fn albums_view(&self) -> Element<'_, Message> {
        if self.albums.is_empty() {
            return center(
                column![
                    design::icon(Icon::Albums, 40.0),
                    text(if self.albums_loading {
                        "Loading your albums…"
                    } else if self.section == Section::Hidden {
                        "No hidden albums"
                    } else if self.section == Section::Drive {
                        "No Drive galleries yet"
                    } else {
                        "No albums yet"
                    })
                    .size(22)
                    .font(design::MEDIUM),
                    text(if self.section == Section::Hidden {
                        "Albums you hide in Gallery appear here."
                    } else if self.section == Section::Drive {
                        "Choose a Drive folder to browse its photos, videos, and subfolders."
                    } else {
                        "Your Proton Photos albums appear after refreshing the library."
                    })
                    .size(14)
                    .style(design::muted),
                ]
                .spacing(16)
                .align_x(Alignment::Center),
            )
            .into();
        }
        self.media_grid()
    }

    fn cover_columns(&self) -> usize {
        ((self.grid_width() + COVER_GAP) / (COVER_WIDTH + COVER_GAP))
            .floor()
            .max(1.0) as usize
    }

    fn cover_groups(&self) -> impl Iterator<Item = (Option<&'static str>, std::ops::Range<usize>)> {
        let count = if matches!(
            self.section,
            Section::Drive | Section::Albums | Section::Hidden
        ) && (!self.album_open || self.section == Section::Drive)
        {
            self.albums.len()
        } else {
            0
        };
        let groups = if self.section == Section::Hidden {
            let photos = self.albums.partition_point(|album| album.photos_album);
            [
                (Some("Proton Photos"), 0..photos),
                (Some("Drive galleries"), photos..count),
            ]
        } else {
            [(None, 0..count), (None, 0..0)]
        };
        groups.into_iter().filter(|(_, albums)| !albums.is_empty())
    }

    fn cover_height(&self) -> f32 {
        self.cover_groups()
            .map(|(title, albums)| {
                albums.len().div_ceil(self.cover_columns()) as f32 * (COVER_HEIGHT + COVER_GAP)
                    + if title.is_some() { COVER_HEADING } else { 0.0 }
            })
            .sum()
    }

    fn visible_cover_groups(&self) -> impl Iterator<Item = CoverGroup> {
        let stride = COVER_HEIGHT + COVER_GAP;
        let columns = self.cover_columns();
        let top = (self.scroll_top - grid::OVERSCAN).max(0.0);
        let bottom = self.scroll_top + self.viewport_height + grid::OVERSCAN;
        self.cover_groups().scan(0.0, move |y, (title, albums)| {
            let heading = if title.is_some() { COVER_HEADING } else { 0.0 };
            let rows = albums.len().div_ceil(columns);
            let first = (((top - *y - heading) / stride).floor() as usize).min(rows);
            let last = (((bottom - *y - heading) / stride).ceil() as usize).min(rows);
            *y += heading + rows as f32 * stride;
            let visible = (albums.start + first * columns).min(albums.end)
                ..(albums.start + last * columns).min(albums.end);
            Some(CoverGroup {
                title,
                visible,
                before: first as f32 * stride,
                after: (rows - last) as f32 * stride,
            })
        })
    }

    fn media_top(&self) -> f32 {
        (self.scroll_top - self.cover_height()).max(0.0)
    }

    fn media_height(&self) -> f32 {
        (self.viewport_height - (self.cover_height() - self.scroll_top).max(0.0)).max(0.0)
    }

    fn album_rows(&self) -> iced::widget::Column<'_, Message> {
        let columns = self.cover_columns();
        let width =
            ((self.grid_width() - (columns - 1) as f32 * COVER_GAP) / columns as f32).min(320.0);
        let mut list = column![].width(Length::Fill);
        for group in self.visible_cover_groups() {
            if let Some(title) = group.title {
                list = list.push(
                    container(text(title).font(design::MEDIUM).size(16))
                        .height(COVER_HEADING)
                        .align_y(Alignment::Center),
                );
            }
            list = list.push(Space::new().height(group.before));
            for albums in self.albums[group.visible].chunks(columns) {
                let mut cards = row![].spacing(COVER_GAP);
                for album in albums {
                    let title = album
                        .settings
                        .title
                        .as_deref()
                        .unwrap_or(&album.source.label);
                    let cover: Element<'_, Message> = match album
                        .cover
                        .as_ref()
                        .and_then(|asset| self.thumbs.get(&cover_id(asset)))
                    {
                        Some(decoded) => image(decoded.handle.clone())
                            .width(Length::Fill)
                            .height(Length::Fill)
                            .content_fit(ContentFit::Cover)
                            .into(),
                        None => center(design::icon(Icon::Albums, 30.0)).into(),
                    };
                    let items = count_label(album.direct_items as usize, "item", "items");
                    let detail = if album.child_albums == 0 {
                        items
                    } else {
                        format!(
                            "{items} · {}",
                            count_label(album.child_albums as usize, "folder", "folders")
                        )
                    };
                    cards = cards.push(
                        button(
                            column![
                                container(cover).height(148).clip(true).style(design::soft),
                                text(title)
                                    .font(design::MEDIUM)
                                    .size(17)
                                    .wrapping(text::Wrapping::None),
                                text(detail).size(13).style(design::muted),
                            ]
                            .spacing(5),
                        )
                        .width(width)
                        .height(COVER_HEIGHT)
                        .padding(8)
                        .on_press(Message::OpenAlbum(album.source.id.to_string()))
                        .style(design::secondary),
                    );
                }
                list = list.push(cards).push(Space::new().height(COVER_GAP));
            }
            list = list.push(Space::new().height(group.after));
        }
        list
    }

    fn drive_chooser<'a>(&'a self, trail: &'a [FolderChoice]) -> Element<'a, Message> {
        let name = trail
            .last()
            .map_or("My files", |folder| folder.name.as_str());
        let mut rows = column![
            row![
                button("Back")
                    .on_press_maybe((!trail.is_empty()).then_some(Message::BackDriveFolder)),
                text(name).font(design::MEDIUM).size(20),
                Space::new().width(Length::Fill),
                button("Cancel")
                    .on_press(Message::CloseDriveChooser)
                    .style(design::secondary),
                button("Use this folder")
                    .on_press_maybe(
                        self.drive
                            .listing
                            .as_ref()
                            .map(|_| Message::AddDriveGallery)
                    )
                    .style(design::primary),
            ]
            .spacing(10)
            .align_y(Alignment::Center)
        ]
        .spacing(10);
        if let Some(listing) = &self.drive.listing {
            for (index, folder) in listing.children.iter().enumerate() {
                rows = rows.push(
                    button(
                        row![design::icon(Icon::Folder, 18.0), text(folder.name.as_str())]
                            .spacing(12)
                            .align_y(Alignment::Center),
                    )
                    .width(Length::Fill)
                    .padding(10)
                    .style(design::secondary)
                    .on_press(Message::BrowseDriveFolder(index)),
                );
            }
        } else if self.sync.error.is_none() {
            rows = rows.push(text("Loading folders…").style(design::muted));
        }
        scrollable(container(rows).padding([0, GUTTER as u16])).into()
    }

    fn content(&self) -> Element<'_, Message> {
        if self.section == Section::Places {
            return self.places.view();
        }
        if self.drive_link.open {
            return self.drive_link_panel();
        }
        if self.section == Section::Drive
            && let Some(trail) = &self.drive.chooser
        {
            return self.drive_chooser(trail);
        }
        if matches!(
            self.section,
            Section::Albums | Section::Drive | Section::Hidden
        ) && !self.album_open
        {
            return self.albums_view();
        }
        self.media_grid()
    }

    fn drive_link_panel(&self) -> Element<'_, Message> {
        let state = &self.drive_link;
        let mut panel = column![text("Public link").size(24).font(design::MEDIUM)]
            .spacing(12)
            .max_width(440);
        if let Some(error) = &state.error {
            panel = panel.push(text(error.as_str()));
        }
        if state.busy {
            panel = panel.push(text("Contacting Proton…").style(design::muted));
        }
        let mut actions = row![].spacing(10);
        if let Some(link) = &state.link {
            panel = panel.push(text(if link.has_custom_password {
                "People with the link and its password can view this folder."
            } else {
                "People with the link can view this folder."
            }));
            if let Some(expiry) = link
                .expiration_time
                .and_then(|epoch| chrono::DateTime::from_timestamp(epoch, 0))
            {
                panel = panel.push(text(format!(
                    "Expires {}",
                    expiry.format("%Y-%m-%d %H:%M UTC")
                )));
            }
            if state.confirming_revoke {
                panel = panel.push(text("Revoke access for everyone using this link?"));
                actions = actions.push(
                    button("Revoke")
                        .on_press_maybe((!state.busy).then_some(Message::RevokeDriveLink))
                        .style(design::secondary),
                );
            } else {
                if link.url.is_some() {
                    actions = actions.push(
                        button(if state.copied { "Copied" } else { "Copy link" })
                            .on_press(Message::CopyDriveLink)
                            .style(design::primary),
                    );
                }
                actions = actions.push(
                    button("Revoke link")
                        .on_press_maybe((!state.busy).then_some(Message::ConfirmRevokeDriveLink))
                        .style(design::secondary),
                );
            }
        } else if state.loaded && self.hidden.private_album {
            panel = panel.push(text("This folder is hidden in Gallery."));
        } else if state.loaded {
            panel = panel
                .push(text(
                    "Anyone with the link can view this Drive folder. You can revoke access here.",
                ))
                .push(
                    text_input("Password (optional)", &state.password)
                        .secure(true)
                        .on_input(Message::DriveLinkPassword)
                        .padding(10),
                )
                .push(
                    text_input("Expires in days (optional)", &state.expiry)
                        .on_input(Message::DriveLinkExpiry)
                        .padding(10),
                );
            if self.drive_link_expiry().is_none() {
                panel = panel.push(text("Enter a number from 1 to 3650."));
            }
            actions = actions.push(
                button("Create viewer link")
                    .style(design::primary)
                    .on_press_maybe(
                        (!state.busy && self.drive_link_expiry().is_some())
                            .then_some(Message::CreateDriveLink),
                    ),
            );
        } else if !state.busy {
            actions = actions.push(
                button("Retry")
                    .on_press(Message::OpenDriveLink)
                    .style(design::secondary),
            );
        }
        center(
            panel.push(
                actions.push(
                    button("Close")
                        .on_press(Message::CloseDriveLink)
                        .style(design::secondary),
                ),
            ),
        )
        .into()
    }

    fn media_grid(&self) -> Element<'_, Message> {
        if self.cover_height() == 0.0 && self.outline.as_ref().is_none_or(|o| o.is_empty()) {
            let (message, hint) = if self.sync.running {
                (
                    "Your photos are on their way…",
                    "You can browse as soon as the first photos arrive.",
                )
            } else if self.outline_work.is_some() {
                ("Loading photos…", "")
            } else if self.album_open {
                ("This album is empty", "")
            } else if self.section == Section::Favorites {
                (
                    "No favorites yet",
                    "Open a photo and choose Favorite to find it here.",
                )
            } else {
                (
                    "No photos yet",
                    "Refresh to look for photos in your Proton library.",
                )
            };
            let mut empty = column![
                design::icon(Icon::Photos, 40.0),
                text(message).font(design::MEDIUM).size(22),
            ]
            .spacing(16)
            .align_x(Alignment::Center);
            if !hint.is_empty() {
                empty = empty.push(text(hint).size(14).style(design::muted));
            }
            return center(empty).into();
        }
        let rows = self.layout.visible(self.media_top(), self.media_height());
        let mut grid = column![].width(Length::Fill);
        if self.cover_height() > 0.0 {
            grid = grid.push(self.album_rows());
        }
        let mut cursor = 0.0;
        for row in &self.layout.rows[rows] {
            if row.y > cursor {
                grid = grid.push(Space::new().height(row.y - cursor));
            }
            grid = grid.push(match &row.kind {
                RowKind::Heading(label) => {
                    container(text(label.as_str()).font(design::MEDIUM).size(17))
                        .height(row.height)
                        .align_y(Alignment::End)
                        .padding([12, 0])
                        .into()
                }
                RowKind::Tiles(range) => self.tile_row(range.clone(), row.height),
            });
            cursor = row.y + row.height;
        }
        if self.layout.height > cursor {
            grid = grid.push(Space::new().height(self.layout.height - cursor));
        }
        let scroll = scrollable(container(grid).padding([0, GUTTER as u16]))
            .id(grid_id())
            .direction(scrollable::Direction::Vertical(
                scrollable::Scrollbar::new()
                    .width(6)
                    .scroller_width(6)
                    .margin(4),
            ))
            .on_scroll(Message::Scrolled)
            .width(Length::Fill)
            .height(Length::Fill);
        Element::new(GridZoom(scroll.into()))
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
                    .border_radius(5)
                    .content_fit(ContentFit::Cover)
                    .width(Length::Fill)
                    .height(Length::Fill)
                    .into(),
                None if ids
                    .and_then(|ids| ids.get(index))
                    .is_some_and(|id| self.thumbs_failed.contains(id)) =>
                {
                    center(
                        column![
                            design::icon(Icon::Image, 24.0),
                            text(
                                if self
                                    .outline
                                    .as_ref()
                                    .and_then(|out| out.ids.get(index))
                                    .is_some_and(|id| self.thumbs_retryable.contains(id))
                                {
                                    "Preview failed"
                                } else {
                                    "No preview"
                                }
                            )
                            .size(12)
                            .style(design::muted)
                        ]
                        .spacing(8)
                        .align_x(Alignment::Center),
                    )
                    .into()
                }
                None => Space::new().width(Length::Fill).height(Length::Fill).into(),
            };
            let mut layers = iced::widget::stack![body]
                .width(Length::Fill)
                .height(Length::Fill);
            if video {
                layers = layers.push(
                    container(design::icon(Icon::Play, 18.0).style(|_, _| {
                        iced::widget::svg::Style {
                            color: Some(Color::WHITE),
                        }
                    }))
                    .padding(6)
                    .align_right(Length::Fill),
                );
            }
            let tile = container(layers)
                .padding(if focused { 3 } else { 0 })
                .width(width)
                .height(height)
                .style(move |theme: &Theme| {
                    let palette = theme.extended_palette();
                    container::Style {
                        background: Some(design::colors(theme).placeholder.into()),
                        border: iced::Border {
                            color: if focused {
                                palette.primary.strong.color
                            } else {
                                Color::TRANSPARENT
                            },
                            width: if focused { 3.0 } else { 0.0 },
                            radius: 5.0.into(),
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
            button(
                row![
                    design::icon(Icon::Back, 18.0).style(|_, _| iced::widget::svg::Style {
                        color: Some(Color::WHITE)
                    }),
                    text(self.section_title()).size(13)
                ]
                .spacing(6)
                .align_y(Alignment::Center)
            )
            .padding([10, 12])
            .on_press(Message::Close)
            .style(viewer_button),
            Space::new().width(Length::Fill),
            text(if viewer.loading {
                "Loading…".to_string()
            } else {
                format!(
                    "{} of {}",
                    formatted_count(viewer.index + 1),
                    formatted_count(count)
                )
            })
            .size(13),
            button(
                row![
                    design::icon(Icon::Back, 18.0).style(|_, _| iced::widget::svg::Style {
                        color: Some(Color::WHITE)
                    }),
                    text("Previous").size(13)
                ]
                .spacing(4)
            )
            .on_press_maybe((viewer.index > 0).then_some(Message::Step(-1)))
            .style(viewer_button),
            button(
                row![
                    text("Next").size(13),
                    design::icon(Icon::Next, 18.0).style(|_, _| iced::widget::svg::Style {
                        color: Some(Color::WHITE)
                    })
                ]
                .spacing(4)
            )
            .on_press_maybe((viewer.index + 1 < count).then_some(Message::Step(1)))
            .style(viewer_button),
        ]
        .spacing(8)
        .align_y(Alignment::Center)
        .padding([12, 16]);
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
        #[cfg(target_os = "linux")]
        let body: Element<'_, Message> = match &viewer.player {
            Some(player) if player.frames().has_frame() => {
                iced::widget::shader(crate::video_surface::VideoProgram::new(player.frames()))
                    .width(Length::Fill)
                    .height(Length::Fill)
                    .into()
            }
            // The thumbnail stands in until the first frame is decoded.
            Some(_) => match &viewer.image {
                Some(decoded) => image(decoded.handle.clone())
                    .content_fit(ContentFit::Contain)
                    .width(Length::Fill)
                    .height(Length::Fill)
                    .into(),
                None => center(text("Buffering video…")).into(),
            },
            None => body,
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
        let favorite = viewer.item.as_ref().is_some_and(|item| item.favorite);
        let available = !self.favorite_busy
            && !viewer.loading
            && viewer.item.as_ref().is_some_and(|item| item.in_proton);
        let metadata = row![
            container(text(name).size(14).font(design::MEDIUM)).width(Length::Fill),
            button(
                text(if self.favorite_busy {
                    "Saving…"
                } else if favorite {
                    "Unfavorite"
                } else {
                    "Favorite"
                })
                .size(13)
            )
            .on_press_maybe(available.then_some(Message::ToggleFavorite))
            .style(viewer_button),
        ]
        .align_y(Alignment::Center)
        .padding([0, 28])
        .spacing(12);
        let mut page = column![
            bar,
            metadata,
            container(body)
                .padding([16, 24])
                .width(Length::Fill)
                .height(Length::Fill),
            container(
                text(if self.window.width < 760.0 {
                    if viewer.item.as_ref().is_some_and(|item| item.is_video) {
                        ""
                    } else {
                        "Scroll to zoom   /   Drag to pan"
                    }
                } else {
                    if viewer.item.as_ref().is_some_and(|item| item.is_video) {
                        "← → Navigate   /   Esc Close"
                    } else {
                        "Scroll to zoom   /   Drag to pan   /   ← → Navigate   /   Esc Close"
                    }
                })
                .size(12)
                .color(Color::from_rgb8(166, 166, 180))
            )
            .padding([14, 24])
            .center_x(Length::Fill)
        ];
        #[cfg(target_os = "linux")]
        if viewer.player.is_some() {
            let state = &viewer.playback;
            let mut controls = row![
                button(if state.playing { "Pause" } else { "Play" })
                    .style(viewer_button)
                    .on_press(Message::VideoToggle),
                text(video_time(state.position)).size(13),
            ]
            .spacing(12)
            .align_y(Alignment::Center);
            if state.duration > 0.0 {
                controls = controls
                    .push(
                        slider(
                            0.0..=state.duration,
                            state.position.min(state.duration),
                            Message::VideoSeek,
                        )
                        .step(0.1)
                        .width(Length::Fill),
                    )
                    .push(text(video_time(state.duration)).size(13));
            }
            if state.buffering {
                controls = controls.push(text("Buffering…").size(13));
            }
            page = page.push(
                container(
                    controls.push(text("Volume").size(13)).push(
                        slider(0.0..=1.0, state.volume, Message::VideoVolume)
                            .step(0.05)
                            .width(72),
                    ),
                )
                .padding([0, 24])
                .width(Length::Fill),
            );
        }
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
                background: Some(design::VIEWER.into()),
                text_color: Some(Color::WHITE),
                ..container::Style::default()
            })
            .into()
    }
}

#[cfg(target_os = "linux")]
fn video_time(seconds: f64) -> String {
    let seconds = seconds as u64;
    format!("{}:{:02}", seconds / 60, seconds % 60)
}

fn count_label(count: usize, singular: &str, plural: &str) -> String {
    let label = if count == 1 { singular } else { plural };
    format!("{} {label}", formatted_count(count))
}

fn formatted_count(count: usize) -> String {
    let digits = count.to_string();
    digits
        .chars()
        .enumerate()
        .fold(String::new(), |mut out, (i, ch)| {
            if i > 0 && (digits.len() - i).is_multiple_of(3) {
                out.push(',');
            }
            out.push(ch);
            out
        })
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

fn action<'a>(icon: Icon, label: &'a str) -> iced::widget::Button<'a, Message> {
    button(
        row![design::icon(icon, 15.0), text(label).size(13)]
            .spacing(6)
            .align_y(Alignment::Center),
    )
    .height(32)
    .padding([7, 10])
}

fn cover_id(asset: &proton_gallery_rs::model::Asset) -> String {
    format!("cover:{:?}:{:?}", asset.key, asset.revision)
}

/// A Proton thumbnail, decoded at grid size. With `disk`, a copy kept by an
/// earlier run is used first and a fetched one is kept for the next.
async fn load_thumbnail(
    provider: Arc<dyn MediaProvider>,
    item: MediaItem,
    disk: Option<Arc<thumbs::DiskThumbs>>,
) -> Result<Option<Decoded>, String> {
    let stored = disk.and_then(|disk| {
        let source = item.key.stable_id().ok()?;
        Some((disk, source, item.revision.clone().unwrap_or_default()))
    });
    if let Some((disk, source, revision)) = &stored
        && let Some(bytes) = disk.get(source, revision).await
        && let Ok(decoded) = thumbs::decode_async(bytes, thumbs::THUMB_SIDE).await
    {
        return Ok(Some(decoded));
    }
    let loaded =
        fetch_grid_image(provider.as_ref(), &item.key, item.is_video, item.size_bytes).await?;
    Ok(loaded.map(|(decoded, bytes)| {
        if let Some((disk, source, revision)) = stored {
            disk.store(source, revision, bytes);
        }
        decoded
    }))
}

/// Decode a fetched rendition, keeping its bytes when they decode.
async fn decode_rendition(
    response: proton_gallery_app_rs::Result<Option<Vec<u8>>>,
    side: u32,
) -> Result<Option<(Decoded, Vec<u8>)>, String> {
    match response.map_err(shown)? {
        Some(bytes) => Ok(thumbs::decode_async(bytes.clone(), side)
            .await
            .ok()
            .map(|decoded| (decoded, bytes))),
        None => Ok(None),
    }
}

async fn load_grid_image(
    provider: &dyn MediaProvider,
    key: &proton_gallery_rs::model::AssetKey,
    video: bool,
    size: u64,
) -> Result<Option<Decoded>, String> {
    Ok(fetch_grid_image(provider, key, video, size)
        .await?
        .map(|(decoded, _)| decoded))
}

/// The grid picture of an item, decoded, with the bytes it came from.
async fn fetch_grid_image(
    provider: &dyn MediaProvider,
    key: &proton_gallery_rs::model::AssetKey,
    video: bool,
    size: u64,
) -> Result<Option<(Decoded, Vec<u8>)>, String> {
    let load = async {
        let thumb = decode_rendition(provider.thumbnail(key).await, thumbs::THUMB_SIDE).await;
        if matches!(thumb, Ok(Some(_))) {
            return thumb;
        }
        let preview = decode_rendition(provider.preview(key).await, thumbs::THUMB_SIDE).await;
        if matches!(preview, Ok(Some(_))) {
            return preview;
        }
        if !video && size <= ORIGINAL_LIMIT {
            static FALLBACKS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(2);
            let _permit = FALLBACKS.acquire().await.map_err(|e| e.to_string())?;
            match provider.original_bytes(key, ORIGINAL_LIMIT).await {
                Ok(bytes) => return decode_rendition(Ok(Some(bytes)), thumbs::THUMB_SIDE).await,
                Err(error) => return Err(shown(error)),
            }
        }
        thumb.and(preview)
    };
    tokio::time::timeout(Duration::from_secs(24), load)
        .await
        .map_err(|_| "Preview timed out.".to_owned())?
}

/// The viewer's photo: Proton's screen-size preview, or the decrypted
/// original when there is none. Videos show their thumbnail only.
#[cfg(test)]
async fn load_full(
    app: Arc<App>,
    provider: Arc<dyn MediaProvider>,
    outline: Arc<Outline>,
    index: usize,
    originals: PathBuf,
) -> Result<(MediaItem, Option<Decoded>, Option<String>), String> {
    load_full_with_privacy(app, provider, outline, index, originals, false).await
}

async fn load_full_with_privacy(
    app: Arc<App>,
    provider: Arc<dyn MediaProvider>,
    outline: Arc<Outline>,
    index: usize,
    originals: PathBuf,
    private: bool,
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
        let thumb = load_thumbnail(provider, item.clone(), None)
            .await
            .ok()
            .flatten();
        return Ok((
            item,
            thumb,
            Some("Video playback isn’t available on desktop yet.".into()),
        ));
    }
    let (preview_error, unsupported) = match provider.preview(&item.key).await {
        Ok(Some(bytes)) => match thumbs::decode_async(bytes, thumbs::VIEWER_SIDE).await {
            Ok(decoded) => return Ok((item, Some(decoded), None)),
            Err(error) => (None, Some(error)),
        },
        Ok(None) => (None, None),
        Err(error) => (Some(error), None),
    };
    // The public viewer can reuse its existing original cache. Private albums
    // always fetch in memory, just like the grid fallback.
    let bytes = if private {
        app.original_in_memory(&item.key, ORIGINAL_LIMIT)
            .await
            .map_err(shown)?
    } else {
        match provider
            .original(&item.key, &item.name, &originals, ORIGINAL_LIMIT)
            .await
        {
            Ok(path) => {
                use tokio::io::AsyncReadExt;
                let file = tokio::fs::File::open(&path)
                    .await
                    .map_err(|_| "Couldn’t read the original.")?;
                let mut bytes = Vec::new();
                file.take(ORIGINAL_LIMIT + 1)
                    .read_to_end(&mut bytes)
                    .await
                    .map_err(|_| "Couldn’t read the original.")?;
                if bytes.len() as u64 > ORIGINAL_LIMIT {
                    return Err("Original exceeds viewing budget.".into());
                }
                bytes
            }
            Err(_) if unsupported.is_some() => return Ok((item, None, unsupported)),
            Err(error) => return Err(preview_error.unwrap_or(error).to_string()),
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
        let source = proton_gallery_rs::synthetic::SyntheticSource::new(count).unwrap();
        app.index_source(
            &source,
            app.generation(),
            &proton_gallery_rs::CancellationToken::new(),
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
    async fn grid_thumbnails_are_kept_on_disk_for_the_next_run() {
        struct Counted(Vec<u8>, std::sync::atomic::AtomicUsize);
        #[async_trait::async_trait]
        impl MediaProvider for Counted {
            async fn thumbnail(
                &self,
                _: &proton_gallery_rs::model::AssetKey,
            ) -> proton_gallery_app_rs::Result<Option<Vec<u8>>> {
                self.1.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Ok(Some(self.0.clone()))
            }

            async fn preview(
                &self,
                _: &proton_gallery_rs::model::AssetKey,
            ) -> proton_gallery_app_rs::Result<Option<Vec<u8>>> {
                Ok(None)
            }

            async fn original_bytes(
                &self,
                _: &proton_gallery_rs::model::AssetKey,
                _: u64,
            ) -> proton_gallery_app_rs::Result<Vec<u8>> {
                panic!("a thumbnail was available")
            }

            async fn original(
                &self,
                _: &proton_gallery_rs::model::AssetKey,
                _: &str,
                _: &std::path::Path,
                _: u64,
            ) -> proton_gallery_app_rs::Result<PathBuf> {
                panic!("the grid never writes an original")
            }
        }

        let (dir, gallery) = gallery(10).await;
        let app = gallery.app().cloned().unwrap();
        let outline = gallery.outline.clone().unwrap();
        let item = app
            .outline_items(&outline, 0, 1)
            .await
            .unwrap()
            .pop()
            .unwrap()
            .unwrap();
        let mut png = Vec::new();
        ::image::RgbaImage::new(64, 32)
            .write_to(
                &mut std::io::Cursor::new(&mut png),
                ::image::ImageFormat::Png,
            )
            .unwrap();
        let media = Arc::new(Counted(png, std::sync::atomic::AtomicUsize::new(0)));
        let root = dir.path().join("thumbnails");

        let disk = Arc::new(thumbs::DiskThumbs::new(root.clone()));
        let first = load_thumbnail(media.clone(), item.clone(), Some(disk.clone()))
            .await
            .unwrap()
            .unwrap();
        assert_eq!((first.width, first.height), (64, 32));
        // The copy is written in the background.
        let source = item.key.stable_id().unwrap();
        let revision = item.revision.clone().unwrap_or_default();
        for _ in 0..200 {
            if disk.get(&source, &revision).await.is_some() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }

        // A new run finds it without asking Proton.
        let reopened = Arc::new(thumbs::DiskThumbs::new(root.clone()));
        let second = load_thumbnail(media.clone(), item.clone(), Some(reopened.clone()))
            .await
            .unwrap()
            .unwrap();
        assert_eq!((second.width, second.height), (64, 32));
        assert_eq!(media.1.load(std::sync::atomic::Ordering::SeqCst), 1);

        reopened.clear().await;
        assert!(!root.exists());
    }

    #[tokio::test]
    async fn invalid_renditions_fall_back_to_bounded_originals_for_stills() {
        struct FallbackMedia(Vec<u8>);
        #[async_trait::async_trait]
        impl MediaProvider for FallbackMedia {
            async fn thumbnail(
                &self,
                _: &proton_gallery_rs::model::AssetKey,
            ) -> proton_gallery_app_rs::Result<Option<Vec<u8>>> {
                Ok(Some(b"invalid thumbnail".to_vec()))
            }

            async fn preview(
                &self,
                _: &proton_gallery_rs::model::AssetKey,
            ) -> proton_gallery_app_rs::Result<Option<Vec<u8>>> {
                Ok(Some(b"invalid preview".to_vec()))
            }

            async fn original_bytes(
                &self,
                _: &proton_gallery_rs::model::AssetKey,
                limit: u64,
            ) -> proton_gallery_app_rs::Result<Vec<u8>> {
                assert_eq!(limit, ORIGINAL_LIMIT);
                Ok(self.0.clone())
            }

            async fn original(
                &self,
                _: &proton_gallery_rs::model::AssetKey,
                _: &str,
                _: &std::path::Path,
                _: u64,
            ) -> proton_gallery_app_rs::Result<PathBuf> {
                panic!("the grid never writes an original")
            }
        }

        let (_dir, gallery) = gallery(10).await;
        let key = &gallery.outline.as_ref().unwrap().keys[0];
        let mut png = Vec::new();
        ::image::RgbaImage::new(400, 200)
            .write_to(
                &mut std::io::Cursor::new(&mut png),
                ::image::ImageFormat::Png,
            )
            .unwrap();
        let media = FallbackMedia(png);
        let decoded = load_grid_image(&media, key, false, 8192)
            .await
            .unwrap()
            .unwrap();
        assert_eq!((decoded.width, decoded.height), (400, 200));
        assert!(
            load_grid_image(&media, key, true, 8192)
                .await
                .unwrap()
                .is_none()
        );
        assert!(
            load_grid_image(&media, key, false, ORIGINAL_LIMIT + 1)
                .await
                .unwrap()
                .is_none()
        );
    }

    #[tokio::test]
    async fn hidden_auth_waits_for_focus_and_cannot_reopen_after_lock() {
        let (_dir, mut gallery) = gallery(10).await;
        let generation = gallery.app().unwrap().generation();
        gallery.section = Section::Drive;
        gallery.hidden.origin = Some(Section::Drive);
        gallery.hidden.request = 1;
        gallery.window_focused = false;
        let _ = gallery.update(Message::HiddenUnlocked(1, generation, Ok(())));
        assert_eq!(gallery.section, Section::Drive);
        assert!(!gallery.hidden.unlocked);
        let _ = gallery.update(Message::WindowFocusChanged(true));
        assert_eq!(gallery.section, Section::Hidden);
        assert!(gallery.hidden.unlocked);
        let _ = gallery.update(Message::WindowFocusChanged(false));
        assert_eq!(gallery.section, Section::Drive);
        assert!(!gallery.hidden.unlocked);
        let _ = gallery.update(Message::HiddenUnlocked(1, generation, Ok(())));
        let _ = gallery.update(Message::WindowFocusChanged(true));
        assert_eq!(gallery.section, Section::Drive);
        assert!(!gallery.hidden.unlocked);
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
    async fn a_catalog_change_closes_the_deleted_photo_in_the_viewer() {
        let (_dir, mut gallery) = gallery(10).await;
        let _ = gallery.open(0);
        assert!(gallery.viewer.is_some());
        let old = gallery.outline.clone().unwrap();
        let next = Outline {
            keys: old.keys[1..].to_vec(),
            ids: old.ids[1..].to_vec(),
            times: old.times[1..].to_vec(),
            ratios: old.ratios[1..].to_vec(),
        };
        let _ = gallery.show_outline(Arc::new(next));
        assert!(gallery.viewer.is_none());
        assert!(gallery.viewer_work.is_none());
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
            load_thumbnail(provider.clone(), item, None)
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
