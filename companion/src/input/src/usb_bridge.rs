//! Carries the direct USB link into the UDP server: every datagram from the phone goes to the server's port from
//! a loopback socket of our own, and whatever the server sends back to that socket goes down the cable. The
//! engine sees one more phone address (127.0.0.1:port), so pairing, sessions and encryption are exactly those of
//! Wi-Fi. Thread gp-usb-rx reads the cable, gp-usb-tx the loopback socket. Port of UsbBridge.cs.

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::sys::{self, Priority};
use crate::udp::Log;
use crate::usb_framing::{self, UsbDeframer};
use crate::wire::{self, packet};

/// The bulk endpoints of a phone in accessory mode (direct USB). `read` blocks for the next transfer and fails
/// once the phone is gone; `write` gives up after a short timeout (the app on the phone may not be reading).
/// `cancel` unblocks a pending read and write. Dropping it closes the handles, which happens only once no call
/// is running. Implemented with WinUSB; faked in tests.
pub trait UsbPipe: Send + Sync {
    fn read(&self, buffer: &mut [u8]) -> std::io::Result<usize>;
    /// False when the transfer failed or timed out.
    fn write(&self, data: &[u8]) -> bool;
    fn cancel(&self);
}

#[derive(Default)]
struct Counters {
    from_phone: AtomicI64,
    to_phone: AtomicI64,
    write_failures: AtomicI64,
}

struct Shared {
    pipe: Arc<dyn UsbPipe>,
    socket: UdpSocket,
    server: SocketAddr,
    log: Log,
    on_thread_start: Option<Arc<dyn Fn() + Send + Sync>>,
    stopping: AtomicBool,
    dead: AtomicBool,
    counters: Counters,
}

pub struct UsbBridge {
    shared: Arc<Shared>,
    threads: Mutex<Vec<(std::thread::JoinHandle<()>, std::sync::mpsc::Receiver<()>)>>,
    local: SocketAddr,
}

/// Whether a datagram from the cable goes to the server. Everything from the bridge reaches the engine from
/// 127.0.0.1, which it trusts as "a program on this PC" for LINK_TEST_CTL and LOG_CTL: those never come from a
/// phone, so they stop here (anything on the phone that holds the accessory could send them, unpaired).
pub fn forwards(d: &[u8]) -> bool {
    d.len() < 4 || !matches!(d[3], packet::LINK_TEST_CTL | packet::LOG_CTL)
}

impl UsbBridge {
    pub fn new(pipe: Arc<dyn UsbPipe>, server_port: u16, log: Option<Log>, on_thread_start: Option<Arc<dyn Fn() + Send + Sync>>) -> std::io::Result<UsbBridge> {
        let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))?;
        let local = socket.local_addr()?;
        Ok(UsbBridge {
            shared: Arc::new(Shared {
                pipe,
                socket,
                server: SocketAddr::from((Ipv4Addr::LOCALHOST, server_port)),
                log: log.unwrap_or_else(|| Arc::new(|_| {})),
                on_thread_start,
                stopping: AtomicBool::new(false),
                dead: AtomicBool::new(false),
                counters: Counters::default(),
            }),
            threads: Mutex::new(Vec::new()),
            local,
        })
    }

    /// False once the cable side failed (unplugged, phone left accessory mode).
    pub fn alive(&self) -> bool {
        !self.shared.dead.load(Ordering::SeqCst) && !self.shared.stopping.load(Ordering::SeqCst)
    }

    pub fn from_phone(&self) -> i64 {
        self.shared.counters.from_phone.load(Ordering::Relaxed)
    }

    pub fn to_phone(&self) -> i64 {
        self.shared.counters.to_phone.load(Ordering::Relaxed)
    }

    pub fn write_failures(&self) -> i64 {
        self.shared.counters.write_failures.load(Ordering::Relaxed)
    }

    /// The loopback address the engine sees for this phone.
    pub fn local_addr(&self) -> SocketAddr {
        self.local
    }

    pub fn start(&self) -> std::io::Result<()> {
        let mut threads = self.threads.lock().unwrap_or_else(|e| e.into_inner());
        if !threads.is_empty() {
            return Ok(());
        }
        for (name, tx) in [("gp-usb-rx", false), ("gp-usb-tx", true)] {
            let shared = self.shared.clone();
            let (done_tx, done) = std::sync::mpsc::channel();
            let spawned = std::thread::Builder::new().name(name.into()).spawn(move || {
                sys::set_thread_priority(Priority::Highest);
                if let Some(f) = &shared.on_thread_start {
                    f();
                }
                if tx {
                    shared.tx_loop();
                } else {
                    shared.rx_loop();
                }
                let _ = done_tx.send(());
            });
            match spawned {
                Ok(t) => threads.push((t, done)),
                Err(e) => {
                    drop(threads);
                    self.stop();
                    return Err(e);
                }
            }
        }
        Ok(())
    }

    /// Stops both threads, waiting up to a second for each. A thread stuck in a transfer keeps the pipe alive
    /// (it holds its own reference), so the handle is never freed under a running call.
    pub fn stop(&self) {
        if self.shared.stopping.swap(true, Ordering::SeqCst) {
            return;
        }
        self.shared.pipe.cancel();
        crate::udp::wake(&self.shared.socket);
        let threads = std::mem::take(&mut *self.threads.lock().unwrap_or_else(|e| e.into_inner()));
        let me = std::thread::current().id();
        for (t, done) in threads {
            if t.thread().id() == me {
                continue;
            }
            if done.recv_timeout(Duration::from_secs(1)).is_ok() {
                let _ = t.join();
            } else {
                (self.shared.log)("usb: a transfer did not stop; the handle is left open");
            }
        }
    }
}

impl Drop for UsbBridge {
    fn drop(&mut self) {
        self.stop();
    }
}

impl Shared {
    fn rx_loop(&self) {
        let mut buf = vec![0u8; 16384];
        let mut deframer = UsbDeframer::new();
        while !self.stopping.load(Ordering::SeqCst) {
            match self.pipe.read(&mut buf) {
                Ok(n) => {
                    let n = n.min(buf.len());
                    deframer.feed(&buf[..n], |d| self.to_server(d));
                }
                Err(e) => {
                    if !self.stopping.load(Ordering::SeqCst) {
                        (self.log)(&format!("usb: cable side closed ({e})"));
                    }
                    break;
                }
            }
        }
        self.dead.store(true, Ordering::SeqCst);
        // gp-usb-tx sits in a receive: let it see that the cable is gone.
        crate::udp::wake(&self.socket);
    }

    fn to_server(&self, d: &[u8]) {
        if !forwards(d) {
            return;
        }
        if self.socket.send_to(d, self.server).is_ok() {
            self.counters.from_phone.fetch_add(1, Ordering::Relaxed);
        }
    }

    fn tx_loop(&self) {
        let mut buf = [0u8; 2048];
        let mut frame = vec![0u8; usb_framing::max_size(wire::MAX_DATAGRAM)];
        let mut failing = 0u32;
        while !self.stopping.load(Ordering::SeqCst) && !self.dead.load(Ordering::SeqCst) {
            let (n, from) = match self.socket.recv_from(&mut buf) {
                Ok(x) => x,
                // An ICMP port-unreachable from an earlier send, or a datagram too long for us: not fatal.
                Err(e) if sys::is_connection_reset(&e) || sys::is_message_size(&e) || e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => {
                    failing += 1;
                    if failing > 100 {
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(10));
                    continue;
                }
            };
            failing = 0;
            if from != self.server || !(1..=wire::MAX_DATAGRAM).contains(&n) {
                continue; // only the server talks to us
            }
            let Some(len) = usb_framing::encode(&buf[..n], &mut frame) else { continue };
            if self.pipe.write(&frame[..len]) {
                self.counters.to_phone.fetch_add(1, Ordering::Relaxed);
            } else {
                self.counters.write_failures.fetch_add(1, Ordering::Relaxed);
            }
        }
    }
}
