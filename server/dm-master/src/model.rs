use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Response<T> {
    pub code: i8,
    pub msg: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<T>,
}

impl<T> Response<T> {
    pub fn ok(data: T) -> Self {
        Self {
            code: 0,
            msg: String::new(),
            data: Some(data),
        }
    }
    pub fn empty() -> Self {
        Self {
            code: 0,
            msg: String::new(),
            data: None,
        }
    }
    pub fn error(message: impl Into<String>) -> Self {
        Self {
            code: -1,
            msg: message.into(),
            data: None,
        }
    }

    pub fn error_data<E: Serialize>(message: impl Into<String>, data: E) -> Response<E> {
        Response {
            code: -1,
            msg: message.into(),
            data: Some(data),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HostRecord {
    pub id: Uuid,
    pub name: String,
    /// The player who created the room; rooms created before this was recorded have none.
    #[serde(rename = "creatorId", default, skip_serializing_if = "Option::is_none")]
    pub creator_id: Option<Uuid>,
    /// The player whose host session the gateway last registered.
    #[serde(rename = "lastHostId", default, skip_serializing_if = "Option::is_none")]
    pub last_host_id: Option<Uuid>,
}

#[derive(Debug, Clone, Serialize)]
pub struct InfoData {
    #[serde(rename = "publicHost", skip_serializing_if = "Option::is_none")]
    pub public_host: Option<String>,
    #[serde(rename = "tunnelPort")]
    pub tunnel_port: u16,
    #[serde(rename = "worldCheckpointApiVersion")]
    pub world_checkpoint_api_version: u32,
    #[serde(rename = "worldCheckpointLimits")]
    pub world_checkpoint_limits: crate::config::WorldCheckpointLimits,
    /// Lets a host with a wrong clock still check the expiry of join tickets.
    #[serde(rename = "serverTimeSeconds")]
    pub server_time_seconds: i64,
}

/// One row of the player's room list.
#[derive(Debug, Clone, Serialize)]
pub struct HostBriefData {
    pub id: Uuid,
    pub name: String,
    pub state: PresenceState,
}

#[derive(Debug, Clone, Serialize)]
pub struct HostData {
    pub id: Uuid,
    pub name: String,
    pub state: PresenceState,
    #[serde(rename = "gamePort", skip_serializing_if = "Option::is_none")]
    pub game_port: Option<u16>,
    #[serde(rename = "checkpointSequence")]
    pub checkpoint_sequence: i64,
    #[serde(rename = "checkpointCycleId", skip_serializing_if = "Option::is_none")]
    pub checkpoint_cycle_id: Option<Uuid>,
    /// Overall hash of the room's static registries; the full fingerprint is at `/hosts/{id}/mods`.
    #[serde(rename = "modFingerprint", skip_serializing_if = "Option::is_none")]
    pub mod_fingerprint: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub takeover: Option<crate::takeover::TakeoverState>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TakeoverRequest {
    #[serde(rename = "candidateId")]
    pub candidate_id: Uuid,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum TakeoverOutcome {
    Granted,
    Pending,
    Claimed,
    /// The candidate's mods differ from the room's, so it may not take over.
    Incompatible,
}

#[derive(Debug, Serialize)]
pub struct TakeoverData {
    pub outcome: TakeoverOutcome,
    #[serde(rename = "claimId", skip_serializing_if = "Option::is_none")]
    pub claim_id: Option<Uuid>,
    #[serde(rename = "leaseMs", skip_serializing_if = "Option::is_none")]
    pub lease_ms: Option<u64>,
    #[serde(rename = "retryAfterMs", skip_serializing_if = "Option::is_none")]
    pub retry_after_ms: Option<u64>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum TakeoverErrorReason {
    InvalidRequest,
    HostNotFound,
    HostOnline,
    /// The host is still uploading its final checkpoint; try again once it is offline.
    HostLeaving,
    GatewayUnavailable,
    ClaimLost,
    TooManyCandidates,
    StorageError,
}

#[derive(Debug, Serialize)]
pub struct SpeedTestUploadData {
    pub bytes: u64,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum PresenceState {
    Online,
    Offline,
    Unavailable,
    /// The host is saving its final checkpoint before going offline.
    Leaving,
}

#[derive(Debug, Deserialize)]
pub struct HostSessionRequest {
    #[serde(rename = "sessionId")]
    pub session_id: Uuid,
    #[serde(rename = "gamePort")]
    pub game_port: u16,
}

#[derive(Debug, Deserialize)]
pub struct RegistrationRequest {
    #[serde(rename = "claimId", default)]
    pub claim_id: Option<Uuid>,
    /// The player's tunnel ticket, passed on by the gateway.
    #[serde(default)]
    pub ticket: String,
}

/// Why a host session may not start; the gateway passes this on to the player.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum RegistrationRefusal {
    /// The takeover claim is missing, lost, or belongs to someone else.
    Claim,
    /// The ticket is invalid or expired.
    Ticket,
    /// Without a claim, only the last host may come back (or the creator of an unused room).
    NotOwner,
}

#[derive(Debug, Deserialize)]
pub struct OfflineRequest {
    #[serde(rename = "sessionId")]
    pub session_id: Uuid,
}

#[derive(Debug, Deserialize)]
pub struct SessionListRequest {
    pub sessions: Vec<SessionRequest>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SessionRequest {
    #[serde(rename = "hostId")]
    pub host_id: Uuid,
    #[serde(rename = "sessionId")]
    pub session_id: Uuid,
    #[serde(rename = "gamePort")]
    pub game_port: u16,
}

#[derive(Debug, Serialize)]
pub struct HeartbeatData {
    #[serde(rename = "syncRequired")]
    pub sync_required: bool,
}

#[derive(Debug, Serialize)]
pub struct CreatedHostData {
    pub id: Uuid,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Eq, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SyncChunk {
    #[serde(rename = "dimensionId")]
    pub dimension_id: String,
    #[serde(rename = "chunkX")]
    pub chunk_x: i32,
    #[serde(rename = "chunkZ")]
    pub chunk_z: i32,
    #[serde(rename = "ownerId")]
    pub owner_id: Uuid,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SyncChunkMutationRequest {
    #[serde(rename = "sessionId")]
    pub session_id: Uuid,
    #[serde(rename = "expectedRevision")]
    pub expected_revision: i64,
    #[serde(rename = "playerId")]
    pub player_id: Uuid,
    pub chunk: SyncChunkCoordinates,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SyncChunkCoordinates {
    #[serde(rename = "dimensionId")]
    pub dimension_id: String,
    #[serde(rename = "chunkX")]
    pub chunk_x: i32,
    #[serde(rename = "chunkZ")]
    pub chunk_z: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct SyncChunkLimits {
    #[serde(rename = "maxTotal")]
    pub max_total: usize,
}

#[derive(Debug, Clone, Serialize)]
pub struct SyncChunksData {
    pub revision: i64,
    pub chunks: Vec<SyncChunk>,
    pub limits: SyncChunkLimits,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub outcome: Option<SyncChunkOutcome>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum SyncChunkOutcome {
    Added,
    AlreadyPresent,
    Removed,
    AlreadyAbsent,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum SyncChunkErrorReason {
    RevisionConflict,
    SessionUnavailable,
    OwnedByOther,
    TotalLimitReached,
    InvalidRequest,
    HostNotFound,
    StorageError,
}

#[derive(Debug, Clone, Serialize)]
pub struct SyncChunkErrorData {
    pub reason: SyncChunkErrorReason,
}
