use anyhow::{Context, Result, bail};
use std::{env, net::SocketAddr, path::PathBuf};

/// Hard limits applied to world-snapshot uploads and expanded directory snapshots.
///
/// Master enforces every value independently; the same numbers are published so that a
/// client can fail fast before spending a cycle building an oversized candidate.
#[derive(Debug, Clone, Copy, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldSnapshotLimits {
    /// Maximum size of a single uploaded (incremental) ZIP.
    pub max_upload_bytes: u64,
    /// Maximum total payload bytes in an upload or a complete directory snapshot.
    pub max_expanded_bytes: u64,
    /// Maximum uncompressed size of one WorldData file.
    pub max_file_bytes: u64,
    /// Maximum uncompressed size of one chunk NBT member.
    pub max_nbt_bytes: u64,
    /// Maximum uncompressed size of `metadata.json`.
    pub max_metadata_bytes: u64,
    /// Maximum number of WorldData files plus directories declared in one snapshot.
    pub max_world_entries: usize,
    /// Maximum UTF-8 length of one project-relative path.
    pub max_path_bytes: usize,
    /// Maximum number of segments in one project-relative path.
    pub max_path_depth: usize,
}

impl Default for WorldSnapshotLimits {
    fn default() -> Self {
        Self {
            max_upload_bytes: 256 * 1024 * 1024,
            max_expanded_bytes: 1024 * 1024 * 1024,
            max_file_bytes: 256 * 1024 * 1024,
            max_nbt_bytes: 64 * 1024 * 1024,
            max_metadata_bytes: 32 * 1024 * 1024,
            max_world_entries: 100_000,
            max_path_bytes: 1024,
            max_path_depth: 64,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Config {
    pub http_listen: SocketAddr,
    pub data_dir: PathBuf,
    pub max_upload_bytes: u64,
    pub sync_chunk_max_total: usize,
    pub world_snapshot: WorldSnapshotLimits,
    pub public_host: Option<String>,
    pub tunnel_port: u16,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            http_listen: "0.0.0.0:15566".parse().unwrap(),
            data_dir: PathBuf::from("./data"),
            max_upload_bytes: 256 * 1024 * 1024,
            sync_chunk_max_total: 256,
            world_snapshot: WorldSnapshotLimits::default(),
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
                "--sync-chunk-max-total" => {
                    config.sync_chunk_max_total = args
                        .next()
                        .context("--sync-chunk-max-total requires a number")?
                        .parse()
                        .context("invalid SyncChunk total limit")?;
                    if config.sync_chunk_max_total == 0 {
                        bail!("SyncChunk total limit must be greater than zero");
                    }
                }
                "--world-snapshot-upload-mib" => {
                    let mib: u64 = args
                        .next()
                        .context("--world-snapshot-upload-mib requires a number")?
                        .parse()
                        .context("invalid world snapshot upload limit")?;
                    let bytes = mib
                        .checked_mul(1024 * 1024)
                        .context("world snapshot upload limit overflow")?;
                    if bytes == 0 {
                        bail!("world snapshot upload limit must be greater than zero");
                    }
                    config.world_snapshot.max_upload_bytes = bytes;
                }
                "--world-snapshot-expanded-mib" => {
                    let mib: u64 = args
                        .next()
                        .context("--world-snapshot-expanded-mib requires a number")?
                        .parse()
                        .context("invalid world snapshot expansion limit")?;
                    let bytes = mib
                        .checked_mul(1024 * 1024)
                        .context("world snapshot expansion limit overflow")?;
                    if bytes == 0 {
                        bail!("world snapshot expansion limit must be greater than zero");
                    }
                    config.world_snapshot.max_expanded_bytes = bytes;
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
            "dm-master\n\nOptions:\n  --http-listen ADDR                HTTP listen address (default 0.0.0.0:15566)\n  --data-dir PATH                   persistent data directory (default ./data)\n  --max-upload-mib N                max world-init upload (default 256)\n  --sync-chunk-max-total N          max SyncChunk entries per host (default 256)\n  --world-snapshot-upload-mib N     max world snapshot upload (default 256)\n  --world-snapshot-expanded-mib N   max expanded world snapshot bytes (default 1024)\n  --public-host HOST                public gateway host returned by /info\n  --tunnel-port PORT                gateway tunnel port (default 25566)"
        );
    }
}
