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
}

#[derive(Debug, Clone, Serialize)]
pub struct InfoData {
    #[serde(rename = "worldSnapshotApiVersion")]
    pub world_snapshot_api_version: u32,
    #[serde(rename = "publicHost", skip_serializing_if = "Option::is_none")]
    pub public_host: Option<String>,
    #[serde(rename = "tunnelPort")]
    pub tunnel_port: u16,
    /// Published so a client can reject an oversized cycle before building the ZIP.
    /// Master still enforces every limit independently.
    #[serde(rename = "worldSnapshotLimits")]
    pub world_snapshot_limits: crate::config::WorldSnapshotLimits,
}

#[derive(Debug, Clone, Serialize)]
pub struct HostData {
    pub id: Uuid,
    pub name: String,
    pub state: PresenceState,
    #[serde(rename = "gamePort", skip_serializing_if = "Option::is_none")]
    pub game_port: Option<u16>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum PresenceState {
    Online,
    Offline,
    Unavailable,
}

#[derive(Debug, Deserialize)]
pub struct HostSessionRequest {
    #[serde(rename = "sessionId")]
    pub session_id: Uuid,
    #[serde(rename = "gamePort")]
    pub game_port: u16,
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
