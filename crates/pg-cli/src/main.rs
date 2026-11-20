use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow, bail};
use clap::{Parser, Subcommand};
use proton_drive_rs::ProtonPhotosClient;
use proton_drive_rs::{Node, ProtonDriveClient};
use proton_gallery_drive_rs::{ProtonFolderSource, photos::ProtonPhotosSource};
use proton_gallery_rs::bytes::{ObjectStore, RangeReader, RevisionDescriptor};
use proton_gallery_rs::catalog::Catalog;
use proton_gallery_rs::model::*;
use proton_gallery_rs::source::{BlockSource, MediaSource};
use proton_gallery_rs::synthetic::{SyntheticBlocks, SyntheticSource};
use proton_gallery_rs::{CancellationToken, indexer};
use proton_sdk::error::ProtonError;
use proton_sdk::ids::NodeUid;
use proton_sdk::session::{PasswordMode, ProtonApiSession};
use zeroize::Zeroizing;

#[derive(Parser)]
#[command(
    name = "proton-gallery",
    version,
    about = "Proton Gallery Rust core and headless diagnostics"
)]
struct Cli {
    /// App data directory. Defaults to the platform's local application-data path.
    #[arg(long, global = true)]
    data_dir: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Index a deterministic synthetic library without an account or network.
    Demo {
        #[arg(long, default_value_t = 10_000)]
        count: usize,
        #[arg(long, default_value_t = 0)]
        delay_ms: u64,
        /// Reindex from the beginning; otherwise resume the saved checkpoint.
        #[arg(long)]
        restart: bool,
    },
    /// Query a local page. This command never authenticates or calls Proton.
    List {
        #[arg(long)]
        source: Option<String>,
        #[arg(long, default_value_t = 100)]
        limit: usize,
        #[arg(long)]
        after: Option<String>,
    },
    /// List registered local sources.
    Sources,
    /// Show local catalog counts without contacting Proton.
    Stats,
    /// Measure local keyset page latency; does not contact Proton.
    Benchmark {
        #[arg(long, default_value_t = 200)]
        queries: usize,
        #[arg(long, default_value_t = 200)]
        limit: usize,
    },
    /// Exercise synthetic range access and durable offline blocks (not real media).
    OfflineDemo {
        /// Reopen a completed synthetic download with no remote source attached.
        #[arg(long)]
        read_only: bool,
        /// Pause after this many block completions to exercise restart/resume.
        #[arg(long)]
        interrupt_after: Option<u64>,
    },
    /// Sign in for this invocation and list child folders with their remote UIDs.
    DriveFolders {
        #[arg(long)]
        username: String,
        /// volume~node UID; defaults to My Files.
        #[arg(long)]
        folder: Option<String>,
    },
    /// Index direct media children of a Proton folder into the local catalog.
    ScanDrive {
        #[arg(long)]
        username: String,
        /// volume~node UID; defaults to My Files. Subfolders are not crawled yet.
        #[arg(long)]
        folder: Option<String>,
        #[arg(long)]
        restart: bool,
    },
    /// Index the normal Proton Photos timeline with capture dates.
    ScanPhotos {
        #[arg(long)]
        username: String,
        #[arg(long)]
        restart: bool,
    },
    /// Add a Drive folder as a gallery and index its nested albums.
    ScanGallery {
        #[arg(long)]
        username: String,
        #[arg(long)]
        folder: String,
        #[arg(long)]
        restart: bool,
    },
    /// Browse persisted Drive gallery roots, or one album's children.
    Albums {
        #[arg(long, requires = "parent")]
        gallery: Option<String>,
        #[arg(long, requires = "gallery")]
        parent: Option<String>,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let root = match cli.data_dir {
        Some(path) => path,
        None => directories::ProjectDirs::from("io", "narl", "proton-gallery")
            .context("cannot determine application data directory; use --data-dir")?
            .data_local_dir()
            .to_owned(),
    };
    let catalog = Catalog::open(root.join("catalog.db")).await?;
    let cancel = CancellationToken::new();
    let signal = cancel.clone();
    let signal_task = tokio::spawn(async move {
        if tokio::signal::ctrl_c().await.is_ok() {
            signal.cancel();
        }
    });
    let result = execute(cli.command, &root, catalog, &cancel).await;
    signal_task.abort();
    result
}

async fn execute(
    command: Command,
    root: &Path,
    catalog: Catalog,
    cancel: &CancellationToken,
) -> Result<()> {
    match command {
        Command::Demo {
            count,
            delay_ms,
            restart,
        } => {
            let source = SyntheticSource::new(count)?.with_delay(Duration::from_millis(delay_ms));
            let started = Instant::now();
            let checkpoint = indexer::scan(&catalog, &source, restart, cancel, |progress| {
                if progress.indexed == 100
                    || progress.indexed.is_multiple_of(1000)
                    || progress.complete
                {
                    eprintln!(
                        "Indexed {} media items{}",
                        progress.indexed,
                        if progress.complete { " (complete)" } else { "" }
                    );
                }
            })
            .await?;
            println!(
                "{}",
                serde_json::json!({ "source": source.spec().id, "indexed": checkpoint.indexed, "complete": checkpoint.complete, "elapsed_ms": started.elapsed().as_millis() })
            );
        }
        Command::List {
            source,
            limit,
            after,
        } => {
            let page = catalog
                .page(
                    source.map(SourceId::new).transpose()?,
                    after.as_deref().map(TimelineCursor::decode).transpose()?,
                    PageSize::new(limit)?,
                )
                .await?;
            println!(
                "{}",
                serde_json::to_string_pretty(
                    &serde_json::json!({ "items": page.items, "next": page.next.map(|cursor| cursor.encode()).transpose()? })
                )?
            );
        }
        Command::Sources => println!(
            "{}",
            serde_json::to_string_pretty(&catalog.sources().await?)?
        ),
        Command::Stats => println!("{}", serde_json::to_string_pretty(&catalog.stats().await?)?),
        Command::Benchmark { queries, limit } => {
            if !(1..=10_000).contains(&queries) {
                bail!("queries must be 1..=10000");
            }
            let size = PageSize::new(limit)?;
            let mut cursor = None;
            let mut samples = Vec::with_capacity(queries);
            let mut rows = 0usize;
            for _ in 0..queries {
                let started = Instant::now();
                let page = catalog.page(None, cursor, size).await?;
                samples.push(started.elapsed().as_micros());
                rows += page.items.len();
                cursor = page.next;
            }
            samples.sort_unstable();
            let percentile =
                |percent: usize| samples[(samples.len() * percent).div_ceil(100).saturating_sub(1)];
            println!(
                "{}",
                serde_json::json!({
                    "queries": queries, "page_size": size.get(), "rows_read": rows,
                    "p50_us": percentile(50), "p95_us": percentile(95), "p99_us": percentile(99),
                    "maximum_us": samples.last(), "assets": catalog.stats().await?.assets,
                    "scope": "local catalog query and DTO construction; current build profile and host",
                })
            );
        }
        Command::OfflineDemo {
            read_only,
            interrupt_after,
        } => {
            offline_demo(root, &catalog, read_only, interrupt_after, cancel).await?;
        }
        Command::Albums { gallery, parent } => {
            let albums = catalog
                .albums(
                    gallery.map(SourceId::new).transpose()?,
                    parent.map(SourceId::new).transpose()?,
                )
                .await?;
            println!("{}", serde_json::to_string(&albums)?);
        }
        Command::ScanPhotos { username, restart } => {
            let (client, account) = sign_in(&username, cancel).await?;
            let photos = ProtonPhotosClient::from_drive_client(client);
            let root = cancellable(cancel, async {
                photos.get_photos_root().await.map_err(proton_failure)
            })
            .await?
            .context("this account has no Proton Photos library")?;
            let source = ProtonPhotosSource::new(photos, account, &root)?;
            eprintln!(
                "SDK 0.12 collects timeline IDs before metadata pages; Photos streaming is not enabled."
            );
            let checkpoint = indexer::scan(&catalog, &source, restart, cancel, |p| {
                eprintln!("Indexed {} Photos items", p.indexed)
            })
            .await?;
            println!(
                "{}",
                serde_json::json!({"source":source.spec().id,"indexed":checkpoint.indexed,"complete":checkpoint.complete})
            );
        }
        Command::ScanGallery {
            username,
            folder,
            restart,
        } => {
            let (client, account) = sign_in(&username, cancel).await?;
            let root = find_folder(&client, Some(&folder), cancel).await?;
            let gallery = proton_gallery_drive_rs::galleries::scan_gallery(
                &catalog,
                client,
                account,
                root,
                restart,
                cancel,
                |source, checkpoint| {
                    eprintln!("{}: {} media items", source.label, checkpoint.indexed)
                },
            )
            .await?;
            println!("{}", serde_json::json!({"gallery":gallery,"complete":true}));
        }
        Command::DriveFolders { username, folder } => {
            let (client, _) = sign_in(&username, cancel).await?;
            let folder = find_folder(&client, folder.as_deref(), cancel).await?;
            let ids = cancellable(cancel, async {
                client
                    .enumerate_folder_children_node_uids(&folder.uid)
                    .await
                    .map_err(proton_failure)
            })
            .await?;
            for batch in ids.chunks(PageSize::MAX) {
                let nodes = cancellable(cancel, async {
                    client.enumerate_nodes(batch).await.map_err(proton_failure)
                })
                .await?;
                for node in nodes
                    .into_iter()
                    .filter(|node| node.is_folder() && !node.trashed)
                {
                    println!(
                        "{}",
                        serde_json::json!({ "uid": node.uid.to_string(), "name": node.name })
                    );
                }
            }
        }
        Command::ScanDrive {
            username,
            folder,
            restart,
        } => {
            let (client, account) = sign_in(&username, cancel).await?;
            let folder = find_folder(&client, folder.as_deref(), cancel).await?;
            let source = ProtonFolderSource::new(client, account, &folder)?;
            eprintln!(
                "SDK 0.12 first collects child IDs; metadata is then committed in bounded pages. Direct children only."
            );
            let checkpoint = indexer::scan(&catalog, &source, restart, cancel, |progress| {
                eprintln!(
                    "Indexed {} media items{}",
                    progress.indexed,
                    if progress.complete { " (complete)" } else { "" }
                );
            })
            .await?;
            println!(
                "{}",
                serde_json::json!({ "source": source.spec().id, "indexed": checkpoint.indexed, "complete": checkpoint.complete })
            );
        }
    }
    Ok(())
}

async fn sign_in(
    username: &str,
    cancel: &CancellationToken,
) -> Result<(ProtonDriveClient, AccountId)> {
    eprintln!(
        "This development CLI keeps the session in memory for this invocation. Passwords and tokens are never written to the catalog."
    );
    let password = Zeroizing::new(rpassword::prompt_password("Proton password: ")?);
    let mut session = cancellable(cancel, async {
        ProtonApiSession::begin(
            proton_gallery_drive_rs::client_configuration(),
            username,
            password.as_bytes(),
        )
        .await
        .map_err(proton_failure)
    })
    .await?;
    if session.is_waiting_for_second_factor() {
        if !session.second_factor_methods().totp {
            bail!(
                "this CLI supports authenticator codes; a security-key-only account needs the future graphical sign-in flow"
            );
        }
        let code = Zeroizing::new(rpassword::prompt_password("Authenticator code: ")?);
        cancellable(cancel, async {
            session
                .apply_second_factor_code(code.trim())
                .await
                .map_err(proton_failure)
        })
        .await?;
    }
    let mailbox = match session.password_mode() {
        PasswordMode::Single => Zeroizing::new(password.as_bytes().to_vec()),
        PasswordMode::Dual => {
            Zeroizing::new(rpassword::prompt_password("Mailbox/data password: ")?.into_bytes())
        }
    };
    let account = AccountId::new(session.user_id().as_str())?;
    let client = ProtonDriveClient::new(&session, mailbox.to_vec());
    cancellable(cancel, async {
        client.account().unlock().await.map_err(proton_failure)
    })
    .await?;
    Ok((client, account))
}

fn proton_failure(error: ProtonError) -> anyhow::Error {
    match error {
        ProtonError::Api(api) if api.is_human_verification_required() => anyhow!(
            "Proton requires human verification; CAPTCHA handling is not implemented in this development CLI"
        ),
        ProtonError::KeysLocked => {
            anyhow!("account keys could not be unlocked; check the mailbox/data password")
        }
        ProtonError::Transport(_) => {
            anyhow!("Proton connection failed; check connectivity and retry")
        }
        ProtonError::Api(api) => anyhow!(
            "Proton rejected the request (HTTP {}); check sign-in, permissions, or retry later",
            api.http_status
        ),
        ProtonError::Crypto(_) => anyhow!("Proton cryptographic operation failed"),
        _ => anyhow!("Proton operation failed"),
    }
}

async fn find_folder(
    client: &ProtonDriveClient,
    uid: Option<&str>,
    cancel: &CancellationToken,
) -> Result<Node> {
    let node = match uid {
        None => {
            cancellable(cancel, async {
                client.get_my_files_folder().await.map_err(proton_failure)
            })
            .await?
        }
        Some(uid) => {
            let (volume, link) = uid
                .split_once('~')
                .context("folder UID must be volume~node")?;
            if volume.is_empty() || link.is_empty() || link.contains('~') {
                bail!("folder UID must be volume~node");
            }
            let uid = NodeUid::new(volume.into(), link.into());
            cancellable(cancel, async {
                client.get_node(&uid).await.map_err(proton_failure)
            })
            .await?
            .context("folder was not found")?
        }
    };
    if !node.is_folder() || node.trashed {
        bail!("select an active folder");
    }
    Ok(node)
}

async fn cancellable<T>(
    cancel: &CancellationToken,
    work: impl std::future::Future<Output = Result<T>>,
) -> Result<T> {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => Err(proton_gallery_rs::Error::Cancelled.into()),
        result = work => result,
    }
}

async fn offline_demo(
    root: &Path,
    catalog: &Catalog,
    read_only: bool,
    interrupt_after: Option<u64>,
    cancel: &CancellationToken,
) -> Result<()> {
    // Public fixture key, used ONLY for patterned synthetic bytes. Never use this
    // namespace or key for real media. Platform key wrapping is a later milestone.
    let store = ObjectStore::open(root.join("synthetic-blocks"), [0x47; 32]).await?;
    let descriptor = RevisionDescriptor::new(
        SyntheticSource::new(1)?.asset(0)?.key,
        RevisionId::new("synthetic-blocks-v1")?,
        vec![65_537, 131_071, 32_771, 262_139],
    )?;
    let remote = Arc::new(SyntheticBlocks::new(descriptor.clone()));
    let reader = if read_only {
        if !catalog.retained().await?.contains(&descriptor) {
            bail!("run offline-demo without --read-only to complete a synthetic download first");
        }
        RangeReader::new(descriptor.clone(), store, None)?
    } else {
        let reader = RangeReader::new(
            descriptor.clone(),
            store,
            Some(remote.clone() as Arc<dyn BlockSource>),
        )?;
        let first = reader.read_at(65_530, 30, cancel).await?;
        if first != SyntheticBlocks::expected(65_530, 30) {
            bail!("synthetic seek validation failed");
        }
        let mut completed_blocks = 0u64;
        let result = reader
            .keep_offline(catalog, cancel, |done, total| {
                completed_blocks += 1;
                eprintln!("Retained {done}/{total} synthetic bytes");
                if interrupt_after.is_some_and(|limit| completed_blocks >= limit) {
                    cancel.cancel();
                }
            })
            .await;
        if matches!(result, Err(proton_gallery_rs::Error::Cancelled)) {
            println!(
                "{}",
                serde_json::json!({ "complete": false, "paused": true, "remote_blocks_fetched": remote.calls() })
            );
            return Ok(());
        }
        result?;
        reader
    };
    for (offset, length) in [
        (0, 100),
        (65_530, 30),
        (200_000, 1024),
        (descriptor.length() - 20, 100),
    ] {
        let bytes = reader.read_at(offset, length, cancel).await?;
        let expected = length.min(descriptor.length() - offset) as usize;
        if bytes != SyntheticBlocks::expected(offset, expected) {
            bail!("synthetic offline read validation failed");
        }
    }
    println!(
        "{}",
        serde_json::json!({ "complete": true, "offline_only": read_only, "length": descriptor.length(), "remote_blocks_fetched": remote.calls(), "validated_ranges": 4 })
    );
    Ok(())
}
