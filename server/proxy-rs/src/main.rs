use anyhow::Result;
use rdi_proxy_rs::{Config, run};
use tracing_subscriber::EnvFilter;

fn main() -> Result<()> {
    let Some(config) = Config::from_args_env(std::env::args())? else {
        println!("{}", Config::usage());
        return Ok(());
    };
    let workers = config.workers;
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(workers)
        .max_blocking_threads(2)
        .enable_all()
        .build()?
        .block_on(async move {
            let filter = std::env::var("RUST_LOG")
                .ok()
                .map(EnvFilter::new)
                .unwrap_or_else(|| EnvFilter::new("info"));
            tracing_subscriber::fmt().with_env_filter(filter).init();
            run(config).await
        })
}
