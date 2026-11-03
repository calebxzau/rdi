//! Host takeover election.
//!
//! Participants report hosting metrics while they play. When the host goes offline, claims
//! are collected for [`ELECTION_WINDOW`] after the first one, and the best ranked claimant
//! receives a lease. Everything is in memory: a master restart simply starts a new election.

use serde::{Deserialize, Serialize};
use std::{
    cmp::Reverse,
    collections::HashMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use uuid::Uuid;

pub const ELECTION_WINDOW: Duration = Duration::from_secs(3);
pub const CLAIM_LEASE: Duration = Duration::from_secs(30);
pub const CANDIDATE_EXPIRY: Duration = Duration::from_secs(15 * 60);
pub const MAX_CANDIDATES_PER_HOST: usize = 64;
/// Hosts below these are only chosen when no qualified claimant exists.
pub const MIN_MEMORY_BYTES: u64 = 4 << 30;
pub const MIN_UPLOAD_BPS: u64 = 250_000;
pub const MAX_RTT_MS: u32 = 250;
/// Used when a candidate could not measure the value.
const FALLBACK_DOWNLOAD_BPS: u64 = 1_000_000;
const FALLBACK_STARTUP_MS: u64 = 120_000;
const CLAIMED_RETRY: Duration = Duration::from_secs(2);

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CandidateMetrics {
    #[serde(default)]
    pub startup_ms: Option<u64>,
    pub max_memory_bytes: u64,
    pub cpu_cores: u32,
    #[serde(default)]
    pub rtt_ms: Option<u32>,
    #[serde(default)]
    pub download_bps: Option<u64>,
    #[serde(default)]
    pub upload_bps: Option<u64>,
    /// Overall hash of the candidate's static registries, compared with the room's.
    #[serde(default)]
    pub registry_fingerprint: Option<String>,
}

impl CandidateMetrics {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.max_memory_bytes == 0 {
            return Err("maxMemoryBytes must be positive");
        }
        if !(1..=4096).contains(&self.cpu_cores) {
            return Err("cpuCores is out of range");
        }
        if self.startup_ms.is_some_and(|value| value > 3_600_000) {
            return Err("startupMs is out of range");
        }
        if self.rtt_ms.is_some_and(|value| value > 60_000) {
            return Err("rttMs is out of range");
        }
        if self.download_bps == Some(0) || self.upload_bps == Some(0) {
            return Err("measured speeds must be positive");
        }
        if self
            .registry_fingerprint
            .as_deref()
            .is_some_and(|value| !crate::mods::valid_sha1(value))
        {
            return Err("registryFingerprint must be a SHA-1");
        }
        Ok(())
    }

    fn qualified(&self) -> bool {
        self.max_memory_bytes >= MIN_MEMORY_BYTES
            && self.upload_bps.is_some_and(|value| value >= MIN_UPLOAD_BPS)
            && self.rtt_ms.is_some_and(|value| value <= MAX_RTT_MS)
    }

    /// Estimated takeover time: world download plus game startup as a proxy for world load.
    pub fn estimate_ms(&self, world_bytes: u64) -> u64 {
        let download_bps = self.download_bps.unwrap_or(FALLBACK_DOWNLOAD_BPS);
        let download_ms = world_bytes.saturating_mul(1000) / download_bps;
        download_ms.saturating_add(self.startup_ms.unwrap_or(FALLBACK_STARTUP_MS))
    }
}

/// Lower is better: qualified candidates first, then the faster estimated takeover, then
/// the faster upload, then whoever claimed first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Rank {
    tier: u8,
    estimate_ms: u64,
    upload: Reverse<u64>,
    order: usize,
}

pub fn rank(metrics: Option<&CandidateMetrics>, world_bytes: u64, order: usize) -> Rank {
    match metrics {
        Some(metrics) => Rank {
            tier: if metrics.qualified() { 0 } else { 1 },
            estimate_ms: metrics.estimate_ms(world_bytes),
            upload: Reverse(metrics.upload_bps.unwrap_or(0)),
            order,
        },
        None => Rank {
            tier: 2,
            estimate_ms: u64::MAX,
            upload: Reverse(0),
            order,
        },
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClaimOutcome {
    Granted { claim_id: Uuid, lease: Duration },
    Pending { retry_after: Duration },
    Claimed { retry_after: Duration },
    /// The candidate reported registries that differ from the room's.
    Incompatible,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum TakeoverState {
    Electing,
    Claimed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReportError {
    TooManyCandidates,
    /// The candidate id was first reported by another player.
    NotYourCandidate,
}

struct Candidate {
    player: Uuid,
    metrics: CandidateMetrics,
    updated_at: Instant,
}

impl Candidate {
    /// Candidates that never reported a fingerprint are let through; the host's own check before
    /// its first checkpoint upload still stops one with different mods.
    fn fits(&self, room_fingerprint: Option<&str>) -> bool {
        match (room_fingerprint, self.metrics.registry_fingerprint.as_deref()) {
            (Some(room), Some(reported)) => room == reported,
            _ => true,
        }
    }
}

struct Winner {
    candidate: Uuid,
    player: Uuid,
    claim_id: Uuid,
    expires_at: Instant,
}

#[derive(Clone, Copy)]
struct Claimant {
    candidate: Uuid,
    player: Uuid,
}

struct Election {
    opened_at: Instant,
    claimants: Vec<Claimant>,
    winner: Option<Winner>,
}

#[derive(Default)]
struct HostTakeover {
    candidates: HashMap<Uuid, Candidate>,
    election: Option<Election>,
}

impl HostTakeover {
    fn expire(&mut self, now: Instant) {
        self.candidates
            .retain(|_, candidate| now.duration_since(candidate.updated_at) < CANDIDATE_EXPIRY);
        if self
            .election
            .as_ref()
            .and_then(|election| election.winner.as_ref())
            .is_some_and(|winner| now >= winner.expires_at)
        {
            self.election = None;
        }
    }
}

#[derive(Clone, Default)]
pub struct Takeovers {
    hosts: Arc<Mutex<HashMap<Uuid, HostTakeover>>>,
}

impl Takeovers {
    pub fn report(
        &self,
        host: Uuid,
        candidate: Uuid,
        player: Uuid,
        metrics: CandidateMetrics,
        now: Instant,
    ) -> Result<(), ReportError> {
        let mut hosts = self.hosts.lock().expect("takeover registry poisoned");
        let entry = hosts.entry(host).or_default();
        entry.expire(now);
        if entry
            .candidates
            .get(&candidate)
            .is_some_and(|known| known.player != player)
        {
            return Err(ReportError::NotYourCandidate);
        }
        if !entry.candidates.contains_key(&candidate)
            && entry.candidates.len() >= MAX_CANDIDATES_PER_HOST
        {
            return Err(ReportError::TooManyCandidates);
        }
        entry.candidates.insert(
            candidate,
            Candidate {
                player,
                metrics,
                updated_at: now,
            },
        );
        Ok(())
    }

    /// The caller must have checked that the host is offline.
    pub fn claim(
        &self,
        host: Uuid,
        candidate: Uuid,
        player: Uuid,
        world_bytes: u64,
        room_fingerprint: Option<&str>,
        now: Instant,
    ) -> ClaimOutcome {
        let mut hosts = self.hosts.lock().expect("takeover registry poisoned");
        let entry = hosts.entry(host).or_default();
        entry.expire(now);
        let fits = |claimant: &Uuid, candidates: &HashMap<Uuid, Candidate>| {
            candidates
                .get(claimant)
                .is_none_or(|value| value.fits(room_fingerprint))
        };
        if !fits(&candidate, &entry.candidates) {
            return ClaimOutcome::Incompatible;
        }
        // A candidate id belongs to the player who used it first.
        let taken = entry
            .candidates
            .get(&candidate)
            .is_some_and(|known| known.player != player)
            || entry.election.as_ref().is_some_and(|election| {
                election
                    .claimants
                    .iter()
                    .any(|known| known.candidate == candidate && known.player != player)
            });
        if taken {
            return ClaimOutcome::Claimed {
                retry_after: CLAIMED_RETRY,
            };
        }
        let election = entry.election.get_or_insert_with(|| Election {
            opened_at: now,
            claimants: Vec::new(),
            winner: None,
        });
        if election.winner.is_none() {
            let elapsed = now.duration_since(election.opened_at);
            if elapsed < ELECTION_WINDOW {
                if !election.claimants.iter().any(|known| known.candidate == candidate) {
                    election.claimants.push(Claimant { candidate, player });
                }
                return ClaimOutcome::Pending {
                    retry_after: ELECTION_WINDOW - elapsed,
                };
            }
            let best = election
                .claimants
                .iter()
                .enumerate()
                // A claimant may have reported different registries after claiming.
                .filter(|(_, claimant)| fits(&claimant.candidate, &entry.candidates))
                .min_by_key(|(order, claimant)| {
                    let metrics = entry
                        .candidates
                        .get(&claimant.candidate)
                        .map(|value| &value.metrics);
                    rank(metrics, world_bytes, *order)
                })
                .map(|(_, claimant)| *claimant)
                // Every claimant vanished from the list only if none claimed in the window.
                .unwrap_or(Claimant { candidate, player });
            election.winner = Some(Winner {
                candidate: best.candidate,
                player: best.player,
                claim_id: Uuid::now_v7(),
                expires_at: now + CLAIM_LEASE,
            });
        }
        let winner = election.winner.as_ref().expect("winner was just chosen");
        if winner.candidate == candidate {
            ClaimOutcome::Granted {
                claim_id: winner.claim_id,
                lease: winner.expires_at.saturating_duration_since(now),
            }
        } else {
            ClaimOutcome::Claimed {
                retry_after: CLAIMED_RETRY,
            }
        }
    }

    pub fn renew(&self, host: Uuid, claim_id: Uuid, player: Uuid, now: Instant) -> Option<Duration> {
        let mut hosts = self.hosts.lock().expect("takeover registry poisoned");
        let entry = hosts.get_mut(&host)?;
        entry.expire(now);
        let winner = entry.election.as_mut()?.winner.as_mut()?;
        if winner.claim_id != claim_id || winner.player != player {
            return None;
        }
        winner.expires_at = now + CLAIM_LEASE;
        Some(CLAIM_LEASE)
    }

    pub fn release(&self, host: Uuid, claim_id: Uuid, player: Uuid) -> bool {
        let mut hosts = self.hosts.lock().expect("takeover registry poisoned");
        let Some(entry) = hosts.get_mut(&host) else {
            return false;
        };
        let owns = entry
            .election
            .as_ref()
            .and_then(|election| election.winner.as_ref())
            .is_some_and(|winner| winner.claim_id == claim_id && winner.player == player);
        if owns {
            entry.election = None;
        }
        owns
    }

    /// Whether a new host session may start: the elected winner must present its claim, and
    /// nobody else may start while a winner holds the lease. An accepted claim gets a full lease
    /// so it cannot expire before the session is reported online.
    pub fn authorize_registration(
        &self,
        host: Uuid,
        claim: Option<Uuid>,
        player: Uuid,
        now: Instant,
    ) -> bool {
        let mut hosts = self.hosts.lock().expect("takeover registry poisoned");
        let Some(entry) = hosts.get_mut(&host) else {
            return claim.is_none();
        };
        entry.expire(now);
        let winner = entry
            .election
            .as_mut()
            .and_then(|election| election.winner.as_mut());
        match (claim, winner) {
            (None, None) => true,
            (Some(claim), Some(winner)) if winner.claim_id == claim && winner.player == player => {
                winner.expires_at = now + CLAIM_LEASE;
                true
            }
            _ => false,
        }
    }

    /// The room was deleted; nothing about it is needed any more.
    pub fn forget(&self, host: Uuid) {
        self.hosts
            .lock()
            .expect("takeover registry poisoned")
            .remove(&host);
    }

    /// A host session came online, so any election for it is over.
    pub fn host_online(&self, host: Uuid) {
        if let Some(entry) = self
            .hosts
            .lock()
            .expect("takeover registry poisoned")
            .get_mut(&host)
        {
            entry.election = None;
        }
    }

    pub fn state(&self, host: Uuid, now: Instant) -> Option<TakeoverState> {
        let mut hosts = self.hosts.lock().expect("takeover registry poisoned");
        let entry = hosts.get_mut(&host)?;
        entry.expire(now);
        let election = entry.election.as_ref()?;
        Some(if election.winner.is_some() {
            TakeoverState::Claimed
        } else {
            TakeoverState::Electing
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PLAYER: Uuid = Uuid::from_u128(1);
    const OTHER_PLAYER: Uuid = Uuid::from_u128(2);

    fn metrics(startup_ms: u64, download_bps: u64, upload_bps: u64) -> CandidateMetrics {
        CandidateMetrics {
            startup_ms: Some(startup_ms),
            max_memory_bytes: 8 << 30,
            cpu_cores: 8,
            rtt_ms: Some(40),
            download_bps: Some(download_bps),
            upload_bps: Some(upload_bps),
            registry_fingerprint: None,
        }
    }

    fn granted(outcome: ClaimOutcome) -> Uuid {
        match outcome {
            ClaimOutcome::Granted { claim_id, .. } => claim_id,
            other => panic!("expected Granted, got {other:?}"),
        }
    }

    #[test]
    fn collects_claims_for_the_window_then_grants_the_fastest() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let slow = Uuid::now_v7();
        let fast = Uuid::now_v7();
        let start = Instant::now();
        takeovers
            .report(host, slow, PLAYER, metrics(90_000, 10_000_000, 1_000_000), start)
            .unwrap();
        takeovers
            .report(host, fast, PLAYER, metrics(30_000, 10_000_000, 1_000_000), start)
            .unwrap();

        assert!(matches!(
            takeovers.claim(host, slow, PLAYER, 100_000_000, None, start),
            ClaimOutcome::Pending { .. }
        ));
        assert!(matches!(
            takeovers.claim(host, fast, PLAYER, 100_000_000, None, start + Duration::from_secs(1)),
            ClaimOutcome::Pending { .. }
        ));
        assert_eq!(takeovers.state(host, start), Some(TakeoverState::Electing));

        let after = start + ELECTION_WINDOW;
        assert!(matches!(
            takeovers.claim(host, slow, PLAYER, 100_000_000, None, after),
            ClaimOutcome::Claimed { .. }
        ));
        let claim = granted(takeovers.claim(host, fast, PLAYER, 100_000_000, None, after));
        assert_eq!(
            granted(takeovers.claim(host, fast, PLAYER, 100_000_000, None, after)),
            claim
        );
        assert_eq!(takeovers.state(host, after), Some(TakeoverState::Claimed));
    }

    #[test]
    fn late_claimant_cannot_win_and_unreported_claimant_ranks_last() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let unknown = Uuid::now_v7();
        let reported = Uuid::now_v7();
        let late = Uuid::now_v7();
        let start = Instant::now();
        takeovers
            .report(host, reported, PLAYER, metrics(200_000, 1_000_000, 300_000), start)
            .unwrap();
        takeovers
            .report(host, late, PLAYER, metrics(1_000, 100_000_000, 10_000_000), start)
            .unwrap();
        takeovers.claim(host, unknown, PLAYER, 0, None, start);
        takeovers.claim(host, reported, PLAYER, 0, None, start);
        let after = start + ELECTION_WINDOW + Duration::from_millis(1);
        assert!(matches!(
            takeovers.claim(host, late, PLAYER, 0, None, after),
            ClaimOutcome::Claimed { .. }
        ));
        granted(takeovers.claim(host, reported, PLAYER, 0, None, after));
    }

    #[test]
    fn qualified_candidate_beats_a_faster_one_with_low_upload() {
        let poor = metrics(10_000, 50_000_000, 100_000);
        let good = metrics(60_000, 5_000_000, 2_000_000);
        assert!(rank(Some(&good), 100_000_000, 1) < rank(Some(&poor), 100_000_000, 0));
        assert!(rank(Some(&poor), 100_000_000, 0) < rank(None, 100_000_000, 0));
    }

    #[test]
    fn candidates_with_other_registries_cannot_claim_or_win() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let room = "a".repeat(40);
        let fitting = Uuid::now_v7();
        let other = Uuid::now_v7();
        let silent = Uuid::now_v7();
        let start = Instant::now();
        let with_fingerprint = |fingerprint: &str| CandidateMetrics {
            registry_fingerprint: Some(fingerprint.to_owned()),
            ..metrics(1_000, 50_000_000, 10_000_000)
        };
        takeovers
            .report(host, fitting, PLAYER, with_fingerprint(&room), start)
            .unwrap();
        // The faster candidate has different mods.
        takeovers
            .report(host, other, PLAYER, CandidateMetrics {
                startup_ms: Some(10),
                ..with_fingerprint(&"b".repeat(40))
            }, start)
            .unwrap();
        assert_eq!(
            takeovers.claim(host, other, PLAYER, 0, Some(&room), start),
            ClaimOutcome::Incompatible
        );
        assert!(matches!(
            takeovers.claim(host, fitting, PLAYER, 0, Some(&room), start),
            ClaimOutcome::Pending { .. }
        ));
        assert!(matches!(
            takeovers.claim(host, silent, PLAYER, 0, Some(&room), start),
            ClaimOutcome::Pending { .. }
        ));
        let after = start + ELECTION_WINDOW;
        granted(takeovers.claim(host, fitting, PLAYER, 0, Some(&room), after));
        // Without a room fingerprint (older rooms) nobody is turned away.
        assert!(matches!(
            takeovers.claim(Uuid::now_v7(), other, PLAYER, 0, None, after),
            ClaimOutcome::Pending { .. }
        ));
    }

    #[test]
    fn rejects_a_fingerprint_that_is_not_sha1() {
        let bad = CandidateMetrics {
            registry_fingerprint: Some("not-a-hash".to_owned()),
            ..metrics(1_000, 1_000_000, 1_000_000)
        };
        assert!(bad.validate().is_err());
    }

    #[test]
    fn registration_without_a_winner_needs_no_claim() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let start = Instant::now();
        assert!(takeovers.authorize_registration(host, None, PLAYER, start));
        // Claims from before a master restart, or for a room with no election, are not valid.
        assert!(!takeovers.authorize_registration(host, Some(Uuid::now_v7()), PLAYER, start));

        // A returning owner may still come back before anyone was chosen.
        takeovers.claim(host, Uuid::now_v7(), PLAYER, 0, None, start);
        assert!(takeovers.authorize_registration(host, None, PLAYER, start));
        assert!(!takeovers.authorize_registration(host, Some(Uuid::now_v7()), PLAYER, start));
    }

    #[test]
    fn only_the_winner_may_register_while_it_holds_the_lease() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let candidate = Uuid::now_v7();
        let start = Instant::now();
        takeovers.claim(host, candidate, PLAYER, 0, None, start);
        let chosen = start + ELECTION_WINDOW;
        let claim = granted(takeovers.claim(host, candidate, PLAYER, 0, None, chosen));

        assert!(!takeovers.authorize_registration(host, None, PLAYER, chosen));
        assert!(!takeovers.authorize_registration(host, Some(Uuid::now_v7()), PLAYER, chosen));
        assert!(takeovers.authorize_registration(host, Some(claim), PLAYER, chosen));
    }

    #[test]
    fn an_accepted_registration_extends_the_lease_and_an_expired_claim_is_refused() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let candidate = Uuid::now_v7();
        let start = Instant::now();
        takeovers.claim(host, candidate, PLAYER, 0, None, start);
        let chosen = start + ELECTION_WINDOW;
        let claim = granted(takeovers.claim(host, candidate, PLAYER, 0, None, chosen));

        let late = chosen + CLAIM_LEASE - Duration::from_secs(1);
        assert!(takeovers.authorize_registration(host, Some(claim), PLAYER, late));
        assert!(takeovers.renew(host, claim, PLAYER, chosen + CLAIM_LEASE).is_some());

        let expired = chosen + CLAIM_LEASE * 3;
        assert!(!takeovers.authorize_registration(host, Some(claim), PLAYER, expired));
        // The expired election is gone, so the room is open again.
        assert!(takeovers.authorize_registration(host, None, PLAYER, expired));
    }

    #[test]
    fn expired_lease_restarts_the_election_and_release_or_online_clears_it() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let first = Uuid::now_v7();
        let second = Uuid::now_v7();
        let start = Instant::now();
        takeovers.claim(host, first, PLAYER, 0, None, start);
        let lease_start = start + ELECTION_WINDOW;
        let claim = granted(takeovers.claim(host, first, PLAYER, 0, None, lease_start));
        assert!(
            takeovers
                .renew(host, claim, PLAYER, lease_start + CLAIM_LEASE / 2)
                .is_some()
        );
        let expired = lease_start + CLAIM_LEASE / 2 + CLAIM_LEASE;
        assert!(takeovers.renew(host, claim, PLAYER, expired).is_none());
        assert!(matches!(
            takeovers.claim(host, second, PLAYER, 0, None, expired),
            ClaimOutcome::Pending { .. }
        ));
        let second_claim = granted(takeovers.claim(host, second, PLAYER, 0, None, expired + ELECTION_WINDOW));
        assert!(!takeovers.release(host, claim, PLAYER));
        assert!(takeovers.release(host, second_claim, PLAYER));
        assert_eq!(takeovers.state(host, expired + ELECTION_WINDOW), None);

        takeovers.claim(host, first, PLAYER, 0, None, expired + ELECTION_WINDOW);
        takeovers.host_online(host);
        assert_eq!(takeovers.state(host, expired + ELECTION_WINDOW), None);
    }

    #[test]
    fn candidate_count_is_bounded_and_reports_expire() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let start = Instant::now();
        for _ in 0..MAX_CANDIDATES_PER_HOST {
            takeovers
                .report(host, Uuid::now_v7(), PLAYER, metrics(1, 1, 1), start)
                .unwrap();
        }
        assert_eq!(
            takeovers.report(host, Uuid::now_v7(), PLAYER, metrics(1, 1, 1), start),
            Err(ReportError::TooManyCandidates)
        );
        takeovers
            .report(host, Uuid::now_v7(), PLAYER, metrics(1, 1, 1),
                start + CANDIDATE_EXPIRY,
            )
            .unwrap();
    }

    #[test]
    fn rejects_invalid_metrics() {
        let mut value = metrics(1, 1, 1);
        assert!(value.validate().is_ok());
        value.cpu_cores = 0;
        assert!(value.validate().is_err());
        let mut value = metrics(1, 1, 1);
        value.upload_bps = Some(0);
        assert!(value.validate().is_err());
    }

    #[test]
    fn a_claim_only_works_for_the_player_who_won_it() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let candidate = Uuid::now_v7();
        let start = Instant::now();
        takeovers.claim(host, candidate, PLAYER, 0, None, start);
        let claim = granted(takeovers.claim(host, candidate, PLAYER, 0, None, start + ELECTION_WINDOW));

        assert!(matches!(
            takeovers.claim(host, candidate, OTHER_PLAYER, 0, None, start + ELECTION_WINDOW),
            ClaimOutcome::Claimed { .. }
        ));
        assert!(takeovers.renew(host, claim, OTHER_PLAYER, start + ELECTION_WINDOW).is_none());
        assert!(!takeovers.release(host, claim, OTHER_PLAYER));
        assert!(!takeovers.authorize_registration(host, Some(claim), OTHER_PLAYER, start + ELECTION_WINDOW));

        assert!(takeovers.renew(host, claim, PLAYER, start + ELECTION_WINDOW).is_some());
        assert!(takeovers.authorize_registration(host, Some(claim), PLAYER, start + ELECTION_WINDOW));
    }

    #[test]
    fn a_candidate_id_belongs_to_the_player_who_reported_it_first() {
        let takeovers = Takeovers::default();
        let host = Uuid::now_v7();
        let candidate = Uuid::now_v7();
        let start = Instant::now();
        takeovers.report(host, candidate, PLAYER, metrics(1, 1, 1), start).unwrap();
        assert_eq!(
            takeovers.report(host, candidate, OTHER_PLAYER, metrics(1, 1, 1), start),
            Err(ReportError::NotYourCandidate)
        );
        assert!(matches!(
            takeovers.claim(host, candidate, OTHER_PLAYER, 0, None, start),
            ClaimOutcome::Claimed { .. }
        ));
    }
}
