//! Which PC adapter reaches the phone, and the setup bits.

use std::net::{IpAddr, Ipv4Addr};

use gp_input::pc_setup::*;
use gp_input::wire::host_status_flags2 as flags;

fn adapter(name: &str, description: &str, wireless: bool, address: [u8; 4], prefix: u8) -> NetAdapter {
    NetAdapter { name: name.into(), description: description.into(), id: format!("{{{name}}}"), up: true, wireless, ethernet: !wireless, addresses: vec![(Ipv4Addr::from(address), prefix)] }
}

fn ip(a: [u8; 4]) -> Option<IpAddr> {
    Some(IpAddr::V4(Ipv4Addr::from(a)))
}

#[test]
fn the_adapter_on_the_phones_subnet_is_found() {
    let adapters = vec![
        adapter("Ethernet", "Realtek PCIe GbE", false, [192, 168, 1, 10], 24),
        adapter("Wi-Fi", "Realtek 8852CE", true, [192, 168, 50, 7], 24),
        adapter("Local Area Connection* 10", "Microsoft Wi-Fi Direct Virtual Adapter #2", true, [192, 168, 137, 1], 24),
        NetAdapter { up: false, ..adapter("Old", "Down", true, [10, 0, 0, 1], 8) },
        adapter("Odd", "Zero prefix", false, [172, 16, 0, 1], 0),
    ];
    let wifi = adapter_for(ip([192, 168, 50, 99]), &adapters).unwrap();
    assert_eq!((wifi.name.as_str(), wifi.hotspot, wifi.wireless, wifi.station, wifi.id.as_str()), ("Wi-Fi", false, true, true, "{Wi-Fi}"));
    let wired = adapter_for(ip([192, 168, 1, 77]), &adapters).unwrap();
    assert_eq!((wired.hotspot, wired.wireless, wired.station), (false, false, false));
    let hotspot = adapter_for(ip([192, 168, 137, 42]), &adapters).unwrap();
    assert_eq!((hotspot.hotspot, hotspot.wireless, hotspot.station), (true, true, false));
    assert_eq!(adapter_for(ip([10, 1, 2, 3]), &adapters), None, "a down adapter");
    assert_eq!(adapter_for(ip([172, 16, 0, 9]), &adapters), None, "a prefix of 0 matches nothing");
    assert_eq!(adapter_for(ip([127, 0, 0, 1]), &adapters), None);
    assert_eq!(adapter_for(None, &adapters), None);
    assert_eq!(adapter_for(Some("fe80::1".parse().unwrap()), &adapters), None);

    // A router that happens to use 192.168.137.x: the PC's own Wi-Fi card is still a client there.
    let router = [adapter("Wi-Fi", "Intel AX211", true, [192, 168, 137, 20], 24)];
    let a = adapter_for(ip([192, 168, 137, 1]), &router).unwrap();
    assert_eq!((a.hotspot, a.station), (true, true));
    // /32 and /31 work without overflowing.
    let narrow = [adapter("P2P", "x", false, [10, 0, 0, 2], 32), adapter("P2P31", "x", false, [10, 0, 1, 2], 31)];
    assert!(adapter_for(ip([10, 0, 0, 2]), &narrow).is_some());
    assert!(adapter_for(ip([10, 0, 0, 3]), &narrow).is_none());
    assert!(adapter_for(ip([10, 0, 1, 3]), &narrow).is_some());
}

#[test]
fn the_setup_bits_say_only_what_could_be_better() {
    let adapters = [adapter("Wi-Fi", "Realtek", true, [192, 168, 50, 7], 24), adapter("LAC", "Microsoft Wi-Fi Direct Virtual Adapter", true, [192, 168, 137, 1], 24), adapter("Ethernet", "GbE", false, [10, 0, 0, 1], 8)];
    let wifi = adapter_for(ip([192, 168, 50, 9]), &adapters);
    let hotspot = adapter_for(ip([192, 168, 137, 9]), &adapters);
    let wired = adapter_for(ip([10, 9, 9, 9]), &adapters);
    let radio = |f, r| PcWifiRadio { freq_mhz: f, rssi_dbm: r };
    assert_eq!(bits(wifi.as_ref(), radio(Some(5180), Some(-50)), false, false), flags::PC_WIFI);
    assert_eq!(bits(wifi.as_ref(), radio(Some(2437), Some(-71)), false, false), flags::PC_WIFI | flags::PC_WIFI_24 | flags::PC_WIFI_WEAK);
    assert_eq!(bits(wifi.as_ref(), radio(None, Some(-70)), true, false), flags::PC_WIFI | flags::PC_ON_BATTERY);
    assert_eq!(bits(wifi.as_ref(), radio(Some(0), Some(-127)), false, true), flags::PC_WIFI | flags::PC_POWER_SAVING);
    assert_eq!(bits(hotspot.as_ref(), radio(Some(2437), Some(-90)), false, false), 0);
    assert_eq!(bits(wired.as_ref(), radio(Some(2437), Some(-90)), true, true), flags::PC_ON_BATTERY | flags::PC_POWER_SAVING);
    assert_eq!(bits(None, PcWifiRadio::default(), false, false), 0);
    assert_eq!(bits(wifi.as_ref(), radio(Some(2437), Some(-90)), true, true) & !flags::SETUP_MASK, 0);
}

#[test]
fn only_real_wifi_and_ethernet_count_for_a_public_network() {
    assert!(may_carry_the_phone(&adapter("Wi-Fi", "Realtek 8852CE", true, [1, 1, 1, 1], 24)));
    assert!(may_carry_the_phone(&adapter("Ethernet", "Realtek PCIe GbE Family Controller", false, [1, 1, 1, 1], 24)));
    for (name, description) in [("vEthernet (WSL)", "Hyper-V Virtual Ethernet Adapter"), ("Ethernet 3", "Remote NDIS based Internet Sharing Device"), ("Ethernet 4", "TAP-Windows Adapter V9"), ("NordLynx", "WireGuard Tunnel"), ("Bluetooth Network Connection", "x"), ("Ethernet 5", "VMWARE virtual adapter"), ("my vpn", "x")] {
        assert!(!may_carry_the_phone(&adapter(name, description, false, [1, 1, 1, 1], 24)), "{name}");
    }
    let tunnel = NetAdapter { ethernet: false, ..adapter("Teredo", "Tunnel", false, [1, 1, 1, 1], 24) };
    assert!(!may_carry_the_phone(&tunnel));
}
