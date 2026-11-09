//! Best-effort, once-per-process collection. Monitor handles never cross the UI thread.
use anyhow::Result;
use serde_json::{Value, json};
use slint::{ComponentHandle, winit_030::WinitWindowAccessor};
use std::{
    collections::BTreeSet,
    sync::{Arc, OnceLock},
    time::{Duration, Instant},
};
static SPEC: OnceLock<Arc<tokio::sync::OnceCell<Option<Value>>>> = OnceLock::new();

pub fn monitors(ui: &crate::AppWindow) -> Value {
    let mut displays = vec![];
    let mut modes = BTreeSet::new();
    ui.window().with_winit_window(|window| {
        for monitor in window.available_monitors() {
            displays.push(json!({"name":monitor.name().unwrap_or_default(),"width":0,"height":0}));
            for mode in monitor.video_modes() {
                modes.insert(format!(
                    "{}x{}@{}",
                    mode.size().width,
                    mode.size().height,
                    mode.refresh_rate_millihertz() as f64 / 1000.
                ));
            }
        }
    });
    json!({"display":displays,"videoMode":modes.into_iter().collect::<Vec<_>>()})
}
pub async fn get(monitors: Value) -> Option<Value> {
    SPEC.get_or_init(|| Arc::new(tokio::sync::OnceCell::new()))
        .get_or_init(|| async {
            let started = Instant::now();
            let task = tokio::task::spawn_blocking(move || collect(monitors));
            match tokio::time::timeout(Duration::from_secs(3), task).await {
                Ok(Ok(Ok(spec))) => {
                    tracing::info!(
                        elapsed_ms = started.elapsed().as_millis(),
                        "硬件信息采集完成"
                    );
                    Some(spec)
                }
                Ok(Ok(Err(error))) => {
                    tracing::warn!(%error,"硬件信息采集失败，省略spec");
                    None
                }
                Ok(Err(error)) => {
                    tracing::warn!(%error,"硬件信息采集任务失败，省略spec");
                    None
                }
                Err(error) => {
                    tracing::warn!(%error,"硬件信息采集超过3秒，省略spec");
                    None
                }
            }
        })
        .await
        .clone()
}
fn available<T: Default>(result: std::result::Result<T, sysinfo::Error>, field: &str) -> T {
    result.unwrap_or_else(|error| {
        tracing::warn!(field,%error,"硬件字段不可用");
        T::default()
    })
}
fn collect(monitors: Value) -> Result<Value> {
    let system = sysinfo::System::new_all()?;
    let cpu = system.cpus().first();
    let mut spec = json!({
        "model":available(sysinfo::Product::name(),"model"),
        "os":available(sysinfo::System::long_os_version(),"os"),
        "cpu":{"name":cpu.map(|c|c.brand().split_whitespace().collect::<Vec<_>>().join(" ")).unwrap_or_default(),
            "cores":available(sysinfo::System::physical_core_count(),"cpu.cores"),"threads":system.cpus().len(),
            "frequency":cpu.map(|c|c.frequency()*1_000_000).unwrap_or_default()},
        "gpus":[],"mems":[],"disk":[],"display":monitors["display"],"videoMode":monitors["videoMode"]
    });
    #[cfg(windows)]
    windows_details(&mut spec)?;
    #[cfg(not(windows))]
    {
        spec["mems"] = json!([{"size":system.total_memory(),"type":"","speed":0}]);
        let disks = sysinfo::Disks::new_with_refreshed_list()?;
        spec["disk"] = json!(
            disks
                .iter()
                .map(
                    |d| json!({"name":d.name().to_string_lossy(),"size":d.total_space(),"model":""})
                )
                .collect::<Vec<_>>()
        );
        let instance = slint::wgpu_30::wgpu::Instance::new(
            slint::wgpu_30::wgpu::InstanceDescriptor::new_without_display_handle(),
        );
        spec["gpus"] = json!(
            pollster::block_on(instance.enumerate_adapters(slint::wgpu_30::wgpu::Backends::all()))
                .iter()
                .map(|a| json!({"name":a.get_info().name,"vram":0}))
                .collect::<Vec<_>>()
        );
    }
    tracing::info!(empty_fields=%empty_fields(&spec).join(","), "硬件字段可用性");
    Ok(spec)
}
fn empty_fields(value: &Value) -> Vec<String> {
    fn visit(value: &Value, path: String, out: &mut Vec<String>) {
        match value {
            Value::Object(map) => {
                for (k, v) in map {
                    visit(
                        v,
                        if path.is_empty() {
                            k.clone()
                        } else {
                            format!("{path}.{k}")
                        },
                        out,
                    )
                }
            }
            Value::Array(list) if !list.is_empty() => {
                for (i, v) in list.iter().enumerate() {
                    visit(v, format!("{path}[{i}]"), out)
                }
            }
            v if v.is_null()
                || v.as_str() == Some("")
                || v.as_u64() == Some(0)
                || v.as_array().is_some_and(Vec::is_empty) =>
            {
                out.push(path)
            }
            _ => {}
        }
    }
    let mut out = vec![];
    visit(value, String::new(), &mut out);
    out
}
#[cfg(windows)]
fn memory_type(code: u64) -> &'static str {
    match code {
        20 => "DDR",
        21 => "DDR2",
        24 => "DDR3",
        26 => "DDR4",
        27 => "LPDDR",
        28 => "LPDDR2",
        29 => "LPDDR3",
        30 => "LPDDR4",
        34 => "DDR5",
        35 => "LPDDR5",
        _ => "",
    }
}
#[cfg(windows)]
fn windows_details(spec: &mut Value) -> Result<()> {
    use wmi::WMIConnection;
    fn query(connection: &WMIConnection, sql: &str) -> Vec<Value> {
        connection
            .raw_query::<std::collections::HashMap<String, Value>>(sql)
            .map(|rows| {
                rows.into_iter()
                    .map(|row| Value::Object(row.into_iter().collect()))
                    .collect()
            })
            .unwrap_or_else(|error| {
                tracing::warn!(%error,query=sql,"WMI字段不可用");
                vec![]
            })
    }
    fn num(v: &Value, key: &str) -> u64 {
        v[key]
            .as_u64()
            .or_else(|| v[key].as_str().and_then(|s| s.parse().ok()))
            .unwrap_or_default()
    }
    fn text(v: &Value, key: &str) -> String {
        v[key].as_str().unwrap_or_default().trim().into()
    }
    let wmi_started = Instant::now();
    let connection = WMIConnection::new()?;
    if let Some(c) = query(
        &connection,
        "SELECT Manufacturer,Model FROM Win32_ComputerSystem",
    )
    .first()
    {
        spec["model"] = json!(format!("{} {}", text(c, "Manufacturer"), text(c, "Model")).trim());
    }
    if let Some(c) = query(&connection, "SELECT MaxClockSpeed FROM Win32_Processor").first()
        && num(c, "MaxClockSpeed") > 0
    {
        spec["cpu"]["frequency"] = json!(num(c, "MaxClockSpeed") * 1_000_000);
    }
    spec["mems"]=json!(query(&connection,"SELECT Capacity,SMBIOSMemoryType,Speed FROM Win32_PhysicalMemory").iter().map(|m|json!({"size":num(m,"Capacity"),"type":memory_type(num(m,"SMBIOSMemoryType")),"speed":num(m,"Speed")*1_000_000})).collect::<Vec<_>>());
    spec["disk"] = json!(
        query(&connection, "SELECT Name,Size,Model FROM Win32_DiskDrive")
            .iter()
            .map(|d| json!({"name":text(d,"Name"),"size":num(d,"Size"),"model":text(d,"Model")}))
            .collect::<Vec<_>>()
    );
    let video = query(&connection, "SELECT Name FROM Win32_VideoController");
    let dxgi = dxgi_memory().unwrap_or_else(|error| {
        tracing::warn!(%error,"DXGI显存不可用");
        vec![]
    });
    spec["gpus"] = json!(
        video
            .iter()
            .map(|g| {
                let name = text(g, "Name");
                let vram = dxgi
                    .iter()
                    .find(|(n, _)| n.trim() == name)
                    .map(|(_, n)| *n)
                    .unwrap_or_default();
                json!({"name":name,"vram":vram})
            })
            .collect::<Vec<_>>()
    );
    match WMIConnection::with_namespace_path("ROOT\\wmi") {
        Ok(monitors) => {
            let ids = query(
                &monitors,
                "SELECT InstanceName,UserFriendlyName FROM WmiMonitorID",
            );
            let sizes = query(
                &monitors,
                "SELECT InstanceName,MaxHorizontalImageSize,MaxVerticalImageSize FROM WmiMonitorBasicDisplayParams",
            );
            let displays=ids.iter().map(|m|{
                let name=m["UserFriendlyName"].as_array().map(|a|a.iter().filter_map(|c|c.as_u64()).take_while(|c|*c!=0).filter_map(|c|char::from_u32(c as u32)).collect::<String>()).unwrap_or_default();
                let size=sizes.iter().find(|s|s["InstanceName"]==m["InstanceName"]);
                json!({"name":name,"width":size.map(|s|num(s,"MaxHorizontalImageSize")).unwrap_or_default(),"height":size.map(|s|num(s,"MaxVerticalImageSize")).unwrap_or_default()})
            }).collect::<Vec<_>>();
            if !displays.is_empty() {
                spec["display"] = json!(displays);
            }
        }
        Err(error) => tracing::warn!(%error,"WMI显示器信息不可用"),
    }
    tracing::info!(
        elapsed_ms = wmi_started.elapsed().as_millis(),
        "WMI硬件查询完成"
    );
    Ok(())
}
#[cfg(windows)]
fn dxgi_memory() -> Result<Vec<(String, u64)>> {
    use windows::Win32::Graphics::Dxgi::{CreateDXGIFactory1, DXGI_ERROR_NOT_FOUND, IDXGIFactory1};
    let mut out = vec![];
    // The COM interfaces stay on this blocking thread, and no application pointers are passed.
    unsafe {
        let factory: IDXGIFactory1 = CreateDXGIFactory1()?;
        let mut index = 0;
        loop {
            match factory.EnumAdapters1(index) {
                Ok(adapter) => {
                    let desc = adapter.GetDesc1()?;
                    let end = desc
                        .Description
                        .iter()
                        .position(|c| *c == 0)
                        .unwrap_or(desc.Description.len());
                    out.push((
                        String::from_utf16_lossy(&desc.Description[..end]),
                        desc.DedicatedVideoMemory as u64,
                    ));
                }
                Err(error) if error.code() == DXGI_ERROR_NOT_FOUND => break,
                Err(error) => return Err(error.into()),
            }
            index += 1;
        }
    }
    Ok(out)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_fixture_has_every_required_field() {
        let v: Value =
            serde_json::from_str(include_str!("../protocol/fixtures/hwspec.json")).unwrap();
        for key in [
            "model",
            "os",
            "cpu",
            "gpus",
            "mems",
            "disk",
            "display",
            "videoMode",
        ] {
            assert!(v.get(key).is_some());
        }
        for (key, fields) in [
            ("cpu", vec!["name", "cores", "threads", "frequency"]),
            ("gpus", vec!["name", "vram"]),
            ("mems", vec!["size", "type", "speed"]),
            ("disk", vec!["name", "size", "model"]),
            ("display", vec!["name", "height", "width"]),
        ] {
            let item = if key == "cpu" { &v[key] } else { &v[key][0] };
            for field in fields {
                assert!(item.get(field).is_some());
            }
        }
    }
}
