mod config;
mod gateway;
mod protocol;
mod relay;
mod rooms;

use std::path::PathBuf;

use anyhow::{Context, Result};
use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::{EnvFilter, fmt, prelude::*};

const LOG_FILES_KEPT: usize = 14;

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("dm-gateway: {error:#}");
        std::process::exit(1);
    }
}

async fn run() -> Result<()> {
    let _log_guard = init_logging()?;
    match config::parse_args()? {
        Some(config) => gateway::run(config).await,
        None => {
            config::print_usage();
            Ok(())
        }
    }
}

/// Logs to the console and to a daily file under `DM_LOG_DIR` (default `logs`); keep the guard until exit.
fn init_logging() -> Result<WorkerGuard> {
    let dir = std::env::var_os("DM_LOG_DIR").map_or_else(|| PathBuf::from("logs"), PathBuf::from);
    let appender = RollingFileAppender::builder()
        .rotation(Rotation::DAILY)
        .filename_prefix("dm-gateway")
        .filename_suffix("log")
        .max_log_files(LOG_FILES_KEPT)
        .build(&dir)
        .with_context(|| format!("failed to open log directory {}", dir.display()))?;
    let (file, guard) = tracing_appender::non_blocking(appender);
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::registry()
        .with(filter)
        .with(fmt::layer())
        .with(fmt::layer().with_ansi(false).with_writer(file))
        .init();
    Ok(guard)
}
