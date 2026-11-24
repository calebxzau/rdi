#![allow(dead_code)]

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::fs;
use std::path::{Path, PathBuf};

use rdi_anvilrw::compress::{self, chunk_compression};
use rdi_anvilrw::{RemapError, tag};
use uuid::Uuid;

pub fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("fixtures")
        .join("kotlin")
}

pub fn read_json(path: &Path) -> serde_json::Value {
    serde_json::from_slice(&fs::read(path).unwrap_or_else(|e| panic!("{}: {e}", path.display())))
        .expect("valid JSON")
}

pub fn uuid(text: &str) -> Uuid {
    Uuid::parse_str(text).expect("valid UUID")
}

pub fn mappings(file: &Path) -> HashMap<String, HashMap<Uuid, Uuid>> {
    let json = read_json(file);
    json.as_object()
        .expect("object")
        .iter()
        .map(|(name, mapping)| {
            let mapping = mapping
                .as_object()
                .expect("object")
                .iter()
                .map(|(k, v)| (uuid(k), uuid(v.as_str().unwrap())))
                .collect();
            (name.clone(), mapping)
        })
        .collect()
}

/// Kotlin exception names that a Rust `RemapError::Io` stands for.
const IO_FAMILY: &[&str] = &[
    "IOException",
    "ZipException",
    "EOFException",
    "UTFDataFormatException",
    "FileNotFoundException",
    "NoSuchFileException",
    "ZstdIOException",
];

/// True if `error` corresponds to the Kotlin exception class `kotlin`.
pub fn same_error(kotlin: &str, error: &RemapError) -> bool {
    error.kind_name() == kotlin
        || (matches!(error, RemapError::Io { .. }) && IO_FAMILY.contains(&kotlin))
}

pub fn counts_json(counts: &HashMap<Uuid, usize>) -> serde_json::Value {
    let sorted: BTreeMap<String, usize> = counts.iter().map(|(k, v)| (k.to_string(), *v)).collect();
    serde_json::to_value(sorted).unwrap()
}

// ---- Region comparison ----

#[derive(Debug, PartialEq)]
pub struct RawChunk {
    pub type_byte: u8,
    pub timestamp: i32,
    pub external: bool,
    pub compressed: Vec<u8>,
}

impl RawChunk {
    pub fn nbt(&self) -> Vec<u8> {
        compress::decompress_chunk(
            self.type_byte & 0x7f,
            &self.compressed,
            usize::MAX / 2,
            "test",
        )
        .expect("decompressible")
    }
}

fn be32(bytes: &[u8], at: usize) -> i32 {
    i32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

/// Reads every chunk of a region file written by either implementation.
pub fn read_region(path: &Path) -> BTreeMap<usize, RawChunk> {
    let bytes = fs::read(path).unwrap();
    let name = path.file_name().unwrap().to_str().unwrap();
    let parts: Vec<&str> = name.split('.').collect();
    let (rx, rz): (i32, i32) = (parts[1].parse().unwrap(), parts[2].parse().unwrap());
    let mut chunks = BTreeMap::new();
    if bytes.len() < 8192 {
        return chunks;
    }
    for i in 0..1024 {
        let location = be32(&bytes, i * 4) as u32;
        if location == 0 {
            continue;
        }
        // Source files of failure cases are corrupt on purpose: skip what cannot be read.
        let start = (location >> 8) as usize * 4096;
        if start + 5 > bytes.len() {
            continue;
        }
        let length = be32(&bytes, start);
        let type_byte = bytes[start + 4];
        let external = type_byte & 0x80 != 0;
        let compressed = if external {
            let x = rx * 32 + (i & 31) as i32;
            let z = rz * 32 + (i >> 5) as i32;
            let Ok(bytes) = fs::read(path.with_file_name(format!("c.{x}.{z}.mcc"))) else {
                continue;
            };
            bytes
        } else {
            if length < 1 || start + 4 + length as usize > bytes.len() {
                continue;
            }
            bytes[start + 5..start + 4 + length as usize].to_vec()
        };
        chunks.insert(
            i,
            RawChunk {
                type_byte,
                timestamp: be32(&bytes, 4096 + i * 4),
                external,
                compressed,
            },
        );
    }
    chunks
}

/// Compares two region files chunk by chunk: same chunks, type bytes, timestamps and NBT. Chunks
/// Kotlin copied byte-for-byte from `source` must be byte-for-byte copies in Rust too.
pub fn assert_same_region(kotlin: &Path, rust: &Path, source: Option<&Path>, context: &str) {
    let expected = read_region(kotlin);
    let actual = read_region(rust);
    let original = source.map(read_region).unwrap_or_default();
    assert_eq!(
        expected.keys().collect::<Vec<_>>(),
        actual.keys().collect::<Vec<_>>(),
        "{context}: chunk indexes"
    );
    for (index, want) in &expected {
        let got = &actual[index];
        assert_eq!(
            want.type_byte, got.type_byte,
            "{context}: type of chunk {index}"
        );
        assert_eq!(
            want.timestamp, got.timestamp,
            "{context}: timestamp of chunk {index}"
        );
        assert_eq!(want.nbt(), got.nbt(), "{context}: NBT of chunk {index}");
        if original
            .get(index)
            .is_some_and(|o| o.compressed == want.compressed)
        {
            assert_eq!(
                want.compressed, got.compressed,
                "{context}: unchanged chunk {index} must keep its bytes"
            );
        }
    }
}

/// Every file below `root`, as `/`-separated relative paths.
pub fn tree(root: &Path) -> BTreeSet<String> {
    let mut files = BTreeSet::new();
    if !root.exists() {
        return files;
    }
    let mut pending = vec![root.to_path_buf()];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                pending.push(path);
            } else {
                let relative: Vec<String> = path
                    .strip_prefix(root)
                    .unwrap()
                    .components()
                    .map(|c| c.as_os_str().to_string_lossy().into_owned())
                    .collect();
                files.insert(relative.join("/"));
            }
        }
    }
    files
}

fn is_gzip(bytes: &[u8]) -> bool {
    bytes.len() >= 2 && bytes[0] == 0x1f && bytes[1] == 0x8b
}

/// Compares two output trees: the same files; region files chunk by chunk, gzip files that both
/// sides could decompress by content, everything else byte for byte. Gzip and zlib bytes differ
/// between Java's zlib and Rust's miniz, so compressed bytes are only compared when Kotlin kept them.
pub fn assert_same_tree(kotlin: &Path, rust: &Path, source: Option<&Path>, context: &str) {
    let expected = tree(kotlin);
    let actual = tree(rust);
    assert_eq!(expected, actual, "{context}: file list");
    for relative in &expected {
        let want_path = kotlin.join(relative);
        let got_path = rust.join(relative);
        let label = format!("{context}: {relative}");
        if relative.ends_with(".mca") {
            let source_region = source.map(|s| s.join(relative)).filter(|p| p.exists());
            assert_same_region(&want_path, &got_path, source_region.as_deref(), &label);
            continue;
        }
        if relative.ends_with(".mcc") && is_chunk_file(relative) {
            // Compared through the region that owns it; raw bytes may differ after recompression.
            continue;
        }
        let want = fs::read(&want_path).unwrap();
        let got = fs::read(&got_path).unwrap();
        if want == got {
            continue;
        }
        // Kotlin may have rewritten a file into the same bytes it had; content decides.
        assert!(is_gzip(&want) && is_gzip(&got), "{label}: bytes differ");
        let want_content = compress::gunzip(&want, usize::MAX / 2, "kotlin").unwrap();
        let got_content = compress::gunzip(&got, usize::MAX / 2, "rust").unwrap();
        assert_eq!(
            want_content, got_content,
            "{label}: decompressed content differs"
        );
    }
}

fn is_chunk_file(relative: &str) -> bool {
    let name = relative.rsplit('/').next().unwrap();
    name.starts_with("c.") && name.matches('.').count() == 3
}

pub fn copy_tree(from: &Path, to: &Path) {
    fs::create_dir_all(to).unwrap();
    for relative in tree(from) {
        let destination = to.join(&relative);
        fs::create_dir_all(destination.parent().unwrap()).unwrap();
        fs::copy(from.join(&relative), destination).unwrap();
    }
}

// ---- NBT builder for unit tests ----

#[derive(Debug, Clone, PartialEq)]
pub enum Tag {
    Byte(i8),
    Int(i32),
    Long(i64),
    Double(f64),
    ByteArray(Vec<u8>),
    String(String),
    List(u8, Vec<Tag>),
    Compound(Vec<(String, Tag)>),
    IntArray(Vec<i32>),
    LongArray(Vec<i64>),
}

impl Tag {
    pub fn kind(&self) -> u8 {
        match self {
            Tag::Byte(_) => tag::BYTE,
            Tag::Int(_) => tag::INT,
            Tag::Long(_) => tag::LONG,
            Tag::Double(_) => tag::DOUBLE,
            Tag::ByteArray(_) => tag::BYTE_ARRAY,
            Tag::String(_) => tag::STRING,
            Tag::List(..) => tag::LIST,
            Tag::Compound(_) => tag::COMPOUND,
            Tag::IntArray(_) => tag::INT_ARRAY,
            Tag::LongArray(_) => tag::LONG_ARRAY,
        }
    }

    fn write_payload(&self, out: &mut Vec<u8>) {
        match self {
            Tag::Byte(v) => out.push(*v as u8),
            Tag::Int(v) => out.extend_from_slice(&v.to_be_bytes()),
            Tag::Long(v) => out.extend_from_slice(&v.to_be_bytes()),
            Tag::Double(v) => out.extend_from_slice(&v.to_be_bytes()),
            Tag::ByteArray(v) => {
                out.extend_from_slice(&(v.len() as i32).to_be_bytes());
                out.extend_from_slice(v);
            }
            Tag::String(v) => write_string(out, v),
            Tag::List(kind, items) => {
                out.push(*kind);
                out.extend_from_slice(&(items.len() as i32).to_be_bytes());
                for item in items {
                    item.write_payload(out);
                }
            }
            Tag::Compound(entries) => {
                for (name, value) in entries {
                    out.push(value.kind());
                    write_string(out, name);
                    value.write_payload(out);
                }
                out.push(0);
            }
            Tag::IntArray(v) => {
                out.extend_from_slice(&(v.len() as i32).to_be_bytes());
                for i in v {
                    out.extend_from_slice(&i.to_be_bytes());
                }
            }
            Tag::LongArray(v) => {
                out.extend_from_slice(&(v.len() as i32).to_be_bytes());
                for i in v {
                    out.extend_from_slice(&i.to_be_bytes());
                }
            }
        }
    }
}

/// Java modified UTF-8 with a length prefix.
fn write_string(out: &mut Vec<u8>, value: &str) {
    let mut bytes = Vec::new();
    for unit in value.encode_utf16() {
        match unit {
            0x0001..=0x007F => bytes.push(unit as u8),
            0x0000 | 0x0080..=0x07FF => {
                bytes.push(0xC0 | ((unit >> 6) & 0x1F) as u8);
                bytes.push(0x80 | (unit & 0x3F) as u8);
            }
            _ => {
                bytes.push(0xE0 | ((unit >> 12) & 0x0F) as u8);
                bytes.push(0x80 | ((unit >> 6) & 0x3F) as u8);
                bytes.push(0x80 | (unit & 0x3F) as u8);
            }
        }
    }
    out.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
    out.extend_from_slice(&bytes);
}

pub fn compound(entries: Vec<(&str, Tag)>) -> Tag {
    Tag::Compound(
        entries
            .into_iter()
            .map(|(k, v)| (k.to_string(), v))
            .collect(),
    )
}

/// A complete document with an unnamed root, as `NbtIo.write` produces.
pub fn document(root: &Tag) -> Vec<u8> {
    let mut out = vec![tag::COMPOUND, 0, 0];
    root.write_payload(&mut out);
    out
}

pub fn uuid_ints(uuid: Uuid) -> Tag {
    let (msb, lsb) = uuid.as_u64_pair();
    Tag::IntArray(vec![
        (msb >> 32) as i32,
        msb as i32,
        (lsb >> 32) as i32,
        lsb as i32,
    ])
}

pub fn player(uuid: Uuid, extra: Vec<(&str, Tag)>) -> Tag {
    let mut entries = vec![("UUID", uuid_ints(uuid))];
    entries.extend(extra);
    compound(entries)
}

/// Builds a small save on disk.
pub struct SyntheticSave {
    pub game: tempfile::TempDir,
    pub root: PathBuf,
}

impl SyntheticSave {
    pub fn new() -> Self {
        let game = tempfile::tempdir().unwrap();
        let root = game.path().join("saves").join("world");
        fs::create_dir_all(&root).unwrap();
        SyntheticSave { game, root }
    }

    pub fn level_dat(self, host: Option<Tag>, hardcore: bool) -> Self {
        let mut data = vec![
            (
                "Version",
                compound(vec![
                    ("Name", Tag::String("1.20.1".into())),
                    ("Id", Tag::Int(3465)),
                ]),
            ),
            ("DataVersion", Tag::Int(3465)),
            (
                "ServerBrands",
                Tag::List(tag::STRING, vec![Tag::String("forge".into())]),
            ),
            ("hardcore", Tag::Byte(hardcore as i8)),
        ];
        if let Some(host) = host {
            data.push(("Player", host));
        }
        let fml = compound(vec![(
            "LoadingModList",
            Tag::List(
                tag::COMPOUND,
                ["minecraft", "forge"]
                    .iter()
                    .map(|id| compound(vec![("ModId", Tag::String(id.to_string()))]))
                    .collect(),
            ),
        )]);
        self.gzip_nbt(
            "level.dat",
            &compound(vec![("Data", compound(data)), ("fml", fml)]),
        )
    }

    pub fn gzip_nbt(self, relative: &str, root: &Tag) -> Self {
        self.bytes(relative, &compress::gzip(&document(root)))
    }

    pub fn text(self, relative: &str, text: &str) -> Self {
        self.bytes(relative, text.as_bytes())
    }

    pub fn bytes(self, relative: &str, bytes: &[u8]) -> Self {
        let path = self.root.join(relative);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, bytes).unwrap();
        self
    }
}

/// Builds region files the way Minecraft lays them out.
pub struct TestRegion {
    dir: PathBuf,
    rx: i32,
    rz: i32,
    chunks: Vec<(usize, u8, Vec<u8>, i32, bool)>,
}

impl TestRegion {
    pub fn new(dir: &Path, rx: i32, rz: i32) -> Self {
        fs::create_dir_all(dir).unwrap();
        TestRegion {
            dir: dir.to_path_buf(),
            rx,
            rz,
            chunks: Vec::new(),
        }
    }

    pub fn chunk(mut self, index: usize, kind: u8, nbt: &[u8], external: bool) -> Self {
        let payload = compress::compress_chunk(kind, nbt).unwrap();
        self.chunks
            .push((index, kind, payload, 1_700_000_000, external));
        self
    }

    pub fn raw_chunk(mut self, index: usize, kind: u8, payload: &[u8]) -> Self {
        self.chunks
            .push((index, kind, payload.to_vec(), 1_700_000_000, false));
        self
    }

    pub fn write(self) -> PathBuf {
        let mut header = vec![0u8; 8192];
        let mut body = Vec::new();
        let mut sector = 2usize;
        for (index, kind, payload, timestamp, external) in &self.chunks {
            let record = if *external {
                let x = self.rx * 32 + (*index & 31) as i32;
                let z = self.rz * 32 + (*index >> 5) as i32;
                fs::write(self.dir.join(format!("c.{x}.{z}.mcc")), payload).unwrap();
                let mut r = 1i32.to_be_bytes().to_vec();
                r.push(kind | 0x80);
                r
            } else {
                let mut r = ((payload.len() + 1) as i32).to_be_bytes().to_vec();
                r.push(*kind);
                r.extend_from_slice(payload);
                r
            };
            let sectors = record.len().div_ceil(4096);
            body.extend_from_slice(&record);
            body.resize(body.len() + sectors * 4096 - record.len(), 0);
            header[index * 4..index * 4 + 4]
                .copy_from_slice(&(((sector << 8) | sectors) as u32).to_be_bytes());
            header[4096 + index * 4..4096 + index * 4 + 4]
                .copy_from_slice(&timestamp.to_be_bytes());
            sector += sectors;
        }
        let path = self.dir.join(format!("r.{}.{}.mca", self.rx, self.rz));
        header.extend_from_slice(&body);
        fs::write(&path, header).unwrap();
        path
    }
}

pub const ZLIB: u8 = chunk_compression::ZLIB;
