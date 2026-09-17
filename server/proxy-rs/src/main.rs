use anyhow::Result;
use rdi_proxy_rs::{Config, run};
use tracing_subscriber::EnvFilter;

fn main() -> Result<()> {
    let Some(config) = Config::from_args_env(std::env::args())? else {
        println!("{}", Config::usage());
        return Ok(());
    };
    let filter = std::env::var("RUST_LOG")
        .ok()
        .map(EnvFilter::new)
        .unwrap_or_else(|| EnvFilter::new("info"));
    let (writer, _log_guard) = tracing_appender::non_blocking::NonBlockingBuilder::default()
        .buffered_lines_limit(4096)
        .lossy(true)
        .finish(std::io::stdout());
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_writer(writer)
        .init();
    let workers = config.workers;
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(workers)
        .max_blocking_threads(2)
        .enable_all()
        .build()?
        .block_on(async move { run(config).await })
}
