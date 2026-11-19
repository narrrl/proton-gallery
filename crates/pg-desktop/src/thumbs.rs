//! Decoded thumbnails, kept in memory up to a byte budget. Decoding happens
//! on a blocking thread, never on the UI thread; the renderer only uploads
//! ready pixels.
use iced::widget::image::Handle;
use image::{ImageDecoder, metadata::Orientation};
use std::collections::HashMap;

/// Longest side of a decoded grid thumbnail, in pixels.
pub const THUMB_SIDE: u32 = 320;
/// Longest side of a decoded photo in the viewer.
pub const VIEWER_SIDE: u32 = 2560;
/// Memory for decoded grid thumbnails.
pub const BUDGET: usize = 160 * 1024 * 1024;

#[derive(Clone, Debug)]
pub struct Decoded {
    pub handle: Handle,
    /// Size of the source image, before scaling: its real shape.
    pub width: u32,
    pub height: u32,
    /// Bytes of the decoded pixels.
    pub bytes: usize,
}

/// Decode `bytes` and scale them to fit `side`. Call off the UI thread.
pub fn decode(bytes: &[u8], side: u32) -> Result<Decoded, String> {
    let mut reader = image::ImageReader::new(std::io::Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| "Couldn’t identify the image.".to_string())?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(16_384);
    limits.max_image_height = Some(16_384);
    limits.max_alloc = Some(128 * 1024 * 1024);
    reader.limits(limits.clone());
    let unsupported = |_| "This image format or size can’t be shown yet.".to_string();
    let mut decoder = reader.into_decoder().map_err(unsupported)?;
    limits.reserve(decoder.total_bytes()).map_err(unsupported)?;
    decoder.set_limits(limits).map_err(unsupported)?;
    let orientation = decoder.orientation().unwrap_or(Orientation::NoTransforms);
    let (mut width, mut height) = decoder.dimensions();
    let image = image::DynamicImage::from_decoder(decoder).map_err(unsupported)?;
    let mut image = if width > side || height > side {
        image.thumbnail(side, side)
    } else {
        image
    };
    image.apply_orientation(orientation);
    if matches!(
        orientation,
        Orientation::Rotate90
            | Orientation::Rotate270
            | Orientation::Rotate90FlipH
            | Orientation::Rotate270FlipH
    ) {
        std::mem::swap(&mut width, &mut height);
    }
    let rgba = image.into_rgba8();
    let (w, h) = rgba.dimensions();
    let pixels = rgba.into_raw();
    Ok(Decoded {
        bytes: pixels.len(),
        handle: Handle::from_rgba(w, h, pixels),
        width,
        height,
    })
}

/// A stand-in thumbnail for the synthetic library: a small gradient whose
/// colors and shape follow the item's index.
#[cfg(test)]
pub fn synthetic(index: usize, ratio: f32) -> Decoded {
    let height = 96u32;
    let width = ((height as f32 * ratio).round() as u32).clamp(24, 384);
    let hue = (index.wrapping_mul(2_654_435_761) % 360) as f32;
    let mut pixels = Vec::with_capacity((width * height * 4) as usize);
    for y in 0..height {
        for x in 0..width {
            let t = (x + y) as f32 / (width + height) as f32;
            let [r, g, b] = hsv(hue + t * 60.0, 0.45, 0.55 + 0.35 * t);
            pixels.extend_from_slice(&[r, g, b, 255]);
        }
    }
    Decoded {
        bytes: pixels.len(),
        handle: Handle::from_rgba(width, height, pixels),
        width,
        height,
    }
}

#[cfg(test)]
fn hsv(hue: f32, saturation: f32, value: f32) -> [u8; 3] {
    let h = (hue % 360.0) / 60.0;
    let c = value * saturation;
    let x = c * (1.0 - (h % 2.0 - 1.0).abs());
    let (r, g, b) = match h as u32 {
        0 => (c, x, 0.0),
        1 => (x, c, 0.0),
        2 => (0.0, c, x),
        3 => (0.0, x, c),
        4 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let m = value - c;
    [r, g, b].map(|v| ((v + m) * 255.0) as u8)
}

/// Thumbnails by item id, least recently shown dropped first.
#[derive(Default)]
pub struct Cache {
    entries: HashMap<String, (Decoded, u64)>,
    bytes: usize,
    tick: u64,
}

impl Cache {
    pub fn get(&self, id: &str) -> Option<&Decoded> {
        self.entries.get(id).map(|(decoded, _)| decoded)
    }

    pub fn contains(&self, id: &str) -> bool {
        self.entries.contains_key(id)
    }

    #[cfg(test)]
    pub fn bytes(&self) -> usize {
        self.bytes
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Mark `ids` as shown now, so they are the last to go.
    pub fn touch<'a>(&mut self, ids: impl IntoIterator<Item = &'a str>) {
        self.tick += 1;
        for id in ids {
            if let Some(entry) = self.entries.get_mut(id) {
                entry.1 = self.tick;
            }
        }
    }

    /// Keep `decoded` for `id`, then drop the least recently shown entries
    /// until the cache fits `budget`. The newest entry always stays.
    pub fn insert(&mut self, id: String, decoded: Decoded, budget: usize) {
        self.tick += 1;
        self.bytes += decoded.bytes;
        if let Some((old, _)) = self.entries.insert(id.clone(), (decoded, self.tick)) {
            self.bytes -= old.bytes;
        }
        if self.bytes <= budget {
            return;
        }
        let mut order: Vec<(u64, String)> = self
            .entries
            .iter()
            .filter(|(key, _)| **key != id)
            .map(|(key, (_, tick))| (*tick, key.clone()))
            .collect();
        order.sort_unstable();
        for (_, key) in order {
            if self.bytes <= budget {
                break;
            }
            if let Some((old, _)) = self.entries.remove(&key) {
                self.bytes -= old.bytes;
            }
        }
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        self.bytes = 0;
    }
}

/// Permits stay with blocking decoders even when their requesting task is aborted.
/// Repeated viewer navigation can therefore never queue unbounded decode work.
pub async fn decode_async(bytes: Vec<u8>, side: u32) -> Result<Decoded, String> {
    static DECODERS: std::sync::OnceLock<std::sync::Arc<tokio::sync::Semaphore>> =
        std::sync::OnceLock::new();
    let permit = DECODERS
        .get_or_init(|| std::sync::Arc::new(tokio::sync::Semaphore::new(2)))
        .clone()
        .acquire_owned()
        .await
        .map_err(|e| e.to_string())?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        decode(&bytes, side)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_cache_stays_within_its_budget_and_keeps_what_was_shown() {
        let mut cache = Cache::default();
        let thumbnail = |i: usize| Decoded {
            bytes: 64,
            handle: Handle::from_rgba(4, 4, vec![i as u8; 64]),
            width: 4,
            height: 4,
        };
        let one = thumbnail(0).bytes;
        let budget = one * 10;
        for i in 0..10 {
            cache.insert(i.to_string(), thumbnail(i), budget);
        }
        cache.touch(["0"]);
        // Browsing far more than fits never grows the cache past the budget.
        for i in 10..100_000 {
            cache.insert(i.to_string(), thumbnail(i), budget);
            if i % 3 == 0 {
                cache.touch(["0"]);
            }
            assert!(cache.bytes() <= budget);
        }
        assert!(cache.contains("0"));
        assert!(!cache.contains("1"));
        assert_eq!(cache.len(), 10);
    }

    #[test]
    fn dimensions_are_limited_before_allocating_the_decoded_image() {
        let mut png = Vec::new();
        image::RgbaImage::new(20_000, 1)
            .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
            .unwrap();
        assert!(decode(&png, VIEWER_SIDE).unwrap_err().contains("size"));
    }

    #[test]
    fn jpeg_orientation_swaps_the_display_shape_before_it_reaches_the_grid() {
        let mut jpeg = Vec::new();
        image::codecs::jpeg::JpegEncoder::new(&mut jpeg)
            .encode(&[200; 16 * 8 * 3], 16, 8, image::ExtendedColorType::Rgb8)
            .unwrap();
        let tiff = [
            b'I', b'I', 42, 0, 8, 0, 0, 0, 1, 0, 18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0,
        ];
        let length = (2 + 6 + tiff.len()) as u16;
        let mut exif = vec![0xff, 0xe1];
        exif.extend_from_slice(&length.to_be_bytes());
        exif.extend_from_slice(b"Exif\0\0");
        exif.extend_from_slice(&tiff);
        jpeg.splice(2..2, exif);
        let decoded = decode(&jpeg, THUMB_SIDE).unwrap();
        assert_eq!((decoded.width, decoded.height), (8, 16));
    }

    #[test]
    fn decoding_scales_down_and_reports_the_real_shape() {
        let mut png = Vec::new();
        image::RgbaImage::new(800, 400)
            .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
            .unwrap();
        let decoded = decode(&png, THUMB_SIDE).unwrap();
        assert_eq!((decoded.width, decoded.height), (800, 400));
        assert_eq!(decoded.bytes, (320 * 160 * 4) as usize);
        assert!(decode(b"not an image", THUMB_SIDE).is_err());
    }
}
