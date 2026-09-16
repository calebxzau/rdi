use anyhow::{anyhow, bail, Context, Result};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use uuid::Uuid;

pub const PREFACE: &[u8; 4] = b"DMG1";
pub const ROLE_CONTROL: u8 = 1;
pub const ROLE_ATTACH: u8 = 2;

pub const READY: u8 = 1;
pub const BUSY: u8 = 2;
pub const NO_PORT: u8 = 3;
pub const OPEN: u8 = 4;
pub const FAILED: u8 = 5;
pub const PING: u8 = 6;
pub const PONG: u8 = 7;

pub const MAX_ROOM_BYTES: usize = 128;

pub enum TunnelHandshake {
    Register { room: String },
    Attach { session: Uuid, connection: u64 },
}

pub async fn read_tunnel_handshake<R: AsyncRead + Unpin>(reader: &mut R) -> Result<TunnelHandshake> {
    let mut prefix = [0u8; 4];
    reader.read_exact(&mut prefix).await.context("read DM gateway preface")?;
    if &prefix != PREFACE {
        bail!("invalid DM gateway preface");
    }
    let mut role = [0u8; 1];
    reader.read_exact(&mut role).await.context("read DM gateway role")?;
    match role[0] {
        ROLE_CONTROL => {
            let length = read_u16(reader).await? as usize;
            if length == 0 || length > MAX_ROOM_BYTES {
                bail!("invalid room name length {length}");
            }
            let mut bytes = vec![0u8; length];
            reader.read_exact(&mut bytes).await.context("read room name")?;
            let room = String::from_utf8(bytes).context("room name is not UTF-8")?;
            if room.is_empty() || room.len() > MAX_ROOM_BYTES {
                bail!("invalid room name");
            }
            Ok(TunnelHandshake::Register { room })
        }
        ROLE_ATTACH => {
            let mut uuid = [0u8; 16];
            reader.read_exact(&mut uuid).await.context("read session id")?;
            let connection = read_u64(reader).await?;
            Ok(TunnelHandshake::Attach { session: Uuid::from_bytes(uuid), connection })
        }
        other => bail!("invalid DM gateway role {other}"),
    }
}

pub async fn write_ready<W: AsyncWrite + Unpin>(writer: &mut W, session: Uuid, game_port: u16) -> Result<()> {
    writer.write_u8(READY).await?;
    writer.write_all(session.as_bytes()).await?;
    writer.write_u16(game_port).await?;
    writer.flush().await.context("flush READY response")
}

pub async fn write_status<W: AsyncWrite + Unpin>(writer: &mut W, status: u8) -> Result<()> {
    if status != BUSY && status != NO_PORT {
        return Err(anyhow!("invalid status opcode {status}"));
    }
    writer.write_u8(status).await?;
    writer.flush().await.context("flush status response")
}

pub async fn read_host_message<R: AsyncRead + Unpin>(reader: &mut R) -> Result<HostMessage> {
    match reader.read_u8().await.context("read host control opcode")? {
        PONG => Ok(HostMessage::Pong),
        FAILED => Ok(HostMessage::Failed(read_u64(reader).await?)),
        other => bail!("invalid host control opcode {other}"),
    }
}

pub enum HostMessage {
    Pong,
    Failed(u64),
}

pub async fn write_control_opcode<W: AsyncWrite + Unpin>(writer: &mut W, opcode: u8) -> Result<()> {
    if !matches!(opcode, PING | PONG) {
        bail!("invalid control opcode {opcode}");
    }
    writer.write_u8(opcode).await?;
    writer.flush().await.context("flush control opcode")
}

pub async fn write_connection_opcode<W: AsyncWrite + Unpin>(writer: &mut W, opcode: u8, connection: u64) -> Result<()> {
    if !matches!(opcode, OPEN | FAILED) {
        bail!("invalid connection opcode {opcode}");
    }
    writer.write_u8(opcode).await?;
    writer.write_u64(connection).await?;
    writer.flush().await.context("flush connection opcode")
}

async fn read_u16<R: AsyncRead + Unpin>(reader: &mut R) -> Result<u16> {
    reader.read_u16().await.context("read u16")
}

async fn read_u64<R: AsyncRead + Unpin>(reader: &mut R) -> Result<u64> {
    reader.read_u64().await.context("read u64")
}
