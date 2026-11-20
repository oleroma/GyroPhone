//! The direct USB link's system side: the USB devices present (SetupAPI), one interface opened with WinUSB, the
//! accessory's bulk pipes, and device arrival notifications. Ports of the Windows half of AoaUsb.cs, UsbNative.cs
//! and DeviceChangeNotifier.cs; the link's own logic is `usb_link`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use windows_sys::core::GUID;
use windows_sys::Win32::Devices::DeviceAndDriverInstallation as di;
use windows_sys::Win32::Devices::Usb as usb;
use windows_sys::Win32::Foundation::{GENERIC_READ, GENERIC_WRITE, INVALID_HANDLE_VALUE};
use windows_sys::Win32::Storage::FileSystem as fs;
use windows_sys::Win32::System::Registry::{RegCloseKey, RegQueryValueExW, HKEY};

use super::{basics, last_error, process, wide, Handle};
use crate::udp::Log;
use crate::usb_bridge::UsbPipe;
use crate::usb_link::{UsbDevice, UsbNode, UsbSystem};

const SPDRP_HARDWAREID: u32 = 1;
const SPDRP_COMPATIBLEIDS: u32 = 2;
const DICS_FLAG_GLOBAL: u32 = 1;
const DIREG_DEV: u32 = 1;
const KEY_READ: u32 = 0x20019;
const REG_SZ: u32 = 1;
const REG_MULTI_SZ: u32 = 7;
const ERROR_SEM_TIMEOUT: u32 = 121;
/// A write the phone does not take within this long is dropped (the app is not reading).
const WRITE_TIMEOUT_MS: u32 = 250;

/// A device information set, destroyed when dropped.
struct DevInfo(di::HDEVINFO);

impl DevInfo {
    fn new(class: Option<&GUID>, enumerator: Option<&str>, flags: u32) -> Result<DevInfo, u32> {
        let name = enumerator.map(wide);
        // SAFETY: the class and the NUL-ended enumerator name (or null) outlive the call.
        let set = unsafe {
            di::SetupDiGetClassDevsW(class.map_or(std::ptr::null(), |g| g), name.as_ref().map_or(std::ptr::null(), |n| n.as_ptr()), std::ptr::null_mut(), flags)
        };
        if set == INVALID_HANDLE_VALUE as isize {
            Err(last_error())
        } else {
            Ok(DevInfo(set))
        }
    }
}

impl Drop for DevInfo {
    fn drop(&mut self) {
        // SAFETY: the set from SetupDiGetClassDevsW, destroyed once.
        unsafe {
            di::SetupDiDestroyDeviceInfoList(self.0);
        }
    }
}

fn devinfo_data() -> di::SP_DEVINFO_DATA {
    // SAFETY: plain data; zero with its size set is how one is made.
    let mut d: di::SP_DEVINFO_DATA = unsafe { std::mem::zeroed() };
    d.cbSize = std::mem::size_of::<di::SP_DEVINFO_DATA>() as u32;
    d
}

/// NUL-separated UTF-16 strings (as bytes) to a list, the empty ones left out.
fn multi_sz(bytes: &[u8]) -> Vec<String> {
    let units: Vec<u16> = bytes.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
    units.split(|c| *c == 0).filter(|s| !s.is_empty()).map(String::from_utf16_lossy).collect()
}

fn registry_property(set: &DevInfo, d: &di::SP_DEVINFO_DATA, property: u32) -> Vec<String> {
    let mut size = 0u32;
    // SAFETY: a null buffer of size 0 asks for the size needed.
    unsafe {
        di::SetupDiGetDeviceRegistryPropertyW(set.0, d, property, std::ptr::null_mut(), std::ptr::null_mut(), 0, &mut size);
    }
    if size < 2 || size > 1 << 20 {
        return Vec::new();
    }
    let mut buf = vec![0u8; size as usize];
    // SAFETY: the buffer is `size` bytes.
    if unsafe { di::SetupDiGetDeviceRegistryPropertyW(set.0, d, property, std::ptr::null_mut(), buf.as_mut_ptr(), size, std::ptr::null_mut()) } == 0 {
        return Vec::new();
    }
    multi_sz(&buf)
}

fn instance_id(set: &DevInfo, d: &di::SP_DEVINFO_DATA) -> String {
    let mut buf = [0u16; 512];
    // SAFETY: the buffer is 512 characters.
    if unsafe { di::SetupDiGetDeviceInstanceIdW(set.0, d, buf.as_mut_ptr(), buf.len() as u32, std::ptr::null_mut()) } == 0 {
        return String::new();
    }
    let end = buf.iter().position(|c| *c == 0).unwrap_or(buf.len());
    String::from_utf16_lossy(&buf[..end])
}

fn registry_strings(key: HKEY, name: &str) -> Vec<String> {
    let name = wide(name);
    let (mut kind, mut size) = (0u32, 0u32);
    // SAFETY: a live key, a NUL-ended name; a null buffer asks for the size.
    if unsafe { RegQueryValueExW(key, name.as_ptr(), std::ptr::null(), &mut kind, std::ptr::null_mut(), &mut size) } != 0 || size < 2 || size > 1 << 16 {
        return Vec::new();
    }
    if kind != REG_SZ && kind != REG_MULTI_SZ {
        return Vec::new();
    }
    let mut buf = vec![0u8; size as usize];
    // SAFETY: the buffer is `size` bytes.
    if unsafe { RegQueryValueExW(key, name.as_ptr(), std::ptr::null(), &mut kind, buf.as_mut_ptr(), &mut size) } != 0 {
        return Vec::new();
    }
    buf.truncate(size as usize);
    multi_sz(&buf)
}

/// DeviceInterfaceGUIDs from the device's registry key: set by WinUSB installs (INF, Zadig, MS OS descriptors).
fn interface_guids(set: &DevInfo, d: &di::SP_DEVINFO_DATA) -> Vec<String> {
    // SAFETY: a live set and its device.
    let key = unsafe { di::SetupDiOpenDevRegKey(set.0, d, DICS_FLAG_GLOBAL, 0, DIREG_DEV, KEY_READ) };
    if key.is_null() || key == INVALID_HANDLE_VALUE {
        return Vec::new();
    }
    let mut guids = registry_strings(key, "DeviceInterfaceGUIDs");
    if guids.is_empty() {
        guids = registry_strings(key, "DeviceInterfaceGUID");
    }
    // SAFETY: the key opened above, closed once.
    unsafe {
        RegCloseKey(key);
    }
    guids
}

/// "{xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}" (braces optional) to a GUID.
pub fn parse_guid(s: &str) -> Option<GUID> {
    let s = s.trim().trim_start_matches('{').trim_end_matches('}');
    let parts: Vec<&str> = s.split('-').collect();
    if parts.len() != 5 || [8, 4, 4, 4, 12].iter().zip(&parts).any(|(n, p)| p.len() != *n || !p.bytes().all(|b| b.is_ascii_hexdigit())) {
        return None;
    }
    let tail = u64::from_str_radix(&format!("{}{}", parts[3], parts[4]), 16).ok()?;
    Some(GUID {
        data1: u32::from_str_radix(parts[0], 16).ok()?,
        data2: u16::from_str_radix(parts[1], 16).ok()?,
        data3: u16::from_str_radix(parts[2], 16).ok()?,
        data4: tail.to_be_bytes(),
    })
}

fn list() -> Result<Vec<UsbNode>, String> {
    let set = DevInfo::new(None, Some("USB"), di::DIGCF_PRESENT | di::DIGCF_ALLCLASSES).map_err(|e| format!("cannot list USB devices (error {e})"))?;
    let mut nodes = Vec::new();
    let mut d = devinfo_data();
    let mut i = 0u32;
    // SAFETY: a live set and a sized SP_DEVINFO_DATA.
    while unsafe { di::SetupDiEnumDeviceInfo(set.0, i, &mut d) } != 0 {
        i += 1;
        let hardware_ids = registry_property(&set, &d, SPDRP_HARDWAREID);
        if hardware_ids.is_empty() {
            continue;
        }
        nodes.push(UsbNode {
            dev_inst: d.DevInst,
            instance_id: instance_id(&set, &d),
            hardware_ids,
            compatible_ids: registry_property(&set, &d, SPDRP_COMPATIBLEIDS),
            interface_guids: interface_guids(&set, &d),
        });
    }
    Ok(nodes)
}

fn interface_path(node: &UsbNode) -> Option<String> {
    for guid in node.interface_guids.iter().filter_map(|g| parse_guid(g)) {
        let Ok(set) = DevInfo::new(Some(&guid), None, di::DIGCF_PRESENT | di::DIGCF_DEVICEINTERFACE) else { continue };
        // SAFETY: plain data; zero with its size set is how one is made.
        let mut ifd: di::SP_DEVICE_INTERFACE_DATA = unsafe { std::mem::zeroed() };
        ifd.cbSize = std::mem::size_of::<di::SP_DEVICE_INTERFACE_DATA>() as u32;
        let mut i = 0u32;
        // SAFETY: a live set, the class it was made for, a sized SP_DEVICE_INTERFACE_DATA.
        while unsafe { di::SetupDiEnumDeviceInterfaces(set.0, std::ptr::null(), &guid, i, &mut ifd) } != 0 {
            i += 1;
            let mut dev = devinfo_data();
            let mut size = 0u32;
            // SAFETY: a null buffer of size 0 asks for the size needed.
            unsafe {
                di::SetupDiGetDeviceInterfaceDetailW(set.0, &ifd, std::ptr::null_mut(), 0, &mut size, &mut dev);
            }
            if size < 8 || size > 1 << 16 {
                continue;
            }
            // SP_DEVICE_INTERFACE_DETAIL_DATA_W: DWORD cbSize, then the path. Kept in u32s for its alignment.
            let mut buf = vec![0u32; (size as usize).div_ceil(4)];
            let detail = buf.as_mut_ptr().cast::<di::SP_DEVICE_INTERFACE_DETAIL_DATA_W>();
            // SAFETY: the buffer is at least `size` bytes and aligned for the struct; its cbSize is the fixed
            // part's size, as the call asks.
            unsafe {
                (*detail).cbSize = std::mem::size_of::<di::SP_DEVICE_INTERFACE_DETAIL_DATA_W>() as u32;
                if di::SetupDiGetDeviceInterfaceDetailW(set.0, &ifd, detail, size, std::ptr::null_mut(), &mut dev) == 0 {
                    continue;
                }
            }
            if dev.DevInst != node.dev_inst {
                continue;
            }
            // The path starts 4 bytes in and ends at its NUL, within `size` bytes.
            let bytes: Vec<u8> = buf.iter().flat_map(|w| w.to_le_bytes()).take(size as usize).skip(4).collect();
            return multi_sz(&bytes).into_iter().next();
        }
    }
    None
}

/// One USB interface opened with WinUSB; both handles are freed when dropped.
struct WinUsbDevice {
    // Declared before `file`: the WinUSB handle is freed first.
    iface: usb::WINUSB_INTERFACE_HANDLE,
    #[allow(dead_code)]
    file: Handle,
}

// SAFETY: WinUSB's handle may be used from any thread; reads, writes and aborts on it may run at once.
unsafe impl Send for WinUsbDevice {}
unsafe impl Sync for WinUsbDevice {}

impl Drop for WinUsbDevice {
    fn drop(&mut self) {
        // SAFETY: the handle from WinUsb_Initialize, freed once, before its file closes.
        unsafe {
            usb::WinUsb_Free(self.iface);
        }
    }
}

impl WinUsbDevice {
    fn open(path: &str) -> Result<WinUsbDevice, u32> {
        let path = wide(path);
        // SAFETY: a NUL-ended path; no security attributes, no template.
        let file = Handle::new(unsafe {
            fs::CreateFileW(path.as_ptr(), GENERIC_READ | GENERIC_WRITE, fs::FILE_SHARE_READ | fs::FILE_SHARE_WRITE, std::ptr::null(), fs::OPEN_EXISTING, fs::FILE_ATTRIBUTE_NORMAL | fs::FILE_FLAG_OVERLAPPED, std::ptr::null_mut())
        })
        .ok_or_else(last_error)?;
        let mut iface: usb::WINUSB_INTERFACE_HANDLE = std::ptr::null_mut();
        // SAFETY: a live file opened for overlapped use, as WinUSB asks; a valid out pointer.
        if unsafe { usb::WinUsb_Initialize(file.raw(), &mut iface) } == 0 {
            return Err(last_error());
        }
        Ok(WinUsbDevice { iface, file })
    }

    /// The first bulk IN and OUT pipes.
    fn bulk_pipes(&self) -> Option<(u8, u8)> {
        // SAFETY: plain data filled by the call.
        let mut desc: usb::USB_INTERFACE_DESCRIPTOR = unsafe { std::mem::zeroed() };
        // SAFETY: a live handle and a valid out pointer.
        if unsafe { usb::WinUsb_QueryInterfaceSettings(self.iface, 0, &mut desc) } == 0 {
            return None;
        }
        let (mut pipe_in, mut pipe_out) = (0u8, 0u8);
        for i in 0..desc.bNumEndpoints {
            // SAFETY: plain data filled by the call.
            let mut pipe: usb::WINUSB_PIPE_INFORMATION = unsafe { std::mem::zeroed() };
            // SAFETY: a live handle and a valid out pointer.
            if unsafe { usb::WinUsb_QueryPipe(self.iface, 0, i, &mut pipe) } == 0 || pipe.PipeType != usb::UsbdPipeTypeBulk {
                continue;
            }
            if pipe.PipeId & 0x80 != 0 {
                if pipe_in == 0 {
                    pipe_in = pipe.PipeId;
                }
            } else if pipe_out == 0 {
                pipe_out = pipe.PipeId;
            }
        }
        (pipe_in != 0 && pipe_out != 0).then_some((pipe_in, pipe_out))
    }
}

impl UsbDevice for WinUsbDevice {
    fn control(&self, request_type: u8, request: u8, value: u16, index: u16, data: &mut [u8]) -> Result<u32, u32> {
        let setup = usb::WINUSB_SETUP_PACKET { RequestType: request_type, Request: request, Value: value, Index: index, Length: data.len() as u16 };
        let mut moved = 0u32;
        // SAFETY: a live handle; the buffer is of the length given; no OVERLAPPED: the call waits.
        if unsafe { usb::WinUsb_ControlTransfer(self.iface, setup, data.as_mut_ptr(), data.len() as u32, &mut moved, std::ptr::null()) } == 0 {
            return Err(last_error());
        }
        Ok(moved)
    }

    fn into_pipe(self: Box<Self>) -> Result<Arc<dyn UsbPipe>, String> {
        let Some((pipe_in, pipe_out)) = self.bulk_pipes() else { return Err("the accessory has no bulk endpoints".to_string()) };
        let ms = WRITE_TIMEOUT_MS;
        // SAFETY: a live handle; the policy's value is one u32.
        unsafe {
            usb::WinUsb_SetPipePolicy(self.iface, pipe_out, usb::PIPE_TRANSFER_TIMEOUT, 4, (&ms as *const u32).cast());
        }
        Ok(Arc::new(WinUsbPipe { dev: *self, pipe_in, pipe_out, cancelled: AtomicBool::new(false) }))
    }
}

/// The accessory's bulk pipes for the bridge.
struct WinUsbPipe {
    dev: WinUsbDevice,
    pipe_in: u8,
    pipe_out: u8,
    cancelled: AtomicBool,
}

impl UsbPipe for WinUsbPipe {
    fn read(&self, buffer: &mut [u8]) -> std::io::Result<usize> {
        loop {
            if self.cancelled.load(Ordering::Acquire) {
                return Err(std::io::Error::other("closed"));
            }
            let mut got = 0u32;
            let len = buffer.len().min(u32::MAX as usize) as u32;
            // SAFETY: a live handle; the buffer is of the length given; no OVERLAPPED: the call waits.
            if unsafe { usb::WinUsb_ReadPipe(self.dev.iface, self.pipe_in, buffer.as_mut_ptr(), len, &mut got, std::ptr::null()) } != 0 {
                if got > 0 {
                    return Ok(got as usize);
                }
                continue; // a zero-length packet
            }
            let e = last_error();
            if e == ERROR_SEM_TIMEOUT && !self.cancelled.load(Ordering::Acquire) {
                continue;
            }
            return Err(std::io::Error::other(format!("USB read failed (error {e})")));
        }
    }

    fn write(&self, data: &[u8]) -> bool {
        if self.cancelled.load(Ordering::Acquire) {
            return false;
        }
        let mut sent = 0u32;
        // SAFETY: a live handle; the buffer is of the length given; no OVERLAPPED: the call waits (250 ms at most).
        unsafe { usb::WinUsb_WritePipe(self.dev.iface, self.pipe_out, data.as_ptr(), data.len() as u32, &mut sent, std::ptr::null()) != 0 && sent as usize == data.len() }
    }

    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        // SAFETY: a live handle; aborting ends a transfer running on another thread.
        unsafe {
            usb::WinUsb_AbortPipe(self.dev.iface, self.pipe_in);
            usb::WinUsb_AbortPipe(self.dev.iface, self.pipe_out);
        }
    }
}

/// Calls back when any device interface arrives or is removed (no window needed), so the link reacts to a plugged
/// cable or a phone coming back in accessory mode at once. The callback runs on a system thread: kept short.
struct DeviceWatch {
    handle: di::HCMNOTIFICATION,
    /// What the system's callback is given; freed after the registration is gone.
    context: *mut Arc<dyn Fn() + Send + Sync>,
}

// SAFETY: the registration handle may be closed from any thread; the context is only read by the callback.
unsafe impl Send for DeviceWatch {}
unsafe impl Sync for DeviceWatch {}

unsafe extern "system" fn device_changed(_notify: di::HCMNOTIFICATION, context: *const core::ffi::c_void, action: di::CM_NOTIFY_ACTION, _data: *const di::CM_NOTIFY_EVENT_DATA, _size: u32) -> u32 {
    if (action == di::CM_NOTIFY_ACTION_DEVICEINTERFACEARRIVAL || action == di::CM_NOTIFY_ACTION_DEVICEINTERFACEREMOVAL) && !context.is_null() {
        // SAFETY: the context is the boxed callback, alive until after CM_Unregister_Notification returned (it
        // waits for a callback in progress).
        let changed = unsafe { &*(context as *const Arc<dyn Fn() + Send + Sync>) };
        changed();
    }
    0
}

impl Drop for DeviceWatch {
    fn drop(&mut self) {
        // SAFETY: the registration made in `watch`; unregistering waits for a callback in progress, after which
        // nothing reads the context and it can be freed.
        unsafe {
            di::CM_Unregister_Notification(self.handle);
            drop(Box::from_raw(self.context));
        }
    }
}

/// Windows' USB, as `usb_link` takes it.
pub struct WindowsUsb {
    pub log: Log,
}

impl UsbSystem for WindowsUsb {
    fn list(&self) -> Result<Vec<UsbNode>, String> {
        list()
    }

    fn interface_path(&self, node: &UsbNode) -> Option<String> {
        interface_path(node)
    }

    fn open(&self, path: &str) -> Result<Box<dyn UsbDevice>, u32> {
        WinUsbDevice::open(path).map(|d| Box::new(d) as Box<dyn UsbDevice>)
    }

    fn stop_adb_server(&self, log: &Log) {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let child = std::process::Command::new("adb").arg("kill-server").creation_flags(CREATE_NO_WINDOW).stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn();
        let mut child = match child {
            Ok(c) => c,
            Err(e) => return log(&format!("usb: adb not found on PATH ({})", e.raw_os_error().unwrap_or(0))),
        };
        let mut result = "did not finish".to_string();
        for _ in 0..100 {
            if let Ok(Some(status)) = child.try_wait() {
                result = format!("exit {}", status.code().unwrap_or(-1));
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        log(&format!("usb: adb kill-server {result}"));
        std::thread::sleep(std::time::Duration::from_millis(500)); // let Windows close adb's handle
    }

    fn watch(&self, changed: Arc<dyn Fn() + Send + Sync>, log: &Log) -> Option<Box<dyn Send + Sync>> {
        // SAFETY: plain data; zero with its size, flags and type set is how one is made.
        let mut filter: di::CM_NOTIFY_FILTER = unsafe { std::mem::zeroed() };
        filter.cbSize = std::mem::size_of::<di::CM_NOTIFY_FILTER>() as u32;
        filter.Flags = di::CM_NOTIFY_FILTER_FLAG_ALL_INTERFACE_CLASSES;
        filter.FilterType = di::CM_NOTIFY_FILTER_TYPE_DEVICEINTERFACE;
        let context = Box::into_raw(Box::new(changed));
        let mut handle: di::HCMNOTIFICATION = std::ptr::null_mut();
        // SAFETY: the filter outlives the call; the context lives until the registration is gone.
        let cr = unsafe { di::CM_Register_Notification(&filter, context.cast_const().cast(), Some(device_changed), &mut handle) };
        if cr != 0 {
            // SAFETY: never registered: nothing else holds the context.
            unsafe {
                drop(Box::from_raw(context));
            }
            log(&format!("usb: no device notifications (CONFIGRET {cr}); scanning every second"));
            return None;
        }
        Some(Box::new(DeviceWatch { handle, context }))
    }

    fn machine_name(&self) -> String {
        process::machine_name()
    }

    fn on_bridge_thread(&self) -> Option<Arc<dyn Fn() + Send + Sync>> {
        // The cable's threads carry every packet ahead of gp-rx: the same MMCSS and throttling opt-out.
        let log = self.log.clone();
        Some(Arc::new(move || {
            basics::mmcss_enter("Games", &log);
            basics::power_qos_off(false, &log);
        }))
    }
}
