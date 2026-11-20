//! `proton-gallery --bench`: an offline benchmark of the desktop's hot paths
//! on a synthetic library, so changes can be held to numbers.
//!
//! Everything runs on generated data in a throwaway directory; no account,
//! network or display is used. See `docs/BENCH.md`.
use crate::grid::Layout;
use crate::places;
use crate::thumbs;
use crate::vault::TemporaryVault;
use proton_gallery_app_rs::App;
use proton_gallery_app_rs::View;
use proton_gallery_app_rs::places::LocatedPhoto;
use proton_gallery_rs::places::{Coordinates, World};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const USAGE: &str = "Usage: proton-gallery --bench [--count N] [--points N] [--quick] [--json]";

/// The grid width of a 1280-pixel window with the sidebar: what the app
/// plans for at its default size.
const WIDTH: u32 = 1010;
/// Items the app loads per page.
const PAGE: usize = 100;
const MAP: iced::Rectangle = iced::Rectangle {
    x: 0.0,
    y: 0.0,
    width: 1000.0,
    height: 700.0,
};

#[derive(Debug, Clone)]
pub struct Config {
    /// Items in the synthetic library.
    pub count: usize,
    /// Located photos for the Places clustering.
    pub points: usize,
    /// Divides every repeat count, for a quick smoke run.
    pub quick: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            count: 50_000,
            points: 100_000,
            quick: false,
        }
    }
}

impl Config {
    fn repeats(&self, full: usize) -> usize {
        if self.quick { (full / 10).max(2) } else { full }
    }
}

/// One measured operation.
#[derive(Debug, Clone)]
pub struct Measure {
    pub name: String,
    pub samples: usize,
    pub p50: Duration,
    pub p95: Duration,
    pub max: Duration,
}

impl Measure {
    fn new(name: impl Into<String>, mut samples: Vec<Duration>) -> Self {
        samples.sort_unstable();
        let at = |q: f64| samples[((samples.len() - 1) as f64 * q).round() as usize];
        Self {
            name: name.into(),
            samples: samples.len(),
            p50: at(0.5),
            p95: at(0.95),
            max: *samples.last().unwrap_or(&Duration::ZERO),
        }
    }
}

/// Time `run` `repeats` times.
fn sample(repeats: usize, mut run: impl FnMut()) -> Vec<Duration> {
    (0..repeats.max(1))
        .map(|_| {
            let start = Instant::now();
            run();
            start.elapsed()
        })
        .collect()
}

/// Run every measurement.
pub fn run(config: &Config) -> Result<Vec<Measure>, String> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|e| e.to_string())?;
    let dir = tempfile::Builder::new()
        .prefix("proton-gallery-bench-")
        .tempdir()
        .map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    let (ratios, times) = runtime.block_on(catalog(config, dir.path(), &mut out))?;
    timeline(config, &ratios, &times, &mut out);
    clustering(config, &mut out);
    decoding(config, &mut out)?;
    Ok(out)
}

async fn open(dir: &std::path::Path) -> Result<App, String> {
    App::open(dir, Arc::new(TemporaryVault::default()))
        .await
        .map_err(|e| e.to_string())
}

/// What the app does when it shows the demo timeline: its outline, then the
/// records of the first page.
async fn first_page(app: &App) -> Result<proton_gallery_app_rs::Outline, String> {
    let outline = app
        .outline_with_visibility(View::Source("demo".into()), false, false)
        .await
        .map_err(|e| e.to_string())?;
    let items = app
        .outline_items(&outline, 0, PAGE)
        .await
        .map_err(|e| e.to_string())?;
    if items.len() != PAGE.min(outline.len()) {
        return Err(format!("first page held {} items", items.len()));
    }
    Ok(outline)
}

async fn catalog(
    config: &Config,
    dir: &std::path::Path,
    out: &mut Vec<Measure>,
) -> Result<(Vec<f32>, Vec<i64>), String> {
    {
        let app = open(dir).await?;
        let source = proton_gallery_rs::synthetic::SyntheticSource::new(config.count)
            .map_err(|e| e.to_string())?;
        app.index_source(
            &source,
            app.generation(),
            &proton_gallery_rs::CancellationToken::new(),
            |_| {},
        )
        .await
        .map_err(|e| e.to_string())?;
    }
    // A new App each time: the catalog is opened from disk (the OS cache is
    // warm, hence "cold-ish").
    let mut cold = Vec::new();
    for _ in 0..config.repeats(10) {
        let start = Instant::now();
        let app = open(dir).await?;
        first_page(&app).await?;
        cold.push(start.elapsed());
    }
    out.push(Measure::new("open catalog + first page (cold-ish)", cold));
    let app = open(dir).await?;
    let mut warm = Vec::new();
    let mut outline = None;
    for _ in 0..config.repeats(20) {
        let start = Instant::now();
        outline = Some(first_page(&app).await?);
        warm.push(start.elapsed());
    }
    out.push(Measure::new("outline + first page (warm)", warm));
    let outline = outline.ok_or("no outline")?;
    if outline.len() != config.count {
        return Err(format!(
            "the outline held {} of {} items",
            outline.len(),
            config.count
        ));
    }
    Ok((outline.ratios, outline.times))
}

fn timeline(config: &Config, ratios: &[f32], times: &[i64], out: &mut Vec<Measure>) {
    let mut layout = Layout::default();
    let plans = sample(config.repeats(20), || {
        layout = Layout::plan(ratios, times, WIDTH, 200);
    });
    out.push(Measure::new(
        format!("plan justified grid ({} px)", WIDTH),
        plans,
    ));
    // Scrolling: the rows and items near the viewport, at positions spread
    // over the whole timeline. Each sample is one call.
    let positions = config.repeats(20_000);
    let mut step = 0u64;
    let scrolls = sample(positions, || {
        step = step.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
        let top = layout.height * ((step >> 33) as f32 / (1u64 << 31) as f32);
        let rows = layout.visible(std::hint::black_box(top), 820.0);
        std::hint::black_box(layout.items_in(rows));
    });
    out.push(Measure::new("visible rows + items (per scroll)", scrolls));
}

/// `count` photos around a few hundred places, as a real library bunches
/// around home and trips.
pub fn located_photos(count: usize) -> Vec<LocatedPhoto> {
    let mut seed = 0x9e37_79b9_7f4a_7c15u64;
    let mut next = move || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        (seed >> 11) as f64 / (1u64 << 53) as f64
    };
    let spots: Vec<(f64, f64)> = (0..300)
        .map(|_| (next() * 120.0 - 55.0, next() * 340.0 - 170.0))
        .collect();
    (0..count)
        .map(|index| {
            // Most photos near the first few places.
            let spot = spots[((next().powi(3)) * spots.len() as f64) as usize];
            let latitude = (spot.0 + (next() - 0.5) * 0.4).clamp(-89.0, 89.0);
            let longitude = (spot.1 + (next() - 0.5) * 0.4).clamp(-179.0, 179.0);
            LocatedPhoto {
                index,
                name: format!("photo-{index}.jpg"),
                coordinates: Coordinates::new(latitude, longitude).expect("in range"),
                place: None,
            }
        })
        .collect()
}

fn clustering(config: &Config, out: &mut Vec<Measure>) {
    let photos = located_photos(config.points);
    let home = photos
        .first()
        .map_or(World { x: 0.5, y: 0.5 }, |p| p.coordinates.projected());
    for zoom in [1.0, 16.0, 256.0, 4096.0] {
        let center = if zoom == 1.0 {
            World { x: 0.5, y: 0.5 }
        } else {
            home
        };
        let camera = places::Camera::new(center, zoom);
        let mut bubbles = 0;
        let samples = sample(config.repeats(10), || {
            bubbles = places::bubbles(&photos, camera, MAP).len();
        });
        out.push(Measure::new(
            format!(
                "cluster {} points, zoom {zoom} ({bubbles} markers)",
                config.points
            ),
            samples,
        ));
    }
}

/// A photo-like JPEG: smooth gradients with some grain.
pub fn jpeg(width: u32, height: u32) -> Vec<u8> {
    let mut pixels = Vec::with_capacity((width * height * 3) as usize);
    let mut seed = 1u32;
    for y in 0..height {
        for x in 0..width {
            seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            let grain = (seed >> 28) as u8;
            pixels.extend_from_slice(&[
                ((x * 255 / width) as u8).wrapping_add(grain),
                ((y * 255 / height) as u8).wrapping_add(grain),
                (((x + y) * 127 / (width + height)) as u8 + 64).wrapping_add(grain),
            ]);
        }
    }
    let mut out = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 88)
        .encode(&pixels, width, height, image::ExtendedColorType::Rgb8)
        .expect("encode a JPEG");
    out
}

fn decoding(config: &Config, out: &mut Vec<Measure>) -> Result<(), String> {
    // A Proton grid thumbnail, and an original standing in for a missing one.
    let (small, large) = if config.quick {
        ((512, 384), (1024, 768))
    } else {
        ((512, 384), (4032, 3024))
    };
    for ((width, height), repeats) in [(small, 200), (large, 10)] {
        let bytes = jpeg(width, height);
        let mut failed = None;
        let samples = sample(config.repeats(repeats), || {
            if let Err(error) = thumbs::decode(&bytes, thumbs::THUMB_SIDE) {
                failed = Some(error);
            }
        });
        if let Some(error) = failed {
            return Err(error);
        }
        out.push(Measure::new(
            format!("decode {width}x{height} JPEG to tile"),
            samples,
        ));
    }
    // The shrink before an oversized picture is kept on disk.
    let bytes = jpeg(large.0, large.1);
    let mut failed = None;
    let samples = sample(config.repeats(10), || {
        if let Err(error) = proton_media_rs::render::thumbnail(
            &bytes,
            thumbs::THUMB_SIDE,
            85,
            proton_media_rs::render::DecodeLimits::default(),
        ) {
            failed = Some(error.to_string());
        }
    });
    if let Some(error) = failed {
        return Err(error);
    }
    out.push(Measure::new(
        format!("shrink {}x{} JPEG for disk cache", large.0, large.1),
        samples,
    ));
    Ok(())
}

fn human(duration: Duration) -> String {
    let nanos = duration.as_nanos();
    if nanos < 10_000 {
        format!("{nanos} ns")
    } else if nanos < 10_000_000 {
        format!("{:.1} µs", nanos as f64 / 1e3)
    } else {
        format!("{:.1} ms", nanos as f64 / 1e6)
    }
}

/// A plain-text table.
pub fn table(config: &Config, measures: &[Measure]) -> String {
    let width = measures.iter().map(|m| m.name.len()).max().unwrap_or(0);
    let mut out = format!(
        "proton-gallery bench: {} items, {} located photos{}\n{:width$}  {:>7}  {:>10}  {:>10}  {:>10}\n",
        config.count,
        config.points,
        if config.quick { " (quick)" } else { "" },
        "operation",
        "samples",
        "p50",
        "p95",
        "max",
    );
    for m in measures {
        out.push_str(&format!(
            "{:width$}  {:>7}  {:>10}  {:>10}  {:>10}\n",
            m.name,
            m.samples,
            human(m.p50),
            human(m.p95),
            human(m.max)
        ));
    }
    out
}

/// The same numbers as JSON, durations in microseconds.
pub fn json(config: &Config, measures: &[Measure]) -> serde_json::Value {
    let micros = |d: Duration| d.as_nanos() as f64 / 1e3;
    serde_json::json!({
        "count": config.count,
        "points": config.points,
        "quick": config.quick,
        "results": measures.iter().map(|m| serde_json::json!({
            "name": m.name,
            "samples": m.samples,
            "p50_us": micros(m.p50),
            "p95_us": micros(m.p95),
            "max_us": micros(m.max),
        })).collect::<Vec<_>>(),
    })
}

/// Parse the arguments after `--bench`.
pub fn parse(args: impl Iterator<Item = String>) -> Result<(Config, bool), String> {
    let mut config = Config::default();
    let mut as_json = false;
    let mut args = args.peekable();
    let number = |value: Option<String>| -> Result<usize, String> {
        let value = value.ok_or(USAGE)?;
        value.parse().map_err(|_| format!("Not a number: {value}"))
    };
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--count" => config.count = number(args.next())?,
            "--points" => config.points = number(args.next())?,
            "--quick" => config.quick = true,
            "--json" => as_json = true,
            "--help" | "-h" => return Err(USAGE.into()),
            other => return Err(format!("Unknown option: {other}\n{USAGE}")),
        }
    }
    if config.count == 0 || config.count > 1_000_000 {
        return Err("--count must be between 1 and 1,000,000".into());
    }
    Ok((config, as_json))
}

/// Run the benchmark from the command line; returns the exit code.
pub fn main(args: impl Iterator<Item = String>) -> i32 {
    let (config, as_json) = match parse(args) {
        Ok(parsed) => parsed,
        Err(message) => {
            eprintln!("{message}");
            return 2;
        }
    };
    match run(&config) {
        Ok(measures) if as_json => {
            println!("{:#}", json(&config, &measures));
            0
        }
        Ok(measures) => {
            print!("{}", table(&config, &measures));
            0
        }
        Err(error) => {
            eprintln!("benchmark failed: {error}");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_benchmark_runs_end_to_end_on_a_tiny_library() {
        let (config, as_json) = parse(
            ["--count", "250", "--points", "400", "--quick", "--json"]
                .into_iter()
                .map(String::from),
        )
        .unwrap();
        assert!(as_json);
        let measures = run(&config).unwrap();
        let names: Vec<&str> = measures.iter().map(|m| m.name.as_str()).collect();
        for expected in [
            "open catalog + first page",
            "outline + first page",
            "plan justified grid",
            "visible rows",
            "cluster 400 points, zoom 1",
            "cluster 400 points, zoom 4096",
            "decode 512x384",
            "shrink",
        ] {
            assert!(
                names.iter().any(|name| name.starts_with(expected)),
                "{expected} missing from {names:?}"
            );
        }
        for m in &measures {
            assert!(m.samples >= 2 && m.p50 <= m.p95 && m.p95 <= m.max, "{m:?}");
        }
        let json = json(&config, &measures);
        assert_eq!(json["results"].as_array().unwrap().len(), measures.len());
        assert!(table(&config, &measures).contains("p95"));
    }

    #[test]
    fn bad_arguments_are_refused() {
        let parse = |args: &[&str]| parse(args.iter().map(|a| a.to_string()));
        assert!(parse(&["--count", "lots"]).is_err());
        assert!(parse(&["--count", "0"]).is_err());
        assert!(parse(&["--frobnicate"]).is_err());
        assert_eq!(parse(&[]).unwrap().0.count, 50_000);
    }
}
