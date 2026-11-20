//! What the process shares with the companion and the watcher: its single-instance lock, the shared memory, the
//! wake events, the named pipe, the log's lock; and the system facts it starts with (names, folders, time).

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use windows_sys::Win32::Foundation::{
    LocalFree, ERROR_ACCESS_DENIED, ERROR_ALREADY_EXISTS, ERROR_BROKEN_PIPE, ERROR_IO_PENDING, ERROR_PIPE_CONNECTED, HANDLE,
    INVALID_HANDLE_VALUE, SYSTEMTIME, WAIT_ABANDONED, WAIT_OBJECT_0,
};
use windows_sys::Win32::Security::Authorization::{ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW};
use windows_sys::Win32::Security::{GetTokenInformation, TokenOwner, PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES, TOKEN_OWNER, TOKEN_QUERY};
use windows_sys::Win32::Storage::FileSystem as fs;
use windows_sys::Win32::System::IO::{CancelIoEx, GetOverlappedResult, OVERLAPPED};
use windows_sys::Win32::System::Memory as mem;
use windows_sys::Win32::System::Pipes as pipes;
use windows_sys::Win32::System::RemoteDesktop::ProcessIdToSessionId;
use windows_sys::Win32::System::SystemInformation::GetLocalTime;
use windows_sys::Win32::System::Threading as th;
use windows_sys::Win32::System::WindowsProgramming::{GetComputerNameW, GetUserNameW};

use super::{last_error, wide, Handle};
use crate::file_log::AppendLock;
use crate::host::PipeListener;
use crate::pipe::Duplex;
use crate::shared::{RegionError, SharedRegion, TOTAL_BYTES};
use crate::udp::Log;

const SYNCHRONIZE: u32 = 0x0010_0000;
const EVENT_MODIFY_STATE: u32 = 0x0002;
const INFINITE: u32 = u32::MAX;

fn io_error(code: u32) -> std::io::Error {
    std::io::Error::from_raw_os_error(code as i32)
}

// =====================================================================================
// names, folders, time
// =====================================================================================

fn from_wide(buf: &[u16]) -> String {
    let end = buf.iter().position(|c| *c == 0).unwrap_or(buf.len());
    String::from_utf16_lossy(&buf[..end])
}

/// The signed-in user's name, as .NET's Environment.UserName gives it (the pipe's name holds it).
pub fn user_name() -> String {
    let mut buf = [0u16; 257];
    let mut len = buf.len() as u32;
    // SAFETY: the buffer and its length in characters.
    if unsafe { GetUserNameW(buf.as_mut_ptr(), &mut len) } == 0 {
        return std::env::var("USERNAME").unwrap_or_default();
    }
    from_wide(&buf)
}

/// This PC's name, as .NET's Environment.MachineName gives it.
pub fn machine_name() -> String {
    let mut buf = [0u16; 64];
    let mut len = buf.len() as u32;
    // SAFETY: the buffer and its length in characters.
    if unsafe { GetComputerNameW(buf.as_mut_ptr(), &mut len) } == 0 {
        return std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".to_string());
    }
    from_wide(&buf)
}

/// The Windows session this process runs in.
pub fn session_id() -> u32 {
    let mut id = 0u32;
    // SAFETY: our own process id and a valid out pointer.
    unsafe {
        ProcessIdToSessionId(th::GetCurrentProcessId(), &mut id);
    }
    id
}

fn local_time() -> SYSTEMTIME {
    // SAFETY: SYSTEMTIME is plain numbers; GetLocalTime fills it.
    unsafe {
        let mut t: SYSTEMTIME = std::mem::zeroed();
        GetLocalTime(&mut t);
        t
    }
}

/// The local time as "yyyy-MM-dd HH:mm:ss.fff" (the log's lines).
pub fn local_stamp() -> String {
    let t = local_time();
    format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}", t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond, t.wMilliseconds)
}

/// The local time as "HH:mm:ss".
pub fn local_clock() -> String {
    let t = local_time();
    format!("{:02}:{:02}:{:02}", t.wHour, t.wMinute, t.wSecond)
}

fn profile_folder(var: &str, under_profile: &str) -> PathBuf {
    match std::env::var_os(var).filter(|v| !v.is_empty()) {
        Some(v) => PathBuf::from(v),
        None => PathBuf::from(std::env::var_os("USERPROFILE").unwrap_or_default()).join("AppData").join(under_profile),
    }
}

/// %APPDATA%\GyroPhone: settings.json, the PC's key and authorized_phones.
pub fn roaming_folder() -> PathBuf {
    profile_folder("APPDATA", "Roaming").join("GyroPhone")
}

/// %LOCALAPPDATA%\GyroPhone: held.json and logs.
pub fn local_folder() -> PathBuf {
    profile_folder("LOCALAPPDATA", "Local").join("GyroPhone")
}

// =====================================================================================
// mutexes and events
// =====================================================================================

/// Owns the named mutex while alive: the single-instance lock.
pub struct InstanceLock(#[allow(dead_code)] Handle);

/// Takes the session's single-instance lock; None when another input process holds it.
pub fn take_instance_lock(name: &str) -> Option<InstanceLock> {
    let name = wide(name);
    // SAFETY: no attributes, owned at once, a NUL-ended name.
    let h = unsafe { th::CreateMutexW(std::ptr::null(), 1, name.as_ptr()) };
    let existed = last_error() == ERROR_ALREADY_EXISTS;
    let h = Handle::new(h)?;
    if existed {
        return None;
    }
    Some(InstanceLock(h))
}

/// True while a mutex of that name exists in this session (another of our processes runs).
pub fn mutex_exists(name: &str) -> bool {
    let name = wide(name);
    // SAFETY: a NUL-ended name.
    match Handle::new(unsafe { th::OpenMutexW(SYNCHRONIZE, 0, name.as_ptr()) }) {
        Some(_) => true,
        None => last_error() == ERROR_ACCESS_DENIED, // an elevated one owns it
    }
}

/// Sets a named event another of our processes made; false when there is none.
pub fn set_named_event(name: &str) -> bool {
    let name = wide(name);
    // SAFETY: a NUL-ended name; the handle is closed by `Handle`.
    match Handle::new(unsafe { th::OpenEventW(EVENT_MODIFY_STATE, 0, name.as_ptr()) }) {
        Some(h) => unsafe { th::SetEvent(h.raw()) != 0 },
        None => false,
    }
}

/// A named auto-reset event (made, or opened when the companion made it first).
pub struct NamedEvent(Handle);

impl NamedEvent {
    pub fn create(name: &str) -> std::io::Result<NamedEvent> {
        let name = wide(name);
        // SAFETY: no attributes, auto reset, not set, a NUL-ended name.
        Handle::new(unsafe { th::CreateEventW(std::ptr::null(), 0, 0, name.as_ptr()) }).map(NamedEvent).ok_or_else(|| io_error(last_error()))
    }

    pub fn set(&self) {
        // SAFETY: a live event handle.
        unsafe {
            th::SetEvent(self.0.raw());
        }
    }
}

/// The log file's lock: a mutex named after the file, shared with the companion and the watcher. Only the log
/// thread waits on it.
pub struct NamedAppendLock(Handle);

impl NamedAppendLock {
    pub fn new(path: &Path) -> Option<NamedAppendLock> {
        let name = wide(&crate::file_log::lock_name(path));
        // SAFETY: no attributes, not owned, a NUL-ended name.
        Handle::new(unsafe { th::CreateMutexW(std::ptr::null(), 0, name.as_ptr()) }).map(NamedAppendLock)
    }
}

impl AppendLock for NamedAppendLock {
    fn with(&self, f: &mut dyn FnMut()) {
        // SAFETY: a live mutex handle; released on the thread that took it, right below.
        let r = unsafe { th::WaitForSingleObject(self.0.raw(), 500) };
        // Abandoned: a writer died holding it; the file is still fine to append to.
        let owned = r == WAIT_OBJECT_0 || r == WAIT_ABANDONED;
        f();
        if owned {
            // SAFETY: owned by this thread.
            unsafe {
                th::ReleaseMutex(self.0.raw());
            }
        }
    }
}

// =====================================================================================
// the shared memory
// =====================================================================================

struct View {
    base: mem::MEMORY_MAPPED_VIEW_ADDRESS,
    #[allow(dead_code)]
    mapping: Handle,
}

// SAFETY: the view is plain shared memory; `SharedRegion` only reads and writes it through atomics.
unsafe impl Send for View {}
unsafe impl Sync for View {}

impl Drop for View {
    fn drop(&mut self) {
        // SAFETY: the view mapped in `open_region`, unmapped once; the mapping handle closes after it.
        unsafe {
            mem::UnmapViewOfFile(self.base);
        }
    }
}

#[derive(Debug)]
pub enum RegionOpenError {
    Io(std::io::Error),
    /// An older or newer build still has it open.
    OtherLayout,
}

/// The session's region, made by whichever process comes first and kept while either has it open: a companion
/// keeps it across an input process restart, an input process across a companion restart.
pub fn open_region(name: &str) -> Result<SharedRegion, RegionOpenError> {
    let wide_name = wide(name);
    let size = TOTAL_BYTES as u64;
    // SAFETY: backed by the page file, no attributes, a NUL-ended name.
    let mapping = Handle::new(unsafe {
        mem::CreateFileMappingW(INVALID_HANDLE_VALUE, std::ptr::null(), mem::PAGE_READWRITE, (size >> 32) as u32, size as u32, wide_name.as_ptr())
    })
    .ok_or_else(|| RegionOpenError::Io(io_error(last_error())))?;
    // SAFETY: a live mapping of at least TOTAL_BYTES (an existing smaller one fails the call).
    let base = unsafe { mem::MapViewOfFile(mapping.raw(), mem::FILE_MAP_READ | mem::FILE_MAP_WRITE, 0, 0, TOTAL_BYTES) };
    if base.Value.is_null() {
        return Err(RegionOpenError::Io(io_error(last_error())));
    }
    let view = View { base, mapping };
    let p = view.base.Value as *mut u8;
    // SAFETY: `p` is TOTAL_BYTES of read-write memory, page aligned, zeros or a region; it stays mapped until
    // `view` is dropped by the release.
    unsafe { SharedRegion::from_view(p, Box::new(move || drop(view))) }.map_err(|e| match e {
        RegionError::OtherLayout => RegionOpenError::OtherLayout,
    })
}

// =====================================================================================
// the named pipe
// =====================================================================================

/// A security descriptor that lets only this process's user (its token's owner) in, as .NET's
/// PipeOptions.CurrentUserOnly makes it; the companion checks the pipe's owner the same way.
struct OwnerOnly(PSECURITY_DESCRIPTOR);

// SAFETY: an immutable block of memory from LocalAlloc.
unsafe impl Send for OwnerOnly {}
unsafe impl Sync for OwnerOnly {}

impl Drop for OwnerOnly {
    fn drop(&mut self) {
        // SAFETY: allocated by ConvertStringSecurityDescriptorToSecurityDescriptorW, freed once.
        unsafe {
            LocalFree(self.0);
        }
    }
}

fn owner_sid_text() -> std::io::Result<String> {
    let mut token: HANDLE = std::ptr::null_mut();
    // SAFETY: the pseudo-handle of this process and a valid out pointer.
    if unsafe { th::OpenProcessToken(th::GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
        return Err(io_error(last_error()));
    }
    let token = Handle::new(token).ok_or_else(|| io_error(last_error()))?;
    // TOKEN_OWNER and the SID it points to; a SID is at most 68 bytes.
    let mut buf = [0u64; 32];
    let mut returned = 0u32;
    // SAFETY: a live token; the buffer is 256 bytes, 8-aligned, of the size given.
    if unsafe { GetTokenInformation(token.raw(), TokenOwner, buf.as_mut_ptr().cast(), std::mem::size_of_val(&buf) as u32, &mut returned) } == 0 {
        return Err(io_error(last_error()));
    }
    // SAFETY: GetTokenInformation wrote a TOKEN_OWNER at the start of the buffer.
    let owner = unsafe { &*(buf.as_ptr() as *const TOKEN_OWNER) };
    let mut text: *mut u16 = std::ptr::null_mut();
    // SAFETY: a valid SID inside `buf`, and a valid out pointer.
    if unsafe { ConvertSidToStringSidW(owner.Owner, &mut text) } == 0 {
        return Err(io_error(last_error()));
    }
    // SAFETY: a NUL-ended string from LocalAlloc; read to its NUL, then freed once.
    unsafe {
        let mut len = 0;
        while *text.add(len) != 0 {
            len += 1;
        }
        let s = String::from_utf16_lossy(std::slice::from_raw_parts(text, len));
        LocalFree(text.cast());
        Ok(s)
    }
}

impl OwnerOnly {
    fn new() -> std::io::Result<OwnerOnly> {
        let sid = owner_sid_text()?;
        // Owner: us. Access: all to us, nothing inherited, nobody else.
        let sddl = wide(&format!("O:{sid}D:P(A;;GA;;;{sid})"));
        let mut sd: PSECURITY_DESCRIPTOR = std::ptr::null_mut();
        // SAFETY: a NUL-ended SDDL string, revision 1, a valid out pointer.
        if unsafe { ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.as_ptr(), 1, &mut sd, std::ptr::null_mut()) } == 0 {
            return Err(io_error(last_error()));
        }
        Ok(OwnerOnly(sd))
    }
}

fn manual_event() -> std::io::Result<Handle> {
    // SAFETY: no attributes, manual reset, not set, no name.
    Handle::new(unsafe { th::CreateEventW(std::ptr::null(), 1, 0, std::ptr::null()) }).ok_or_else(|| io_error(last_error()))
}

/// Waits for an overlapped operation on `pipe` that `started` (the call's own result) began; the bytes it moved.
/// A set `cancel` event ends the wait by cancelling the operation.
///
/// # Safety
/// `ov` must be the OVERLAPPED the operation was started with and stay where it is until this returns.
unsafe fn finish(pipe: HANDLE, ov: *mut OVERLAPPED, started: bool, cancel: HANDLE) -> std::io::Result<u32> {
    // SAFETY: the caller's promise: `ov` is the operation's OVERLAPPED and stays in place; the handles are live.
    unsafe {
        if !started {
            let e = last_error();
            if e != ERROR_IO_PENDING {
                return Err(io_error(e));
            }
            let handles = [(*ov).hEvent, cancel];
            if th::WaitForMultipleObjects(2, handles.as_ptr(), 0, INFINITE) != WAIT_OBJECT_0 {
                CancelIoEx(pipe, ov);
            }
        }
        let mut n = 0u32;
        // Waits until the (maybe cancelled) operation is really over, so the OVERLAPPED can go.
        if GetOverlappedResult(pipe, ov, &mut n, 1) == 0 {
            return Err(io_error(last_error()));
        }
        Ok(n)
    }
}

/// One connected companion's pipe. Overlapped, so the reader thread's read and a write can run at once.
struct PipeStream {
    pipe: Handle,
    read_event: Handle,
    write_event: Handle,
    /// Set by `close`: ends the waits.
    closing: Handle,
    closed: AtomicBool,
}

impl PipeStream {
    fn overlapped(event: &Handle) -> OVERLAPPED {
        // SAFETY: OVERLAPPED is plain data; zero with an event is how one is made.
        let mut ov: OVERLAPPED = unsafe { std::mem::zeroed() };
        ov.hEvent = event.raw();
        ov
    }
}

impl Duplex for PipeStream {
    fn read(&self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.closed.load(Ordering::Acquire) {
            return Ok(0);
        }
        let mut ov = Self::overlapped(&self.read_event);
        let len = buf.len().min(u32::MAX as usize) as u32;
        // SAFETY: a live pipe; `buf` and `ov` stay in place until `finish` returns, which is after the operation
        // ended (done, failed or cancelled). Only one read runs at a time (the channel's reader thread).
        let r = unsafe {
            let started = fs::ReadFile(self.pipe.raw(), buf.as_mut_ptr(), len, std::ptr::null_mut(), &mut ov) != 0;
            finish(self.pipe.raw(), &mut ov, started, self.closing.raw())
        };
        match r {
            Ok(n) => Ok(n as usize),
            // The companion closed its end: the end of the stream, not an error.
            Err(e) if e.raw_os_error() == Some(ERROR_BROKEN_PIPE as i32) => Ok(0),
            Err(_) if self.closed.load(Ordering::Acquire) => Ok(0),
            Err(e) => Err(e),
        }
    }

    fn write_all(&self, buf: &[u8]) -> std::io::Result<()> {
        let mut done = 0usize;
        while done < buf.len() {
            if self.closed.load(Ordering::Acquire) {
                return Err(io_error(ERROR_BROKEN_PIPE));
            }
            let mut ov = Self::overlapped(&self.write_event);
            let rest = &buf[done..];
            let len = rest.len().min(u32::MAX as usize) as u32;
            // SAFETY: as in `read`; the channel's write lock lets one write run at a time.
            let n = unsafe {
                let started = fs::WriteFile(self.pipe.raw(), rest.as_ptr(), len, std::ptr::null_mut(), &mut ov) != 0;
                finish(self.pipe.raw(), &mut ov, started, self.closing.raw())?
            };
            if n == 0 {
                return Err(io_error(ERROR_BROKEN_PIPE));
            }
            done += n as usize;
        }
        Ok(())
    }

    fn close(&self) {
        if self.closed.swap(true, Ordering::AcqRel) {
            return;
        }
        // SAFETY: live handles. The waits end (each cancels its own operation); the companion sees the end of
        // the stream. The handles themselves close when the last holder drops the stream.
        unsafe {
            th::SetEvent(self.closing.raw());
            CancelIoEx(self.pipe.raw(), std::ptr::null());
            pipes::DisconnectNamedPipe(self.pipe.raw());
        }
    }
}

/// Where companions connect: `\\.\pipe\GyroPhoneInput.<user>.<session>`, one companion at a time, this user only.
pub struct NamedPipeListener {
    name: Vec<u16>,
    security: OwnerOnly,
    connect_event: Handle,
    cancel: Handle,
    cancelled: AtomicBool,
}

impl NamedPipeListener {
    pub fn new(pipe_name: &str) -> std::io::Result<NamedPipeListener> {
        Ok(NamedPipeListener {
            name: wide(&format!(r"\\.\pipe\{pipe_name}")),
            security: OwnerOnly::new()?,
            connect_event: manual_event()?,
            cancel: manual_event()?,
            cancelled: AtomicBool::new(false),
        })
    }
}

impl PipeListener for NamedPipeListener {
    fn accept(&self) -> std::io::Result<Arc<dyn Duplex>> {
        if self.cancelled.load(Ordering::Acquire) {
            return Err(std::io::Error::other("stopping"));
        }
        let attributes = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: self.security.0,
            bInheritHandle: 0,
        };
        // SAFETY: a NUL-ended name; the attributes and their descriptor outlive the call.
        let pipe = Handle::new(unsafe {
            pipes::CreateNamedPipeW(
                self.name.as_ptr(),
                fs::PIPE_ACCESS_DUPLEX | fs::FILE_FLAG_OVERLAPPED,
                pipes::PIPE_TYPE_BYTE | pipes::PIPE_READMODE_BYTE | pipes::PIPE_REJECT_REMOTE_CLIENTS,
                1,
                65536,
                65536,
                0,
                &attributes,
            )
        })
        .ok_or_else(|| io_error(last_error()))?;
        let mut ov = PipeStream::overlapped(&self.connect_event);
        // SAFETY: a live pipe; `ov` stays in place until the connect ended (done, failed or cancelled).
        let connected = unsafe {
            th::ResetEvent(self.connect_event.raw());
            let started = pipes::ConnectNamedPipe(pipe.raw(), &mut ov) != 0;
            if !started && last_error() == ERROR_PIPE_CONNECTED {
                Ok(0) // the companion connected between the two calls
            } else {
                finish(pipe.raw(), &mut ov, started, self.cancel.raw())
            }
        };
        connected?;
        if self.cancelled.load(Ordering::Acquire) {
            return Err(std::io::Error::other("stopping"));
        }
        Ok(Arc::new(PipeStream { pipe, read_event: manual_event()?, write_event: manual_event()?, closing: manual_event()?, closed: AtomicBool::new(false) }))
    }

    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        // SAFETY: a live event handle.
        unsafe {
            th::SetEvent(self.cancel.raw());
        }
    }
}

// =====================================================================================
// folder changes, and the companion
// =====================================================================================

struct FolderWatch {
    stop: Arc<Handle>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Drop for FolderWatch {
    fn drop(&mut self) {
        // SAFETY: a live event handle.
        unsafe {
            th::SetEvent(self.stop.raw());
        }
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

/// Calls `changed` whenever a file in `dir` is added, removed, renamed or written, until what it returns is
/// dropped. None when the folder can't be watched.
pub fn watch_folder(dir: &Path, changed: Arc<dyn Fn() + Send + Sync>, log: &Log) -> Option<Box<dyn Send + Sync>> {
    let path = wide(&dir.to_string_lossy());
    let filter = fs::FILE_NOTIFY_CHANGE_FILE_NAME | fs::FILE_NOTIFY_CHANGE_LAST_WRITE | fs::FILE_NOTIFY_CHANGE_SIZE;
    // SAFETY: a NUL-ended path; this folder only.
    let raw = unsafe { fs::FindFirstChangeNotificationW(path.as_ptr(), 0, filter) };
    if raw.is_null() || raw == INVALID_HANDLE_VALUE {
        log(&format!("cannot watch authorized_phones: error {}", last_error()));
        return None;
    }
    struct Notification(HANDLE);
    // SAFETY: a kernel handle, used by the watching thread only.
    unsafe impl Send for Notification {}
    impl Drop for Notification {
        fn drop(&mut self) {
            // SAFETY: the handle from FindFirstChangeNotificationW, closed once.
            unsafe {
                fs::FindCloseChangeNotification(self.0);
            }
        }
    }
    let notification = Notification(raw);
    let stop = Arc::new(manual_event().ok()?);
    let (stop2, log2) = (stop.clone(), log.clone());
    let thread = std::thread::Builder::new()
        .name("gp-watch".to_string())
        .spawn(move || {
            let notification = notification;
            let handles = [notification.0, stop2.raw()];
            loop {
                // SAFETY: two live handles that outlive the wait.
                let r = unsafe { th::WaitForMultipleObjects(2, handles.as_ptr(), 0, INFINITE) };
                if r != WAIT_OBJECT_0 {
                    return; // stopped (or the wait failed)
                }
                changed();
                // SAFETY: the live notification handle.
                if unsafe { fs::FindNextChangeNotification(notification.0) } == 0 {
                    log2(&format!("authorized_phones watcher error: {}", last_error()));
                    return;
                }
            }
        })
        .ok()?;
    Some(Box::new(FolderWatch { stop, thread: Some(thread) }))
}

/// Waits up to `ms` for the process `pid` to end (at once when it is gone already or can't be opened).
pub fn wait_for_exit(pid: u32, ms: u32) {
    // SAFETY: only the right to wait is asked for; the handle is closed by `Handle`.
    if let Some(h) = Handle::new(unsafe { th::OpenProcess(SYNCHRONIZE, 0, pid) }) {
        // SAFETY: a live process handle.
        unsafe {
            th::WaitForSingleObject(h.raw(), ms);
        }
    }
}
