//! Windows: everything `host::Platform` asks for. The portable parts above never call Windows themselves, so
//! this is the only place with `unsafe` calls into the system. Ported from companion/src/GyroPhone.Windows.
//!
//! None of this runs in the cloud tests (it is only type-checked there against the Windows headers); it first
//! runs on the PC, behind the Performance tab's switch.

pub mod basics;
pub mod drivers;
pub mod pc_pad;
pub mod probes;
pub mod process;
pub mod raw_input;
pub mod usb;

use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, HANDLE, INVALID_HANDLE_VALUE};

/// A Windows handle that is closed when dropped. Handles may be used from any thread.
pub struct Handle(pub HANDLE);

// SAFETY: a kernel handle is a process-wide number; the kernel serializes what is done through it.
unsafe impl Send for Handle {}
unsafe impl Sync for Handle {}

impl Handle {
    /// None for the null or the invalid handle.
    pub fn new(h: HANDLE) -> Option<Handle> {
        if h.is_null() || h == INVALID_HANDLE_VALUE {
            None
        } else {
            Some(Handle(h))
        }
    }

    pub fn raw(&self) -> HANDLE {
        self.0
    }
}

impl Drop for Handle {
    fn drop(&mut self) {
        // SAFETY: the handle is ours and still open; nothing uses it after this.
        unsafe {
            CloseHandle(self.0);
        }
    }
}

/// The calling thread's last Windows error code.
pub fn last_error() -> u32 {
    // SAFETY: no arguments, reads thread-local state.
    unsafe { GetLastError() }
}

/// `s` as a NUL-ended UTF-16 string.
pub fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}
