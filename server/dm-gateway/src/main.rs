mod config;
mod gateway;
mod protocol;
mod relay;
mod rooms;

use anyhow::Result;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("dm-gateway: {error:#}");
        std::process::exit(1);
    }
}

async fn run() -> Result<()> {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt().with_env_filter(filter).init();
    match config::parse_args()? {
        Some(config) => gateway::run(config).await,
        None => {
            config::print_usage();
            Ok(())
        }
    }
}
