//! What a tester can read and copy on the phone when no debugger is attached:
//! the latest warnings, and a step-by-step check of the way to Proton.
//!
//! Nothing here records credentials. Warnings carry request paths, status
//! codes and error causes only; see `pg_drive::auth::friendly`.
use crate::GalleryEngine;
use std::collections::VecDeque;
use std::fmt::Write as _;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Mutex, Once};
use std::time::{Duration, Instant};

const RECENT_LINES: usize = 30;
const LINE_LIMIT: usize = 800;
const HOST: &str = "drive-api.proton.me";
const STEP_TIMEOUT: Duration = Duration::from_secs(20);

static RECENT: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static INSTALL: Once = Once::new();
static PANIC_FILE: Mutex<Option<std::path::PathBuf>> = Mutex::new(None);

/// 0 = not started (host builds), 1 = ready, 2 = failed.
static VERIFIER: AtomicU8 = AtomicU8::new(0);

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) fn set_verifier_ready(ready: bool) {
    VERIFIER.store(if ready { 1 } else { 2 }, Ordering::Relaxed);
}

/// Keep warnings and errors in memory and pass every record on to logcat.
struct Recorder {
    #[cfg(target_os = "android")]
    logcat: android_logger::AndroidLogger,
}

impl log::Log for Recorder {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        // Info and above only: debug output includes TLS internals.
        metadata.level() <= log::Level::Info
    }

    fn log(&self, record: &log::Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        if record.level() <= log::Level::Warn {
            remember(format!(
                "{} {}: {}",
                record.level(),
                record.target(),
                record.args()
            ));
        }
        #[cfg(target_os = "android")]
        self.logcat.log(record);
    }

    fn flush(&self) {}
}

fn remember(mut line: String) {
    if line.len() > LINE_LIMIT {
        let mut end = LINE_LIMIT;
        while !line.is_char_boundary(end) {
            end -= 1;
        }
        line.truncate(end);
        line.push('…');
    }
    let mut recent = RECENT.lock().unwrap_or_else(|e| e.into_inner());
    if recent.len() == RECENT_LINES {
        recent.pop_front();
    }
    recent.push_back(line);
}

/// Route `log` records (and `tracing` events, through its "log" feature) to
/// the recorder. Safe to call more than once.
pub(crate) fn install() {
    INSTALL.call_once(|| {
        let recorder = Recorder {
            #[cfg(target_os = "android")]
            logcat: android_logger::AndroidLogger::new(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("pg-rust"),
            ),
        };
        if log::set_boxed_logger(Box::new(recorder)).is_ok() {
            log::set_max_level(log::LevelFilter::Info);
        }
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            record_panic(info);
            previous(info);
        }));
    });
}

/// Where the panic hook keeps the last panic, for the app's crash report on
/// the next start. The app shows it only after an unexpected exit.
pub(crate) fn set_panic_file(path: std::path::PathBuf) {
    *PANIC_FILE.lock().unwrap_or_else(|e| e.into_inner()) = Some(path);
}

fn record_panic(info: &std::panic::PanicHookInfo) {
    let thread = std::thread::current();
    let line = format!("thread '{}' {info}", thread.name().unwrap_or("unnamed"));
    log::error!("{line}");
    let path = PANIC_FILE.lock().unwrap_or_else(|e| e.into_inner()).clone();
    if let Some(path) = path {
        let _ = std::fs::write(path, line);
    }
}

fn recent() -> Vec<String> {
    RECENT
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .cloned()
        .collect()
}

fn elapsed(start: Instant) -> String {
    format!("{} ms", start.elapsed().as_millis())
}

/// Check each step to Proton on its own: name lookup, TCP, then a real API
/// request through the same client the app signs in with.
pub(crate) async fn connection_report() -> String {
    let mut out = String::new();
    let _ = writeln!(
        out,
        "Proton Gallery {} on {}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::ARCH
    );
    let _ = writeln!(out, "Client: {}", pg_drive::APP_VERSION);
    let _ = writeln!(
        out,
        "Certificate check: {}",
        match VERIFIER.load(Ordering::Relaxed) {
            1 => "ready",
            2 => "FAILED to start",
            _ => "not started",
        }
    );

    let start = Instant::now();
    let lookup = tokio::time::timeout(STEP_TIMEOUT, tokio::net::lookup_host((HOST, 443))).await;
    let addresses: Vec<std::net::SocketAddr> = match lookup {
        Ok(Ok(found)) => found.collect(),
        Ok(Err(error)) => {
            let _ = writeln!(out, "Name lookup: FAILED after {}: {error}", elapsed(start));
            Vec::new()
        }
        Err(_) => {
            let _ = writeln!(out, "Name lookup: TIMED OUT after {}", elapsed(start));
            Vec::new()
        }
    };
    if !addresses.is_empty() {
        let v4 = addresses.iter().filter(|a| a.is_ipv4()).count();
        let _ = writeln!(
            out,
            "Name lookup: {v4} IPv4, {} IPv6 in {}",
            addresses.len() - v4,
            elapsed(start)
        );
    }
    for family in ["IPv4", "IPv6"] {
        let Some(address) = addresses.iter().find(|a| a.is_ipv4() == (family == "IPv4")) else {
            continue;
        };
        let start = Instant::now();
        let result =
            tokio::time::timeout(STEP_TIMEOUT, tokio::net::TcpStream::connect(address)).await;
        let _ = match result {
            Ok(Ok(_)) => writeln!(out, "Connect {family}: ok in {}", elapsed(start)),
            Ok(Err(error)) => writeln!(out, "Connect {family}: FAILED: {error}"),
            Err(_) => writeln!(out, "Connect {family}: TIMED OUT after {}", elapsed(start)),
        };
    }

    let start = Instant::now();
    let config = pg_drive::client_configuration();
    let request = proton_sdk::http::get_unauthenticated::<serde_json::Value>(&config, "tests/ping");
    let _ = match tokio::time::timeout(Duration::from_secs(60), request).await {
        Ok(Ok(_)) => writeln!(out, "Proton API: ok in {}", elapsed(start)),
        Ok(Err(error)) => writeln!(
            out,
            "Proton API: FAILED after {}: {}",
            elapsed(start),
            pg_drive::auth::cause_chain(&error)
        ),
        Err(_) => writeln!(out, "Proton API: TIMED OUT after {}", elapsed(start)),
    };

    let lines = recent();
    let _ = writeln!(out, "\nRecent warnings ({}):", lines.len());
    if lines.is_empty() {
        let _ = writeln!(out, "none");
    }
    for line in lines {
        let _ = writeln!(out, "{line}");
    }
    out
}

#[uniffi::export]
impl GalleryEngine {
    /// A plain-text report a tester can copy. Takes up to about two minutes
    /// when every step times out.
    pub fn connection_report(&self) -> String {
        self.runtime.block_on(connection_report())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_only_the_latest_bounded_lines() {
        for i in 0..(RECENT_LINES + 5) {
            remember(format!("line {i}"));
        }
        remember("é".repeat(LINE_LIMIT));
        let lines = recent();
        assert_eq!(lines.len(), RECENT_LINES);
        assert!(!lines.iter().any(|l| l == "line 0"));
        // The other test may log meanwhile, so find the line rather than
        // assume it is last.
        let long = lines.iter().find(|l| l.starts_with('é')).unwrap();
        assert!(long.len() <= LINE_LIMIT + '…'.len_utf8());
        assert!(long.ends_with('…'));
    }

    #[test]
    fn a_panic_is_kept_for_the_next_crash_report() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("last-panic.txt");
        install();
        // Engines opened by parallel tests point the hook at their own folder;
        // try again until this panic lands here.
        for _ in 0..50 {
            set_panic_file(path.clone());
            let _ = std::thread::Builder::new()
                .name("panic-probe".into())
                .spawn(|| panic!("diagnostics-panic-marker"))
                .unwrap()
                .join();
            if path.is_file() {
                break;
            }
        }
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.contains("panic-probe"), "{text}");
        assert!(text.contains("diagnostics-panic-marker"), "{text}");
    }

    #[test]
    fn recorder_keeps_warnings_not_info() {
        install();
        log::info!("diagnostics-info-marker");
        log::warn!("diagnostics-warn-marker");
        let lines = recent();
        assert!(lines.iter().any(|l| l.contains("diagnostics-warn-marker")));
        assert!(!lines.iter().any(|l| l.contains("diagnostics-info-marker")));
    }
}

#[cfg(test)]
mod live {
    /// Reaches the real Proton API: `cargo test -p pg-mobile -- --ignored live`.
    #[test]
    #[ignore]
    fn live_connection_report() {
        let dir = tempfile::tempdir().unwrap();
        let engine = crate::GalleryEngine::open(dir.path().to_str().unwrap().into()).unwrap();
        let report = engine.connection_report();
        println!("{report}");
        assert!(report.contains("Proton API: ok"), "{report}");
    }

    /// A made-up account must fail as an account problem, not a network one.
    #[test]
    #[ignore]
    fn live_sign_in_reaches_proton() {
        let dir = tempfile::tempdir().unwrap();
        let engine = crate::GalleryEngine::open(dir.path().to_str().unwrap().into()).unwrap();
        let error = engine
            .sign_in("pg-nonexistent-user-zz".into(), "not-a-password".into())
            .unwrap_err()
            .to_string();
        println!("{error}\n{}", super::recent().join("\n"));
        assert!(!error.contains("reach Proton"), "{error}");
    }
}
