use std::ffi::{OsStr, c_void};
use std::fs::OpenOptions;
use std::mem::size_of;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::ptr::null_mut;

use windows_sys::Win32::Foundation::{GetLastError, HANDLE};

use crate::AppResult;

const DRIVE_FIXED: u32 = 3;
const OPEN_EXISTING: u32 = 3;
const FILE_SHARE_READ: u32 = 0x0000_0001;
const FILE_SHARE_WRITE: u32 = 0x0000_0002;
const IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS: u32 = 0x0056_0000;
const IOCTL_STORAGE_QUERY_PROPERTY: u32 = 0x002D_1400;
const STORAGE_DEVICE_SEEK_PENALTY_PROPERTY: u32 = 7;
const PROPERTY_STANDARD_QUERY: u32 = 0;
const MOVEFILE_REPLACE_EXISTING: u32 = 0x0000_0001;
const STD_INPUT_HANDLE: u32 = (-10i32) as u32;
const KEY_EVENT: u16 = 0x0001;
const ERROR_CANCELLED: i32 = 0x8007_04C7u32 as i32;
const KF_FLAG_CREATE: u32 = 0x0000_8000;
const COINIT_MULTITHREADED: u32 = 0;
const COINIT_APARTMENTTHREADED: u32 = 2;
const CLSCTX_INPROC_SERVER: u32 = 1;
const FOS_PICKFOLDERS: u32 = 0x20;
const FOS_FORCEFILESYSTEM: u32 = 0x40;
const FOS_PATHMUSTEXIST: u32 = 0x800;
const SIGDN_FILESYSPATH: u32 = 0x8005_8000;
const WS_EX_TOPMOST: u32 = 0x0000_0008;
const WS_EX_TOOLWINDOW: u32 = 0x0000_0080;
const WS_POPUP: u32 = 0x8000_0000;

#[repr(C)]
#[derive(Clone, Copy)]
struct Guid {
    data1: u32,
    data2: u16,
    data3: u16,
    data4: [u8; 8],
}

const CLSID_FILE_OPEN_DIALOG: Guid = Guid {
    data1: 0xDC1C5A9C,
    data2: 0xE88A,
    data3: 0x4DDE,
    data4: [0xA5, 0xA1, 0x60, 0xF8, 0x2A, 0x20, 0xAE, 0xF7],
};
const IID_FILE_OPEN_DIALOG: Guid = Guid {
    data1: 0xD57C7288,
    data2: 0xD4AD,
    data3: 0x4768,
    data4: [0xBE, 0x02, 0x9D, 0x96, 0x95, 0x32, 0xD9, 0x60],
};
const IID_SHELL_ITEM: Guid = Guid {
    data1: 0x43826D1E,
    data2: 0xE718,
    data3: 0x42EE,
    data4: [0xBC, 0x55, 0xA1, 0xE2, 0x61, 0xC3, 0x7B, 0xFE],
};
const CLSID_SHELL_LINK: Guid = Guid {
    data1: 0x00021401,
    data2: 0,
    data3: 0,
    data4: [0xC0, 0, 0, 0, 0, 0, 0, 0x46],
};
const IID_SHELL_LINK: Guid = Guid {
    data1: 0x000214F9,
    data2: 0,
    data3: 0,
    data4: [0xC0, 0, 0, 0, 0, 0, 0, 0x46],
};
const IID_PERSIST_FILE: Guid = Guid {
    data1: 0x0000010B,
    data2: 0,
    data3: 0,
    data4: [0xC0, 0, 0, 0, 0, 0, 0, 0x46],
};
const FOLDERID_DOCUMENTS: Guid = Guid {
    data1: 0xFDD39AD0,
    data2: 0x238F,
    data3: 0x46AF,
    data4: [0xAD, 0xB4, 0x6C, 0x85, 0x48, 0x03, 0x69, 0xC7],
};
const FOLDERID_DESKTOP: Guid = Guid {
    data1: 0xB4BFCC3A,
    data2: 0xDB2C,
    data3: 0x424C,
    data4: [0xB0, 0x29, 0x7F, 0xE9, 0x9A, 0x87, 0xC6, 0x41],
};
const FOLDERID_PROGRAMS: Guid = Guid {
    data1: 0xA77F5D77,
    data2: 0x2E2B,
    data3: 0x44C3,
    data4: [0xA6, 0xA2, 0xAB, 0xA6, 0x01, 0x05, 0x4A, 0x51],
};
const FOLDERID_PROFILE: Guid = Guid {
    data1: 0x5E6C858F,
    data2: 0x0E22,
    data3: 0x4760,
    data4: [0x9A, 0xFE, 0xEA, 0x33, 0x17, 0xB6, 0x71, 0x73],
};

#[derive(Clone, Copy)]
pub enum KnownFolder {
    Documents,
    Desktop,
    Programs,
}

pub fn last_error(context: &str) -> String {
    format!("{context}，错误码{}", unsafe { GetLastError() })
}

pub fn process_id() -> u32 {
    unsafe { GetCurrentProcessId() }
}

pub fn set_console_utf8() {
    unsafe {
        let _ = SetConsoleOutputCP(65001);
        let _ = SetConsoleCP(65001);
    }
}

pub fn read_key() -> AppResult<u16> {
    let input = unsafe { GetStdHandle(STD_INPUT_HANDLE) };
    if input.is_null() || input == INVALID_HANDLE_VALUE {
        return Err(last_error("获取控制台输入句柄失败"));
    }

    let mut mode = 0;
    let is_console = unsafe { GetConsoleMode(input, &mut mode) != 0 };
    if !is_console {
        let mut byte = 0u8;
        let mut read = 0;
        if unsafe {
            ReadFile(
                input,
                &mut byte as *mut u8 as *mut c_void,
                1,
                &mut read,
                null_mut(),
            )
        } == 0
            || read == 0
        {
            return Err(last_error("读取输入失败"));
        }
        return Ok(byte.to_ascii_uppercase() as u16);
    }

    loop {
        let mut record = InputRecord {
            event_type: 0,
            key_event: KeyEventRecord {
                key_down: 0,
                repeat_count: 0,
                virtual_key_code: 0,
                virtual_scan_code: 0,
                unicode_char: 0,
                control_key_state: 0,
            },
        };
        let mut read = 0;
        if unsafe { ReadConsoleInputW(input, &mut record, 1, &mut read) } == 0 {
            return Err(last_error("读取控制台输入失败"));
        }
        if read != 0 && record.event_type == KEY_EVENT && record.key_event.key_down != 0 {
            return Ok(record.key_event.virtual_key_code);
        }
    }
}

pub fn select_default_path() -> AppResult<PathBuf> {
    let mask = unsafe { GetLogicalDrives() };
    let mut candidates = Vec::new();
    for index in 0..26u32 {
        if mask & (1 << index) == 0 {
            continue;
        }
        let letter = (b'A' + index as u8) as char;
        if letter.eq_ignore_ascii_case(&'C') {
            continue;
        }
        let root = format!("{letter}:\\");
        let root_wide = wide(&root);
        if unsafe { GetDriveTypeW(root_wide.as_ptr()) } != DRIVE_FIXED {
            continue;
        }
        let mut available = 0u64;
        if unsafe {
            GetDiskFreeSpaceExW(root_wide.as_ptr(), &mut available, null_mut(), null_mut())
        } == 0
        {
            continue;
        }
        if is_ssd(&root) {
            candidates.push((available, letter));
        }
    }

    candidates.sort_by(|left, right| {
        right.0.cmp(&left.0).then_with(|| {
            left.1
                .to_ascii_uppercase()
                .cmp(&right.1.to_ascii_uppercase())
        })
    });
    if let Some((_, letter)) = candidates.first() {
        return Ok(PathBuf::from(format!("{letter}:\\mc\\rdi")));
    }

    fallback_install_path(known_folder_path(KnownFolder::Documents), || {
        read_known_folder(&FOLDERID_PROFILE, "用户")
    })
}

/// 没有合适的非C盘时的默认路径：文档文件夹，读不出来就用用户文件夹。
///
/// 文档文件夹可能被改到已经不存在的盘上，而这里只是给玩家建议一个路径，
/// 不应该让安装在询问位置之前就失败。
fn fallback_install_path(
    documents: AppResult<PathBuf>,
    profile: impl FnOnce() -> AppResult<PathBuf>,
) -> AppResult<PathBuf> {
    let documents_error = match documents {
        Ok(path) => return Ok(path.join("rdi")),
        Err(error) => error,
    };
    println!("warning:{documents_error}，改用用户文件夹");
    profile()
        .map(|path| path.join("rdi"))
        .map_err(|profile_error| format!("{documents_error}；{profile_error}"))
}

fn is_ssd(root: &str) -> bool {
    let volume_name = format!(r"\\.\{}", &root[..2]);
    let Some(volume) = create_file(&volume_name, 0, FILE_SHARE_READ | FILE_SHARE_WRITE) else {
        return false;
    };

    let mut extents = VolumeDiskExtents {
        number_of_disk_extents: 0,
        first_extent: DiskExtent {
            disk_number: 0,
            starting_offset: 0,
            extent_length: 0,
        },
    };
    let mut bytes_returned = 0;
    let volume_success = unsafe {
        DeviceIoControl(
            volume.0,
            IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS,
            null_mut(),
            0,
            &mut extents as *mut VolumeDiskExtents as *mut c_void,
            size_of::<VolumeDiskExtents>() as u32,
            &mut bytes_returned,
            null_mut(),
        ) != 0
    };
    if !volume_success || extents.number_of_disk_extents != 1 {
        return false;
    }

    let physical_name = format!(r"\\.\PhysicalDrive{}", extents.first_extent.disk_number);
    let Some(physical_drive) = create_file(&physical_name, 0, FILE_SHARE_READ | FILE_SHARE_WRITE)
    else {
        return false;
    };
    let query = StoragePropertyQuery {
        property_id: STORAGE_DEVICE_SEEK_PENALTY_PROPERTY,
        query_type: PROPERTY_STANDARD_QUERY,
        additional_parameters: 0,
    };
    let mut descriptor = DeviceSeekPenaltyDescriptor {
        version: 0,
        size: 0,
        incurs_seek_penalty: 1,
    };
    unsafe {
        DeviceIoControl(
            physical_drive.0,
            IOCTL_STORAGE_QUERY_PROPERTY,
            &query as *const StoragePropertyQuery as *mut c_void,
            size_of::<StoragePropertyQuery>() as u32,
            &mut descriptor as *mut DeviceSeekPenaltyDescriptor as *mut c_void,
            size_of::<DeviceSeekPenaltyDescriptor>() as u32,
            &mut bytes_returned,
            null_mut(),
        ) != 0
            && descriptor.incurs_seek_penalty == 0
    }
}

pub fn pick_install_folder(default_path: &Path) -> AppResult<Option<PathBuf>> {
    let _apartment = ComApartment::new(COINIT_APARTMENTTHREADED)?;
    let mut dialog = null_mut();
    check_hresult(
        unsafe {
            CoCreateInstance(
                &CLSID_FILE_OPEN_DIALOG,
                null_mut(),
                CLSCTX_INPROC_SERVER,
                &IID_FILE_OPEN_DIALOG,
                &mut dialog,
            )
        },
        "创建文件夹选择器失败",
    )?;
    let dialog = ComPtr(dialog);

    let mut options = 0;
    check_hresult(
        unsafe { invoke_get_options(dialog.0, &mut options) },
        "读取选择器选项失败",
    )?;
    check_hresult(
        unsafe {
            invoke_set_options(
                dialog.0,
                options | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST,
            )
        },
        "设置选择器选项失败",
    )?;

    let title = wide("选择rdi安装文件夹");
    check_hresult(
        unsafe { invoke_set_title(dialog.0, title.as_ptr()) },
        "设置选择器标题失败",
    )?;

    let existing_directory = find_existing_directory(default_path);
    let existing_wide = wide_path(&existing_directory);
    let mut default_folder = null_mut();
    check_hresult(
        unsafe {
            SHCreateItemFromParsingName(
                existing_wide.as_ptr(),
                null_mut(),
                &IID_SHELL_ITEM,
                &mut default_folder,
            )
        },
        "设置选择器初始目录失败",
    )?;
    let default_folder = ComPtr(default_folder);
    check_hresult(
        unsafe { invoke_set_default_folder(dialog.0, default_folder.0) },
        "设置选择器初始目录失败",
    )?;

    let owner = create_topmost_owner()?;
    let show_result = unsafe { invoke_show(dialog.0, owner) };
    unsafe {
        DestroyWindow(owner);
    }
    if is_folder_picker_cancelled(show_result) {
        return Ok(None);
    }
    check_hresult(show_result, "打开文件夹选择器失败")?;

    let mut result = null_mut();
    check_hresult(
        unsafe { invoke_get_result(dialog.0, &mut result) },
        "读取选择器结果失败",
    )?;
    let result = ComPtr(result);
    let mut display_name = null_mut();
    check_hresult(
        unsafe { invoke_get_display_name(result.0, SIGDN_FILESYSPATH, &mut display_name) },
        "读取选择文件夹失败",
    )?;
    let path = unsafe { string_from_wide(display_name) };
    unsafe {
        CoTaskMemFree(display_name as *mut c_void);
    }
    Ok(Some(PathBuf::from(path)))
}

pub fn known_folder_path(folder: KnownFolder) -> AppResult<PathBuf> {
    match folder {
        KnownFolder::Documents => read_known_folder(&FOLDERID_DOCUMENTS, "文档"),
        KnownFolder::Desktop => read_known_folder(&FOLDERID_DESKTOP, "桌面"),
        KnownFolder::Programs => read_known_folder(&FOLDERID_PROGRAMS, "开始菜单"),
    }
}

fn read_known_folder(id: &Guid, name: &str) -> AppResult<PathBuf> {
    let mut path = null_mut();
    // 不带KF_FLAG_CREATE时，目录被删掉就会返回0x80070003。
    let status = unsafe { SHGetKnownFolderPath(id, KF_FLAG_CREATE, null_mut(), &mut path) };
    if let Err(error) = check_hresult(status, &format!("读取Windows{name}文件夹失败")) {
        // 文档要求失败时也释放输出指针；path为空时CoTaskMemFree什么也不做。
        unsafe {
            CoTaskMemFree(path as *mut c_void);
        }
        return Err(error);
    }
    let result = unsafe { string_from_wide(path) };
    unsafe {
        CoTaskMemFree(path as *mut c_void);
    }
    Ok(PathBuf::from(result))
}

pub fn ensure_exclusive_file(path: &Path) -> AppResult<()> {
    OpenOptions::new()
        .read(true)
        .write(true)
        .share_mode(0)
        .open(path)
        .map(|_| ())
        .map_err(|error| format!("文件不可覆盖:{}:{error}", path.display()))
}

pub fn move_file_replace(source: &Path, destination: &Path) -> AppResult<()> {
    let source = wide_path(source);
    let destination = wide_path(destination);
    if unsafe {
        MoveFileExW(
            source.as_ptr(),
            destination.as_ptr(),
            MOVEFILE_REPLACE_EXISTING,
        )
    } == 0
    {
        return Err(last_error(&format!(
            "覆盖文件失败:{}",
            destination_path(destination.as_ptr())
        )));
    }
    Ok(())
}

pub fn create_shortcut(
    shortcut_path: &Path,
    target_path: &Path,
    working_directory: &Path,
) -> AppResult<()> {
    let _apartment = ComApartment::new(COINIT_MULTITHREADED)?;
    let mut shell_link = null_mut();
    check_hresult(
        unsafe {
            CoCreateInstance(
                &CLSID_SHELL_LINK,
                null_mut(),
                CLSCTX_INPROC_SERVER,
                &IID_SHELL_LINK,
                &mut shell_link,
            )
        },
        "创建Shell快捷方式失败",
    )?;
    let shell_link = ComPtr(shell_link);
    let target = wide_path(target_path);
    let working_directory = wide_path(working_directory);
    let description = wide("rdi");
    check_hresult(
        unsafe { invoke_set_path(shell_link.0, target.as_ptr()) },
        "设置快捷方式目标失败",
    )?;
    check_hresult(
        unsafe { invoke_set_working_directory(shell_link.0, working_directory.as_ptr()) },
        "设置快捷方式工作目录失败",
    )?;
    check_hresult(
        unsafe { invoke_set_description(shell_link.0, description.as_ptr()) },
        "设置快捷方式描述失败",
    )?;
    check_hresult(
        unsafe { invoke_set_icon_location(shell_link.0, target.as_ptr(), 0) },
        "设置快捷方式图标失败",
    )?;

    let mut persist_file = null_mut();
    check_hresult(
        unsafe { query_interface(shell_link.0, &IID_PERSIST_FILE, &mut persist_file) },
        "获取快捷方式保存接口失败",
    )?;
    let persist_file = ComPtr(persist_file);
    if let Some(parent) = shortcut_path.parent() {
        std::fs::create_dir_all(parent).map_err(|error| format!("创建快捷方式目录失败:{error}"))?;
    }
    let shortcut_path = wide_path(shortcut_path);
    check_hresult(
        unsafe { invoke_persist_save(persist_file.0, shortcut_path.as_ptr()) },
        "保存快捷方式失败",
    )
}

fn find_existing_directory(default_path: &Path) -> PathBuf {
    let mut directory = default_path.to_path_buf();
    while !directory.is_dir() && directory.pop() {}
    directory
}

fn is_folder_picker_cancelled(status: i32) -> bool {
    status == ERROR_CANCELLED
}

fn create_topmost_owner() -> AppResult<HANDLE> {
    let class_name = wide("STATIC");
    let window_name = wide("rdi installer folder picker");
    let owner = unsafe {
        CreateWindowExW(
            WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
            class_name.as_ptr(),
            window_name.as_ptr(),
            WS_POPUP,
            0,
            0,
            0,
            0,
            null_mut(),
            null_mut(),
            null_mut(),
            null_mut(),
        )
    };
    if owner.is_null() {
        return Err(last_error("创建文件夹选择器owner窗口失败"));
    }
    Ok(owner)
}

fn create_file(path: &str, desired_access: u32, share_mode: u32) -> Option<OwnedHandle> {
    let path = wide(path);
    let handle = unsafe {
        CreateFileW(
            path.as_ptr(),
            desired_access,
            share_mode,
            null_mut(),
            OPEN_EXISTING,
            0,
            null_mut(),
        )
    };
    if handle.is_null() || handle == INVALID_HANDLE_VALUE {
        None
    } else {
        Some(OwnedHandle(handle))
    }
}

fn wide(value: &str) -> Vec<u16> {
    OsStr::new(value)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect()
}

fn wide_path(path: &Path) -> Vec<u16> {
    path.as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect()
}

unsafe fn string_from_wide(pointer: *const u16) -> String {
    let mut length = 0;
    while unsafe { *pointer.add(length) } != 0 {
        length += 1;
    }
    String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(pointer, length) })
}

fn check_hresult(status: i32, context: &str) -> AppResult<()> {
    if status < 0 {
        Err(format!("{context}，错误码{status:#x}"))
    } else {
        Ok(())
    }
}

struct ComApartment {
    initialized: bool,
}

impl ComApartment {
    fn new(model: u32) -> AppResult<Self> {
        let status = unsafe { CoInitializeEx(null_mut(), model) };
        check_hresult(status, "初始化COM失败")?;
        Ok(Self { initialized: true })
    }
}

impl Drop for ComApartment {
    fn drop(&mut self) {
        if self.initialized {
            unsafe {
                CoUninitialize();
            }
        }
    }
}

struct ComPtr(HANDLE);

impl Drop for ComPtr {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                com_release(self.0);
            }
        }
    }
}

struct OwnedHandle(HANDLE);

impl Drop for OwnedHandle {
    fn drop(&mut self) {
        if !self.0.is_null() && self.0 != INVALID_HANDLE_VALUE {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
}

const INVALID_HANDLE_VALUE: HANDLE = -1isize as HANDLE;

#[repr(C)]
#[derive(Clone, Copy)]
struct DiskExtent {
    disk_number: u32,
    starting_offset: i64,
    extent_length: i64,
}

#[repr(C)]
struct VolumeDiskExtents {
    number_of_disk_extents: u32,
    first_extent: DiskExtent,
}

#[repr(C)]
struct StoragePropertyQuery {
    property_id: u32,
    query_type: u32,
    additional_parameters: u8,
}

#[repr(C)]
struct DeviceSeekPenaltyDescriptor {
    version: u32,
    size: u32,
    incurs_seek_penalty: u8,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct KeyEventRecord {
    key_down: i32,
    repeat_count: u16,
    virtual_key_code: u16,
    virtual_scan_code: u16,
    unicode_char: u16,
    control_key_state: u32,
}

#[repr(C)]
struct InputRecord {
    event_type: u16,
    key_event: KeyEventRecord,
}

unsafe fn vtable(instance: HANDLE) -> *mut *mut c_void {
    unsafe { *(instance as *mut *mut *mut c_void) }
}

unsafe fn com_release(instance: HANDLE) {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE) -> u32>(
            *vtable(instance).add(2),
        )
    };
    unsafe {
        function(instance);
    }
}

unsafe fn query_interface(instance: HANDLE, id: &Guid, result: *mut HANDLE) -> i32 {
    let function = unsafe {
        std::mem::transmute::<
            *mut c_void,
            unsafe extern "system" fn(HANDLE, *const Guid, *mut HANDLE) -> i32,
        >(*vtable(instance))
    };
    unsafe { function(instance, id, result) }
}

unsafe fn invoke_show(instance: HANDLE, owner: HANDLE) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, HANDLE) -> i32>(
            *vtable(instance).add(3),
        )
    };
    unsafe { function(instance, owner) }
}

unsafe fn invoke_get_options(instance: HANDLE, result: *mut u32) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, *mut u32) -> i32>(
            *vtable(instance).add(10),
        )
    };
    unsafe { function(instance, result) }
}

unsafe fn invoke_set_options(instance: HANDLE, options: u32) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, u32) -> i32>(
            *vtable(instance).add(9),
        )
    };
    unsafe { function(instance, options) }
}

unsafe fn invoke_set_default_folder(instance: HANDLE, folder: HANDLE) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, HANDLE) -> i32>(
            *vtable(instance).add(11),
        )
    };
    unsafe { function(instance, folder) }
}

unsafe fn invoke_set_title(instance: HANDLE, title: *const u16) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, *const u16) -> i32>(
            *vtable(instance).add(17),
        )
    };
    unsafe { function(instance, title) }
}

unsafe fn invoke_get_result(instance: HANDLE, result: *mut HANDLE) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, *mut HANDLE) -> i32>(
            *vtable(instance).add(20),
        )
    };
    unsafe { function(instance, result) }
}

unsafe fn invoke_get_display_name(
    instance: HANDLE,
    display_name_type: u32,
    result: *mut *mut u16,
) -> i32 {
    let function = unsafe {
        std::mem::transmute::<
            *mut c_void,
            unsafe extern "system" fn(HANDLE, u32, *mut *mut u16) -> i32,
        >(*vtable(instance).add(5))
    };
    unsafe { function(instance, display_name_type, result) }
}

unsafe fn invoke_set_path(instance: HANDLE, path: *const u16) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, *const u16) -> i32>(
            *vtable(instance).add(20),
        )
    };
    unsafe { function(instance, path) }
}

unsafe fn invoke_set_working_directory(instance: HANDLE, path: *const u16) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, *const u16) -> i32>(
            *vtable(instance).add(9),
        )
    };
    unsafe { function(instance, path) }
}

unsafe fn invoke_set_description(instance: HANDLE, description: *const u16) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, *const u16) -> i32>(
            *vtable(instance).add(7),
        )
    };
    unsafe { function(instance, description) }
}

unsafe fn invoke_set_icon_location(instance: HANDLE, path: *const u16, index: i32) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, *const u16, i32) -> i32>(
            *vtable(instance).add(17),
        )
    };
    unsafe { function(instance, path, index) }
}

unsafe fn invoke_persist_save(instance: HANDLE, path: *const u16) -> i32 {
    let function = unsafe {
        std::mem::transmute::<*mut c_void, unsafe extern "system" fn(HANDLE, *const u16, i32) -> i32>(
            *vtable(instance).add(6),
        )
    };
    unsafe { function(instance, path, 1) }
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetCurrentProcessId() -> u32;
    fn GetStdHandle(standard_handle: u32) -> HANDLE;
    fn GetConsoleMode(console_handle: HANDLE, mode: *mut u32) -> i32;
    fn ReadConsoleInputW(
        console_input: HANDLE,
        records: *mut InputRecord,
        length: u32,
        events_read: *mut u32,
    ) -> i32;
    fn ReadFile(
        file: HANDLE,
        buffer: *mut c_void,
        bytes_to_read: u32,
        bytes_read: *mut u32,
        overlapped: *mut c_void,
    ) -> i32;
    fn SetConsoleOutputCP(code_page: u32) -> i32;
    fn SetConsoleCP(code_page: u32) -> i32;
    fn GetLogicalDrives() -> u32;
    fn GetDriveTypeW(root_path_name: *const u16) -> u32;
    fn GetDiskFreeSpaceExW(
        directory_name: *const u16,
        free_bytes_available: *mut u64,
        total_number_of_bytes: *mut u64,
        total_number_of_free_bytes: *mut u64,
    ) -> i32;
    fn CreateFileW(
        file_name: *const u16,
        desired_access: u32,
        share_mode: u32,
        security_attributes: HANDLE,
        creation_disposition: u32,
        flags_and_attributes: u32,
        template_file: HANDLE,
    ) -> HANDLE;
    fn DeviceIoControl(
        device: HANDLE,
        control_code: u32,
        input_buffer: *mut c_void,
        input_buffer_size: u32,
        output_buffer: *mut c_void,
        output_buffer_size: u32,
        bytes_returned: *mut u32,
        overlapped: HANDLE,
    ) -> i32;
    fn CloseHandle(handle: HANDLE) -> i32;
    fn MoveFileExW(existing_file_name: *const u16, new_file_name: *const u16, flags: u32) -> i32;
}

#[link(name = "ole32")]
unsafe extern "system" {
    fn CoInitializeEx(reserved: HANDLE, concurrency_model: u32) -> i32;
    fn CoUninitialize();
    fn CoCreateInstance(
        class_id: *const Guid,
        outer: HANDLE,
        context: u32,
        interface_id: *const Guid,
        instance: *mut HANDLE,
    ) -> i32;
    fn CoTaskMemFree(memory: *mut c_void);
}

#[link(name = "shell32")]
unsafe extern "system" {
    fn SHCreateItemFromParsingName(
        path: *const u16,
        binding_context: HANDLE,
        interface_id: *const Guid,
        item: *mut HANDLE,
    ) -> i32;
    fn SHGetKnownFolderPath(
        folder_id: *const Guid,
        flags: u32,
        token: HANDLE,
        path: *mut *mut u16,
    ) -> i32;
}

#[link(name = "user32")]
unsafe extern "system" {
    fn CreateWindowExW(
        extended_style: u32,
        class_name: *const u16,
        window_name: *const u16,
        style: u32,
        x: i32,
        y: i32,
        width: i32,
        height: i32,
        parent: HANDLE,
        menu: HANDLE,
        instance: HANDLE,
        parameter: HANDLE,
    ) -> HANDLE;
    fn DestroyWindow(window: HANDLE) -> i32;
}

fn destination_path(pointer: *const u16) -> String {
    unsafe { string_from_wide(pointer) }
}

#[cfg(test)]
mod tests {
    use super::create_shortcut;
    use super::{
        CLSCTX_INPROC_SERVER, CLSID_FILE_OPEN_DIALOG, COINIT_APARTMENTTHREADED, CoCreateInstance,
        ComApartment, ComPtr, FOLDERID_PROFILE, IID_FILE_OPEN_DIALOG, fallback_install_path,
        is_folder_picker_cancelled, read_known_folder,
    };
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn default_path_uses_documents_when_readable() {
        let path = fallback_install_path(Ok(PathBuf::from(r"C:\Users\p\Documents")), || {
            panic!("documents is readable, profile must not be read")
        });
        assert_eq!(path, Ok(PathBuf::from(r"C:\Users\p\Documents\rdi")));
    }

    #[test]
    fn default_path_falls_back_to_profile_when_documents_fails() {
        let path = fallback_install_path(Err("文档失败".to_owned()), || {
            Ok(PathBuf::from(r"C:\Users\p"))
        });
        assert_eq!(path, Ok(PathBuf::from(r"C:\Users\p\rdi")));
    }

    #[test]
    fn default_path_reports_both_errors_when_nothing_is_readable() {
        let path =
            fallback_install_path(Err("文档失败".to_owned()), || Err("用户失败".to_owned()));
        assert_eq!(path, Err("文档失败；用户失败".to_owned()));
    }

    #[test]
    fn reads_profile_folder() {
        let path = read_known_folder(&FOLDERID_PROFILE, "用户").expect("profile folder");
        assert!(path.is_dir(), "{} should exist", path.display());
    }

    #[test]
    fn recognizes_folder_picker_cancel() {
        assert!(is_folder_picker_cancelled(0x8007_04C7u32 as i32));
        assert!(!is_folder_picker_cancelled(0));
    }

    #[test]
    fn creates_file_open_dialog() {
        let _apartment = ComApartment::new(COINIT_APARTMENTTHREADED).expect("COM apartment");
        let mut dialog = std::ptr::null_mut();
        let status = unsafe {
            CoCreateInstance(
                &CLSID_FILE_OPEN_DIALOG,
                std::ptr::null_mut(),
                CLSCTX_INPROC_SERVER,
                &IID_FILE_OPEN_DIALOG,
                &mut dialog,
            )
        };
        assert!(status >= 0, "CoCreateInstance failed: {status:#x}");
        let _dialog = ComPtr(dialog);
    }

    #[test]
    fn creates_shell_link_in_temp_directory() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system time")
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "rdi-installer-shortcut-test-{}-{unique}",
            crate::windows::process_id()
        ));
        std::fs::create_dir_all(&root).expect("test directory");
        let target = root.join("start.exe");
        let shortcut = root.join("rdi.lnk");
        std::fs::write(&target, "fixture").expect("target");

        create_shortcut(&shortcut, &target, &root).expect("create shortcut");
        assert!(shortcut.is_file());
        std::fs::remove_dir_all(root).expect("test cleanup");
    }
}
