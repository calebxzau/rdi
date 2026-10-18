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

#[derive(Debug, Clone, Copy, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldCheckpointLimits {
    pub max_upload_bytes: u64,
    pub max_world_bytes: u64,
    pub max_journal_bytes: u64,
    pub max_file_bytes: u64,
    pub max_metadata_bytes: u64,
    pub max_world_entries: usize,
    pub max_path_bytes: usize,
    pub max_path_depth: usize,
}

impl Default for WorldCheckpointLimits {
    fn default() -> Self {
        Self {
            max_upload_bytes: 256 * 1024 * 1024,
            max_world_bytes: 8192 * 1024 * 1024,
            max_journal_bytes: 1024 * 1024 * 1024,
            max_file_bytes: 256 * 1024 * 1024,
            max_metadata_bytes: 32 * 1024 * 1024,
            max_world_entries: 100_000,
            max_path_bytes: 1024,
            max_path_depth: 64,
        }
    }
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
    pub world_checkpoint: WorldCheckpointLimits,
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
            world_checkpoint: WorldCheckpointLimits::default(),
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
                    config.world_checkpoint.max_upload_bytes = config.max_upload_bytes;
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
                "--max-world-mib" => {
                    let mib: u64 = args
                        .next()
                        .context("--max-world-mib requires a number")?
                        .parse()
                        .context("invalid world limit")?;
                    config.world_checkpoint.max_world_bytes = mib
                        .checked_mul(1024 * 1024)
                        .context("world limit overflow")?;
                }
                "--max-journal-mib" => {
                    let mib: u64 = args
                        .next()
                        .context("--max-journal-mib requires a number")?
                        .parse()
                        .context("invalid journal limit")?;
                    config.world_checkpoint.max_journal_bytes = mib
                        .checked_mul(1024 * 1024)
                        .context("journal limit overflow")?;
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
            "dm-master\n\nOptions:\n  --http-listen ADDR                HTTP listen address (default 0.0.0.0:15566)\n  --data-dir PATH                   persistent data directory (default ./data)\n  --max-upload-mib N                max world-init/checkpoint upload (default 256)\n  --sync-chunk-max-total N          max SyncChunk entries per host (default 256)\n  --max-world-mib N                 max playable checkpoint world (default 8192)\n  --max-journal-mib N               max unapplied checkpoint journal (default 1024)\n  --public-host HOST                public gateway host returned by /info\n  --tunnel-port PORT                gateway tunnel port (default 25566)"
        );
    }
}
