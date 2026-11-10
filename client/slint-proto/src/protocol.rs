//! Shared stdio framing. No logging or file payloads belong on this stream.
use anyhow::{Context, Result, ensure};
use serde_json::Value;
use std::io::{Read, Write};

pub const VERSION: u32 = 4;
pub const MAX_FRAME: usize = 8 * 1024 * 1024;

/// EOF is clean only between frames; a partial header or payload is an error.
pub fn read_frame(reader: &mut impl Read) -> Result<Option<Value>> {
    let mut header = [0u8; 4];
    loop {
        match reader.read(&mut header[..1]) {
            Ok(0) => return Ok(None),
            Ok(_) => break,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e).context("读取启动服务响应失败"),
        }
    }
    reader
        .read_exact(&mut header[1..])
        .context("启动服务响应头不完整")?;
    let length = u32::from_be_bytes(header) as usize;
    ensure!(length > 0 && length <= MAX_FRAME, "启动服务消息长度无效");
    let mut body = vec![0; length];
    reader.read_exact(&mut body).context("启动服务响应不完整")?;
    let value: Value = serde_json::from_slice(&body).context("启动服务消息格式无效")?;
    ensure!(value.is_object(), "启动服务消息必须是对象");
    Ok(Some(value))
}

pub fn write_frame(writer: &mut impl Write, value: &Value) -> Result<()> {
    ensure!(value.is_object(), "启动服务消息必须是对象");
    let body = serde_json::to_vec(value)?;
    ensure!(
        !body.is_empty() && body.len() <= MAX_FRAME,
        "启动服务消息过大"
    );
    writer.write_all(&(body.len() as u32).to_be_bytes())?;
    writer.write_all(&body)?;
    writer.flush().context("发送启动服务消息失败")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn shared_golden_frames_round_trip() {
        let fixtures: Vec<Value> =
            serde_json::from_str(include_str!("../protocol/fixtures/messages.json")).unwrap();
        assert_eq!(fixtures[0]["params"]["protocolVersion"], VERSION);
        let mut bytes = Vec::new();
        for value in &fixtures {
            write_frame(&mut bytes, value).unwrap();
        }
        let mut reader = Cursor::new(bytes);
        for expected in fixtures {
            assert_eq!(read_frame(&mut reader).unwrap(), Some(expected));
        }
        assert_eq!(read_frame(&mut reader).unwrap(), None);
    }

    #[test]
    fn rejects_truncation_invalid_json_and_oversized_frames() {
        for bytes in [
            vec![0],
            vec![0, 0, 0, 2, b'{'],
            vec![0, 0, 0, 1, b'x'],
            vec![0, 0, 0, 0],
            ((MAX_FRAME + 1) as u32).to_be_bytes().to_vec(),
        ] {
            assert!(read_frame(&mut Cursor::new(bytes)).is_err());
        }
    }
}
