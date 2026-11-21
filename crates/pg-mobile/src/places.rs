use super::*;
use proton_gallery_app_rs::places::PlacesScan;
use proton_gallery_rs::camera::{self, Camera, Viewport};
use proton_gallery_rs::places::{self, World};
use proton_map::{Basemap, Style, TileId};
use std::future::{Future, poll_fn};
use std::pin::pin;

#[derive(Debug, Clone, uniffi::Record)]
pub struct PlacePhoto {
    pub id: String,
    pub name: String,
    pub x: f64,
    pub y: f64,
    pub place: Option<String>,
    /// Position in the Photos timeline, newest first.
    pub index: u32,
}

#[derive(uniffi::Record)]
pub struct PlaceBatch {
    pub photos: Vec<PlacePhoto>,
    pub next: u32,
    pub failed: u32,
}

#[derive(uniffi::Object)]
pub struct PhotoPlaces(PlacesScan);

#[uniffi::export]
impl PhotoPlaces {
    pub fn count(&self) -> u32 {
        self.0.outline.len() as u32
    }
}

#[uniffi::export]
impl GalleryEngine {
    pub fn start_places(&self) -> Result<Arc<PhotoPlaces>, GalleryError> {
        Ok(Arc::new(PhotoPlaces(
            self.runtime.block_on(self.app.places_scan())?,
        )))
    }

    pub async fn places_batch(
        self: Arc<Self>,
        scan: Arc<PhotoPlaces>,
        start: u32,
    ) -> Result<PlaceBatch, GalleryError> {
        // Enter the engine's runtime on every poll. Dropping the UniFFI
        // future then cancels the active network work when Places is closed.
        let mut batch = pin!(self.app.places_batch(&scan.0, start as usize));
        let batch = poll_fn(|cx| {
            let _guard = self.runtime.enter();
            batch.as_mut().poll(cx)
        })
        .await?;
        Ok(PlaceBatch {
            photos: batch
                .photos
                .into_iter()
                .map(|photo| {
                    let point = photo.coordinates.projected();
                    PlacePhoto {
                        id: scan.0.outline.ids[photo.index].clone(),
                        name: photo.name,
                        x: point.x,
                        y: point.y,
                        place: photo.place,
                        index: photo.index as u32,
                    }
                })
                .collect(),
            next: batch.next as u32,
            failed: batch.failed as u32,
        })
    }
}

#[derive(uniffi::Record)]
pub struct PlacesGeometry {
    /// X/Y pairs in normalized Web Mercator coordinates per ring.
    pub land: Vec<Vec<f32>>,
    pub borders: Vec<Vec<f32>>,
}

#[uniffi::export]
pub fn places_geometry() -> PlacesGeometry {
    let points = |rings: &[places::Ring]| {
        rings
            .iter()
            .map(|ring| {
                ring.points
                    .iter()
                    .flat_map(|point| [point.x as f32, point.y as f32])
                    .collect()
            })
            .collect()
    };
    PlacesGeometry {
        land: points(places::land()),
        borders: points(places::borders()),
    }
}

#[derive(uniffi::Record)]
pub struct PlaceCluster {
    pub x: f32,
    pub y: f32,
    pub radius: f32,
    pub count: String,
    pub members: Vec<u32>,
}

#[uniffi::export]
pub fn cluster_places(points: Vec<f32>, width: f32, height: f32) -> Vec<PlaceCluster> {
    let screen: Vec<_> = points
        .as_chunks::<2>()
        .0
        .iter()
        .map(|xy| (xy[0].into(), xy[1].into()))
        .collect();
    places::cluster(&screen, &vec![1; screen.len()], width.into(), height.into())
        .into_iter()
        .map(|bubble| PlaceCluster {
            x: bubble.x as f32,
            y: bubble.y as f32,
            radius: bubble.radius as f32,
            count: places::compact_count(bubble.photos),
            members: bubble.members.into_iter().map(|i| i as u32).collect(),
        })
        .collect()
}

/// The offline basemap, read whole into memory while Places is open.
#[derive(uniffi::Object)]
pub struct PlacesMap(Basemap);

#[derive(uniffi::Record)]
pub struct MapTile {
    pub width: u32,
    pub height: u32,
    /// Premultiplied RGBA, row by row: the byte order of an `ARGB_8888`
    /// Android bitmap.
    pub rgba: Vec<u8>,
}

fn map_error(error: proton_map::Error) -> GalleryError {
    GalleryError::Operation {
        detail: error.to_string(),
    }
}

#[uniffi::export]
impl PlacesMap {
    /// Read `length` bytes at `offset` of the open file `fd`, such as an
    /// uncompressed asset inside the APK. The caller keeps `fd` and closes it.
    #[uniffi::constructor]
    pub fn open_fd(fd: i32, offset: u64, length: u64) -> Result<Arc<Self>, GalleryError> {
        Ok(Arc::new(Self(read_fd(fd, offset, length).and_then(
            |bytes| Basemap::from_bytes(bytes).map_err(map_error),
        )?)))
    }

    #[uniffi::constructor]
    pub fn open_path(path: String) -> Result<Arc<Self>, GalleryError> {
        Ok(Arc::new(Self(Basemap::open(path).map_err(map_error)?)))
    }

    /// Tile `level`/`x`/`y` drawn `density` / 4 device px per basemap px, so
    /// lines and names keep their size at every zoom. Call off the UI thread.
    pub fn render_tile(&self, level: u8, x: u32, y: u32, density: u8, dark: bool) -> MapTile {
        let tile = proton_map::render_tile(
            &self.0,
            TileId::new(level, x, y),
            f32::from(density.clamp(2, 24)) / 4.0,
            &map_style(dark),
        );
        MapTile {
            width: tile.width,
            height: tile.height,
            rgba: tile.rgba,
        }
    }
}

#[cfg(unix)]
fn read_fd(fd: i32, offset: u64, length: u64) -> Result<Vec<u8>, GalleryError> {
    use std::os::unix::fs::FileExt;
    let io = |error: std::io::Error| GalleryError::Operation {
        detail: format!("reading the basemap: {error}"),
    };
    let length =
        usize::try_from(length).map_err(|_| io(std::io::ErrorKind::InvalidInput.into()))?;
    if fd < 0 {
        return Err(io(std::io::ErrorKind::InvalidInput.into()));
    }
    // Opening the descriptor's own path gives a separate handle, so the
    // caller's descriptor stays as it is and is closed by the caller.
    let file = std::fs::File::open(format!("/proc/self/fd/{fd}")).map_err(io)?;
    let mut bytes = vec![0; length];
    file.read_exact_at(&mut bytes, offset).map_err(io)?;
    Ok(bytes)
}

#[cfg(not(unix))]
fn read_fd(_fd: i32, _offset: u64, _length: u64) -> Result<Vec<u8>, GalleryError> {
    Err(GalleryError::Operation {
        detail: "reading a basemap from a descriptor needs a Unix system".into(),
    })
}

fn map_style(dark: bool) -> Style {
    if dark { Style::dark() } else { Style::light() }
}

/// The sea colour behind tiles still being drawn, as Android `0xAARRGGBB`.
#[uniffi::export]
pub fn map_sea(dark: bool) -> u32 {
    let sea = map_style(dark).water[0];
    u32::from_be_bytes([sea.a, sea.r, sea.g, sea.b])
}

/// The credit a map drawn from the basemap must show.
#[uniffi::export]
pub fn map_attribution() -> String {
    proton_map::ATTRIBUTION.into()
}

/// The camera, in normalized Web Mercator: centre and zoom (1 shows the
/// whole world across the shorter side of the view).
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct MapCamera {
    pub x: f64,
    pub y: f64,
    pub zoom: f64,
}

impl MapCamera {
    fn shared(self) -> Camera {
        Camera::new(
            World {
                x: self.x,
                y: self.y,
            },
            self.zoom,
        )
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScaleBar {
    pub label: String,
    /// Length in logical px.
    pub length: f32,
}

/// A round scale bar no longer than `max_length` logical px.
#[uniffi::export]
pub fn map_scale_bar(
    camera: MapCamera,
    width: f32,
    height: f32,
    max_length: f32,
) -> Option<ScaleBar> {
    let (metres, px) = camera::scale_bar(
        camera.shared(),
        Viewport::new(width.into(), height.into()),
        max_length.into(),
    );
    if !metres.is_finite() || px < 1.0 {
        return None;
    }
    let label = if metres >= 1000.0 {
        format!("{} km", (metres / 1000.0).round())
    } else {
        format!("{} m", metres.round())
    };
    Some(ScaleBar {
        label,
        length: px as f32,
    })
}

/// A marker on the map.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PlaceMarker {
    /// Centre and radius in logical px.
    pub x: f32,
    pub y: f32,
    pub radius: f32,
    pub photos: u32,
    pub count: String,
    /// Positions in the order photos were added.
    pub members: Vec<u32>,
    /// The member whose picture the marker shows: the newest photo.
    pub cover: u32,
    /// The place every member shares, when they all share one.
    pub place: Option<String>,
    /// Whether the place name fits under the marker without covering
    /// another marker or name.
    pub labelled: bool,
}

struct LaidPhoto {
    world: World,
    index: u32,
    place: Option<String>,
}

/// The located photos of a Places scan, laid out into markers for any camera.
/// Photos cross the bridge once; each layout passes only the camera.
#[derive(uniffi::Object)]
pub struct PlacesLayout(std::sync::Mutex<Vec<LaidPhoto>>);

#[uniffi::export]
impl PlacesLayout {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self(std::sync::Mutex::default()))
    }

    pub fn add(&self, photos: Vec<PlacePhoto>) {
        let mut laid = self.0.lock().unwrap_or_else(|e| e.into_inner());
        laid.extend(photos.into_iter().map(|photo| LaidPhoto {
            world: World {
                x: photo.x,
                y: photo.y,
            },
            index: photo.index,
            place: photo.place,
        }));
    }

    pub fn clear(&self) {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }

    /// Markers for a `width` × `height` view in logical px. No two overlap.
    pub fn layout(&self, camera: MapCamera, width: f32, height: f32) -> Vec<PlaceMarker> {
        let laid = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let view = Viewport::new(width.into(), height.into());
        let camera = camera.shared();
        let screen: Vec<_> = laid
            .iter()
            .map(|photo| camera.to_screen(photo.world, view))
            .collect();
        let bubbles = places::cluster(&screen, &vec![1; screen.len()], view.width, view.height);
        let shared_place = |bubble: &places::Bubble| {
            let first = laid[*bubble.members.first()?].place.as_deref()?;
            bubble
                .members
                .iter()
                .all(|&i| laid[i].place.as_deref() == Some(first))
                .then(|| first.to_owned())
        };
        let names: Vec<_> = bubbles.iter().map(shared_place).collect();
        let sizes: Vec<_> = names
            .iter()
            .map(|name| name.as_deref().map(label_size))
            .collect();
        let labelled = places::place_labels(&bubbles, &sizes);
        bubbles
            .into_iter()
            .zip(names)
            .zip(labelled)
            .map(|((bubble, place), labelled)| PlaceMarker {
                x: bubble.x as f32,
                y: bubble.y as f32,
                radius: bubble.radius as f32,
                photos: bubble.photos as u32,
                count: places::compact_count(bubble.photos),
                cover: bubble
                    .members
                    .iter()
                    .copied()
                    .min_by_key(|&i| laid[i].index)
                    .unwrap_or_default() as u32,
                members: bubble.members.into_iter().map(|i| i as u32).collect(),
                place,
                labelled,
            })
            .collect()
    }
}

/// Width and height in logical px of a place name's pill, as on desktop.
fn label_size(place: &str) -> (f64, f64) {
    (place.chars().count() as f64 * 6.6 + 14.0, 20.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn empty_basemap() -> Vec<u8> {
        proton_map::pmtiles::writer::Writer::new().finish(
            "{}",
            [-180.0, -85.0, 180.0, 85.0],
            [0.0, 0.0],
        )
    }

    fn photo(x: f64, y: f64, index: u32, place: Option<&str>) -> PlacePhoto {
        PlacePhoto {
            id: format!("p{index}"),
            name: format!("{index}.jpg"),
            x,
            y,
            place: place.map(str::to_owned),
            index,
        }
    }

    #[test]
    fn tiles_come_out_at_the_asked_density_as_bitmap_bytes() {
        let dir = tempfile::tempdir().expect("temporary directory");
        let path = dir.path().join("basemap.pmtiles");
        std::fs::write(&path, empty_basemap()).expect("written");
        let map = PlacesMap::open_path(path.to_string_lossy().into_owned()).expect("opens");
        let tile = map.render_tile(0, 0, 0, 8, false);
        assert_eq!((tile.width, tile.height), (512, 512));
        assert_eq!(tile.rgba.len(), 512 * 512 * 4);
        // An empty basemap is all sea, fully opaque.
        let sea = map_sea(false);
        assert_eq!(
            &tile.rgba[..4],
            &[(sea >> 16) as u8, (sea >> 8) as u8, sea as u8, 255]
        );
    }

    #[cfg(unix)]
    #[test]
    fn a_basemap_is_read_from_part_of_an_open_file() {
        use std::os::fd::AsRawFd;
        let archive = empty_basemap();
        let mut packed = vec![7u8; 100];
        packed.extend_from_slice(&archive);
        packed.extend_from_slice(&[7; 50]);
        let dir = tempfile::tempdir().expect("temporary directory");
        let path = dir.path().join("app.apk");
        std::fs::write(&path, &packed).expect("written");
        let file = std::fs::File::open(&path).expect("opens");
        let map = PlacesMap::open_fd(file.as_raw_fd(), 100, archive.len() as u64)
            .expect("reads the asset");
        assert_eq!(map.render_tile(0, 0, 0, 4, true).width, 256);
        assert!(PlacesMap::open_fd(file.as_raw_fd(), 0, archive.len() as u64).is_err());
    }

    #[test]
    fn scale_bars_are_round_and_say_their_unit() {
        let world = MapCamera {
            x: 0.5,
            y: 0.5,
            zoom: 1.0,
        };
        let bar = map_scale_bar(world, 400.0, 800.0, 110.0).expect("a bar");
        assert!(bar.label.ends_with(" km"), "{}", bar.label);
        assert!(bar.length <= 110.0 && bar.length > 40.0);
        let street = MapCamera {
            zoom: 8192.0,
            ..world
        };
        let bar = map_scale_bar(street, 2000.0, 2000.0, 110.0).expect("a bar");
        assert!(bar.label.ends_with(" m"), "{}", bar.label);
    }

    #[test]
    fn a_marker_shows_its_newest_photo_and_the_place_they_share() {
        let layout = PlacesLayout::new();
        layout.add(vec![
            photo(0.5, 0.5, 9, Some("Lisbon")),
            photo(0.5, 0.5, 3, Some("Lisbon")),
        ]);
        layout.add(vec![
            photo(0.1, 0.2, 5, Some("Oslo")),
            photo(0.1, 0.2, 4, None),
        ]);
        let camera = MapCamera {
            x: 0.3,
            y: 0.35,
            zoom: 1.0,
        };
        let mut markers = layout.layout(camera, 800.0, 800.0);
        markers.sort_by_key(|m| m.cover);
        assert_eq!(markers.len(), 2);
        assert_eq!(markers[0].cover, 1);
        assert_eq!(markers[0].place.as_deref(), Some("Lisbon"));
        assert!(markers[0].labelled);
        assert_eq!((markers[0].photos, markers[0].count.as_str()), (2, "2"));
        assert_eq!(markers[1].cover, 3);
        assert_eq!(markers[1].place, None);
        assert!(!markers[1].labelled);
        layout.clear();
        assert!(layout.layout(camera, 800.0, 800.0).is_empty());
    }
}
