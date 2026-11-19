use super::*;
use pg_app::places::PlacesScan;
use pg_core::places;
use std::future::{Future, poll_fn};
use std::pin::pin;

#[derive(Debug, Clone, uniffi::Record)]
pub struct PlacePhoto {
    pub id: String,
    pub name: String,
    pub x: f64,
    pub y: f64,
    pub place: Option<String>,
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
