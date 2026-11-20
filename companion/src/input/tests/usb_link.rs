//! The direct USB link's look at the cable, with a made-up system behind it.

use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use gp_input::host::UsbLink;
use gp_input::udp::Log;
use gp_input::usb_bridge::UsbPipe;
use gp_input::usb_link::*;

fn node(instance: &str, hardware: &str, compatible: &[&str], guids: usize) -> UsbNode {
    UsbNode {
        dev_inst: 1,
        instance_id: instance.to_string(),
        hardware_ids: vec![hardware.to_string()],
        compatible_ids: compatible.iter().map(|s| s.to_string()).collect(),
        interface_guids: (0..guids).map(|_| "{F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}".to_string()).collect(),
    }
}

const ADB: &[&str] = &[r"USB\Class_ff&SubClass_42&Prot_01", r"USB\Class_ff"];

fn adb_phone(mi: Option<u32>) -> UsbNode {
    match mi {
        Some(mi) => node(&format!(r"USB\VID_22D9&PID_2769&MI_{mi:02X}\7&1&0&0000"), &format!(r"USB\VID_22D9&PID_2769&REV_0404&MI_{mi:02X}"), ADB, 1),
        None => node(r"USB\VID_22D9&PID_2769\abc", r"USB\VID_22D9&PID_2769&REV_0404", ADB, 1),
    }
}

#[test]
fn nodes_are_told_apart() {
    let whole = node(r"USB\VID_18D1&PID_2D00\serial", r"USB\VID_18D1&PID_2D00&REV_0404", &[], 1);
    assert_eq!(whole.vid_pid(), Some((0x18D1, 0x2D00)));
    assert_eq!(whole.interface(), None);
    assert!(whole.in_accessory_mode() && whole.is_accessory() && !whole.is_adb());
    assert_eq!(whole.name(), "VID_18D1&PID_2D00&REV_0404");

    let with_adb = node(r"usb\vid_18d1&pid_2d01&mi_00\7&x", r"USB\VID_18D1&PID_2D01&MI_00", &[], 1);
    assert!(with_adb.is_accessory());
    let its_adb = node(r"USB\VID_18D1&PID_2D01&MI_01\7&x", r"USB\VID_18D1&PID_2D01&MI_01", ADB, 1);
    assert!(its_adb.in_accessory_mode() && !its_adb.is_accessory() && !its_adb.is_adb());
    let parent = node(r"USB\VID_18D1&PID_2D01\serial", r"USB\VID_18D1&PID_2D01", &[], 0);
    assert!(parent.in_accessory_mode() && !parent.is_accessory());

    // A vendor's composite driver names the interface in the instance id; the number is in the hardware id.
    let vendor = node(r"USB\VID_22D9&PID_2769&ADB\7&x", r"USB\VID_22D9&PID_2769&REV_0404&MI_02", ADB, 1);
    assert_eq!(vendor.interface(), Some(2));
    assert!(vendor.is_adb());
    assert!(!node(r"USB\VID_22D9&PID_2769&MI_01\7", r"USB\VID_22D9&PID_2769&MI_01", ADB, 0).is_adb(), "no driver interface");
    assert!(!node(r"USB\VID_046D&PID_C52B\x", r"USB\VID_046D&PID_C52B", &[r"USB\Class_03"], 1).is_adb());

    for bad in ["", "USB", r"USB\VID_", r"USB\VID_18D1", r"USB\VID_18D1&PID_", r"USB\VID_ZZZZ&PID_2D00", r"HID\VID_18D1&PID_2D00", r"USB\VID_18D1&PID_2D0é", r"USB\VID_18D1&PID_2D00&MI_", r"USB\VID_18D1&PID_2D00&MI_Z1&MI_03"] {
        let n = node(bad, bad, &[], 0);
        let _ = (n.vid_pid(), n.interface(), n.name(), n.is_accessory(), n.is_adb());
    }
    assert_eq!(node(r"USB\VID_18D1&PID_2D00&MI_Z1&MI_03", "", &[], 0).interface(), Some(3));
}

#[test]
fn the_waits_follow_the_state() {
    assert_eq!(wait_ms(UsbState::Switching, true), 250);
    assert_eq!(wait_ms(UsbState::Switching, false), 250);
    for s in [UsbState::Off, UsbState::NoPhone, UsbState::NeedsDriver] {
        assert_eq!(wait_ms(s, true), 30_000);
        assert_eq!(wait_ms(s, false), 1000);
    }
    for s in [UsbState::AdbBusy, UsbState::WrongUsbMode, UsbState::Connected, UsbState::Failed] {
        assert_eq!(wait_ms(s, true), 1000);
    }
}

struct Pipe {
    feed: Mutex<Option<Sender<Vec<u8>>>>,
    from_phone: Mutex<Receiver<Vec<u8>>>,
}

impl UsbPipe for Pipe {
    fn read(&self, buffer: &mut [u8]) -> std::io::Result<usize> {
        match self.from_phone.lock().unwrap().recv() {
            Ok(d) => {
                buffer[..d.len()].copy_from_slice(&d);
                Ok(d.len())
            }
            Err(_) => Err(std::io::Error::new(std::io::ErrorKind::BrokenPipe, "unplugged")),
        }
    }
    fn write(&self, _data: &[u8]) -> bool {
        true
    }
    fn cancel(&self) {
        *self.feed.lock().unwrap() = None;
    }
}

#[derive(Default)]
struct System {
    nodes: Mutex<Vec<UsbNode>>,
    /// What opening answers: Err(code), or a device whose requests answer `aoa`.
    open_error: AtomicU32,
    aoa_version: AtomicU32,
    no_driver: AtomicBool,
    requests: Mutex<Vec<(u8, u8, u16, Vec<u8>)>>,
    adb_stops: AtomicU32,
    changed: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
    pipe: Mutex<Option<Arc<Pipe>>>,
    logged: Mutex<Vec<String>>,
}

struct Device(Arc<System>);

impl UsbDevice for Device {
    fn control(&self, request_type: u8, request: u8, _value: u16, index: u16, data: &mut [u8]) -> Result<u32, u32> {
        self.0.requests.lock().unwrap().push((request_type, request, index, data.to_vec()));
        if request == 51 {
            let v = self.0.aoa_version.load(Ordering::SeqCst);
            if v == u32::MAX {
                return Err(31);
            }
            data.copy_from_slice(&(v as u16).to_le_bytes());
            return Ok(2);
        }
        Ok(data.len() as u32)
    }
    fn into_pipe(self: Box<Self>) -> Result<Arc<dyn UsbPipe>, String> {
        let (feed, from_phone) = channel();
        let pipe = Arc::new(Pipe { feed: Mutex::new(Some(feed)), from_phone: Mutex::new(from_phone) });
        *self.0.pipe.lock().unwrap() = Some(pipe.clone());
        Ok(pipe)
    }
}

struct Sys(Arc<System>);

impl UsbSystem for Sys {
    fn list(&self) -> Result<Vec<UsbNode>, String> {
        Ok(self.0.nodes.lock().unwrap().clone())
    }
    fn interface_path(&self, node: &UsbNode) -> Option<String> {
        (!self.0.no_driver.load(Ordering::SeqCst)).then(|| format!(r"\\?\{}", node.instance_id))
    }
    fn open(&self, _path: &str) -> Result<Box<dyn UsbDevice>, u32> {
        match self.0.open_error.load(Ordering::SeqCst) {
            0 => Ok(Box::new(Device(self.0.clone()))),
            e => Err(e),
        }
    }
    fn stop_adb_server(&self, _log: &Log) {
        self.0.adb_stops.fetch_add(1, Ordering::SeqCst);
    }
    fn watch(&self, changed: Arc<dyn Fn() + Send + Sync>, _log: &Log) -> Option<Box<dyn Send + Sync>> {
        *self.0.changed.lock().unwrap() = Some(changed);
        Some(Box::new(()))
    }
    fn machine_name(&self) -> String {
        "DESK".to_string()
    }
}

struct Rig {
    system: Arc<System>,
    link: AoaUsbLink,
}

impl Rig {
    fn new(nodes: Vec<UsbNode>) -> Rig {
        let system = Arc::new(System::default());
        system.aoa_version.store(2, Ordering::SeqCst);
        *system.nodes.lock().unwrap() = nodes;
        let s2 = system.clone();
        let log: Log = Arc::new(move |m: &str| s2.logged.lock().unwrap().push(m.to_string()));
        // A port nothing listens on: the bridge only sends there.
        let link = AoaUsbLink::new(Arc::new(Sys(system.clone())), 9, log);
        Rig { system, link }
    }

    fn started(nodes: Vec<UsbNode>) -> Rig {
        let r = Rig::new(nodes);
        r.link.set_enabled(true);
        r.link.start();
        r
    }

    fn becomes(&self, state: &str) -> bool {
        let until = Instant::now() + Duration::from_secs(5);
        while Instant::now() < until {
            if self.link.state() == state {
                return true;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        false
    }

    fn changed(&self, nodes: Vec<UsbNode>) {
        *self.system.nodes.lock().unwrap() = nodes;
        let f = self.system.changed.lock().unwrap().clone().unwrap();
        f();
    }

    fn logged(&self, part: &str) -> usize {
        self.system.logged.lock().unwrap().iter().filter(|l| l.contains(part)).count()
    }
}

#[test]
fn off_until_switched_on_then_no_phone() {
    let r = Rig::new(vec![]);
    assert_eq!((r.link.state().as_str(), r.link.enabled()), ("Off", false));
    r.link.start();
    std::thread::sleep(Duration::from_millis(50));
    assert_eq!(r.link.state(), "Off");
    r.link.set_enabled(true);
    assert!(r.becomes("NoPhone"));
    r.link.set_enabled(false);
    assert!(r.becomes("Off"));
    assert_eq!(r.logged("usb: NoPhone"), 1);
    r.link.stop();
}

#[test]
fn a_phone_is_asked_to_switch_and_then_bridged() {
    let r = Rig::started(vec![adb_phone(None)]);
    assert!(r.becomes("Switching"));
    assert_eq!(r.link.detail(), "VID_22D9&PID_2769&REV_0404");
    {
        let q = r.system.requests.lock().unwrap();
        assert_eq!((q[0].0, q[0].1), (0xC0, 51));
        let strings: Vec<String> = q[1..7].iter().map(|x| String::from_utf8(x.3.clone()).unwrap()).collect();
        assert_eq!(strings, ["GyroPhone\0", "GyroPhone Companion\0", "Gamepad, keyboard and mouse for this PC\0", "1\0", "https://github.com/oleroma/GyroPhone\0", "DESK\0"]);
        assert_eq!(q[1..7].iter().map(|x| (x.0, x.1, x.2)).collect::<Vec<_>>(), (0..6).map(|i| (0x40, 52, i)).collect::<Vec<_>>());
        assert_eq!((q[7].0, q[7].1, q[7].3.len()), (0x40, 53, 0));
        assert_eq!(q.len(), 8);
    }
    // Gone and back as an accessory whose interface isn't listed yet, then listed.
    r.changed(vec![node(r"USB\VID_18D1&PID_2D01\serial", r"USB\VID_18D1&PID_2D01", &[], 0)]);
    std::thread::sleep(Duration::from_millis(50));
    assert_eq!(r.link.state(), "Switching");
    r.changed(vec![node(r"USB\VID_18D1&PID_2D01&MI_00\7&x", r"USB\VID_18D1&PID_2D01&MI_00", &[], 1)]);
    assert!(r.becomes("Connected"));
    assert_eq!(r.logged("usb: accessory open (VID_18D1&PID_2D01&MI_00), bridged to UDP 9 from 127.0.0.1:"), 1);
    // The cable is pulled: the bridge dies, and the next look finds no phone.
    let pipe = r.system.pipe.lock().unwrap().clone().unwrap();
    *pipe.feed.lock().unwrap() = None;
    r.changed(vec![]);
    assert!(r.becomes("NoPhone"));
    assert_eq!(r.logged("usb: Connected"), 1);
    r.link.stop();
}

#[test]
fn an_accessory_without_winusb_needs_its_driver() {
    let r = Rig::new(vec![node(r"USB\VID_18D1&PID_2D00\serial", r"USB\VID_18D1&PID_2D00", &[], 0)]);
    r.system.no_driver.store(true, Ordering::SeqCst);
    r.link.set_enabled(true);
    r.link.start();
    assert!(r.becomes("NeedsDriver"));
    assert_eq!(r.link.detail(), "VID_18D1&PID_2D00");
    r.system.no_driver.store(false, Ordering::SeqCst);
    r.system.open_error.store(5, Ordering::SeqCst);
    let same = r.system.nodes.lock().unwrap().clone();
    r.changed(same);
    assert!(r.becomes("Failed"));
    assert_eq!(r.link.detail(), "cannot open the accessory (error 5)");
    r.link.stop();
}

#[test]
fn adb_holding_the_phone_shows_and_can_be_taken() {
    let r = Rig::new(vec![adb_phone(Some(0))]);
    r.system.open_error.store(5, Ordering::SeqCst);
    r.link.set_enabled(true);
    r.link.start();
    assert!(r.becomes("AdbBusy"));
    r.system.open_error.store(0, Ordering::SeqCst);
    r.link.take_from_adb();
    assert!(r.becomes("Switching"));
    assert_eq!(r.system.adb_stops.load(Ordering::SeqCst), 1);
    r.link.stop();
}

#[test]
fn a_phone_that_refuses_is_left_alone_for_a_while() {
    // adb is the phone's second function: Windows refuses the request, and it says which USB mode is wrong.
    let r = Rig::new(vec![adb_phone(Some(1))]);
    r.system.aoa_version.store(u32::MAX, Ordering::SeqCst);
    r.link.set_enabled(true);
    r.link.start();
    assert!(r.becomes("WrongUsbMode"));
    assert_eq!(r.logged("did not switch: no AOA answer (error 31)"), 1);
    let same = r.system.nodes.lock().unwrap().clone();
    r.changed(same);
    std::thread::sleep(Duration::from_millis(50));
    assert_eq!(r.system.requests.lock().unwrap().len(), 1, "not asked again within its wait");
    r.link.stop();

    // A phone without accessory mode at all: no phone to use.
    let r = Rig::new(vec![adb_phone(None)]);
    r.system.aoa_version.store(0, Ordering::SeqCst);
    r.link.set_enabled(true);
    r.link.start();
    assert!(r.becomes("NoPhone"));
    assert_eq!(r.logged("did not switch: the phone does not support accessory mode"), 1);
    r.link.stop();
    r.link.stop();
}
