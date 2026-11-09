use std::path::PathBuf;

pub fn data_dir() -> PathBuf {
    std::env::var_os("RDI_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../ui/run"))
}
