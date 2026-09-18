use anyhow::Result;
use dm_master::{
    config::Config,
    http::{AppState, router},
};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .init();
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
