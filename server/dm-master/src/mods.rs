//! The room's mod fingerprint: registry contents hashed per namespace by whoever hosts the room.
//! Takeover candidates whose registries differ are turned away, so a host with missing mods never
//! opens the world and uploads it with that content deleted.

use crate::snapshot_io::{atomic_write, now_millis};
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use std::{collections::BTreeMap, fs, io::ErrorKind, path::Path};
use uuid::Uuid;

pub const FINGERPRINT_VERSION: u32 = 1;
const MAX_NAMESPACES: usize = 4096;
const MAX_NAMESPACE_CHARS: usize = 128;
const MAX_LABEL_CHARS: usize = 256;
const FILE_NAME: &str = "mods.json";

/// One group of registries: a SHA-1 per namespace and a SHA-1 over the sorted `namespace=hash` lines.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FingerprintPart {
    pub overall: String,
    pub namespaces: BTreeMap<String, String>,
}

impl FingerprintPart {
    pub fn overall_of(namespaces: &BTreeMap<String, String>) -> String {
        let mut digest = Sha1::new();
        for (namespace, hash) in namespaces {
            digest.update(format!("{namespace}={hash}\n").as_bytes());
        }
        hex::encode(digest.finalize())
    }

    fn validate(&self) -> Result<()> {
        if self.namespaces.len() > MAX_NAMESPACES {
            bail!("too many namespaces in mod fingerprint");
        }
        for (namespace, hash) in &self.namespaces {
            if !valid_namespace(namespace) || !valid_sha1(hash) {
                bail!("invalid namespace entry in mod fingerprint");
            }
        }
        if self.overall != Self::overall_of(&self.namespaces) {
            bail!("mod fingerprint overall hash does not match its namespaces");
        }
        Ok(())
    }
}

/// `registries` covers the static registries every client knows at the title screen; `world`
/// covers the data pack registries, which only exist once the host has the world open.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ModFingerprint {
    pub version: u32,
    pub registries: FingerprintPart,
    #[serde(default)]
    pub world: Option<FingerprintPart>,
    #[serde(default)]
    pub labels: BTreeMap<String, String>,
}

impl ModFingerprint {
    pub fn validate(&self) -> Result<()> {
        if self.version != FINGERPRINT_VERSION {
            bail!("unsupported mod fingerprint version");
        }
        self.registries.validate()?;
        if let Some(world) = &self.world {
            world.validate()?;
        }
        if self.labels.len() > MAX_NAMESPACES
            || self.labels.iter().any(|(namespace, label)| {
                !valid_namespace(namespace) || label.chars().count() > MAX_LABEL_CHARS
            })
        {
            bail!("invalid labels in mod fingerprint");
        }
        Ok(())
    }

    /// Whether `other` has the same registry contents; labels are only for messages.
    pub fn same_contents(&self, other: &ModFingerprint) -> bool {
        self.registries.overall == other.registries.overall
            && self.world.as_ref().map(|part| &part.overall)
                == other.world.as_ref().map(|part| &part.overall)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomMods {
    pub fingerprint: ModFingerprint,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub updated_by: Option<Uuid>,
    pub updated_at: u64,
}

pub fn read(host_root: &Path) -> Result<Option<RoomMods>> {
    match fs::read(host_root.join(FILE_NAME)) {
        Ok(bytes) => Ok(Some(serde_json::from_slice(&bytes)?)),
        Err(err) if err.kind() == ErrorKind::NotFound => Ok(None),
        Err(err) => Err(err.into()),
    }
}

/// Records `fingerprint` unless the room already holds the same contents; returns whether it wrote.
pub fn record(host_root: &Path, fingerprint: &ModFingerprint, player: Option<Uuid>) -> Result<bool> {
    let current = read(host_root)?;
    if current.as_ref().is_some_and(|room| {
        room.fingerprint.same_contents(fingerprint) && room.fingerprint.labels == fingerprint.labels
    }) {
        return Ok(false);
    }
    let room = RoomMods {
        fingerprint: fingerprint.clone(),
        updated_by: player,
        updated_at: now_millis(),
    };
    atomic_write(&host_root.join(FILE_NAME), &serde_json::to_vec_pretty(&room)?)?;
    Ok(true)
}

pub fn valid_sha1(value: &str) -> bool {
    value.len() == 40 && value.bytes().all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

fn valid_namespace(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_NAMESPACE_CHARS
        && value
            .bytes()
            .all(|byte| matches!(byte, b'a'..=b'z' | b'0'..=b'9' | b'_' | b'-' | b'.'))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use tempfile::tempdir;

    pub(crate) fn part(entries: &[(&str, &str)]) -> FingerprintPart {
        let namespaces: BTreeMap<String, String> = entries
            .iter()
            .map(|(namespace, hash)| (namespace.to_string(), hash.repeat(40)))
            .collect();
        FingerprintPart {
            overall: FingerprintPart::overall_of(&namespaces),
            namespaces,
        }
    }

    pub(crate) fn fingerprint(entries: &[(&str, &str)]) -> ModFingerprint {
        ModFingerprint {
            version: FINGERPRINT_VERSION,
            registries: part(entries),
            world: Some(part(&[("minecraft", "c")])),
            labels: BTreeMap::from([("create".to_owned(), "Create 6.0.4".to_owned())]),
        }
    }

    #[test]
    fn overall_hash_follows_the_client_contract() {
        // The same vector is checked in the client's DmModFingerprintTest.
        let namespaces = BTreeMap::from([
            ("minecraft".to_owned(), "b".repeat(40)),
            ("create".to_owned(), "a".repeat(40)),
        ]);
        assert_eq!(
            FingerprintPart::overall_of(&namespaces),
            "d8369fde1e260371d827cc92657b12e98defadfa"
        );
    }

    #[test]
    fn overall_hash_must_match_the_namespaces() {
        let valid = fingerprint(&[("minecraft", "a"), ("create", "b")]);
        assert!(valid.validate().is_ok());
        let mut tampered = valid.clone();
        tampered
            .registries
            .namespaces
            .insert("create".to_owned(), "d".repeat(40));
        assert!(tampered.validate().is_err());
        let mut bad_namespace = fingerprint(&[("Create!", "a")]);
        bad_namespace.registries.overall =
            FingerprintPart::overall_of(&bad_namespace.registries.namespaces);
        assert!(bad_namespace.validate().is_err());
    }

    #[test]
    fn record_keeps_the_first_writer_until_contents_change() {
        let dir = tempdir().unwrap();
        assert_eq!(read(dir.path()).unwrap(), None);
        let first = Uuid::now_v7();
        let second = Uuid::now_v7();
        let original = fingerprint(&[("minecraft", "a")]);
        assert!(record(dir.path(), &original, Some(first)).unwrap());
        assert!(!record(dir.path(), &original, Some(second)).unwrap());
        assert_eq!(read(dir.path()).unwrap().unwrap().updated_by, Some(first));

        let changed = fingerprint(&[("minecraft", "a"), ("create", "b")]);
        assert!(record(dir.path(), &changed, Some(second)).unwrap());
        let room = read(dir.path()).unwrap().unwrap();
        assert_eq!(room.fingerprint, changed);
        assert_eq!(room.updated_by, Some(second));
    }
}
