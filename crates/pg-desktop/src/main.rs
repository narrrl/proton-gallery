//! Proton Gallery for Linux and Windows.
//!
//! `proton-gallery` opens the signed-in account's Photos timeline.
//! `proton-gallery --demo [COUNT]` browses a synthetic library of COUNT items
//! (100,000 by default) in a throwaway directory, without an account.
mod captcha;
mod design;
mod device_auth;
mod gallery;
mod grid;
mod metrics;
mod thumbs;
mod vault;
#[cfg(target_os = "linux")]
mod video;

use gallery::{Gallery, Options};

const USAGE: &str = "Usage: proton-gallery [--demo [COUNT]] [--data-dir DIR]";

fn options() -> Result<Options, String> {
    let mut options = Options {
        demo: None,
        data_dir: None,
    };
    let mut args = std::env::args().skip(1).peekable();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--demo" => {
                let count = match args.next_if(|next| !next.starts_with("--")) {
                    Some(count) => count
                        .parse()
                        .map_err(|_| format!("Not a number: {count}"))?,
                    None => 100_000,
                };
                options.demo = Some(count);
            }
            "--data-dir" => options.data_dir = Some(args.next().ok_or(USAGE)?.into()),
            "--help" | "-h" => return Err(USAGE.into()),
            other => return Err(format!("Unknown option: {other}\n{USAGE}")),
        }
    }
    Ok(options)
}

/// Only this RAII guard owns a directory; explicit --data-dir is never removed.
fn prepare_demo(options: &mut Options) -> std::io::Result<Option<tempfile::TempDir>> {
    if options.demo.is_some() && options.data_dir.is_none() {
        let dir = tempfile::Builder::new()
            .prefix("proton-gallery-demo-")
            .tempdir()?;
        options.data_dir = Some(dir.path().to_owned());
        Ok(Some(dir))
    } else {
        Ok(None)
    }
}

fn main() -> iced::Result {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_env("PG_LOG").unwrap_or_else(|_| "warn".into()),
        )
        .with_ansi(false)
        .with_writer(std::io::stderr)
        .init();
    let mut options = match options() {
        Ok(options) => options,
        Err(message) => {
            eprintln!("{message}");
            std::process::exit(2);
        }
    };
    let _demo_dir = prepare_demo(&mut options).expect("create demo directory");
    let window = iced::window::Settings {
        size: (1280.0, 820.0).into(),
        icon: iced::window::icon::from_file_data(
            include_bytes!("../../../images/icon-512.png"),
            None,
        )
        .ok(),
        #[cfg(target_os = "linux")]
        platform_specific: iced::window::settings::PlatformSpecific {
            application_id: "io.narl.proton-gallery".into(),
            ..Default::default()
        },
        ..Default::default()
    };
    iced::application(
        move || Gallery::boot(options.clone()),
        Gallery::update,
        Gallery::view,
    )
    .title(Gallery::title)
    .theme(Gallery::theme)
    .subscription(Gallery::subscription)
    .scale_factor(Gallery::scale_factor)
    .window(window)
    .run()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn explicit_demo_directories_are_preserved_and_only_generated_directories_are_removed() {
        let supplied = tempfile::tempdir().unwrap();
        let sentinel = supplied.path().join("keep-me");
        std::fs::write(&sentinel, "user data").unwrap();
        let mut options = Options {
            demo: Some(100),
            data_dir: Some(supplied.path().into()),
        };
        assert!(prepare_demo(&mut options).unwrap().is_none());
        assert!(sentinel.exists());
        let mut generated = Options {
            demo: Some(100),
            data_dir: None,
        };
        let guard = prepare_demo(&mut generated).unwrap().unwrap();
        let path = generated.data_dir.unwrap();
        assert!(path.exists());
        drop(guard);
        assert!(!path.exists());
        assert!(sentinel.exists());
    }
}
