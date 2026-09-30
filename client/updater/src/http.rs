use std::collections::HashMap;
use std::ffi::c_void;
use std::io::{self, Read};
use std::mem::size_of;
use std::os::windows::ffi::OsStrExt;
use std::ptr::{null, null_mut};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use serde::de::DeserializeOwned;
use windows_sys::Win32::Foundation::{ERROR_INSUFFICIENT_BUFFER, GetLastError};
use windows_sys::Win32::Networking::WinHttp::*;

#[derive(Clone, Copy)]
pub struct Client {
    timeout_ms: i32,
}

impl Client {
    pub fn with_timeout(timeout: Duration) -> Self {
        // WinHTTP uses zero for an infinite timeout, as requested by download callers.
        let timeout_ms = if timeout.is_zero() {
            0
        } else {
            timeout.as_millis().clamp(1, i32::MAX as u128) as i32
        };
        Self { timeout_ms }
    }

    pub fn get(&self, url: impl Into<String>) -> Request {
        Request {
            timeout_ms: self.timeout_ms,
            url: url.into(),
            headers: Vec::new(),
        }
    }
}

pub struct Request {
    timeout_ms: i32,
    url: String,
    headers: Vec<(String, String)>,
}

impl Request {
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }

    pub fn send(self) -> Result<Response> {
        let parsed_url = ParsedUrl::parse(&self.url)?;
        let user_agent = wide("RDI-Updater/1.0");
        let session = checked_handle(
            unsafe {
                WinHttpOpen(
                    user_agent.as_ptr(),
                    WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                    null(),
                    null(),
                    0,
                )
            },
            "WinHttpOpen",
        )?;
        if unsafe {
            WinHttpSetTimeouts(
                session.raw(),
                self.timeout_ms,
                self.timeout_ms,
                self.timeout_ms,
                self.timeout_ms,
            )
        } == 0
        {
            return Err(winhttp_error("WinHttpSetTimeouts"));
        }

        let host = wide(&parsed_url.host);
        let connection = checked_handle(
            unsafe { WinHttpConnect(session.raw(), host.as_ptr(), parsed_url.port, 0) },
            "WinHttpConnect",
        )?;
        let method = wide("GET");
        let object_name = wide(&parsed_url.path);
        let flags = if parsed_url.secure {
            WINHTTP_FLAG_SECURE
        } else {
            0
        };
        let request = checked_handle(
            unsafe {
                WinHttpOpenRequest(
                    connection.raw(),
                    method.as_ptr(),
                    object_name.as_ptr(),
                    null(),
                    null(),
                    null(),
                    flags,
                )
            },
            "WinHttpOpenRequest",
        )?;

        if !self.headers.is_empty() {
            let headers = self
                .headers
                .iter()
                .map(|(name, value)| format!("{name}: {value}\r\n"))
                .collect::<String>();
            let headers = wide(&headers);
            if unsafe {
                WinHttpAddRequestHeaders(
                    request.raw(),
                    headers.as_ptr(),
                    (headers.len() - 1) as u32,
                    WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE,
                )
            } == 0
            {
                return Err(winhttp_error("WinHttpAddRequestHeaders"));
            }
        }

        if unsafe { WinHttpSendRequest(request.raw(), null(), 0, null(), 0, 0, 0) } == 0 {
            return Err(winhttp_error("WinHttpSendRequest"));
        }
        if unsafe { WinHttpReceiveResponse(request.raw(), null_mut()) } == 0 {
            return Err(winhttp_error("WinHttpReceiveResponse"));
        }

        let status = query_status(request.raw())?;
        let headers = query_headers(request.raw())?;
        Ok(Response {
            handles: Handles {
                request,
                _connection: connection,
                _session: session,
            },
            status,
            headers,
        })
    }
}

pub struct Response {
    handles: Handles,
    status: u16,
    headers: HashMap<String, String>,
}

impl Response {
    pub fn status(&self) -> u16 {
        self.status
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .get(&name.to_ascii_lowercase())
            .map(String::as_str)
    }

    pub fn content_length(&self) -> Option<u64> {
        self.header("Content-Length")?.parse().ok()
    }

    pub fn error_for_status(self) -> Result<Self> {
        if (200..300).contains(&self.status) {
            Ok(self)
        } else {
            bail!("HTTP响应状态码{}", self.status)
        }
    }

    pub fn text(mut self) -> Result<String> {
        let mut body = Vec::new();
        self.read_to_end(&mut body)
            .context("读取HTTP文本响应失败")?;
        Ok(String::from_utf8_lossy(&body).into_owned())
    }

    pub fn json<T: DeserializeOwned>(mut self) -> Result<T> {
        let mut body = Vec::new();
        self.read_to_end(&mut body)
            .context("读取HTTP JSON响应失败")?;
        serde_json::from_slice(&body).context("解析HTTP JSON响应失败")
    }
}

impl Read for Response {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        let mut available = 0u32;
        if unsafe { WinHttpQueryDataAvailable(self.handles.request.raw(), &mut available) } == 0 {
            return Err(winhttp_io_error("WinHttpQueryDataAvailable"));
        }
        if available == 0 {
            return Ok(0);
        }

        let to_read = available.min(buffer.len() as u32);
        let mut bytes_read = 0u32;
        if unsafe {
            WinHttpReadData(
                self.handles.request.raw(),
                buffer.as_mut_ptr().cast::<c_void>(),
                to_read,
                &mut bytes_read,
            )
        } == 0
        {
            return Err(winhttp_io_error("WinHttpReadData"));
        }
        Ok(bytes_read as usize)
    }
}

struct Handles {
    request: Handle,
    _connection: Handle,
    _session: Handle,
}

struct Handle(*mut c_void);

impl Handle {
    fn raw(&self) -> *mut c_void {
        self.0
    }
}

impl Drop for Handle {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                WinHttpCloseHandle(self.0);
            }
        }
    }
}

fn checked_handle(raw: *mut c_void, operation: &str) -> Result<Handle> {
    if raw.is_null() {
        Err(winhttp_error(operation))
    } else {
        Ok(Handle(raw))
    }
}

fn query_status(request: *mut c_void) -> Result<u16> {
    let mut status = 0u32;
    let mut length = size_of::<u32>() as u32;
    if unsafe {
        WinHttpQueryHeaders(
            request,
            WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
            null(),
            (&mut status as *mut u32).cast::<c_void>(),
            &mut length,
            null_mut(),
        )
    } == 0
    {
        return Err(winhttp_error("WinHttpQueryHeaders(status)"));
    }
    Ok(status as u16)
}

fn query_headers(request: *mut c_void) -> Result<HashMap<String, String>> {
    let mut required = 0u32;
    unsafe {
        WinHttpQueryHeaders(
            request,
            WINHTTP_QUERY_RAW_HEADERS_CRLF,
            null(),
            null_mut(),
            &mut required,
            null_mut(),
        );
    }
    if required == 0 && unsafe { GetLastError() } != ERROR_INSUFFICIENT_BUFFER {
        return Err(winhttp_error("WinHttpQueryHeaders(size)"));
    }

    let mut buffer = vec![0u16; (required as usize / size_of::<u16>()) + 1];
    let mut length = (buffer.len() * size_of::<u16>()) as u32;
    if unsafe {
        WinHttpQueryHeaders(
            request,
            WINHTTP_QUERY_RAW_HEADERS_CRLF,
            null(),
            buffer.as_mut_ptr().cast::<c_void>(),
            &mut length,
            null_mut(),
        )
    } == 0
    {
        return Err(winhttp_error("WinHttpQueryHeaders(headers)"));
    }

    let header_text = String::from_utf16_lossy(&buffer[..(length as usize / size_of::<u16>())])
        .trim_end_matches('\0')
        .to_owned();
    let mut headers = HashMap::new();
    for line in header_text.lines() {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_owned());
    }
    Ok(headers)
}

fn winhttp_error(operation: &str) -> anyhow::Error {
    anyhow!("{operation}失败，WinHTTP错误码{}", unsafe {
        GetLastError()
    })
}

fn winhttp_io_error(operation: &str) -> io::Error {
    io::Error::other(format!("{operation}失败，WinHTTP错误码{}", unsafe {
        GetLastError()
    }))
}

fn wide(value: &str) -> Vec<u16> {
    std::ffi::OsStr::new(value)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect()
}

struct ParsedUrl {
    secure: bool,
    host: String,
    port: u16,
    path: String,
}

impl ParsedUrl {
    fn parse(url: &str) -> Result<Self> {
        let (secure, rest) = if let Some(rest) = url.strip_prefix("https://") {
            (true, rest)
        } else if let Some(rest) = url.strip_prefix("http://") {
            (false, rest)
        } else {
            bail!("WinHTTP仅支持http和https URL: {url}");
        };

        let authority_end = rest
            .find(|character: char| matches!(character, '/' | '?' | '#'))
            .unwrap_or(rest.len());
        let authority = &rest[..authority_end];
        if authority.is_empty() {
            bail!("URL缺少主机名: {url}");
        }

        let (host, port) = if let Some(after_open) = authority.strip_prefix('[') {
            let close = after_open
                .find(']')
                .ok_or_else(|| anyhow!("IPv6 URL缺少结束括号: {url}"))?;
            let host = after_open[..close].to_owned();
            let port = after_open[close + 1..]
                .strip_prefix(':')
                .map(str::parse)
                .transpose()?
                .unwrap_or_else(|| default_port(secure));
            (host, port)
        } else if let Some((host, port)) = authority.rsplit_once(':') {
            if port.is_empty() {
                bail!("URL端口为空: {url}");
            }
            (host.to_owned(), port.parse()?)
        } else {
            (authority.to_owned(), default_port(secure))
        };

        let mut path = rest[authority_end..]
            .split('#')
            .next()
            .unwrap_or_default()
            .to_owned();
        if path.is_empty() {
            path.push('/');
        } else if path.starts_with('?') {
            path.insert(0, '/');
        }
        Ok(Self {
            secure,
            host,
            port,
            path,
        })
    }
}

fn default_port(secure: bool) -> u16 {
    if secure { 443 } else { 80 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn infinite_download_timeout_remains_infinite() {
        assert_eq!(Client::with_timeout(Duration::ZERO).timeout_ms, 0);
    }

    #[test]
    fn finite_timeouts_remain_nonzero_and_do_not_overflow() {
        assert_eq!(
            Client::with_timeout(Duration::from_secs(5)).timeout_ms,
            5_000
        );
        assert_eq!(Client::with_timeout(Duration::from_nanos(1)).timeout_ms, 1);
        assert_eq!(Client::with_timeout(Duration::MAX).timeout_ms, i32::MAX);
    }
}
