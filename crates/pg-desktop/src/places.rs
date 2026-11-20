use crate::design;
use crate::gallery::Message;
use iced::widget::canvas::{self, Action, Canvas, Path, Program};
use iced::widget::{button, column, container, row, scrollable, text};
use iced::{Color, Element, Length, Point, Rectangle, Renderer, Theme, Vector, color, mouse};
use proton_gallery_app_rs::places::{LocatedPhoto, PlacesScan};
use proton_gallery_rs::places::{self, Bubble, World};
use std::cell::RefCell;
use std::sync::Arc;

#[derive(Default)]
pub struct Places {
    pub request: u64,
    pub scan: Option<Arc<PlacesScan>>,
    pub work: Option<iced::task::Handle>,
    pub photos: Vec<LocatedPhoto>,
    pub next: usize,
    pub failed: usize,
    pub error: Option<String>,
    pub selected: Vec<usize>,
    pub list_limit: usize,
    all: bool,
    pub camera: Option<Camera>,
    pub fit_steps: i32,
}

impl Places {
    pub fn reset(&mut self) {
        *self = Self {
            request: self.request + 1,
            ..Self::default()
        };
    }

    pub fn select(&mut self, photos: Vec<usize>, all: bool) {
        self.selected = photos;
        self.all = all;
        self.list_limit = 60;
    }

    pub fn view(&self) -> Element<'_, Message> {
        let status = if let Some(error) = &self.error {
            error.clone()
        } else if let Some(scan) = &self.scan {
            if self.work.is_some() {
                format!(
                    "Checking photo locations… {} of {}",
                    self.next,
                    scan.outline.len()
                )
            } else if self.failed > 0 {
                format!(
                    "{} photos on the map. {} couldn’t be checked.",
                    self.photos.len(),
                    self.failed
                )
            } else if self.photos.is_empty() {
                "No photos with location data".into()
            } else {
                format!("{} photos on the map", self.photos.len())
            }
        } else {
            "Opening Places…".into()
        };
        let mut controls = row![
            button(text("−").size(16))
                .on_press(Message::PlacesZoom(0.5))
                .style(design::secondary),
            button(text("+").size(16))
                .on_press(Message::PlacesZoom(2.0))
                .style(design::secondary),
            button(text("Fit photos").size(13))
                .on_press(Message::PlacesFit)
                .style(design::secondary),
        ]
        .spacing(6);
        if !self.photos.is_empty() && self.selected.is_empty() {
            controls = controls.push(
                button(text("List photos").size(13))
                    .on_press(Message::PlacesListAll)
                    .style(design::secondary),
            );
        }
        if self.error.is_some() || (self.work.is_none() && self.failed > 0) {
            controls = controls.push(
                button(text("Retry").size(13))
                    .on_press(Message::StartPlaces)
                    .style(design::secondary),
            );
        }
        let mut body = column![
            row![
                text(status)
                    .size(13)
                    .style(design::muted)
                    .width(Length::Fill),
                controls
            ]
            .align_y(iced::Alignment::Center)
            .spacing(8),
            Canvas::new(Map {
                photos: &self.photos,
                camera: self.camera,
                fit_steps: self.fit_steps
            })
            .width(Length::Fill)
            .height(Length::Fill),
        ]
        .spacing(8);
        if !self.selected.is_empty() {
            let heading = if self.all {
                "Photos on the map"
            } else {
                self.selected
                    .first()
                    .and_then(|&i| self.photos.get(i))
                    .and_then(|photo| photo.place.as_deref())
                    .unwrap_or("Photos at this location")
            };
            let mut list = column![
                row![
                    text(heading).font(design::MEDIUM).width(Length::Fill),
                    button(text("Close").size(13))
                        .on_press(Message::PlacesSelect(Vec::new()))
                        .style(design::quiet),
                ]
                .align_y(iced::Alignment::Center)
            ];
            for &position in self.selected.iter().take(self.list_limit) {
                if let Some(photo) = self.photos.get(position) {
                    list = list.push(
                        button(text(&photo.name).size(13))
                            .on_press(Message::OpenPlacePhoto(photo.index))
                            .style(design::quiet),
                    );
                }
            }
            if self.list_limit < self.selected.len() {
                list = list.push(
                    button(text("Show more").size(13))
                        .on_press(Message::PlacesListMore)
                        .style(design::secondary),
                );
            }
            body = body.push(container(scrollable(list).height(Length::Shrink)).max_height(170));
        }
        body = body.push(
            text("Map data from Natural Earth and GeoNames (CC BY 4.0)")
                .size(11)
                .style(design::muted),
        );
        container(body)
            .padding([10, 24])
            .height(Length::Fill)
            .into()
    }
}

pub fn camera_zoom(state: Camera, factor: f64) -> Camera {
    Camera {
        zoom: (state.zoom * factor).clamp(1.0, 8192.0),
        ..state
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Camera {
    center: World,
    zoom: f64,
}
impl Default for Camera {
    fn default() -> Self {
        Self {
            center: World { x: 0.5, y: 0.5 },
            zoom: 1.0,
        }
    }
}

impl Camera {
    /// A camera looking at `center` (in projected world units) at `zoom`.
    pub(crate) fn new(center: World, zoom: f64) -> Self {
        Self { center, zoom }
    }

    fn project(self, point: World, bounds: Rectangle) -> Point {
        let scale = self.scale(bounds);
        Point::new(
            bounds.width * 0.5 + ((point.x - self.center.x) * scale) as f32,
            bounds.height * 0.5 + ((point.y - self.center.y) * scale) as f32,
        )
    }

    fn scale(self, bounds: Rectangle) -> f64 {
        f64::from(bounds.width.min(bounds.height).max(1.0)) * self.zoom
    }

    fn fit(self, points: impl Iterator<Item = World>, bounds: Rectangle, max: f64) -> Self {
        let mut x = (f64::INFINITY, f64::NEG_INFINITY);
        let mut y = x;
        for point in points {
            x = (x.0.min(point.x), x.1.max(point.x));
            y = (y.0.min(point.y), y.1.max(point.y));
        }
        if !x.0.is_finite() {
            return Self::default();
        }
        let view = (
            f64::from(bounds.width.max(1.0)),
            f64::from(bounds.height.max(1.0)),
        );
        let scale = view.0.min(view.1);
        let span = ((x.1 - x.0) * scale / view.0).max((y.1 - y.0) * scale / view.1);
        Self {
            center: World {
                x: (x.0 + x.1) * 0.5,
                y: (y.0 + y.1) * 0.5,
            },
            zoom: (0.72 / span).clamp(1.0, max),
        }
    }
}

pub struct Map<'a> {
    photos: &'a [LocatedPhoto],
    camera: Option<Camera>,
    fit_steps: i32,
}

#[derive(Default)]
pub struct MapState {
    base: canvas::Cache,
    paint: RefCell<Option<(Camera, bool)>>,
    drag: Option<(Point, Point)>,
}

impl Map<'_> {
    fn camera(&self, bounds: Rectangle) -> Camera {
        self.camera.unwrap_or_else(|| {
            camera_zoom(
                Camera::default().fit(
                    self.photos
                        .iter()
                        .map(|photo| photo.coordinates.projected()),
                    bounds,
                    64.0,
                ),
                2.0f64.powi(self.fit_steps),
            )
        })
    }

    fn bubbles(&self, camera: Camera, bounds: Rectangle) -> Vec<Bubble> {
        bubbles(self.photos, camera, bounds)
    }
}

/// The markers for `photos` seen through `camera` in `bounds`.
pub(crate) fn bubbles(photos: &[LocatedPhoto], camera: Camera, bounds: Rectangle) -> Vec<Bubble> {
    let screen: Vec<_> = photos
        .iter()
        .map(|photo| {
            let p = camera.project(photo.coordinates.projected(), bounds);
            (f64::from(p.x), f64::from(p.y))
        })
        .collect();
    places::cluster(
        &screen,
        &vec![1; screen.len()],
        bounds.width.into(),
        bounds.height.into(),
    )
}

// A canvas message carries camera changes so zoom buttons, pointer gestures,
// and the photo list use the same state.
impl Program<Message> for Map<'_> {
    type State = MapState;

    fn update(
        &self,
        state: &mut MapState,
        event: &canvas::Event,
        bounds: Rectangle,
        cursor: mouse::Cursor,
    ) -> Option<Action<Message>> {
        let map = self.camera(bounds);
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
                let scale = map.scale(bounds);
                let center = World {
                    x: (map.center.x - f64::from(diff.x) / scale).clamp(0.0, 1.0),
                    y: (map.center.y - f64::from(diff.y) / scale).clamp(0.0, 1.0),
                };
                Some(Action::publish(Message::PlacesCamera(Camera { center, ..map })).and_capture())
            }
            canvas::Event::Mouse(mouse::Event::ButtonReleased(mouse::Button::Left)) => {
                let (start, last) = state.drag.take()?;
                let pos = cursor.position_in(bounds)?;
                if pos.distance(start) < 4.0 && last == start {
                    let bubble = self.bubbles(map, bounds).into_iter().rev().find(|bubble| {
                        let point = Point::new(bubble.x as f32, bubble.y as f32);
                        point.distance(pos) <= bubble.radius as f32
                    })?;
                    if bubble.members.len() == 1 {
                        return Some(
                            Action::publish(Message::OpenPlacePhoto(
                                self.photos[bubble.members[0]].index,
                            ))
                            .and_capture(),
                        );
                    }
                    let first = self.photos[bubble.members[0]].coordinates;
                    let spread = bubble
                        .members
                        .iter()
                        .any(|&i| self.photos[i].coordinates != first);
                    let fitted = map.fit(
                        bubble
                            .members
                            .iter()
                            .map(|&i| self.photos[i].coordinates.projected()),
                        bounds,
                        8192.0,
                    );
                    let message = if spread && fitted.zoom > map.zoom * 1.25 {
                        Message::PlacesCamera(fitted)
                    } else {
                        Message::PlacesSelect(bubble.members)
                    };
                    return Some(Action::publish(message).and_capture());
                }
                Some(Action::capture())
            }
            canvas::Event::Mouse(mouse::Event::WheelScrolled { delta })
                if cursor.is_over(bounds) =>
            {
                let amount = match delta {
                    mouse::ScrollDelta::Lines { y, .. } => *y,
                    mouse::ScrollDelta::Pixels { y, .. } => *y / 40.0,
                };
                let mut zoom = camera_zoom(map, 1.25f64.powf(f64::from(amount.clamp(-4.0, 4.0))));
                let cursor = cursor.position_in(bounds)?;
                let change = map.scale(bounds).recip() - zoom.scale(bounds).recip();
                zoom.center.x = (map.center.x + f64::from(cursor.x - bounds.width / 2.0) * change)
                    .clamp(0.0, 1.0);
                zoom.center.y = (map.center.y + f64::from(cursor.y - bounds.height / 2.0) * change)
                    .clamp(0.0, 1.0);
                Some(Action::publish(Message::PlacesCamera(zoom)).and_capture())
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
        let camera = self.camera(bounds);
        let dark = theme.extended_palette().is_dark;
        if state.paint.replace(Some((camera, dark))) != Some((camera, dark)) {
            state.base.clear();
        }
        let background = state.base.draw(renderer, bounds.size(), |frame| {
            let (water, land, border) = if dark {
                (color!(0x211B34), color!(0x3B3653), color!(0x625873))
            } else {
                (color!(0xF0EBFA), color!(0xD6CFE9), color!(0xA99CBF))
            };
            frame.fill_rectangle(Point::ORIGIN, bounds.size(), water);
            for (rings, fill) in [(places::land(), true), (places::borders(), false)] {
                for ring in rings {
                    let min = camera.project(ring.min, bounds);
                    let max = camera.project(ring.max, bounds);
                    if max.x < 0.0 || max.y < 0.0 || min.x > bounds.width || min.y > bounds.height {
                        continue;
                    }
                    let path = Path::new(|builder| {
                        let mut points = ring.points.iter();
                        if let Some(first) = points.next() {
                            builder.move_to(camera.project(*first, bounds));
                            for point in points {
                                builder.line_to(camera.project(*point, bounds));
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
        });
        let mut frame = canvas::Frame::new(renderer, bounds.size());
        for bubble in self.bubbles(camera, bounds) {
            let point = Point::new(bubble.x as f32, bubble.y as f32);
            let circle = Path::circle(point, bubble.radius as f32);
            frame.fill(&circle, color!(0x6D4AFF));
            frame.stroke(
                &circle,
                canvas::Stroke::default()
                    .with_color(color!(0xE4D9FF))
                    .with_width(1.5),
            );
            frame.fill_text(canvas::Text {
                content: places::compact_count(bubble.photos),
                position: point + Vector::new(0.0, -1.0),
                color: Color::WHITE,
                size: 13.0.into(),
                align_x: iced::alignment::Horizontal::Center.into(),
                align_y: iced::alignment::Vertical::Center,
                font: design::MEDIUM,
                ..Default::default()
            });
            let place = bubble
                .members
                .first()
                .and_then(|&i| self.photos[i].place.as_deref());
            if let Some(place) = place
                && bubble
                    .members
                    .iter()
                    .all(|&i| self.photos[i].place.as_deref() == Some(place))
            {
                frame.fill_text(canvas::Text {
                    content: place.to_owned(),
                    position: point + Vector::new(0.0, bubble.radius as f32 + 4.0),
                    color: theme.palette().text,
                    size: 12.0.into(),
                    align_x: iced::alignment::Horizontal::Center.into(),
                    font: design::MEDIUM,
                    ..Default::default()
                });
            }
        }
        vec![background, frame.into_geometry()]
    }
}
