//! The PC's side of the phone's Setup tips and of KEEP_AWAKE: which of the PC's network adapters reaches the
//! phone, and HOST_STATUS's setup bits from that adapter, its Wi-Fi radio and the PC's power. Only what could be
//! better is set. The system's side (listing adapters, the radio, the power) is the Windows layer's. Ports of
//! PcSetup.cs and the adapter half of LinkPaths.cs, and of NetworkCategory.cs's adapter filter.

use std::net::{IpAddr, Ipv4Addr};

use crate::wire::host_status_flags2 as flags;

/// A signal below this (dBm) is weak, as on the phone.
pub const WEAK_DBM: i32 = -70;

/// One of the PC's network adapters, as the system lists it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct NetAdapter {
    /// Its name as Windows shows it ("Wi-Fi", "Ethernet", "Local Area Connection* 10").
    pub name: String,
    pub description: String,
    /// "{GUID}", as the Wi-Fi API names its interfaces.
    pub id: String,
    pub up: bool,
    /// A Wi-Fi adapter (the hotspot's virtual one included).
    pub wireless: bool,
    /// Wired Ethernet by its type (virtual ones included).
    pub ethernet: bool,
    /// Its IPv4 addresses with their prefix lengths.
    pub addresses: Vec<(Ipv4Addr, u8)>,
}

/// The PC's network adapter that a phone's address belongs to.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PcAdapter {
    pub name: String,
    /// It is Windows' Mobile hotspot (the Wi-Fi Direct virtual adapter, 192.168.137.x).
    pub hotspot: bool,
    /// It is a Wi-Fi adapter (the hotspot's included).
    pub wireless: bool,
    pub id: String,
    /// It is a Wi-Fi card itself, which joins networks as a client and may doze in power save; not the hotspot's
    /// virtual adapter. Told by the adapter's kind alone, never by its subnet, so a router that happens to use
    /// 192.168.137.x still counts.
    pub station: bool,
}

fn contains_ignore_case(text: &str, word: &str) -> bool {
    text.to_lowercase().contains(&word.to_lowercase())
}

/// The PC adapter on `phone`'s subnet, or None (loopback, IPv6, none found).
pub fn adapter_for(phone: Option<IpAddr>, adapters: &[NetAdapter]) -> Option<PcAdapter> {
    let Some(IpAddr::V4(phone)) = phone else { return None };
    if phone.is_loopback() {
        return None;
    }
    let p = u32::from(phone);
    for a in adapters.iter().filter(|a| a.up) {
        for (address, prefix) in &a.addresses {
            if *prefix == 0 || *prefix > 32 {
                continue;
            }
            let mask = if *prefix == 32 { u32::MAX } else { !(u32::MAX >> prefix) };
            let own = u32::from(*address);
            if own & mask != p & mask {
                continue;
            }
            let direct = contains_ignore_case(&a.description, "Wi-Fi Direct");
            let hotspot = direct || own & 0xFFFF_FF00 == 0xC0A8_8900;
            return Some(PcAdapter { name: a.name.clone(), hotspot, wireless: a.wireless || hotspot, id: a.id.clone(), station: a.wireless && !direct });
        }
    }
    None
}

/// What the PC's Wi-Fi radio on the phone's adapter reports: centre frequency (MHz) and signal (dBm), each None
/// when unknown.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct PcWifiRadio {
    pub freq_mhz: Option<i32>,
    pub rssi_dbm: Option<i32>,
}

/// HOST_STATUS flags2's setup bits.
pub fn bits(adapter: Option<&PcAdapter>, radio: PcWifiRadio, on_battery: bool, power_saving: bool) -> u8 {
    let mut b = 0;
    if adapter.is_some_and(|a| a.wireless && !a.hotspot) {
        b |= flags::PC_WIFI;
        if radio.freq_mhz.is_some_and(|f| f > 0 && f < 3000) {
            b |= flags::PC_WIFI_24;
        }
        if radio.rssi_dbm.is_some_and(|r| r < WEAK_DBM && r > -127) {
            b |= flags::PC_WIFI_WEAK;
        }
    }
    if on_battery {
        b |= flags::PC_ON_BATTERY;
    }
    if power_saving {
        b |= flags::PC_POWER_SAVING;
    }
    b
}

/// Adapter descriptions or names that are never the phone's network.
const NOT_THE_PHONE: [&str; 13] =
    ["Virtual", "Hyper-V", "vEthernet", "VMware", "VirtualBox", "Remote NDIS", "RNDIS", "TAP-", "Wintun", "WireGuard", "VPN", "Bluetooth", "Loopback"];

/// Wi-Fi, or wired Ethernet that isn't a virtual, tethering, VPN or Bluetooth adapter: only those count for "the
/// network is Public" (USB tethering has its own firewall rule, and the others are often Public but never carry
/// the phone).
pub fn may_carry_the_phone(a: &NetAdapter) -> bool {
    if a.wireless {
        return true;
    }
    a.ethernet && !NOT_THE_PHONE.iter().any(|w| contains_ignore_case(&a.description, w) || contains_ignore_case(&a.name, w))
}
