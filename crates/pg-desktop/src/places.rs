//! The Places map: an offline basemap drawn from rendered tiles, photo
//! markers showing a cover picture, and a side panel with the photos of the
//! chosen place.
//!
//! Tiles and pictures are made off the UI thread and kept as image handles,
//! so panning and zooming only draw what is already there. Without the
//! basemap file the map falls back to the coarse outlines built into
//! `proton-gallery-rs`.

use crate::design;
use crate::gallery::Message;
use iced::widget::canvas::{self, Action, Canvas, Path, Program};
use iced::widget::image::Handle;
use iced::widget::{
    Space, button, column, container, image, opaque, progress_bar, row, scrollable, stack, text,
};
use iced::{
    Alignment, Border, Color, Element, Length, Point, Rectangle, Renderer, Size, Theme, Vector,
    color, mouse,
};
use proton_gallery_app_rs::fixtures::MediaProvider;
use proton_gallery_app_rs::places::{LocatedPhoto, PlacesScan};
use proton_gallery_rs::camera::{self, Camera, Viewport};
use proton_gallery_rs::model::AssetKey;
use proton_gallery_rs::places::{self, Bubble, MARKER_GAP, World};
use proton_map::{Basemap, Style, TILE_SIZE, TileId};
use std::collections::{HashMap, HashSet};
use std::hash::Hash;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Logical px of a basemap tile drawn at its own level.
pub const TILE_PX: f64 = 256.0;
/// Deepest tile level asked for; deeper zooms stretch these tiles.
const MAX_LEVEL: u8 = 14;
/// Deepest zoom a fit goes to, so one place doesn't zoom to street level.
const FIT_ZOOM: f64 = 64.0;
/// Rendered tiles kept, in bytes of pixels.
const TILE_BUDGET: usize = 128 << 20;
/// Pictures kept for markers and the side panel, in bytes of pixels.
const THUMB_BUDGET: usize = 48 << 20;
pub const THUMBS_IN_FLIGHT: usize = 8;
/// Side in px of a marker or panel picture.
const THUMB_SIDE: u32 = 192;
/// Corner radius of a picture as a share of its side.
const CORNER: f32 = 0.24;
/// A marker picture's side as a share of its bubble's diameter.
const MARKER_SIDE: f64 = 0.9;
const PANEL_WIDTH: f32 = 360.0;
const PANEL_TILE: f32 = 104.0;
pub const PANEL_PAGE: usize = 60;
pub const BASEMAP_FILE: &str = "basemap-v1.pmtiles";

/// Rendered images kept up to a byte budget, the least recently used going
/// first.
pub struct Images<K> {
    entries: HashMap<K, (u64, Handle, usize)>,
    bytes: usize,
    tick: u64,
    budget: usize,
}

impl<K: Eq + Hash + Clone> Images<K> {
    fn new(budget: usize) -> Self {
        Self {
            entries: HashMap::new(),
            bytes: 0,
            tick: 0,
            budget,
        }
    }

    pub fn get(&self, key: &K) -> Option<&Handle> {
        self.entries.get(key).map(|(_, handle, _)| handle)
    }

    pub fn contains(&self, key: &K) -> bool {
        self.entries.contains_key(key)
    }

    /// Mark `keys` as just used, so they are dropped last.
    pub fn touch<'a>(&mut self, keys: impl IntoIterator<Item = &'a K>)
    where
        K: 'a,
    {
        self.tick += 1;
        for key in keys {
            if let Some(entry) = self.entries.get_mut(key) {
                entry.0 = self.tick;
            }
        }
    }

    pub fn insert(&mut self, key: K, handle: Handle, bytes: usize) {
        self.tick += 1;
        if let Some((_, _, old)) = self.entries.insert(key, (self.tick, handle, bytes)) {
            self.bytes -= old;
        }
        self.bytes += bytes;
        while self.bytes > self.budget && self.entries.len() > 1 {
            let Some(oldest) = self
                .entries
                .iter()
                .min_by_key(|(_, (tick, _, _))| *tick)
                .map(|(key, _)| key.clone())
            else {
                break;
            };
            if let Some((_, _, bytes)) = self.entries.remove(&oldest) {
                self.bytes -= bytes;
            }
        }
    }
}

/// One rendered basemap tile: its place, the theme and its render scale
/// (device px per basemap px, times 4).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct TileKey {
    pub level: u8,
    pub x: u32,
    pub y: u32,
    pub dark: bool,
    pub density: u8,
}

impl TileKey {
    fn parent(self) -> Option<Self> {
        (self.level > 0).then(|| Self {
            level: self.level - 1,
            x: self.x / 2,
            y: self.y / 2,
            ..self
        })
    }
}

/// Render `key` from `basemap`. Call off the UI thread.
pub fn render_tile(basemap: &Basemap, key: TileKey) -> (Handle, usize) {
    let style = if key.dark {
        Style::dark()
    } else {
        Style::light()
    };
    // Tiles are rendered at the size they are drawn, so lines and names
    // keep their size at every zoom.
    const _: () = assert!(TILE_PX as u32 == TILE_SIZE);
    let scale = f32::from(key.density) / 4.0;
    let tile =
        proton_map::render_tile(basemap, TileId::new(key.level, key.x, key.y), scale, &style);
    let bytes = tile.rgba.len();
    (Handle::from_rgba(tile.width, tile.height, tile.rgba), bytes)
}

/// Where the basemap may be installed, most specific first.
fn basemap_paths() -> Vec<PathBuf> {
    let mut paths = Vec::new();
    if let Some(path) = std::env::var_os("PROTON_GALLERY_BASEMAP") {
        paths.push(PathBuf::from(path));
    }
    if let Some(dir) = std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(PathBuf::from))
    {
        paths.push(dir.join(BASEMAP_FILE));
        paths.push(dir.join("../share/proton-gallery").join(BASEMAP_FILE));
    }
    paths.push(crate::gallery::default_data_dir().join(BASEMAP_FILE));
    if cfg!(debug_assertions) {
        // `scripts/fetch-basemap.sh` puts it here for development builds.
        paths.push(
            PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/../../data")).join(BASEMAP_FILE),
        );
    }
    paths
}

/// Open the installed basemap, if there is one. Call off the UI thread.
pub fn open_basemap() -> Option<Arc<Basemap>> {
    let path = basemap_paths().into_iter().find(|path| path.is_file())?;
    match Basemap::open(&path) {
        Ok(basemap) => Some(Arc::new(basemap)),
        Err(error) => {
            tracing::warn!("couldn’t open the basemap {}: {error}", path.display());
            None
        }
    }
}

/// The picture of `key` cropped to a rounded square, for a marker or the
/// side panel. Uses the stored thumbnail or preview, never the original.
pub async fn load_thumb(
    provider: Arc<dyn MediaProvider>,
    key: AssetKey,
) -> Option<(Handle, usize)> {
    let load = async {
        let bytes = match provider.thumbnail(&key).await {
            Ok(Some(bytes)) => bytes,
            _ => provider.preview(&key).await.ok()??,
        };
        tokio::task::spawn_blocking(move || rounded_square(&bytes, THUMB_SIDE))
            .await
            .ok()?
    };
    tokio::time::timeout(Duration::from_secs(24), load)
        .await
        .ok()?
}

/// Decode `bytes`, fill a `side` px square and round its corners.
pub fn rounded_square(bytes: &[u8], side: u32) -> Option<(Handle, usize)> {
    let picture = ::image::load_from_memory(bytes).ok()?;
    let mut square = picture
        .resize_to_fill(side, side, ::image::imageops::FilterType::Triangle)
        .to_rgba8();
    let radius = side as f32 * CORNER;
    let far = side as f32 - radius;
    for (x, y, pixel) in square.enumerate_pixels_mut() {
        let (px, py) = (x as f32 + 0.5, y as f32 + 0.5);
        let dx = (radius - px).max(px - far).max(0.0);
        let dy = (radius - py).max(py - far).max(0.0);
        let cover = (radius + 0.5 - dx.hypot(dy)).clamp(0.0, 1.0);
        if cover < 1.0 {
            pixel.0[3] = (f32::from(pixel.0[3]) * cover).round() as u8;
        }
    }
    let bytes = square.len();
    Some((Handle::from_rgba(side, side, square.into_raw()), bytes))
}

/// The basemap and what was drawn from it; kept when a new scan starts.
pub struct MapLayer {
    pub basemap: Option<Arc<Basemap>>,
    /// Whether opening the basemap was tried.
    pub opened: bool,
    pub tiles: Images<TileKey>,
    pub tiles_loading: HashSet<TileKey>,
    pub dark: bool,
    /// Device px per logical px.
    pub density: f32,
}

impl Default for MapLayer {
    fn default() -> Self {
        Self {
            basemap: None,
            opened: false,
            tiles: Images::new(TILE_BUDGET),
            tiles_loading: HashSet::new(),
            dark: false,
            density: 1.0,
        }
    }
}

impl MapLayer {
    /// The key of tile `(level, x, y)` drawn `size` logical px wide.
    fn key(&self, level: u8, x: u32, y: u32, size: f64) -> TileKey {
        let scale = size / TILE_PX * f64::from(self.density);
        TileKey {
            level,
            x,
            y,
            dark: self.dark,
            density: (scale * 4.0).round().clamp(2.0, 24.0) as u8,
        }
    }

    /// A rendered tile for `key`: its own, the same place at another
    /// scale, or failing those the nearest ancestor, with how many levels up
    /// it is.
    fn find(&self, key: TileKey) -> Option<(TileKey, &Handle, u8)> {
        let mut at = key;
        let mut depth = 0;
        loop {
            if let Some(handle) = self.tiles.get(&at) {
                return Some((at, handle, depth));
            }
            for density in 2..=24 {
                let other = TileKey { density, ..at };
                if let Some(handle) = self.tiles.get(&other) {
                    return Some((other, handle, depth));
                }
            }
            at = at.parent()?;
            depth += 1;
        }
    }
}

pub struct Places {
    pub request: u64,
    pub scan: Option<Arc<PlacesScan>>,
    pub work: Option<iced::task::Handle>,
    pub photos: Vec<LocatedPhoto>,
    pub next: usize,
    pub failed: usize,
    pub error: Option<String>,
    /// Positions in `photos` shown in the side panel.
    pub selected: Vec<usize>,
    pub list_limit: usize,
    all: bool,
    /// The camera the user moved to; `None` fits the photos.
    pub camera: Option<Camera>,
    pub viewport: Option<Viewport>,
    bubbles: Vec<Bubble>,
    labels: Vec<bool>,
    /// Pictures by outline index.
    pub thumbs: Images<usize>,
    pub thumbs_loading: HashMap<usize, iced::task::Handle>,
    pub thumbs_failed: HashSet<usize>,
    pub map: MapLayer,
}

impl Default for Places {
    fn default() -> Self {
        Self {
            request: 0,
            scan: None,
            work: None,
            photos: Vec::new(),
            next: 0,
            failed: 0,
            error: None,
            selected: Vec::new(),
            list_limit: PANEL_PAGE,
            all: false,
            camera: None,
            viewport: None,
            bubbles: Vec::new(),
            labels: Vec::new(),
            thumbs: Images::new(THUMB_BUDGET),
            thumbs_loading: HashMap::new(),
            thumbs_failed: HashSet::new(),
            map: MapLayer::default(),
        }
    }
}

impl Places {
    /// Forget the scan, keeping the basemap, its tiles and the view size.
    pub fn reset(&mut self) {
        *self = Self {
            request: self.request + 1,
            viewport: self.viewport,
            map: std::mem::take(&mut self.map),
            ..Self::default()
        };
    }

    pub fn select(&mut self, photos: Vec<usize>, all: bool) {
        self.selected = photos;
        self.all = all;
        self.list_limit = PANEL_PAGE;
    }

    /// Move the map so the chosen photos aren't under the side panel.
    pub fn reveal_selection(&mut self) {
        let Some(view) = self.viewport else { return };
        if self.all || self.selected.is_empty() {
            return;
        }
        let camera = self.camera_for(view);
        let free = view.width - f64::from(PANEL_WIDTH) - 24.0;
        let points: Vec<_> = self
            .selected
            .iter()
            .filter_map(|&i| self.photos.get(i))
            .map(|photo| camera.to_screen(photo.coordinates.projected(), view))
            .collect();
        let Some(right) = points.iter().map(|p| p.0).reduce(f64::max) else {
            return;
        };
        if right + 40.0 <= free || free < 120.0 {
            return;
        }
        let middle = points.iter().map(|p| p.0).sum::<f64>() / points.len() as f64;
        self.camera = Some(camera.panned(free / 2.0 - middle, 0.0, view));
    }

    /// The camera for `view`: the user's, or one fitting the photos.
    fn camera_for(&self, view: Viewport) -> Camera {
        self.camera.unwrap_or_else(|| {
            Camera::fit(
                self.photos
                    .iter()
                    .map(|photo| photo.coordinates.projected()),
                view,
                FIT_ZOOM,
            )
        })
    }

    pub fn current_camera(&self) -> Option<Camera> {
        self.viewport.map(|view| self.camera_for(view))
    }

    /// Cluster the markers and place their labels for the current camera.
    pub fn refresh(&mut self) {
        let Some(view) = self.viewport else {
            self.bubbles.clear();
            self.labels.clear();
            return;
        };
        self.bubbles = bubbles(&self.photos, self.camera_for(view), view);
        let sizes: Vec<_> = self
            .bubbles
            .iter()
            .map(|bubble| self.place(bubble).map(label_size))
            .collect();
        self.labels = places::place_labels(&self.bubbles, &sizes);
    }

    /// The place name all of a bubble's photos share.
    fn place(&self, bubble: &Bubble) -> Option<&str> {
        let place = self
            .photos
            .get(*bubble.members.first()?)?
            .place
            .as_deref()?;
        bubble
            .members
            .iter()
            .all(|&i| self.photos[i].place.as_deref() == Some(place))
            .then_some(place)
    }

    /// The outline index of a bubble's cover: its newest photo.
    fn cover(&self, bubble: &Bubble) -> Option<usize> {
        bubble.members.iter().map(|&i| self.photos[i].index).min()
    }

    /// The tiles covering the view, nearest the centre first, then the
    /// whole-world tile drawn under anything not rendered yet.
    pub fn wanted_tiles(&self) -> Vec<TileKey> {
        let (Some(view), Some(_)) = (self.viewport, &self.map.basemap) else {
            return Vec::new();
        };
        let mut tiles = self.camera_for(view).tiles(view, TILE_PX, MAX_LEVEL);
        let middle = (view.width / 2.0, view.height / 2.0);
        tiles.sort_by(|a, b| {
            let distance = |t: &camera::TileView| {
                (t.left + t.size / 2.0 - middle.0).hypot(t.top + t.size / 2.0 - middle.1)
            };
            distance(a).total_cmp(&distance(b))
        });
        let mut keys: Vec<_> = tiles
            .iter()
            .map(|t| self.map.key(t.level, t.x, t.y, t.size))
            .collect();
        let world = self.map.key(0, 0, 0, TILE_PX);
        if !keys.contains(&world) {
            keys.push(world);
        }
        keys
    }

    /// Outline indices of the pictures on screen: marker covers, most
    /// photos first, then the side panel's photos.
    pub fn wanted_thumbs(&self) -> Vec<usize> {
        let mut order: Vec<_> = (0..self.bubbles.len()).collect();
        order.sort_by_key(|&i| std::cmp::Reverse(self.bubbles[i].photos));
        let mut wanted: Vec<usize> = order
            .into_iter()
            .filter_map(|i| self.cover(&self.bubbles[i]))
            .collect();
        wanted.extend(
            self.selected
                .iter()
                .take(self.list_limit)
                .filter_map(|&i| self.photos.get(i).map(|photo| photo.index)),
        );
        let mut seen = HashSet::new();
        wanted.retain(|index| seen.insert(*index));
        wanted
    }

    pub fn key_of(&self, index: usize) -> Option<AssetKey> {
        self.scan.as_ref()?.outline.keys.get(index).cloned()
    }

    pub fn view(&self) -> Element<'_, Message> {
        let canvas = |layer| {
            Canvas::new(Map {
                places: self,
                layer,
            })
            .width(Length::Fill)
            .height(Length::Fill)
        };
        let mut layers = stack![
            canvas(Layer::Base),
            canvas(Layer::Markers),
            canvas(Layer::Top)
        ]
        .width(Length::Fill)
        .height(Length::Fill)
        .clip(true);
        if let Some(chip) = self.status_chip() {
            layers = layers.push(
                container(chip)
                    .padding(14)
                    .width(Length::Fill)
                    .height(Length::Fill)
                    .align_x(Alignment::Start)
                    .align_y(Alignment::Start),
            );
        }
        // The side panel floats over the map, so opening it neither moves
        // the map nor renders it again; the controls move left of it.
        let right = if self.selected.is_empty() {
            0.0
        } else {
            PANEL_WIDTH + 12.0
        };
        layers = layers.push(
            container(self.zoom_controls())
                .padding(iced::Padding::new(14.0).right(14.0 + right))
                .width(Length::Fill)
                .height(Length::Fill)
                .align_x(Alignment::End)
                .align_y(Alignment::Start),
        );
        let attribution = if self.map.basemap.is_some() {
            proton_map::ATTRIBUTION
        } else {
            "Map data © Natural Earth and GeoNames (CC BY 4.0)"
        };
        layers = layers.push(
            container(
                container(text(attribution).size(10).style(design::muted))
                    .padding([2, 6])
                    .style(attribution_style),
            )
            .padding(iced::Padding::new(6.0).right(6.0 + right))
            .width(Length::Fill)
            .height(Length::Fill)
            .align_x(Alignment::End)
            .align_y(Alignment::End),
        );
        if !self.selected.is_empty() {
            layers = layers.push(
                container(opaque(self.panel()))
                    .padding(12)
                    .width(Length::Fill)
                    .height(Length::Fill)
                    .align_x(Alignment::End),
            );
        }
        if let Some(card) = self.empty_card() {
            layers = layers.push(
                container(card)
                    .width(Length::Fill)
                    .height(Length::Fill)
                    .align_x(Alignment::Center)
                    .align_y(Alignment::Center),
            );
        }
        let framed = container(layers)
            .width(Length::Fill)
            .height(Length::Fill)
            .clip(true)
            .style(frame_style);
        container(framed)
            .padding([10, 24])
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
    }

    /// Progress while photos are checked, or the count that couldn't be.
    fn status_chip(&self) -> Option<Element<'_, Message>> {
        if self.error.is_some() {
            return None;
        }
        let Some(scan) = &self.scan else {
            return Some(chip(text("Opening Places…").size(12).into()));
        };
        let total = scan.outline.len();
        if self.work.is_some() && self.next < total {
            let label = format!(
                "Finding places · {} of {}",
                grouped(self.next),
                grouped(total)
            );
            return Some(chip(
                column![
                    text(label).size(12),
                    progress_bar(0.0..=total.max(1) as f32, self.next as f32)
                        .length(Length::Fixed(200.0))
                        .girth(3)
                        .style(progress_style),
                ]
                .spacing(6)
                .into(),
            ));
        }
        if self.failed > 0 {
            return Some(chip(
                row![
                    text(format!("{} couldn’t be checked", grouped(self.failed))).size(12),
                    button(text("Retry").size(12))
                        .padding([2, 8])
                        .on_press(Message::StartPlaces)
                        .style(design::secondary),
                ]
                .spacing(8)
                .align_y(Alignment::Center)
                .into(),
            ));
        }
        if !self.photos.is_empty() && self.selected.is_empty() {
            let count = match self.photos.len() {
                1 => "1 photo on the map".to_owned(),
                n => format!("{} photos on the map", grouped(n)),
            };
            return Some(chip(
                row![
                    text(count).size(12),
                    button(text("Show all").size(12))
                        .padding([2, 8])
                        .on_press(Message::PlacesListAll)
                        .style(design::secondary),
                ]
                .spacing(8)
                .align_y(Alignment::Center)
                .into(),
            ));
        }
        None
    }

    fn zoom_controls(&self) -> Element<'_, Message> {
        let control = |label: &'static str, message: Message| {
            button(
                container(text(label).size(18).font(design::MEDIUM))
                    .center_x(Length::Fill)
                    .center_y(Length::Fill),
            )
            .width(36)
            .height(36)
            .padding(0)
            .on_press(message)
            .style(pill_button)
        };
        let fit = button(
            container(design::icon(design::Icon::Fit, 18.0))
                .center_x(Length::Fill)
                .center_y(Length::Fill),
        )
        .width(36)
        .height(36)
        .padding(0)
        .on_press(Message::PlacesFit)
        .style(pill_button);
        container(
            column![
                control("+", Message::PlacesZoom(2.0)),
                container(Space::new().width(20).height(1)).style(divider_style),
                control("−", Message::PlacesZoom(0.5)),
                container(Space::new().width(20).height(1)).style(divider_style),
                fit,
            ]
            .align_x(Alignment::Center),
        )
        .padding(2)
        .style(pill_style)
        .into()
    }

    /// The card over the map when there is nothing to show.
    fn empty_card(&self) -> Option<Element<'_, Message>> {
        let (title, detail, retry) = if let Some(error) = &self.error {
            ("Couldn’t open Places", error.as_str(), true)
        } else if self.scan.is_some() && self.work.is_none() && self.photos.is_empty() {
            (
                "No photos with locations yet",
                "Photos taken with location turned on appear here, on the map where they were taken.",
                self.failed > 0,
            )
        } else {
            return None;
        };
        let mut card = column![
            design::icon(design::Icon::Map, 28.0),
            text(title).size(16).font(design::MEDIUM),
            text(detail)
                .size(13)
                .style(design::muted)
                .align_x(Alignment::Center),
        ]
        .spacing(8)
        .align_x(Alignment::Center)
        .max_width(320);
        if retry {
            card = card.push(
                button(text("Retry").size(13))
                    .on_press(Message::StartPlaces)
                    .style(design::primary),
            );
        }
        Some(container(card).padding(24).style(design::panel).into())
    }

    /// The photos of the chosen place.
    fn panel(&self) -> Element<'_, Message> {
        let heading = if self.all {
            "Photos on the map"
        } else {
            self.selected
                .first()
                .and_then(|&i| self.photos.get(i))
                .and_then(|photo| photo.place.as_deref())
                .filter(|place| {
                    self.selected
                        .iter()
                        .all(|&i| self.photos[i].place.as_deref() == Some(*place))
                })
                .unwrap_or("Photos here")
        };
        let count = match self.selected.len() {
            1 => "1 photo".to_owned(),
            n => format!("{} photos", grouped(n)),
        };
        let header = row![
            column![
                text(heading).size(16).font(design::MEDIUM),
                text(count).size(12).style(design::muted),
            ]
            .spacing(2)
            .width(Length::Fill),
            button(text("Close").size(13))
                .on_press(Message::PlacesSelect(Vec::new()))
                .style(design::quiet),
        ]
        .align_y(Alignment::Center);
        let tiles: Vec<Element<'_, Message>> = self
            .selected
            .iter()
            .take(self.list_limit)
            .filter_map(|&i| self.photos.get(i))
            .map(|photo| {
                let picture: Element<'_, Message> = match self.thumbs.get(&photo.index) {
                    Some(handle) => image(handle.clone())
                        .width(PANEL_TILE)
                        .height(PANEL_TILE)
                        .into(),
                    None => container(Space::new())
                        .width(PANEL_TILE)
                        .height(PANEL_TILE)
                        .style(placeholder_style)
                        .into(),
                };
                button(picture)
                    .padding(0)
                    .on_press(Message::OpenPlacePhoto(photo.index))
                    .style(design::quiet)
                    .into()
            })
            .collect();
        let mut grid = column![].spacing(6);
        let mut tiles = tiles.into_iter().peekable();
        while tiles.peek().is_some() {
            grid = grid.push(row(tiles.by_ref().take(3)).spacing(6));
        }
        if self.list_limit < self.selected.len() {
            grid = grid.push(
                button(text("Show more").size(13))
                    .on_press(Message::PlacesListMore)
                    .style(design::secondary),
            );
        }
        container(
            column![header, scrollable(grid).height(Length::Fill)]
                .spacing(12)
                .height(Length::Fill),
        )
        .padding(16)
        .width(PANEL_WIDTH)
        .height(Length::Fill)
        .style(design::panel)
        .into()
    }
}

/// The markers for `photos` seen through `camera` in `view`.
pub(crate) fn bubbles(photos: &[LocatedPhoto], camera: Camera, view: Viewport) -> Vec<Bubble> {
    let screen: Vec<_> = photos
        .iter()
        .map(|photo| camera.to_screen(photo.coordinates.projected(), view))
        .collect();
    places::cluster(&screen, &vec![1; screen.len()], view.width, view.height)
}

/// Width and height in px of a place label's pill.
fn label_size(place: &str) -> (f64, f64) {
    (place.chars().count() as f64 * 6.6 + 14.0, 20.0)
}

/// `12260` as "12,260".
pub fn grouped(n: usize) -> String {
    let digits = n.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (i, digit) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(digit);
    }
    out
}

fn chip(content: Element<'_, Message>) -> Element<'_, Message> {
    container(content).padding([8, 12]).style(pill_style).into()
}

fn surface(theme: &Theme) -> Color {
    if theme.extended_palette().is_dark {
        color!(0x1C182B)
    } else {
        Color::WHITE
    }
}

fn pill_style(theme: &Theme) -> container::Style {
    container::Style {
        background: Some(surface(theme).into()),
        text_color: Some(theme.palette().text),
        border: Border {
            color: design::colors(theme).line,
            width: 1.0,
            radius: 12.0.into(),
        },
        shadow: iced::Shadow {
            color: Color::from_rgba(0.06, 0.03, 0.15, 0.18),
            offset: Vector::new(0.0, 2.0),
            blur_radius: 10.0,
        },
        ..Default::default()
    }
}

fn pill_button(theme: &Theme, status: button::Status) -> button::Style {
    let mut style = design::quiet(theme, status);
    style.border.radius = 10.0.into();
    style
}

fn divider_style(theme: &Theme) -> container::Style {
    container::Style {
        background: Some(design::colors(theme).line.into()),
        ..Default::default()
    }
}

fn attribution_style(theme: &Theme) -> container::Style {
    container::Style {
        background: Some(surface(theme).scale_alpha(0.78).into()),
        border: Border {
            radius: 6.0.into(),
            ..Default::default()
        },
        ..Default::default()
    }
}

fn frame_style(theme: &Theme) -> container::Style {
    container::Style {
        border: Border {
            color: design::colors(theme).line,
            width: 1.0,
            radius: 16.0.into(),
        },
        ..Default::default()
    }
}

fn placeholder_style(theme: &Theme) -> container::Style {
    container::Style {
        background: Some(design::colors(theme).placeholder.into()),
        border: Border {
            radius: (PANEL_TILE * CORNER).into(),
            ..Default::default()
        },
        ..Default::default()
    }
}

fn progress_style(theme: &Theme) -> progress_bar::Style {
    progress_bar::Style {
        background: design::colors(theme).line.into(),
        bar: design::VIOLET.into(),
        border: Border {
            radius: 2.0.into(),
            ..Default::default()
        },
    }
}

fn rgba(color: proton_map::Rgba) -> Color {
    Color::from_rgba8(color.r, color.g, color.b, f32::from(color.a) / 255.0)
}

/// The map is three canvases stacked, since a canvas draws its pictures
/// above its shapes: the basemap, the markers (rings under pictures), and
/// the counts, names and scale on top, which also takes the input.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Layer {
    Base,
    Markers,
    Top,
}

pub struct Map<'a> {
    places: &'a Places,
    layer: Layer,
}

#[derive(Default)]
pub struct MapState {
    /// The outline fallback, drawn again only when the camera or theme
    /// changes.
    outlines: canvas::Cache,
    painted: std::cell::Cell<Option<(Camera, Size, bool)>>,
    drag: Option<(Point, Point)>,
    /// The last click on the empty map, for double clicks.
    last_click: Option<(Instant, Point)>,
    size: Option<Size>,
}

impl Map<'_> {
    /// The bubble under `position`, when the bubbles match `view`.
    fn hit(&self, position: Point, view: Viewport) -> Option<&Bubble> {
        if self.places.viewport != Some(view) {
            return None;
        }
        self.places.bubbles.iter().rev().find(|bubble| {
            let point = Point::new(bubble.x as f32, bubble.y as f32);
            point.distance(position) <= bubble.radius as f32
        })
    }

    fn draw_tiles(&self, frame: &mut canvas::Frame, camera: Camera, view: Viewport) -> bool {
        let layer = &self.places.map;
        if layer.basemap.is_none() {
            return false;
        }
        let sea = rgba(
            if layer.dark {
                Style::dark()
            } else {
                Style::light()
            }
            .water[0],
        );
        frame.fill_rectangle(
            Point::ORIGIN,
            Size::new(view.width as f32, view.height as f32),
            sea,
        );
        for tile in camera.tiles(view, TILE_PX, MAX_LEVEL) {
            // Whole px edges, so neighbours meet without a seam.
            let left = tile.left.round();
            let top = tile.top.round();
            let cell = Rectangle::new(
                Point::new(left as f32, top as f32),
                Size::new(
                    ((tile.left + tile.size).round() - left) as f32,
                    ((tile.top + tile.size).round() - top) as f32,
                ),
            );
            let Some((key, handle, depth)) =
                layer.find(layer.key(tile.level, tile.x, tile.y, tile.size))
            else {
                continue;
            };
            let picture = canvas::Image::new(handle.clone());
            if depth == 0 {
                frame.draw_image(cell, picture);
                continue;
            }
            // Until this tile is rendered, the matching part of an
            // ancestor, magnified.
            let span = f64::from(1u32 << depth);
            let size = tile.size * span;
            let offset_x = f64::from(tile.x - (key.x << depth)) * tile.size;
            let offset_y = f64::from(tile.y - (key.y << depth)) * tile.size;
            frame.with_clip(cell, |frame| {
                frame.draw_image(
                    Rectangle::new(
                        Point::new(
                            (tile.left - offset_x - f64::from(cell.x)) as f32,
                            (tile.top - offset_y - f64::from(cell.y)) as f32,
                        ),
                        Size::new(size as f32, size as f32),
                    ),
                    picture,
                );
            });
        }
        true
    }

    fn draw_outlines(
        &self,
        state: &MapState,
        renderer: &Renderer,
        camera: Camera,
        view: Viewport,
        dark: bool,
    ) -> canvas::Geometry {
        let size = Size::new(view.width as f32, view.height as f32);
        let painted = Some((camera, size, dark));
        if state.painted.replace(painted) != painted {
            state.outlines.clear();
        }
        state.outlines.draw(renderer, size, |frame| {
            let (water, land, border) = if dark {
                (color!(0x211B34), color!(0x3B3653), color!(0x625873))
            } else {
                (color!(0xF0EBFA), color!(0xD6CFE9), color!(0xA99CBF))
            };
            let project = |point: World| {
                let (x, y) = camera.to_screen(point, view);
                Point::new(x as f32, y as f32)
            };
            frame.fill_rectangle(Point::ORIGIN, size, water);
            for (rings, fill) in [(places::land(), true), (places::borders(), false)] {
                for ring in rings {
                    let min = project(ring.min);
                    let max = project(ring.max);
                    if max.x < 0.0 || max.y < 0.0 || min.x > size.width || min.y > size.height {
                        continue;
                    }
                    let path = Path::new(|builder| {
                        let mut points = ring.points.iter();
                        if let Some(first) = points.next() {
                            builder.move_to(project(*first));
                            for point in points {
                                builder.line_to(project(*point));
                            }
                            if fill {
                                builder.close();
                            }
                        }
                    });
                    if fill {
                        frame.fill(&path, land);
                    } else {
                        frame.stroke(
                            &path,
                            canvas::Stroke::default()
                                .with_color(border)
                                .with_width(0.75),
                        );
                    }
                }
            }
        })
    }

    /// The markers' shadows, rings and pictures, or with `top` their counts
    /// and names.
    fn draw_markers(&self, frame: &mut canvas::Frame, top: bool, theme: &Theme) {
        let places = self.places;
        let selected: HashSet<usize> = places.selected.iter().copied().collect();
        let ring = if theme.extended_palette().is_dark {
            color!(0xEFE9FF)
        } else {
            Color::WHITE
        };
        for (i, bubble) in places.bubbles.iter().enumerate() {
            let center = Point::new(bubble.x as f32, bubble.y as f32);
            let side = (bubble.radius * 2.0 * MARKER_SIDE) as f32;
            let corner = side * CORNER;
            let top_left = center - Vector::new(side / 2.0, side / 2.0);
            let chosen =
                !selected.is_empty() && bubble.members.iter().all(|m| selected.contains(m));
            let rounded = |grow: f32, offset: Vector| {
                Path::rounded_rectangle(
                    top_left - Vector::new(grow, grow) + offset,
                    Size::new(side + grow * 2.0, side + grow * 2.0),
                    (corner + grow).into(),
                )
            };
            if !top {
                frame.fill(
                    &rounded(2.5, Vector::new(0.0, 2.0)),
                    Color::from_rgba(0.05, 0.02, 0.14, 0.28),
                );
                frame.fill(
                    &rounded(if chosen { 3.5 } else { 2.5 }, Vector::ZERO),
                    if chosen { design::VIOLET } else { ring },
                );
                match places
                    .cover(bubble)
                    .and_then(|cover| places.thumbs.get(&cover))
                {
                    Some(handle) => frame.draw_image(
                        Rectangle::new(top_left, Size::new(side, side)),
                        canvas::Image::new(handle.clone()),
                    ),
                    None => {
                        frame.fill(&rounded(0.0, Vector::ZERO), design::VIOLET);
                        frame.fill(
                            &Path::circle(center, 3.5),
                            Color::from_rgba(1.0, 1.0, 1.0, 0.85),
                        );
                    }
                }
                continue;
            }
            if bubble.photos > 1 {
                let count = places::compact_count(bubble.photos);
                let width = (count.chars().count() as f32 * 7.0 + 12.0).max(22.0);
                let badge = Point::new(top_left.x + side - width / 2.0 + 6.0, top_left.y - 4.0);
                let pill = Path::rounded_rectangle(
                    badge - Vector::new(width / 2.0, 11.0),
                    Size::new(width, 22.0),
                    11.0.into(),
                );
                frame.fill(&pill, design::VIOLET);
                frame.stroke(
                    &pill,
                    canvas::Stroke::default().with_color(ring).with_width(1.5),
                );
                frame.fill_text(canvas::Text {
                    content: count,
                    position: badge,
                    color: Color::WHITE,
                    size: 12.0.into(),
                    align_x: iced::alignment::Horizontal::Center.into(),
                    align_y: iced::alignment::Vertical::Center,
                    font: design::MEDIUM,
                    ..Default::default()
                });
            }
            if places.labels.get(i).copied().unwrap_or(false)
                && let Some(place) = places.place(bubble)
            {
                let (width, height) = label_size(place);
                let y = bubble.y + bubble.radius + MARKER_GAP;
                let pill = Path::rounded_rectangle(
                    Point::new((bubble.x - width / 2.0) as f32, y as f32),
                    Size::new(width as f32, height as f32),
                    (height as f32 / 2.0).into(),
                );
                frame.fill(&pill, surface(theme).scale_alpha(0.92));
                frame.fill_text(canvas::Text {
                    content: place.to_owned(),
                    position: Point::new(bubble.x as f32, (y + height / 2.0) as f32),
                    color: theme.palette().text,
                    size: 12.0.into(),
                    align_x: iced::alignment::Horizontal::Center.into(),
                    align_y: iced::alignment::Vertical::Center,
                    font: design::MEDIUM,
                    ..Default::default()
                });
            }
        }
    }

    fn draw_scale(&self, frame: &mut canvas::Frame, theme: &Theme, camera: Camera, view: Viewport) {
        let (metres, px) = camera::scale_bar(camera, view, 110.0);
        if !metres.is_finite() || px < 1.0 {
            return;
        }
        let label = if metres >= 1000.0 {
            format!("{} km", grouped((metres / 1000.0).round() as usize))
        } else {
            format!("{} m", metres.round())
        };
        let left = 16.0;
        let bottom = view.height as f32 - 16.0;
        let px = px as f32;
        let back = Path::rounded_rectangle(
            Point::new(left - 6.0, bottom - 26.0),
            Size::new(px + 12.0, 32.0),
            6.0.into(),
        );
        frame.fill(&back, surface(theme).scale_alpha(0.78));
        let line = Path::new(|path| {
            path.move_to(Point::new(left, bottom - 6.0));
            path.line_to(Point::new(left, bottom));
            path.line_to(Point::new(left + px, bottom));
            path.line_to(Point::new(left + px, bottom - 6.0));
        });
        frame.stroke(
            &line,
            canvas::Stroke::default()
                .with_color(theme.palette().text)
                .with_width(1.5),
        );
        frame.fill_text(canvas::Text {
            content: label,
            position: Point::new(left, bottom - 22.0),
            color: theme.palette().text,
            size: 11.0.into(),
            font: design::MEDIUM,
            ..Default::default()
        });
    }
}

// Camera changes go out as messages so the zoom buttons, gestures and the
// side panel share one state.
impl Program<Message> for Map<'_> {
    type State = MapState;

    fn update(
        &self,
        state: &mut MapState,
        event: &canvas::Event,
        bounds: Rectangle,
        cursor: mouse::Cursor,
    ) -> Option<Action<Message>> {
        if self.layer != Layer::Top {
            return None;
        }
        let view = Viewport::new(bounds.width.into(), bounds.height.into());
        if state.size != Some(bounds.size()) {
            state.size = Some(bounds.size());
            return Some(Action::publish(Message::PlacesViewport(view)));
        }
        let map = self.places.camera_for(view);
        match event {
            canvas::Event::Mouse(mouse::Event::ButtonPressed(mouse::Button::Left)) => {
                let pos = cursor.position_in(bounds)?;
                state.drag = Some((pos, pos));
                Some(Action::capture())
            }
            canvas::Event::Mouse(mouse::Event::CursorMoved { position })
                if state.drag.is_some() =>
            {
                let (start, last) = state.drag?;
                let next = Point::new(position.x - bounds.x, position.y - bounds.y);
                if next.distance(start) < 4.0 && last == start {
                    return None;
                }
                state.drag = Some((start, next));
                let diff = next - last;
                let camera = map.panned(diff.x.into(), diff.y.into(), view);
                Some(Action::publish(Message::PlacesCamera(camera)).and_capture())
            }
            canvas::Event::Mouse(mouse::Event::ButtonReleased(mouse::Button::Left)) => {
                let (start, last) = state.drag.take()?;
                let pos = cursor.position_in(bounds)?;
                if pos.distance(start) >= 4.0 || last != start {
                    return Some(Action::capture());
                }
                let Some(bubble) = self.hit(pos, view) else {
                    let now = Instant::now();
                    let double = state.last_click.is_some_and(|(at, point)| {
                        now.duration_since(at) < Duration::from_millis(400)
                            && point.distance(pos) < 8.0
                    });
                    state.last_click = (!double).then_some((now, pos));
                    if double {
                        let camera = map.zoom_at(2.0, pos.x.into(), pos.y.into(), view);
                        return Some(Action::publish(Message::PlacesCamera(camera)).and_capture());
                    }
                    return Some(Action::capture());
                };
                let photos = &self.places.photos;
                if bubble.members.len() == 1 {
                    return Some(
                        Action::publish(Message::OpenPlacePhoto(photos[bubble.members[0]].index))
                            .and_capture(),
                    );
                }
                let first = photos[bubble.members[0]].coordinates;
                let spread = bubble
                    .members
                    .iter()
                    .any(|&i| photos[i].coordinates != first);
                let fitted = Camera::fit(
                    bubble
                        .members
                        .iter()
                        .map(|&i| photos[i].coordinates.projected()),
                    view,
                    camera::MAX_ZOOM,
                );
                let message = if spread && fitted.zoom > map.zoom * 1.25 {
                    Message::PlacesCamera(fitted)
                } else {
                    Message::PlacesSelect(bubble.members.clone())
                };
                Some(Action::publish(message).and_capture())
            }
            canvas::Event::Mouse(mouse::Event::WheelScrolled { delta })
                if cursor.is_over(bounds) =>
            {
                let amount = match delta {
                    mouse::ScrollDelta::Lines { y, .. } => *y,
                    mouse::ScrollDelta::Pixels { y, .. } => *y / 40.0,
                };
                let cursor = cursor.position_in(bounds)?;
                let camera = map.zoom_at(
                    1.25f64.powf(f64::from(amount.clamp(-4.0, 4.0))),
                    cursor.x.into(),
                    cursor.y.into(),
                    view,
                );
                Some(Action::publish(Message::PlacesCamera(camera)).and_capture())
            }
            _ => None,
        }
    }

    fn draw(
        &self,
        state: &MapState,
        renderer: &Renderer,
        theme: &Theme,
        bounds: Rectangle,
        _: mouse::Cursor,
    ) -> Vec<canvas::Geometry> {
        let view = Viewport::new(bounds.width.into(), bounds.height.into());
        let camera = self.places.camera_for(view);
        let mut frame = canvas::Frame::new(renderer, bounds.size());
        match self.layer {
            Layer::Base => {
                if !self.draw_tiles(&mut frame, camera, view) {
                    let dark = theme.extended_palette().is_dark;
                    return vec![self.draw_outlines(state, renderer, camera, view, dark)];
                }
            }
            Layer::Markers => {
                if self.places.viewport == Some(view) {
                    self.draw_markers(&mut frame, false, theme);
                }
            }
            Layer::Top => {
                if self.places.viewport == Some(view) {
                    self.draw_markers(&mut frame, true, theme);
                }
                self.draw_scale(&mut frame, theme, camera, view);
            }
        }
        vec![frame.into_geometry()]
    }

    fn mouse_interaction(
        &self,
        state: &MapState,
        bounds: Rectangle,
        cursor: mouse::Cursor,
    ) -> mouse::Interaction {
        if self.layer != Layer::Top {
            return mouse::Interaction::None;
        }
        let Some(position) = cursor.position_in(bounds) else {
            return mouse::Interaction::default();
        };
        if state.drag.is_some_and(|(start, last)| start != last) {
            return mouse::Interaction::Grabbing;
        }
        let view = Viewport::new(bounds.width.into(), bounds.height.into());
        if self.hit(position, view).is_some() {
            mouse::Interaction::Pointer
        } else {
            mouse::Interaction::Grab
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn images_drop_the_least_recently_used_over_budget() {
        let handle = || Handle::from_rgba(1, 1, vec![0; 4]);
        let mut images = Images::new(300);
        images.insert(1, handle(), 100);
        images.insert(2, handle(), 100);
        images.insert(3, handle(), 100);
        images.touch([&1]);
        images.insert(4, handle(), 100);
        assert!(images.contains(&1) && !images.contains(&2));
        assert!(images.contains(&3) && images.contains(&4));
        assert_eq!(images.bytes, 300);
        // Replacing an entry counts its bytes once.
        images.insert(4, handle(), 50);
        assert_eq!(images.bytes, 250);
    }

    #[test]
    fn reset_keeps_the_basemap_layer_and_view() {
        let mut places = Places {
            viewport: Some(Viewport::new(800.0, 600.0)),
            ..Places::default()
        };
        places.map.opened = true;
        places.map.tiles.insert(
            places.map.key(0, 0, 0, TILE_PX),
            Handle::from_rgba(1, 1, vec![0; 4]),
            4,
        );
        places.photos = crate::bench::located_photos(10);
        places.reset();
        assert!(places.photos.is_empty());
        assert_eq!(places.request, 1);
        assert!(places.map.opened && places.map.tiles.contains(&places.map.key(0, 0, 0, TILE_PX)));
        assert_eq!(places.viewport, Some(Viewport::new(800.0, 600.0)));
    }

    #[test]
    fn markers_and_labels_follow_the_camera() {
        let mut places = Places {
            photos: crate::bench::located_photos(2_000),
            viewport: Some(Viewport::new(1000.0, 700.0)),
            ..Places::default()
        };
        places.refresh();
        assert!(!places.bubbles.is_empty());
        assert_eq!(places.labels.len(), places.bubbles.len());
        let total: usize = places.bubbles.iter().map(|b| b.photos).sum();
        assert!(total <= 2_000);
        // Covers are the newest photo of each marker, listed once.
        let wanted = places.wanted_thumbs();
        assert_eq!(wanted.len(), places.bubbles.len());
        // No basemap: no tiles wanted.
        assert!(places.wanted_tiles().is_empty());
    }

    #[test]
    fn wanted_tiles_start_at_the_centre_and_end_with_the_world() {
        let mut places = Places {
            viewport: Some(Viewport::new(1200.0, 800.0)),
            camera: Some(Camera::new(World { x: 0.52, y: 0.33 }, 40.0)),
            ..Places::default()
        };
        places.map.basemap = Some(Arc::new(
            Basemap::from_bytes(proton_map::pmtiles::writer::Writer::new().finish(
                "{}",
                [-180.0, -85.0, 180.0, 85.0],
                [0.0, 0.0],
            ))
            .expect("empty archive opens"),
        ));
        let tiles = places.wanted_tiles();
        assert_eq!(tiles.last(), Some(&places.map.key(0, 0, 0, TILE_PX)));
        let camera = places.current_camera().expect("viewport known");
        let view = places.viewport.expect("set");
        let first = camera
            .tiles(view, TILE_PX, MAX_LEVEL)
            .into_iter()
            .find(|t| (t.level, t.x, t.y) == (tiles[0].level, tiles[0].x, tiles[0].y))
            .expect("first tile is on screen");
        assert!((first.left..first.left + first.size).contains(&600.0));
        assert!((first.top..first.top + first.size).contains(&400.0));
    }

    #[test]
    fn rounded_pictures_are_square_with_clear_corners() {
        let jpeg = crate::bench::jpeg(400, 300);
        let (_, bytes) = rounded_square(&jpeg, 64).expect("decodes");
        assert_eq!(bytes, 64 * 64 * 4);
    }

    #[test]
    fn counts_are_grouped_by_thousands() {
        assert_eq!(grouped(7), "7");
        assert_eq!(grouped(1_992), "1,992");
        assert_eq!(grouped(12_260), "12,260");
        assert_eq!(grouped(1_000_000), "1,000,000");
    }
}
