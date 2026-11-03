use anyhow::{Context, Result, anyhow, bail};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use uuid::Uuid;

pub const PREFACE: &[u8; 4] = b"DMG1";
pub const ROLE_CONTROL: u8 = 1;
pub const ROLE_ATTACH: u8 = 2;
/// A control connection that also carries the takeover claim it was elected with.
pub const ROLE_CONTROL_CLAIM: u8 = 3;

pub const READY: u8 = 1;
pub const BUSY: u8 = 2;
pub const NO_PORT: u8 = 3;
pub const OPEN: u8 = 4;
pub const FAILED: u8 = 5;
pub const PING: u8 = 6;
pub const PONG: u8 = 7;
pub const HOST_NOT_FOUND: u8 = 8;
pub const MASTER_UNAVAILABLE: u8 = 9;
pub const CLAIM_REFUSED: u8 = 10;
/// The player's ticket is invalid or expired; a new one may work.
pub const TICKET_REFUSED: u8 = 11;
/// Without a takeover claim, only the room's last host may register.
pub const NOT_OWNER: u8 = 12;

const MAX_TICKET_BYTES: usize = 2048;

pub enum TunnelHandshake {
    Register {
        host: Uuid,
        claim: Option<Uuid>,
        ticket: String,
    },
    Attach { session: Uuid, connection: u64 },
}

pub async fn read_tunnel_handshake<R: AsyncRead + Unpin>(
    reader: &mut R,
) -> Result<TunnelHandshake> {
    let mut prefix = [0u8; 4];
    reader
        .read_exact(&mut prefix)
        .await
        .context("read DM gateway preface")?;
    if &prefix != PREFACE {
        bail!("invalid DM gateway preface");
    }
    let mut role = [0u8; 1];
    reader
        .read_exact(&mut role)
        .await
        .context("read DM gateway role")?;
    match role[0] {
        ROLE_CONTROL => {
            let mut uuid = [0u8; 16];
            reader.read_exact(&mut uuid).await.context("read host id")?;
            Ok(TunnelHandshake::Register {
                host: Uuid::from_bytes(uuid),
                claim: None,
                ticket: read_ticket(reader).await?,
            })
        }
        ROLE_CONTROL_CLAIM => {
            let mut host = [0u8; 16];
            reader.read_exact(&mut host).await.context("read host id")?;
            let mut claim = [0u8; 16];
            reader
                .read_exact(&mut claim)
                .await
                .context("read takeover claim")?;
            Ok(TunnelHandshake::Register {
                host: Uuid::from_bytes(host),
                claim: Some(Uuid::from_bytes(claim)),
                ticket: read_ticket(reader).await?,
            })
        }
        ROLE_ATTACH => {
            let mut uuid = [0u8; 16];
            reader
                .read_exact(&mut uuid)
                .await
                .context("read session id")?;
            let connection = read_u64(reader).await?;
            Ok(TunnelHandshake::Attach {
                session: Uuid::from_bytes(uuid),
                connection,
            })
        }
        other => bail!("invalid DM gateway role {other}"),
    }
}

async fn read_ticket<R: AsyncRead + Unpin>(reader: &mut R) -> Result<String> {
    let length = usize::from(reader.read_u16().await.context("read ticket length")?);
    if length == 0 || length > MAX_TICKET_BYTES {
        bail!("invalid ticket length {length}");
    }
    let mut ticket = vec![0u8; length];
    reader.read_exact(&mut ticket).await.context("read ticket")?;
    String::from_utf8(ticket).context("ticket is not text")
}

pub async fn write_ready<W: AsyncWrite + Unpin>(
    writer: &mut W,
    session: Uuid,
    game_port: u16,
) -> Result<()> {
    writer.write_u8(READY).await?;
    writer.write_all(session.as_bytes()).await?;
    writer.write_u16(game_port).await?;
    writer.flush().await.context("flush READY response")
}

pub async fn write_status<W: AsyncWrite + Unpin>(writer: &mut W, status: u8) -> Result<()> {
    if !matches!(
        status,
        BUSY | NO_PORT | HOST_NOT_FOUND | MASTER_UNAVAILABLE | CLAIM_REFUSED | TICKET_REFUSED | NOT_OWNER
    ) {
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

pub async fn write_connection_opcode<W: AsyncWrite + Unpin>(
    writer: &mut W,
    opcode: u8,
    connection: u64,
) -> Result<()> {
    if !matches!(opcode, OPEN | FAILED) {
        bail!("invalid connection opcode {opcode}");
    }
    writer.write_u8(opcode).await?;
    writer.write_u64(connection).await?;
    writer.flush().await.context("flush connection opcode")
}

async fn read_u64<R: AsyncRead + Unpin>(reader: &mut R) -> Result<u64> {
    reader.read_u64().await.context("read u64")
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;

    #[tokio::test]
    async fn control_handshake_reads_uuidv7_host_id() {
        let host = Uuid::now_v7();
        let (mut writer, mut reader) = duplex(64);
        tokio::spawn(async move {
            writer.write_all(PREFACE).await.unwrap();
            writer.write_u8(ROLE_CONTROL).await.unwrap();
            writer.write_all(host.as_bytes()).await.unwrap();
            writer.write_u16(6).await.unwrap();
            writer.write_all(b"ticket").await.unwrap();
        });

        assert!(
            matches!(read_tunnel_handshake(&mut reader).await.unwrap(), TunnelHandshake::Register { host: actual, claim: None, ticket } if actual == host && ticket == "ticket")
        );
    }

    #[tokio::test]
    async fn claim_handshake_reads_host_and_claim_ids() {
        let host = Uuid::now_v7();
        let claim = Uuid::now_v7();
        let (mut writer, mut reader) = duplex(64);
        tokio::spawn(async move {
            writer.write_all(PREFACE).await.unwrap();
            writer.write_u8(ROLE_CONTROL_CLAIM).await.unwrap();
            writer.write_all(host.as_bytes()).await.unwrap();
            writer.write_all(claim.as_bytes()).await.unwrap();
            writer.write_u16(6).await.unwrap();
            writer.write_all(b"ticket").await.unwrap();
        });

        assert!(
            matches!(read_tunnel_handshake(&mut reader).await.unwrap(), TunnelHandshake::Register { host: actual, claim: Some(presented), ticket } if actual == host && presented == claim && ticket == "ticket")
        );
    }

    #[tokio::test]
    async fn a_registration_without_a_usable_ticket_is_invalid() {
        for length in [0_u16, 2049] {
            let host = Uuid::now_v7();
            let (mut writer, mut reader) = duplex(64);
            tokio::spawn(async move {
                writer.write_all(PREFACE).await.unwrap();
                writer.write_u8(ROLE_CONTROL).await.unwrap();
                writer.write_all(host.as_bytes()).await.unwrap();
                writer.write_u16(length).await.unwrap();
            });
            assert!(read_tunnel_handshake(&mut reader).await.is_err());
        }
    }

    #[tokio::test]
    async fn ticket_refusals_are_valid_registration_statuses() {
        for status in [TICKET_REFUSED, NOT_OWNER] {
            let (mut writer, mut reader) = duplex(8);
            write_status(&mut writer, status).await.unwrap();
            assert_eq!(reader.read_u8().await.unwrap(), status);
        }
    }

    #[tokio::test]
    async fn claim_refused_is_a_valid_registration_status() {
        let (mut writer, mut reader) = duplex(8);
        write_status(&mut writer, CLAIM_REFUSED).await.unwrap();
        assert_eq!(reader.read_u8().await.unwrap(), CLAIM_REFUSED);
    }
}
