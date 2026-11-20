//! The command and event line between the companion and the input process (PipeChannel.cs and EventOutbox.cs):
//! one duplex stream (a named pipe), each message a length (i32, little endian), a kind byte and JSON.
//!
//! The input process only answers calls and sends events: a reader thread takes the companion's calls and
//! answers them in order, and the outbox's own thread (gp-events) writes the events. Nothing here is on the
//! input path: the engine's notices are only queued.
//!
//! JSON is what the companion's `PipeChannel.Json` reads and writes: camelCase names, enums as numbers, bytes as
//! base64, a float that is not a number as the text "NaN", "Infinity" or "-Infinity".

use std::collections::VecDeque;
use std::io;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex, RwLock};
use std::thread::JoinHandle;
use std::time::Duration;

use serde_json::Value;

use crate::engine::{PhoneSecond, Signal};
use crate::recorder::LinkSecond;
use crate::sys::{self, Priority};
use crate::udp::Log;

/// The longest message either side accepts (a snapshot with many paired phones is a few KB).
pub const MAX_MESSAGE: usize = 4 * 1024 * 1024;

pub const KIND_CALL: u8 = 1;
pub const KIND_REPLY: u8 = 2;
pub const KIND_EVENT: u8 = 3;

const HEAD: usize = 5;
/// The reader's buffer is given back when a message was longer than this.
const KEEP_BUFFER: usize = 64 * 1024;

/// The stream under the line: a named pipe on Windows, a socket in tests. A read and a write may run at the same
/// time on two threads.
pub trait Duplex: Send + Sync {
    /// Reads some bytes; 0 when the other side closed.
    fn read(&self, buf: &mut [u8]) -> io::Result<usize>;
    fn write_all(&self, buf: &[u8]) -> io::Result<()>;
    /// Closes it: a read or write that waits ends.
    fn close(&self);
}

impl Duplex for std::net::TcpStream {
    fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        let mut s = self;
        io::Read::read(&mut s, buf)
    }

    fn write_all(&self, buf: &[u8]) -> io::Result<()> {
        let mut s = self;
        io::Write::write_all(&mut s, buf)
    }

    fn close(&self) {
        let _ = self.shutdown(std::net::Shutdown::Both);
    }
}

/// Answers a call (its method name and arguments): the result, or why it failed.
pub type CallHandler = Box<dyn Fn(&str, &[Value]) -> Result<Value, String> + Send + Sync>;

struct ChannelInner {
    stream: Arc<dyn Duplex>,
    /// The write lock, and the buffer a message is put together in.
    write: Mutex<Vec<u8>>,
    closed: AtomicBool,
    gone: Signal,
    log: Log,
}

/// One companion's line.
pub struct Channel {
    inner: Arc<ChannelInner>,
    reader: Mutex<Option<JoinHandle<()>>>,
}

impl Channel {
    /// Takes the stream and starts its reader thread, which answers calls with `on_call`.
    pub fn serve(stream: Arc<dyn Duplex>, on_call: CallHandler, log: Log, name: &str) -> io::Result<Arc<Channel>> {
        let inner = Arc::new(ChannelInner {
            stream,
            write: Mutex::new(Vec::with_capacity(4096)),
            closed: AtomicBool::new(false),
            gone: Signal::new(false),
            log,
        });
        let for_thread = inner.clone();
        let reader = std::thread::Builder::new().name(name.to_string()).spawn(move || {
            for_thread.read_loop(&on_call);
            for_thread.close();
        });
        match reader {
            Ok(t) => Ok(Arc::new(Channel { inner, reader: Mutex::new(Some(t)) })),
            Err(e) => {
                inner.close();
                Err(e)
            }
        }
    }

    pub fn is_closed(&self) -> bool {
        self.inner.closed.load(Ordering::Acquire)
    }

    /// Waits up to `ms` for the other side to go away (the pipe broke or closed); true when it has.
    pub fn wait_closed(&self, ms: u64) -> bool {
        self.inner.gone.wait(ms)
    }

    /// Sends an event whose JSON `payload` writes; false when the line is gone (dropped, never an error).
    pub fn post_with(&self, name: &str, payload: impl FnOnce(&mut Vec<u8>)) -> bool {
        self.inner.send(KIND_EVENT, |b| {
            b.extend_from_slice(b"{\"name\":");
            push_str(b, name);
            b.extend_from_slice(b",\"payload\":");
            payload(b);
            b.push(b'}');
        })
    }

    /// Sends an event; false when the line is gone.
    pub fn post(&self, name: &str, payload: &Value) -> bool {
        self.post_with(name, |b| push_value(b, payload))
    }

    /// Closes the line; the reader thread ends.
    pub fn close(&self) {
        self.inner.close();
    }

    /// Closes the line and waits a moment for its reader thread (not from that thread).
    pub fn close_and_join(&self) {
        self.inner.close();
        let t = self.reader.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(t) = t {
            if t.thread().id() != std::thread::current().id() {
                let _ = t.join();
            }
        }
    }
}

impl Drop for Channel {
    fn drop(&mut self) {
        self.inner.close();
    }
}

impl ChannelInner {
    fn close(&self) {
        if self.closed.swap(true, Ordering::AcqRel) {
            return;
        }
        self.stream.close();
        self.gone.set();
    }

    /// Writes one message; false (and the line closed) when it could not.
    fn send(&self, kind: u8, body: impl FnOnce(&mut Vec<u8>)) -> bool {
        if self.closed.load(Ordering::Acquire) {
            return false;
        }
        let mut buf = self.write.lock().unwrap_or_else(|e| e.into_inner());
        buf.clear();
        buf.extend_from_slice(&[0, 0, 0, 0, kind]);
        body(&mut buf);
        let len = buf.len() - HEAD;
        let ok = if len > MAX_MESSAGE {
            (self.log)("pipe: message too long, not sent");
            true
        } else {
            buf[..4].copy_from_slice(&(len as i32).to_le_bytes());
            !self.closed.load(Ordering::Acquire) && self.stream.write_all(&buf).is_ok()
        };
        if buf.capacity() > KEEP_BUFFER {
            *buf = Vec::with_capacity(4096);
        }
        drop(buf);
        if !ok {
            self.close();
        }
        ok
    }

    fn read_exact(&self, buf: &mut [u8]) -> io::Result<bool> {
        let mut got = 0;
        while got < buf.len() {
            match self.stream.read(&mut buf[got..]) {
                Ok(0) => return Ok(false),
                Ok(n) => got += n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
                Err(e) => return Err(e),
            }
        }
        Ok(true)
    }

    fn read_loop(&self, on_call: &CallHandler) {
        let mut head = [0u8; HEAD];
        let mut body: Vec<u8> = Vec::new();
        while !self.closed.load(Ordering::Acquire) {
            match self.read_exact(&mut head) {
                Ok(true) => {}
                Ok(false) => return,
                Err(e) => return self.read_failed(&e.to_string()),
            }
            let length = i32::from_le_bytes([head[0], head[1], head[2], head[3]]);
            if length < 0 || length as usize > MAX_MESSAGE {
                return self.read_failed("bad message length");
            }
            body.clear();
            body.resize(length as usize, 0);
            match self.read_exact(&mut body) {
                Ok(true) => {}
                Ok(false) => return,
                Err(e) => return self.read_failed(&e.to_string()),
            }
            if let Err(why) = self.handle(head[4], &body, on_call) {
                return self.read_failed(why);
            }
            if body.capacity() > KEEP_BUFFER {
                body = Vec::new();
            }
        }
    }

    fn read_failed(&self, why: &str) {
        if !self.closed.load(Ordering::Acquire) {
            (self.log)(&format!("pipe: {why}"));
        }
    }

    /// One message. Err: it was not what this line carries, and the line is closed.
    fn handle(&self, kind: u8, body: &[u8], on_call: &CallHandler) -> Result<(), &'static str> {
        let root: Value = serde_json::from_slice(body).map_err(|_| "a message is not JSON")?;
        if kind != KIND_CALL {
            // The input process calls nothing and takes no events: replies and events are read and dropped.
            return Ok(());
        }
        let id = root.get("id").and_then(Value::as_i64).and_then(|n| i32::try_from(n).ok()).ok_or("a call has no id")?;
        let method = match root.get("method") {
            Some(Value::String(s)) => s.as_str(),
            Some(Value::Null) => "",
            _ => return Err("a call has no method"),
        };
        let none: [Value; 0] = [];
        let args: &[Value] = match root.get("args") {
            Some(Value::Array(a)) => a,
            Some(_) => &none,
            None => return Err("a call has no arguments"),
        };
        let reply = on_call(method, args);
        if let Err(why) = &reply {
            (self.log)(&format!("pipe: {method} failed: {why}"));
        }
        self.send(KIND_REPLY, |b| {
            b.extend_from_slice(b"{\"id\":");
            b.extend_from_slice(id.to_string().as_bytes());
            match &reply {
                Ok(result) => {
                    b.extend_from_slice(b",\"ok\":true,\"error\":null,\"result\":");
                    push_value(b, result);
                }
                Err(why) => {
                    b.extend_from_slice(b",\"ok\":false,\"error\":");
                    push_str(b, why);
                    b.extend_from_slice(b",\"result\":null");
                }
            }
            b.push(b'}');
        });
        Ok(())
    }
}

// =====================================================================================
// JSON text
// =====================================================================================

/// Appends `s` as a JSON string.
pub fn push_str(b: &mut Vec<u8>, s: &str) {
    // Writing a &str into a Vec cannot fail.
    let _ = serde_json::to_writer(&mut *b, s);
}

/// Appends `v` as JSON.
pub fn push_value(b: &mut Vec<u8>, v: &Value) {
    let _ = serde_json::to_writer(&mut *b, v);
}

/// Appends a float as the companion reads it: a number, or "NaN", "Infinity", "-Infinity".
pub fn push_f32(b: &mut Vec<u8>, v: f32) {
    if v.is_finite() {
        let _ = serde_json::to_writer(&mut *b, &v);
    } else {
        push_str(b, non_finite_name(v as f64));
    }
}

fn non_finite_name(v: f64) -> &'static str {
    if v.is_nan() {
        "NaN"
    } else if v > 0.0 {
        "Infinity"
    } else {
        "-Infinity"
    }
}

/// A float as a JSON value the companion reads.
pub fn float_value(v: f64) -> Value {
    match serde_json::Number::from_f64(v) {
        Some(n) => Value::Number(n),
        None => Value::String(non_finite_name(v).to_string()),
    }
}

// =====================================================================================
// the outbox
// =====================================================================================

/// What an event carries. The text is made on the outbox's thread.
#[derive(Clone, Debug, PartialEq)]
pub enum Payload {
    None,
    Json(Value),
    /// A Diagnostics line (event "debug").
    Debug { us: i64, kind: u8, text: String },
    LinkSecond(LinkSecond),
    PhoneSecond(PhoneSecond),
    /// A pairing waits for the user (event "pair-show").
    PairShow { attempt_id: u32, phone_name: String, sas: String },
    /// That pairing ended (event "pair-close").
    PairClose(u32),
}

/// Appends an event's payload as JSON.
pub fn push_payload(b: &mut Vec<u8>, p: &Payload) {
    fn field_i64(b: &mut Vec<u8>, name: &str, v: i64) {
        b.push(b'"');
        b.extend_from_slice(name.as_bytes());
        b.extend_from_slice(b"\":");
        b.extend_from_slice(v.to_string().as_bytes());
        b.push(b',');
    }
    fn field_f32(b: &mut Vec<u8>, name: &str, v: f32) {
        b.push(b'"');
        b.extend_from_slice(name.as_bytes());
        b.extend_from_slice(b"\":");
        push_f32(b, v);
        b.push(b',');
    }
    fn end(b: &mut Vec<u8>) {
        // The last field's comma becomes the closing brace.
        if let Some(last) = b.last_mut() {
            *last = b'}';
        }
    }
    match p {
        Payload::None => b.extend_from_slice(b"null"),
        Payload::Json(v) => push_value(b, v),
        Payload::Debug { us, kind, text } => {
            b.push(b'{');
            field_i64(b, "us", *us);
            field_i64(b, "kind", *kind as i64);
            b.extend_from_slice(b"\"text\":");
            push_str(b, text);
            b.push(b'}');
        }
        Payload::LinkSecond(s) => {
            b.push(b'{');
            field_i64(b, "atUs", s.at_us);
            field_i64(b, "rx", s.rx as i64);
            field_i64(b, "lost", s.lost as i64);
            field_i64(b, "late", s.late as i64);
            field_f32(b, "maxLateMs", s.max_late_ms);
            field_i64(b, "gaps", s.gaps as i64);
            field_i64(b, "releases", s.releases as i64);
            field_f32(b, "latP50Ms", s.lat_p50_ms);
            field_f32(b, "latP99Ms", s.lat_p99_ms);
            field_f32(b, "jitterMs", s.jitter_ms);
            field_f32(b, "floorMs", s.floor_ms);
            field_f32(b, "rttTypMs", s.rtt_typ_ms);
            field_f32(b, "overP50Ms", s.over_p50_ms);
            field_f32(b, "overP99Ms", s.over_p99_ms);
            field_i64(b, "late3", s.late3 as i64);
            field_f32(b, "handOffMaxMs", s.hand_off_max_ms);
            field_i64(b, "slowHandOffs", s.slow_hand_offs as i64);
            field_f32(b, "lockWaitMs", s.lock_wait_ms);
            field_f32(b, "stallMaxMs", s.stall_max_ms);
            field_i64(b, "stalls", s.stalls as i64);
            field_i64(b, "negLat", s.neg_lat as i64);
            field_i64(b, "air", s.air as i64);
            field_i64(b, "pc", s.pc as i64);
            field_i64(b, "phone", s.phone as i64);
            field_i64(b, "moving", s.moving as i64);
            let trouble = s.lost > 0 || s.late > 0 || s.gaps > 0 || s.releases > 0;
            b.extend_from_slice(if trouble { b"\"trouble\":true," } else { b"\"trouble\":false," });
            end(b);
        }
        Payload::PhoneSecond(s) => {
            b.push(b'{');
            field_i64(b, "atUs", s.at_us);
            field_i64(b, "states", s.states as i64);
            field_i64(b, "packets", s.packets as i64);
            field_i64(b, "failed", s.failed as i64);
            field_i64(b, "slowSends", s.slow_sends as i64);
            field_f32(b, "sendMaxMs", s.send_max_ms);
            field_f32(b, "wakeLateMs", s.wake_late_ms);
            field_f32(b, "gapMaxMs", s.gap_max_ms);
            field_i64(b, "phoneTx", s.phone_tx);
            field_f32(b, "rttMinMs", s.rtt_min_ms);
            field_f32(b, "rttTypMs", s.rtt_typ_ms);
            field_f32(b, "driftPpm", s.drift_ppm);
            field_i64(b, "pings", s.pings as i64);
            field_i64(b, "battery", s.battery as i64);
            field_i64(b, "setupFlags", s.setup_flags as i64);
            field_i64(b, "tempDeciC", s.temp_deci_c as i64);
            field_i64(b, "thermal", s.thermal as i64);
            field_i64(b, "usbVendor", s.usb_vendor as i64);
            field_i64(b, "usbProduct", s.usb_product as i64);
            field_i64(b, "wifiStandard", s.wifi_standard as i64);
            end(b);
        }
        Payload::PairShow { attempt_id, phone_name, sas } => {
            b.push(b'{');
            field_i64(b, "attemptId", *attempt_id as i64);
            b.extend_from_slice(b"\"phoneName\":");
            push_str(b, phone_name);
            b.extend_from_slice(b",\"sas\":");
            push_str(b, sas);
            b.push(b'}');
        }
        Payload::PairClose(id) => b.extend_from_slice(id.to_string().as_bytes()),
    }
}

struct OutInner {
    queue: Mutex<VecDeque<(&'static str, Payload)>>,
    wake: Condvar,
    channel: RwLock<Option<Arc<Channel>>>,
    attached: AtomicBool,
    stop: AtomicBool,
    dropped: AtomicI64,
}

/// The input process's events for the companion, sent off the input path: `post` only queues (a line made on
/// gp-rx, say), and its own thread (gp-events) writes them to the pipe. While no companion is attached, or when
/// the queue is long (the companion stopped reading), new events are dropped and counted.
pub struct EventOutbox {
    inner: Arc<OutInner>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

impl EventOutbox {
    pub const MAX_QUEUED: usize = 4096;

    pub fn new() -> EventOutbox {
        let inner = Arc::new(OutInner {
            queue: Mutex::new(VecDeque::with_capacity(Self::MAX_QUEUED)),
            wake: Condvar::new(),
            channel: RwLock::new(None),
            attached: AtomicBool::new(false),
            stop: AtomicBool::new(false),
            dropped: AtomicI64::new(0),
        });
        let for_thread = inner.clone();
        let thread = std::thread::Builder::new()
            .name("gp-events".to_string())
            .spawn(move || {
                sys::set_thread_priority(Priority::BelowNormal);
                for_thread.run();
            })
            .ok();
        EventOutbox { inner, thread: Mutex::new(thread) }
    }

    /// Events dropped (no companion, or it fell behind).
    pub fn dropped(&self) -> i64 {
        self.inner.dropped.load(Ordering::Relaxed)
    }

    /// The companion's line now (None: none attached, events are dropped).
    pub fn channel(&self) -> Option<Arc<Channel>> {
        self.inner.channel.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn attached(&self) -> bool {
        self.inner.attached.load(Ordering::Acquire)
    }

    pub fn set_channel(&self, channel: Option<Arc<Channel>>) {
        let mut g = self.inner.channel.write().unwrap_or_else(|e| e.into_inner());
        self.inner.attached.store(channel.is_some(), Ordering::Release);
        *g = channel;
        drop(g);
        self.inner.wake.notify_all();
    }

    /// Takes the line away when it is still `channel` (a newer companion's line stays).
    pub fn clear_channel(&self, channel: &Arc<Channel>) {
        let mut g = self.inner.channel.write().unwrap_or_else(|e| e.into_inner());
        if g.as_ref().is_some_and(|c| Arc::ptr_eq(c, channel)) {
            self.inner.attached.store(false, Ordering::Release);
            *g = None;
        }
    }

    /// Queues an event. Any thread, the input path's too: it takes the queue's lock for a moment and never waits
    /// for the pipe.
    pub fn post(&self, name: &'static str, payload: Payload) {
        if !self.attached() {
            self.inner.dropped.fetch_add(1, Ordering::Relaxed);
            return;
        }
        let mut q = self.inner.queue.lock().unwrap_or_else(|e| e.into_inner());
        if q.len() >= Self::MAX_QUEUED {
            drop(q);
            self.inner.dropped.fetch_add(1, Ordering::Relaxed);
            return;
        }
        q.push_back((name, payload));
        drop(q);
        self.inner.wake.notify_one();
    }

    /// Sends what waits now (on the caller's thread), for the last events before exit.
    pub fn flush(&self) {
        self.inner.drain(false);
    }

    pub fn stop(&self) {
        self.inner.stop.store(true, Ordering::Release);
        self.inner.wake.notify_all();
        let t = self.thread.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(t) = t {
            let _ = t.join();
        }
    }
}

impl Default for EventOutbox {
    fn default() -> Self {
        EventOutbox::new()
    }
}

impl Drop for EventOutbox {
    fn drop(&mut self) {
        self.stop();
    }
}

impl OutInner {
    fn run(&self) {
        while !self.stop.load(Ordering::Acquire) {
            {
                let q = self.queue.lock().unwrap_or_else(|e| e.into_inner());
                let _ = self
                    .wake
                    .wait_timeout_while(q, Duration::from_millis(1000), |q| q.is_empty() && !self.stop.load(Ordering::Acquire))
                    .unwrap_or_else(|e| e.into_inner());
            }
            self.drain(true);
        }
    }

    fn drain(&self, count_dropped: bool) {
        loop {
            // The queue's lock is held only to take one event: the text and the pipe write happen outside it.
            let next = self.queue.lock().unwrap_or_else(|e| e.into_inner()).pop_front();
            let Some((name, payload)) = next else { return };
            let channel = self.channel.read().unwrap_or_else(|e| e.into_inner()).clone();
            let sent = channel.is_some_and(|c| c.post_with(name, |b| push_payload(b, &payload)));
            if !sent && count_dropped {
                self.dropped.fetch_add(1, Ordering::Relaxed);
            }
        }
    }
}
