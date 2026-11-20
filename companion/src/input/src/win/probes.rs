//! What the watchdog asks Windows once a second, off the input path, changing nothing on the PC: the network
//! adapters, whether the network is marked Public, what the keyboard layout prints on each key, and the PC's
//! setup bits (Wi-Fi band and signal, power). Ports of NetworkCategory.cs, KeyboardLayoutProbe.cs,
//! PcSetupProbe.cs and the system half of LinkPaths.cs.

use std::net::{IpAddr, Ipv4Addr};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use windows_sys::core::GUID;
use windows_sys::Win32::NetworkManagement::IpHelper as ip;
use windows_sys::Win32::NetworkManagement::WiFi as wifi;
use windows_sys::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};
use windows_sys::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{GetKeyboardLayout, MapVirtualKeyExW};
use windows_sys::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};

use super::usb::parse_guid;
use super::wide;
use crate::codec::KeyLabels;
use crate::mapping::hid_scan_codes;
use crate::pc_setup::{self, NetAdapter, PcWifiRadio};
use crate::watchdog::Probes;
use crate::wire;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

// =====================================================================================
// network adapters
// =====================================================================================

const AF_INET: u32 = 2;
const ERROR_BUFFER_OVERFLOW: u32 = 111;
const IF_TYPE_WIFI: u32 = 71;
const IF_TYPES_ETHERNET: [u32; 5] = [6, 26, 62, 69, 117];
const IF_OPER_STATUS_UP: i32 = 1;
/// The list changes rarely and takes a while to make: asked again only this often.
const ADAPTERS_KEPT: Duration = Duration::from_secs(5);

/// A NUL-ended UTF-16 string from the system.
///
/// # Safety
/// `p` is null or points to a NUL-ended string that stays alive for the call.
unsafe fn from_wide_ptr(p: *const u16) -> String {
    if p.is_null() {
        return String::new();
    }
    // SAFETY: the caller's promise; read up to the NUL.
    unsafe {
        let mut len = 0;
        while *p.add(len) != 0 && len < 4096 {
            len += 1;
        }
        String::from_utf16_lossy(std::slice::from_raw_parts(p, len))
    }
}

fn query_adapters() -> Vec<NetAdapter> {
    let flags = ip::GAA_FLAG_SKIP_ANYCAST | ip::GAA_FLAG_SKIP_MULTICAST | ip::GAA_FLAG_SKIP_DNS_SERVER;
    let mut size = 16 * 1024u32;
    let mut buf: Vec<u64> = Vec::new();
    let mut ok = false;
    for _ in 0..4 {
        buf = vec![0u64; (size as usize).div_ceil(8)];
        // SAFETY: the buffer is at least `size` bytes, 8-aligned as the list's structures ask.
        let r = unsafe { ip::GetAdaptersAddresses(AF_INET, flags, std::ptr::null(), buf.as_mut_ptr().cast(), &mut size) };
        if r == 0 {
            ok = true;
            break;
        }
        if r != ERROR_BUFFER_OVERFLOW {
            break;
        }
    }
    let mut list = Vec::new();
    if !ok {
        return list;
    }
    let mut at = buf.as_ptr() as *const ip::IP_ADAPTER_ADDRESSES_LH;
    // SAFETY: the list GetAdaptersAddresses wrote into `buf`, which lives to the end of this function: each
    // `Next` and string pointer points into it (or is null).
    unsafe {
        while !at.is_null() && list.len() < 256 {
            let a = &*at;
            let mut addresses = Vec::new();
            let mut u = a.FirstUnicastAddress;
            while !u.is_null() && addresses.len() < 64 {
                let ua = &*u;
                let sa = ua.Address.lpSockaddr;
                if !sa.is_null() && ua.Address.iSockaddrLength >= 8 && (*sa).sa_family == AF_INET as u16 {
                    // sockaddr_in: family, port, then the address's 4 bytes.
                    let b = std::slice::from_raw_parts(sa.cast::<u8>(), 8);
                    addresses.push((Ipv4Addr::new(b[4], b[5], b[6], b[7]), ua.OnLinkPrefixLength));
                }
                u = ua.Next;
            }
            let id = if a.AdapterName.is_null() { String::new() } else { std::ffi::CStr::from_ptr(a.AdapterName.cast()).to_string_lossy().into_owned() };
            list.push(NetAdapter {
                name: from_wide_ptr(a.FriendlyName),
                description: from_wide_ptr(a.Description),
                id,
                up: a.OperStatus == IF_OPER_STATUS_UP,
                wireless: a.IfType == IF_TYPE_WIFI,
                ethernet: IF_TYPES_ETHERNET.contains(&a.IfType),
                addresses,
            });
            at = a.Next;
        }
    }
    list
}

static ADAPTERS: Mutex<Option<(Instant, Vec<NetAdapter>)>> = Mutex::new(None);

/// The PC's network adapters (kept 5 s).
pub fn adapters() -> Vec<NetAdapter> {
    let mut kept = lock(&ADAPTERS);
    match &*kept {
        Some((at, list)) if at.elapsed() < ADAPTERS_KEPT => list.clone(),
        _ => {
            let list = query_adapters();
            *kept = Some((Instant::now(), list.clone()));
            list
        }
    }
}

// =====================================================================================
// the network's category
// =====================================================================================

/// The category rarely changes, so it is asked again only this often.
const CATEGORY_KEPT: Duration = Duration::from_secs(5);
static PUBLIC: Mutex<Option<(Instant, bool)>> = Mutex::new(None);

thread_local! {
    /// COM is started once on each thread that asks (the watchdog's probe thread).
    static COM_STARTED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

fn same_guid(a: &windows::core::GUID, b: &GUID) -> bool {
    a.data1 == b.data1 && a.data2 == b.data2 && a.data3 == b.data3 && a.data4 == b.data4
}

fn query_public() -> windows::core::Result<bool> {
    use windows::Win32::Networking::NetworkListManager::{INetworkConnection, INetworkListManager, NetworkListManager, NLM_NETWORK_CATEGORY_PUBLIC};
    use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CLSCTX_ALL, COINIT_MULTITHREADED};
    let known: Vec<(GUID, bool)> = adapters().iter().filter_map(|a| parse_guid(&a.id).map(|g| (g, pc_setup::may_carry_the_phone(a)))).collect();
    // SAFETY: COM calls on this thread, started here once; every interface is released when its value drops.
    unsafe {
        if !COM_STARTED.get() {
            // "Already started another way" still lets the calls below work.
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            COM_STARTED.set(true);
        }
        let manager: INetworkListManager = CoCreateInstance(&NetworkListManager, None, CLSCTX_ALL)?;
        let connections = manager.GetNetworkConnections()?;
        for _ in 0..256 {
            let mut one: [Option<INetworkConnection>; 1] = [None];
            let mut got = 0u32;
            if connections.Next(&mut one, Some(&mut got)).is_err() || got == 0 {
                break;
            }
            let Some(c) = one[0].take() else { break };
            if !c.IsConnected().map(|b| b.as_bool()).unwrap_or(false) {
                continue;
            }
            // An adapter the list doesn't know is counted.
            if let Ok(id) = c.GetAdapterId() {
                if known.iter().any(|(g, may)| same_guid(&id, g) && !may) {
                    continue;
                }
            }
            if c.GetNetwork().and_then(|n| n.GetCategory()).is_ok_and(|cat| cat == NLM_NETWORK_CATEGORY_PUBLIC) {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

/// True when a connected Wi-Fi or Ethernet network is Public (discovery is blocked under the default firewall
/// rules). False when none is or it can't be asked.
pub fn network_public() -> bool {
    if let Some((at, public)) = *lock(&PUBLIC) {
        if at.elapsed() < CATEGORY_KEPT {
            return public;
        }
    }
    let public = query_public().unwrap_or(false);
    *lock(&PUBLIC) = Some((Instant::now(), public));
    public
}

// =====================================================================================
// the keyboard layout
// =====================================================================================

const MAPVK_VK_TO_CHAR: u32 = 2;
const MAPVK_VSC_TO_VK_EX: u32 = 3;

/// What each character key types on the keyboard layout in use (the foreground window's): scan code to virtual
/// key to unshifted character (no keyboard state, no dead-key side effects). It reads only the foreground
/// window's thread id, never opens its process.
pub fn key_labels() -> KeyLabels {
    // SAFETY: no pointers but the unused process id (null is allowed); a window that is gone gives thread 0, the
    // calling thread's layout.
    let layout = unsafe {
        let front = GetForegroundWindow();
        let thread = if front.is_null() { 0 } else { GetWindowThreadProcessId(front, std::ptr::null_mut()) };
        GetKeyboardLayout(thread)
    };
    let mut chars = [0u16; wire::KEY_LABEL_USAGES];
    for (c, usage) in chars.iter_mut().zip(wire::key_label_usages()) {
        let sc = hid_scan_codes::for_usage(usage as u32);
        if sc.scan == 0 {
            continue;
        }
        // SAFETY: plain numbers and the layout's handle.
        unsafe {
            let vk = MapVirtualKeyExW(sc.scan as u32, MAPVK_VSC_TO_VK_EX, layout);
            if vk != 0 {
                // The high bit marks a dead key; its character is still what the key shows.
                *c = (MapVirtualKeyExW(vk, MAPVK_VK_TO_CHAR, layout) & 0xFFFF) as u16;
            }
        }
    }
    KeyLabels { layout: layout as usize as u32, chars }
}

// =====================================================================================
// the PC's setup: Wi-Fi radio and power
// =====================================================================================

/// The Wi-Fi radio is asked again only this often (probes run once a second).
const RADIO_KEPT: Duration = Duration::from_secs(5);
const OPCODE_CURRENT_CONNECTION: i32 = 7;
const OPCODE_RSSI: i32 = 0x1000_0102;
const BSS_TYPE_ANY: i32 = 3;
/// sizeof(WLAN_BSS_ENTRY), and the offsets of its BSSID and centre frequency (kHz).
const BSS_ENTRY_SIZE: usize = 360;
const BSS_BSSID_AT: usize = 40;
const BSS_FREQ_AT: usize = 92;
/// WLAN_CONNECTION_ATTRIBUTES: the BSSID and the signal quality (0..100) in its association attributes.
const CONN_BSSID_AT: usize = 560;
const CONN_QUALITY_AT: usize = 576;
const CONN_MIN_SIZE: u32 = 580;

struct Wlan {
    /// The client handle, as a number (0: not opened).
    handle: isize,
    failed: bool,
    radio_for: String,
    radio_at: Option<Instant>,
    radio: PcWifiRadio,
}

static WLAN: Mutex<Wlan> = Mutex::new(Wlan { handle: 0, failed: false, radio_for: String::new(), radio_at: None, radio: PcWifiRadio { freq_mhz: None, rssi_dbm: None } });

/// Memory the WLAN service allocated, freed when dropped.
struct WlanMemory(*const core::ffi::c_void);

impl Drop for WlanMemory {
    fn drop(&mut self) {
        // SAFETY: allocated by a Wlan* call, freed once.
        unsafe {
            wifi::WlanFreeMemory(self.0);
        }
    }
}

fn i32_at(p: *const core::ffi::c_void, at: usize) -> i32 {
    // SAFETY: callers read within the size the service reported; unaligned reads are allowed for.
    unsafe { p.cast::<u8>().add(at).cast::<i32>().read_unaligned() }
}

fn query_interface(handle: isize, guid: &GUID, opcode: i32) -> Option<(WlanMemory, u32)> {
    let mut size = 0u32;
    let mut data: *mut core::ffi::c_void = std::ptr::null_mut();
    // SAFETY: a live client handle and valid out pointers.
    if unsafe { wifi::WlanQueryInterface(handle as _, guid, opcode, std::ptr::null(), &mut size, &mut data, std::ptr::null_mut()) } != 0 || data.is_null() {
        return None;
    }
    Some((WlanMemory(data), size))
}

fn query_radio(w: &mut Wlan, id: &str) -> PcWifiRadio {
    let Some(guid) = parse_guid(id) else { return PcWifiRadio::default() };
    if w.handle == 0 {
        if w.failed {
            return PcWifiRadio::default();
        }
        let mut version = 0u32;
        let mut handle = std::ptr::null_mut();
        // SAFETY: valid out pointers. The handle is kept for the life of the process.
        if unsafe { wifi::WlanOpenHandle(2, std::ptr::null(), &mut version, &mut handle) } != 0 || handle.is_null() {
            w.failed = true; // no WLAN service (a desktop without Wi-Fi)
            return PcWifiRadio::default();
        }
        w.handle = handle as isize;
    }
    let mut rssi: Option<i32> = None;
    let mut freq: Option<i32> = None;
    if let Some((data, size)) = query_interface(w.handle, &guid, OPCODE_RSSI) {
        if size >= 4 {
            rssi = Some(i32_at(data.0, 0));
        }
    }
    let mut bssid = [0u8; 6];
    if let Some((data, size)) = query_interface(w.handle, &guid, OPCODE_CURRENT_CONNECTION) {
        if size >= CONN_MIN_SIZE {
            // SAFETY: within the `size` bytes the service reported.
            unsafe {
                std::ptr::copy_nonoverlapping(data.0.cast::<u8>().add(CONN_BSSID_AT), bssid.as_mut_ptr(), 6);
            }
            // Signal quality 0..100 is -100..-50 dBm.
            if rssi.is_none_or(|r| r >= 0) {
                rssi = Some(i32_at(data.0, CONN_QUALITY_AT) / 2 - 100);
            }
        }
    }
    if bssid.iter().any(|b| *b != 0) {
        let mut list: *mut wifi::WLAN_BSS_LIST = std::ptr::null_mut();
        // SAFETY: a live client handle and a valid out pointer.
        if unsafe { wifi::WlanGetNetworkBssList(w.handle as _, &guid, std::ptr::null(), BSS_TYPE_ANY, 0, std::ptr::null(), &mut list) } == 0 && !list.is_null() {
            let memory = WlanMemory(list.cast());
            // WLAN_BSS_LIST: total size, number of entries, then the entries.
            let total = i32_at(memory.0, 0).max(0) as usize;
            let n = i32_at(memory.0, 4).max(0) as usize;
            for i in 0..n {
                let at = 8 + i * BSS_ENTRY_SIZE;
                if at + BSS_ENTRY_SIZE > total {
                    break;
                }
                let mut entry = [0u8; 6];
                // SAFETY: within the list's `total` bytes.
                unsafe {
                    std::ptr::copy_nonoverlapping(memory.0.cast::<u8>().add(at + BSS_BSSID_AT), entry.as_mut_ptr(), 6);
                }
                if entry == bssid {
                    freq = Some(i32_at(memory.0, at + BSS_FREQ_AT) / 1000);
                    break;
                }
            }
        }
    }
    PcWifiRadio { freq_mhz: freq, rssi_dbm: rssi.filter(|r| *r < 0) }
}

fn radio(id: &str) -> PcWifiRadio {
    let mut w = lock(&WLAN);
    if w.radio_for == id && w.radio_at.is_some_and(|at| at.elapsed() < RADIO_KEPT) {
        return w.radio;
    }
    let r = query_radio(&mut w, id);
    w.radio_for = id.to_string();
    w.radio_at = Some(Instant::now());
    w.radio = r;
    r
}

/// Power mode "Best power efficiency" (the power slider's overlay).
const BEST_EFFICIENCY: GUID = GUID { data1: 0x961cc777, data2: 0x2547, data3: 0x4f9d, data4: [0x81, 0x74, 0x7d, 0x86, 0x18, 0x1b, 0x8a, 0x7a] };

type OverlayScheme = unsafe extern "system" fn(*mut GUID) -> u32;

/// powrprof.dll's PowerGetEffectiveOverlayScheme (Windows 10 1709 and later), looked up once.
fn overlay_scheme() -> Option<OverlayScheme> {
    static FOUND: std::sync::OnceLock<Option<OverlayScheme>> = std::sync::OnceLock::new();
    *FOUND.get_or_init(|| {
        let name = wide("powrprof.dll");
        // SAFETY: NUL-ended names; the library stays loaded. The function takes a GUID* and returns a DWORD.
        unsafe {
            let lib = LoadLibraryW(name.as_ptr());
            if lib.is_null() {
                return None;
            }
            GetProcAddress(lib, c"PowerGetEffectiveOverlayScheme".as_ptr().cast()).map(|f| std::mem::transmute::<unsafe extern "system" fn() -> isize, OverlayScheme>(f))
        }
    })
}

/// On battery, and saving power (energy saver, or the power mode "Best power efficiency").
fn power() -> (bool, bool) {
    let (mut on_battery, mut saving) = (false, false);
    // SAFETY: plain data filled by the call.
    unsafe {
        let mut s: SYSTEM_POWER_STATUS = std::mem::zeroed();
        if GetSystemPowerStatus(&mut s) != 0 {
            on_battery = s.ACLineStatus == 0;
            saving = s.SystemStatusFlag & 1 != 0;
        }
        if let Some(f) = overlay_scheme() {
            let mut overlay: GUID = std::mem::zeroed();
            if f(&mut overlay) == 0 && overlay.data1 == BEST_EFFICIENCY.data1 && overlay.data2 == BEST_EFFICIENCY.data2 && overlay.data3 == BEST_EFFICIENCY.data3 && overlay.data4 == BEST_EFFICIENCY.data4 {
                saving = true;
            }
        }
    }
    (on_battery, saving)
}

/// The setup bits for a session with a phone at `phone` (None: only the PC's power).
pub fn pc_setup(phone: Option<IpAddr>) -> u8 {
    let adapter = pc_setup::adapter_for(phone, &adapters());
    let r = match &adapter {
        Some(a) if a.wireless && !a.hotspot => radio(&a.id),
        _ => PcWifiRadio::default(),
    };
    let (on_battery, saving) = power();
    pc_setup::bits(adapter.as_ref(), r, on_battery, saving)
}

/// Whether the PC is a Wi-Fi client toward the phone at that address (only then is KEEP_AWAKE needed); None when
/// no adapter of the PC is on the phone's subnet.
pub fn pc_wifi_client(phone: Option<IpAddr>) -> Option<bool> {
    pc_setup::adapter_for(phone, &adapters()).map(|a| a.station)
}

/// Everything the watchdog asks, as `host::Platform` takes it.
pub fn probes() -> Probes {
    Probes {
        // The input process never looks at the window in front's process (anti-cheat systems log that).
        elevated_foreground: None,
        network_public: Some(Box::new(network_public)),
        key_labels: Some(Box::new(|| Some(key_labels()))),
        pc_wifi_client: Some(Box::new(pc_wifi_client)),
        pc_setup: Some(Box::new(pc_setup)),
    }
}
