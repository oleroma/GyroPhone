//! The one UDP socket on port 47800. Thread gp-rx blocks in the receive, drains the backlog into
//! `HostEngine::on_datagram`, then calls `flush` once, so a burst of STATEs causes a single injection (the engine
//! injects early only when a newer STATE changes keys or buttons). Port of UdpHostServer.cs.

use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::engine::HostEngine;
use crate::ports::Clock;
use crate::session::DatagramSender;
use crate::sys::{self, BindError, Priority};
use crate::wire;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ServerState {
    Stopped,
    Running,
    PortInUse,
    Failed,
}

/// gp-rx's wait while a lost or late stretch of mouse motion catches up: it must wake at once for a datagram and
/// close to on time without one, so the catch-up moves on every millisecond. Made on gp-rx and used only there.
pub trait CatchUpWait {
    /// Waits until `socket` has a datagram (true) or about `us` µs passed (false). Leaves the socket blocking.
    fn wait(&mut self, socket: &UdpSocket, us: u32) -> bool;
}

/// The system's poll: wakes at once for a datagram, but on Windows its timeout ends only on the system timer's
/// tick (about 15.6 ms). Tests, and the fallback.
pub struct PollCatchUpWait;

impl CatchUpWait for PollCatchUpWait {
    fn wait(&mut self, socket: &UdpSocket, us: u32) -> bool {
        sys::poll_readable(socket, us)
    }
}

pub type Log = Arc<dyn Fn(&str) + Send + Sync>;
/// Makes gp-rx's catch-up wait, on gp-rx (Windows: an exact 1 ms wake whose handles belong to that thread).
pub type CatchUpWaitFactory = Arc<dyn Fn() -> Box<dyn CatchUpWait> + Send + Sync>;

pub struct UdpServerOptions {
    /// Where to bind; None: every address, port 47800.
    pub bind: Option<SocketAddr>,
    /// Run first on gp-rx (Windows: MMCSS and no power throttling).
    pub on_rx_thread_start: Option<Arc<dyn Fn() + Send + Sync>>,
    pub log: Option<Log>,
    pub catch_up_wait: Option<CatchUpWaitFactory>,
}

impl Default for UdpServerOptions {
    fn default() -> Self {
        UdpServerOptions { bind: None, on_rx_thread_start: None, log: None, catch_up_wait: None }
    }
}

const CATCH_UP_WAKE_US: u32 = 1000;
const OVERSIZED_LOG_EVERY: Duration = Duration::from_secs(60);
/// The receive buffer: larger than any datagram of ours, so a longer one is seen as too long, not cut to fit.
const RX_BUFFER: usize = 2048;

struct Running {
    socket: Arc<UdpSocket>,
    stopping: Arc<AtomicBool>,
    thread: std::thread::JoinHandle<()>,
    done: std::sync::mpsc::Receiver<()>,
}

/// What `send` uses: the socket while the server runs. Kept apart from `Running` so a send never waits on a stop.
#[derive(Default)]
struct SendSide(Mutex<Option<Arc<UdpSocket>>>);

impl DatagramSender for SendSide {
    fn send(&self, datagram: &[u8], to: SocketAddr) {
        let s = self.0.lock().unwrap_or_else(|e| e.into_inner()).clone();
        if let Some(s) = s {
            // UDP send failures are transient (no route, buffer full); the protocol repairs itself.
            let _ = s.send_to(datagram, to);
        }
    }
}

pub struct UdpHostServer {
    engine: Arc<HostEngine>,
    clock: Arc<dyn Clock>,
    bind: SocketAddr,
    on_rx_thread_start: Option<Arc<dyn Fn() + Send + Sync>>,
    log: Log,
    catch_up_wait: CatchUpWaitFactory,
    send_side: Arc<SendSide>,
    running: Mutex<Option<Running>>,
    state: Mutex<ServerState>,
    local_port: std::sync::atomic::AtomicU16,
    rx_errors: Arc<AtomicI64>,
}

impl UdpHostServer {
    pub fn new(engine: Arc<HostEngine>, clock: Arc<dyn Clock>, options: UdpServerOptions) -> UdpHostServer {
        UdpHostServer {
            engine,
            clock,
            bind: options.bind.unwrap_or_else(|| SocketAddr::from((Ipv4Addr::UNSPECIFIED, wire::PORT))),
            on_rx_thread_start: options.on_rx_thread_start,
            log: options.log.unwrap_or_else(|| Arc::new(|_| {})),
            catch_up_wait: options.catch_up_wait.unwrap_or_else(|| Arc::new(|| Box::new(PollCatchUpWait))),
            send_side: Arc::new(SendSide::default()),
            running: Mutex::new(None),
            state: Mutex::new(ServerState::Stopped),
            local_port: std::sync::atomic::AtomicU16::new(0),
            rx_errors: Arc::new(AtomicI64::new(0)),
        }
    }

    pub fn state(&self) -> ServerState {
        *self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The bound port (useful when binding port 0 in tests); 0 while stopped.
    pub fn local_port(&self) -> u16 {
        self.local_port.load(Ordering::SeqCst)
    }

    /// Receive errors that were not a closing socket or a too-long datagram.
    pub fn rx_errors(&self) -> i64 {
        self.rx_errors.load(Ordering::SeqCst)
    }

    /// Binds and starts gp-rx. False (state PortInUse or Failed) when the bind fails, which is logged unless
    /// `log_failure` is false (a retry loop logs for itself).
    pub fn start(&self, log_failure: bool) -> bool {
        let mut running = self.running.lock().unwrap_or_else(|e| e.into_inner());
        if running.is_some() {
            return true;
        }
        let socket = match sys::bind_udp(self.bind) {
            Ok(s) => Arc::new(s),
            Err((kind, e)) => {
                self.set_state(if kind == BindError::InUse { ServerState::PortInUse } else { ServerState::Failed });
                if log_failure {
                    (self.log)(&format!("bind {} failed: {:?}", self.bind, e.kind()));
                }
                return false;
            }
        };
        self.local_port.store(socket.local_addr().map(|a| a.port()).unwrap_or(0), Ordering::SeqCst);
        *self.send_side.0.lock().unwrap_or_else(|e| e.into_inner()) = Some(socket.clone());
        self.engine.attach_sender(self.send_side.clone());
        let stopping = Arc::new(AtomicBool::new(false));
        let (done_tx, done) = std::sync::mpsc::channel();
        let rx = RxLoop {
            engine: self.engine.clone(),
            clock: self.clock.clone(),
            socket: socket.clone(),
            stopping: stopping.clone(),
            on_start: self.on_rx_thread_start.clone(),
            log: self.log.clone(),
            catch_up_wait: self.catch_up_wait.clone(),
            errors: self.rx_errors.clone(),
        };
        let spawned = std::thread::Builder::new().name("gp-rx".into()).spawn(move || {
            rx.run();
            let _ = done_tx.send(());
        });
        match spawned {
            Ok(thread) => {
                *running = Some(Running { socket, stopping, thread, done });
                self.set_state(ServerState::Running);
                true
            }
            Err(_) => {
                *self.send_side.0.lock().unwrap_or_else(|e| e.into_inner()) = None;
                self.local_port.store(0, Ordering::SeqCst);
                self.set_state(ServerState::Failed);
                if log_failure {
                    (self.log)("rx thread could not start");
                }
                false
            }
        }
    }

    fn set_state(&self, s: ServerState) {
        *self.state.lock().unwrap_or_else(|e| e.into_inner()) = s;
    }

    /// Stops gp-rx and lets the port go. The thread sits in a blocking receive, so it is woken with an empty
    /// datagram from this PC; it is given 2 s to end.
    pub fn stop(&self) {
        let running = self.running.lock().unwrap_or_else(|e| e.into_inner()).take();
        *self.send_side.0.lock().unwrap_or_else(|e| e.into_inner()) = None;
        self.local_port.store(0, Ordering::SeqCst);
        if let Some(r) = running {
            r.stopping.store(true, Ordering::SeqCst);
            wake(&r.socket);
            let on_rx = std::thread::current().id() == r.thread.thread().id();
            if !on_rx && r.done.recv_timeout(Duration::from_secs(2)).is_ok() {
                let _ = r.thread.join();
            }
            // Otherwise the thread is left to end by itself; it holds the socket until then.
        }
        self.set_state(ServerState::Stopped);
    }
}

impl Drop for UdpHostServer {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Wakes a thread blocked in a receive on `socket` with an empty datagram to its own port.
pub(crate) fn wake(socket: &UdpSocket) {
    let Ok(mut to) = socket.local_addr() else { return };
    if to.ip().is_unspecified() {
        to.set_ip(if to.is_ipv4() { Ipv4Addr::LOCALHOST.into() } else { std::net::Ipv6Addr::LOCALHOST.into() });
    }
    // From the socket itself: no second socket to make, and it works whatever the firewall says about loopback.
    let _ = socket.send_to(&[], to);
}

struct RxLoop {
    engine: Arc<HostEngine>,
    clock: Arc<dyn Clock>,
    socket: Arc<UdpSocket>,
    stopping: Arc<AtomicBool>,
    on_start: Option<Arc<dyn Fn() + Send + Sync>>,
    log: Log,
    catch_up_wait: CatchUpWaitFactory,
    errors: Arc<AtomicI64>,
}

impl RxLoop {
    fn run(self) {
        sys::set_thread_priority(Priority::Highest);
        if let Some(f) = &self.on_start {
            f();
        }
        let mut wait = (self.catch_up_wait)();
        let mut buf = [0u8; RX_BUFFER];
        let mut oversized = 0u64;
        let mut oversized_logged: Option<Instant> = None;
        let mut failing = 0u32; // receive errors in a row
        let stopping = || self.stopping.load(Ordering::SeqCst);
        while !stopping() {
            // While a lost stretch of mouse motion catches up, wake every millisecond to move it on (a datagram
            // still wakes it at once).
            if self.engine.mouse_catching_up() && !wait.wait(&self.socket, CATCH_UP_WAKE_US) {
                self.engine.flush(self.clock.now_us());
                continue;
            }
            let mut next = self.socket.recv_from(&mut buf);
            loop {
                match next {
                    Ok((n, from)) => {
                        if stopping() {
                            return;
                        }
                        failing = 0;
                        self.engine.on_datagram(&buf[..n], from, self.clock.now_us());
                    }
                    Err(e) => {
                        if stopping() {
                            return;
                        }
                        if sys::is_message_size(&e) {
                            // A datagram larger than any of ours: dropped, logged at most once a minute.
                            oversized += 1;
                            if oversized_logged.is_none_or(|t| t.elapsed() >= OVERSIZED_LOG_EVERY) {
                                oversized_logged = Some(Instant::now());
                                (self.log)(&format!("rx: dropped oversized datagrams ({oversized} so far)"));
                            }
                        } else if !sys::is_connection_reset(&e) && e.kind() != std::io::ErrorKind::Interrupted {
                            // One bad receive never ends the loop; a socket that keeps failing is not spun on.
                            self.errors.fetch_add(1, Ordering::SeqCst);
                            failing = failing.saturating_add(1);
                            if failing <= 5 || failing % 1000 == 0 {
                                (self.log)(&format!("rx error: {:?}", e.kind()));
                            }
                            if failing > 5 {
                                std::thread::sleep(Duration::from_millis(10));
                            }
                        }
                    }
                }
                if !sys::available(&self.socket) {
                    break;
                }
                next = self.socket.recv_from(&mut buf);
            }
            // Input already taken in goes out now, whatever the receive after it said.
            self.engine.flush(self.clock.now_us());
        }
    }
}
