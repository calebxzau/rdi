#![cfg(windows)]

use std::ffi::{OsStr, c_void};
use std::mem::size_of;
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use std::ptr::{null, null_mut};

use anyhow::{Context, Result, anyhow, bail};

pub type RawHandle = *mut c_void;

pub const INVALID_HANDLE_VALUE: RawHandle = -1isize as RawHandle;
pub const ERROR_ALREADY_EXISTS: u32 = 183;
pub const ERROR_MORE_DATA: u32 = 234;
pub const ERROR_INVALID_HANDLE: u32 = 6;
pub const ERROR_CANCELLED: u32 = 1223;
pub const STILL_ACTIVE: u32 = 259;

pub const PROCESS_TERMINATE: u32 = 0x0001;
pub const PROCESS_QUERY_LIMITED_INFORMATION: u32 = 0x1000;
pub const SYNCHRONIZE: u32 = 0x0010_0000;

pub const WAIT_OBJECT_0: u32 = 0;
pub const INFINITE: u32 = 0xFFFF_FFFF;

pub const MB_ICONERROR: u32 = 0x0000_0010;
pub const MB_YESNO: u32 = 0x0000_0004;
pub const MB_ICONQUESTION: u32 = 0x0000_0020;
pub const IDYES: i32 = 6;
pub const SW_HIDE: i32 = 0;
pub const SEE_MASK_NOCLOSEPROCESS: u32 = 0x0000_0040;

pub const BIF_RETURNONLYFSDIRS: u32 = 0x0000_0001;
pub const BIF_EDITBOX: u32 = 0x0000_0010;
pub const BIF_NEWDIALOGSTYLE: u32 = 0x0000_0040;
pub const COINIT_APARTMENTTHREADED: u32 = 0x0000_0002;

pub const FO_DELETE: u32 = 3;
pub const FOF_SILENT: u16 = 0x0004;
pub const FOF_NOCONFIRMATION: u16 = 0x0010;
pub const FOF_ALLOWUNDO: u16 = 0x0040;
pub const FOF_NOERRORUI: u16 = 0x0400;

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FileTime {
    pub low_date_time: u32,
    pub high_date_time: u32,
}

#[repr(C)]
pub struct BrowseInfoW {
    pub hwnd_owner: RawHandle,
    pub root: RawHandle,
    pub display_name: *mut u16,
    pub title: *const u16,
    pub flags: u32,
    pub callback: RawHandle,
    pub callback_data: isize,
    pub image: i32,
}

#[repr(C)]
pub struct ShellExecuteInfoW {
    pub cb_size: u32,
    pub f_mask: u32,
    pub hwnd: RawHandle,
    pub lp_verb: *const u16,
    pub lp_file: *const u16,
    pub lp_parameters: *const u16,
    pub lp_directory: *const u16,
    pub n_show: i32,
    pub h_inst_app: RawHandle,
    pub lp_id_list: RawHandle,
    pub lp_class: *const u16,
    pub hkey_class: RawHandle,
    pub dw_hot_key: u32,
    pub h_icon_or_monitor: RawHandle,
    pub h_process: RawHandle,
}

#[repr(C)]
pub struct ShFileOpStructW {
    pub hwnd: RawHandle,
    pub w_func: u32,
    pub p_from: *const u16,
    pub p_to: *const u16,
    pub f_flags: u16,
    pub f_any_operations_aborted: i32,
    pub h_name_mappings: RawHandle,
    pub lpsz_progress_title: *const u16,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct RmUniqueProcess {
    pub process_id: u32,
    pub process_start_time: FileTime,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct RmProcessInfo {
    pub process: RmUniqueProcess,
    pub app_name: [u16; 257],
    pub service_short_name: [u16; 65],
    pub application_type: u32,
    pub app_status: u32,
    pub ts_session_id: u32,
    pub restartable: i32,
}

impl Default for RmProcessInfo {
    fn default() -> Self {
        Self {
            process: RmUniqueProcess::default(),
            app_name: [0; 257],
            service_short_name: [0; 65],
            application_type: 0,
            app_status: 0,
            ts_session_id: 0,
            restartable: 0,
        }
    }
}

#[link(name = "kernel32")]
unsafe extern "system" {
    pub fn GetLastError() -> u32;
    pub fn GetCurrentProcessId() -> u32;
    pub fn GetConsoleWindow() -> RawHandle;
    pub fn CreateMutexW(attributes: RawHandle, initially_owned: i32, name: *const u16)
    -> RawHandle;
    pub fn CloseHandle(handle: RawHandle) -> i32;
    pub fn WaitForSingleObject(handle: RawHandle, milliseconds: u32) -> u32;
    pub fn GetExitCodeProcess(handle: RawHandle, exit_code: *mut u32) -> i32;
    pub fn TerminateProcess(handle: RawHandle, exit_code: u32) -> i32;
    pub fn OpenProcess(desired_access: u32, inherit_handle: i32, process_id: u32) -> RawHandle;
    pub fn GetProcessTimes(
        process: RawHandle,
        creation_time: *mut FileTime,
        exit_time: *mut FileTime,
        kernel_time: *mut FileTime,
        user_time: *mut FileTime,
    ) -> i32;
    pub fn LocalFree(memory: RawHandle) -> RawHandle;
}

#[link(name = "user32")]
unsafe extern "system" {
    pub fn GetAsyncKeyState(virtual_key: i32) -> i16;
    pub fn MessageBoxW(
        window_handle: RawHandle,
        text: *const u16,
        caption: *const u16,
        message_type: u32,
    ) -> i32;
}

#[link(name = "ole32")]
unsafe extern "system" {
    pub fn CoInitializeEx(reserved: RawHandle, concurrency_model: u32) -> i32;
    pub fn CoUninitialize();
    pub fn CoTaskMemFree(memory: RawHandle);
}

#[link(name = "shell32")]
unsafe extern "system" {
    pub fn SHBrowseForFolderW(browse_info: *mut BrowseInfoW) -> RawHandle;
    pub fn SHGetPathFromIDListEx(
        item_id_list: RawHandle,
        path: *mut u16,
        path_length: u32,
        flags: u32,
    ) -> i32;
    pub fn CommandLineToArgvW(command_line: *const u16, argument_count: *mut i32) -> *mut *mut u16;
    pub fn ShellExecuteExW(execute_info: *mut ShellExecuteInfoW) -> i32;
    pub fn SHFileOperationW(file_operation: *mut ShFileOpStructW) -> i32;
}

#[link(name = "rstrtmgr")]
unsafe extern "system" {
    pub fn RmStartSession(
        session_handle: *mut u32,
        session_flags: u32,
        session_key: *mut u16,
    ) -> u32;
    pub fn RmRegisterResources(
        session_handle: u32,
        file_count: u32,
        files: *const *const u16,
        application_count: u32,
        applications: RawHandle,
        service_count: u32,
        services: RawHandle,
    ) -> u32;
    pub fn RmGetList(
        session_handle: u32,
        process_info_needed: *mut u32,
        process_info_count: *mut u32,
        process_info: *mut RmProcessInfo,
        reboot_reasons: *mut u32,
    ) -> u32;
    pub fn RmEndSession(session_handle: u32) -> u32;
}

pub struct OwnedHandle(pub RawHandle);

unsafe impl Send for OwnedHandle {}
unsafe impl Sync for OwnedHandle {}

impl OwnedHandle {
    pub fn is_valid(&self) -> bool {
        !self.0.is_null() && self.0 != INVALID_HANDLE_VALUE
    }
}

impl Drop for OwnedHandle {
    fn drop(&mut self) {
        if self.is_valid() {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
}

pub fn wide(value: &str) -> Vec<u16> {
    OsStr::new(value)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect()
}

pub fn wide_path(path: &Path) -> Vec<u16> {
    wide(&path.to_string_lossy())
}

pub fn from_wide(value: &[u16]) -> String {
    let length = value
        .iter()
        .position(|item| *item == 0)
        .unwrap_or(value.len());
    String::from_utf16_lossy(&value[..length])
}

pub fn message_box(text: &str, caption: &str, message_type: u32) -> i32 {
    let text = wide(text);
    let caption = wide(caption);
    unsafe { MessageBoxW(null_mut(), text.as_ptr(), caption.as_ptr(), message_type) }
}

pub fn mutex(name: &str) -> Result<(OwnedHandle, bool)> {
    let name = wide(name);
    let handle = unsafe { CreateMutexW(null_mut(), 1, name.as_ptr()) };
    if handle.is_null() {
        bail!("创建updater互斥锁失败，错误码{}", unsafe {
            GetLastError()
        });
    }
    let already_exists = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    Ok((OwnedHandle(handle), !already_exists))
}

pub fn is_left_shift_pressed() -> bool {
    unsafe { (GetAsyncKeyState(0xA0) as u16 & 0x8000) != 0 }
}

pub fn process_id() -> u32 {
    unsafe { GetCurrentProcessId() }
}

pub fn parse_windows_command_line(arguments: &str) -> Result<Vec<String>> {
    let command_line = wide(&format!("updater {arguments}"));
    let mut argument_count = 0;
    let argument_list = unsafe { CommandLineToArgvW(command_line.as_ptr(), &mut argument_count) };
    if argument_list.is_null() {
        bail!("无法解析JVM参数，错误码{}", unsafe {
            GetLastError()
        });
    }

    let result = (|| -> Result<Vec<String>> {
        let pointers =
            unsafe { std::slice::from_raw_parts(argument_list, argument_count as usize) };
        let mut parsed = Vec::new();
        for pointer in pointers.iter().skip(1) {
            if pointer.is_null() {
                continue;
            }
            let mut length = 0;
            unsafe {
                while *pointer.add(length) != 0 {
                    length += 1;
                }
            }
            let value = unsafe { std::slice::from_raw_parts(*pointer, length) };
            parsed.push(String::from_utf16(value).context("JVM参数不是有效UTF-16")?);
        }
        Ok(parsed)
    })();
    unsafe {
        LocalFree(argument_list as RawHandle);
    }
    result
}

pub fn pick_folder_path(title: &str) -> Result<Option<String>> {
    let status = unsafe { CoInitializeEx(null_mut(), COINIT_APARTMENTTHREADED) };
    if status < 0 {
        bail!("初始化文件夹选择器失败，错误码{status}");
    }
    let result = (|| -> Result<Option<String>> {
        let mut display_name = vec![0u16; 260];
        let title = wide(title);
        let mut browse_info = BrowseInfoW {
            hwnd_owner: unsafe { GetConsoleWindow() },
            root: null_mut(),
            display_name: display_name.as_mut_ptr(),
            title: title.as_ptr(),
            flags: BIF_RETURNONLYFSDIRS | BIF_EDITBOX | BIF_NEWDIALOGSTYLE,
            callback: null_mut(),
            callback_data: 0,
            image: 0,
        };
        let item_id_list = unsafe { SHBrowseForFolderW(&mut browse_info) };
        if item_id_list.is_null() {
            return Ok(None);
        }
        let result = (|| -> Result<Option<String>> {
            let mut path = vec![0u16; 32_768];
            let success = unsafe {
                SHGetPathFromIDListEx(item_id_list, path.as_mut_ptr(), path.len() as u32, 0)
            };
            Ok((success != 0).then(|| from_wide(&path)))
        })();
        unsafe {
            CoTaskMemFree(item_id_list);
        }
        result
    })();
    unsafe {
        CoUninitialize();
    }
    result
}

pub fn wait_process(handle: RawHandle, timeout_milliseconds: u32) -> bool {
    unsafe { WaitForSingleObject(handle, timeout_milliseconds) == WAIT_OBJECT_0 }
}

pub fn process_exit_code(handle: RawHandle) -> Result<u32> {
    let mut exit_code = 0;
    if unsafe { GetExitCodeProcess(handle, &mut exit_code) } == 0 {
        bail!("读取进程退出码失败，错误码{}", unsafe {
            GetLastError()
        });
    }
    Ok(exit_code)
}

pub fn process_start_time(process_id: u32) -> Result<u64> {
    let handle = unsafe {
        OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE,
            0,
            process_id,
        )
    };
    if handle.is_null() {
        bail!("打开进程{process_id}失败，错误码{}", unsafe {
            GetLastError()
        });
    }
    let handle = OwnedHandle(handle);
    let mut creation = FileTime::default();
    let mut exit = FileTime::default();
    let mut kernel = FileTime::default();
    let mut user = FileTime::default();
    if unsafe { GetProcessTimes(handle.0, &mut creation, &mut exit, &mut kernel, &mut user) } == 0 {
        bail!("读取进程{process_id}启动时间失败，错误码{}", unsafe {
            GetLastError()
        });
    }
    Ok(file_time_value(creation))
}

pub fn process_has_exited(process_id: u32) -> Result<bool> {
    let handle = unsafe {
        OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE,
            0,
            process_id,
        )
    };
    if handle.is_null() {
        bail!("打开进程{process_id}失败，错误码{}", unsafe {
            GetLastError()
        });
    }
    let handle = OwnedHandle(handle);
    Ok(process_exit_code(handle.0)? != STILL_ACTIVE)
}

pub fn terminate_process(process_id: u32) -> bool {
    let handle = unsafe {
        OpenProcess(
            PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE,
            0,
            process_id,
        )
    };
    if handle.is_null() {
        return false;
    }
    let handle = OwnedHandle(handle);
    (unsafe { TerminateProcess(handle.0, 1) != 0 })
        && wait_process(handle.0, 10_000)
        && process_exit_code(handle.0)
            .map(|exit_code| exit_code != STILL_ACTIVE)
            .unwrap_or(false)
}

pub fn file_time_value(value: FileTime) -> u64 {
    ((value.high_date_time as u64) << 32) | value.low_date_time as u64
}

pub fn start_elevated(executable: &Path, arguments: &[String]) -> Result<OwnedHandle> {
    start_elevated_with_error_code(executable, arguments).map_err(|error| {
        if error == ERROR_INVALID_HANDLE {
            anyhow!("管理员流程未返回进程句柄")
        } else {
            anyhow!("启动管理员流程失败，错误码{error}")
        }
    })
}

pub fn start_elevated_with_error_code(
    executable: &Path,
    arguments: &[String],
) -> std::result::Result<OwnedHandle, u32> {
    let verb = wide("runas");
    let file = wide_path(executable);
    let parameters = wide(
        &arguments
            .iter()
            .map(|argument| quote_windows_argument(argument))
            .collect::<Vec<_>>()
            .join(" "),
    );
    let mut execute_info = ShellExecuteInfoW {
        cb_size: size_of::<ShellExecuteInfoW>() as u32,
        f_mask: SEE_MASK_NOCLOSEPROCESS,
        hwnd: null_mut(),
        lp_verb: verb.as_ptr(),
        lp_file: file.as_ptr(),
        lp_parameters: parameters.as_ptr(),
        lp_directory: null(),
        n_show: SW_HIDE,
        h_inst_app: null_mut(),
        lp_id_list: null_mut(),
        lp_class: null(),
        hkey_class: null_mut(),
        dw_hot_key: 0,
        h_icon_or_monitor: null_mut(),
        h_process: null_mut(),
    };
    if unsafe { ShellExecuteExW(&mut execute_info) } == 0 {
        return Err(unsafe { GetLastError() });
    }
    if execute_info.h_process.is_null() {
        return Err(ERROR_INVALID_HANDLE);
    }
    Ok(OwnedHandle(execute_info.h_process))
}

pub fn run_elevated(executable: &Path, arguments: &[String]) -> Result<bool> {
    let process = start_elevated(executable, arguments)?;
    wait_process(process.0, INFINITE);
    Ok(process_exit_code(process.0)? == 0)
}

pub fn move_to_recycle_bin(path: &Path) -> Result<()> {
    let mut source = wide(&format!("{}\0", path.display()));
    let mut operation = ShFileOpStructW {
        hwnd: null_mut(),
        w_func: FO_DELETE,
        p_from: source.as_mut_ptr(),
        p_to: null(),
        f_flags: FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI | FOF_SILENT,
        f_any_operations_aborted: 0,
        h_name_mappings: null_mut(),
        lpsz_progress_title: null(),
    };
    let status = unsafe { SHFileOperationW(&mut operation) };
    if status != 0 {
        bail!("移入回收站失败，错误码{status}");
    }
    if operation.f_any_operations_aborted != 0 {
        bail!("移入回收站被取消");
    }
    Ok(())
}

pub fn quote_windows_argument(argument: &str) -> String {
    if argument.is_empty() {
        return "\"\"".to_owned();
    }
    if !argument
        .chars()
        .any(|character| character.is_whitespace() || character == '"')
    {
        return argument.to_owned();
    }
    let mut result = String::from("\"");
    let mut backslashes = 0;
    for character in argument.chars() {
        match character {
            '\\' => backslashes += 1,
            '"' => {
                result.push_str(&"\\".repeat(backslashes * 2 + 1));
                result.push('"');
                backslashes = 0;
            }
            _ => {
                result.push_str(&"\\".repeat(backslashes));
                result.push(character);
                backslashes = 0;
            }
        }
    }
    result.push_str(&"\\".repeat(backslashes * 2));
    result.push('"');
    result
}
