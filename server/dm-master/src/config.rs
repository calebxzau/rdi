use anyhow::{Context, Result, bail};
use std::{env, net::SocketAddr, path::PathBuf};

#[derive(Debug, Clone)]
pub struct Config {
    pub http_listen: SocketAddr,
    pub data_dir: PathBuf,
    pub max_upload_bytes: u64,
    pub public_host: Option<String>,
    pub tunnel_port: u16,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            http_listen: "0.0.0.0:15566".parse().unwrap(),
            data_dir: PathBuf::from("./data"),
            max_upload_bytes: 256 * 1024 * 1024,
            public_host: None,
            tunnel_port: 25566,
        }
    }
}

impl Config {
    pub fn parse_args() -> Result<Option<Self>> {
        let mut config = Self::default();
        let mut args = env::args().skip(1);
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "-h" | "--help" => return Ok(None),
                "--http-listen" => {
                    config.http_listen = args
                        .next()
                        .context("--http-listen requires an address")?
                        .parse()
                        .context("invalid HTTP listen address")?
                }
                "--data-dir" => {
                    config.data_dir =
                        PathBuf::from(args.next().context("--data-dir requires a path")?)
                }
                "--max-upload-mib" => {
                    let mib: u64 = args
                        .next()
                        .context("--max-upload-mib requires a number")?
                        .parse()
                        .context("invalid upload limit")?;
                    config.max_upload_bytes = mib
                        .checked_mul(1024 * 1024)
                        .context("upload limit overflow")?;
                }
                "--public-host" => {
                    config.public_host = Some(args.next().context("--public-host requires a host")?)
                }
                "--tunnel-port" => {
                    config.tunnel_port = args
                        .next()
                        .context("--tunnel-port requires a port")?
                        .parse()
                        .context("invalid tunnel port")?
                }
                other => bail!("unknown option '{other}' (use --help for usage)"),
            }
        }
        Ok(Some(config))
    }
    pub fn usage() {
        println!(
            "dm-master\n\nOptions:\n  --http-listen ADDR       HTTP listen address (default 0.0.0.0:15566)\n  --data-dir PATH          persistent data directory (default ./data)\n  --max-upload-mib N       max world-init upload (default 256)\n  --public-host HOST       public gateway host returned by /info\n  --tunnel-port PORT       gateway tunnel port (default 25566)"
        );
    }
}
