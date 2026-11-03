use std::path::PathBuf;

use anyhow::{Context, Result};
use dm_master::{
    config::Config,
    http::{AppState, router},
};
use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::{EnvFilter, fmt, prelude::*};

const LOG_FILES_KEPT: usize = 14;

#[tokio::main]
async fn main() -> Result<()> {
    let _log_guard = init_logging()?;
    let Some(config) = Config::parse_args()? else {
        Config::usage();
        return Ok(());
    };
    let listen = config.http_listen;
    let state = AppState::new(config)?;
    let listener = tokio::net::TcpListener::bind(listen).await?;
    tracing::info!(%listen, "dm-master listening");
    axum::serve(listener, router(state))
        .with_graceful_shutdown(shutdown())
        .await?;
    Ok(())
}

async fn shutdown() {
    let _ = tokio::signal::ctrl_c().await;
}

/// Logs to the console and to a daily file under `DM_LOG_DIR` (default `logs`); keep the guard until exit.
fn init_logging() -> Result<WorkerGuard> {
    let dir = std::env::var_os("DM_LOG_DIR").map_or_else(|| PathBuf::from("logs"), PathBuf::from);
    let appender = RollingFileAppender::builder()
        .rotation(Rotation::DAILY)
        .filename_prefix("dm-master")
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
