//! The few calls into the system that the portable parts (the UDP server, the USB bridge, the watchdog) need:
//! the socket's options, how much is waiting on it, a wait for it, and a thread's priority. Windows is the real
//! target; the other branch serves the tests in the cloud and CI.

use std::net::{SocketAddr, UdpSocket};

/// How urgently a thread runs (C#'s ThreadPriority names).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Priority {
    BelowNormal,
    AboveNormal,
    Highest,
}

/// Why a bind failed, as the server reports it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BindError {
    /// Another program has the port (or Windows keeps it from us).
    InUse,
    Other,
}

/// Binds the UDP socket the way the C# server does: a 64 KB receive buffer, and on Windows the port for us alone
/// and no WSAECONNRESET from a phone that went away (an ICMP port-unreachable would otherwise fail the next
/// receive).
pub fn bind_udp(addr: SocketAddr) -> Result<UdpSocket, (BindError, std::io::Error)> {
    use socket2::{Domain, Protocol, Socket, Type};
    let other = |e: std::io::Error| (BindError::Other, e);
    let domain = if addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let s = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP)).map_err(other)?;
    s.set_recv_buffer_size(65536).map_err(other)?;
    imp::before_bind(&s).map_err(other)?;
    s.bind(&addr.into()).map_err(|e| {
        let kind = match e.kind() {
            std::io::ErrorKind::AddrInUse | std::io::ErrorKind::PermissionDenied => BindError::InUse,
            _ => BindError::Other,
        };
        (kind, e)
    })?;
    Ok(s.into())
}

/// True when a datagram is waiting to be read (C#'s `socket.Available > 0`); an error reads as none.
pub fn available(socket: &UdpSocket) -> bool {
    imp::available(socket)
}

/// Waits until the socket has a datagram (true) or about `us` µs passed (false). On Windows the timeout ends only
/// on the system timer's tick (about 15.6 ms); the exact wait is the Windows layer's.
pub fn poll_readable(socket: &UdpSocket, us: u32) -> bool {
    imp::poll_readable(socket, us)
}

/// True for the error a datagram larger than the buffer gives on Windows (it is dropped).
pub fn is_message_size(e: &std::io::Error) -> bool {
    imp::is_message_size(e)
}

/// True for the error an ICMP port-unreachable from an earlier send gives on Windows: not fatal.
pub fn is_connection_reset(e: &std::io::Error) -> bool {
    e.kind() == std::io::ErrorKind::ConnectionReset
}

/// Sets the calling thread's priority; a failure changes nothing.
pub fn set_thread_priority(p: Priority) {
    imp::set_thread_priority(p)
}

#[cfg(windows)]
mod imp {
    use super::Priority;
    use std::net::UdpSocket;
    use std::os::windows::io::AsRawSocket;
    use windows_sys::Win32::Networking::WinSock as ws;
    use windows_sys::Win32::System::Threading as th;

    const SIO_UDP_CONNRESET: u32 = 0x9800_000C;

    pub fn before_bind(s: &socket2::Socket) -> std::io::Result<()> {
        let raw = s.as_raw_socket() as ws::SOCKET;
        let on: u32 = 1;
        // SAFETY: a live socket, and a 4-byte option value that outlives the call.
        let r = unsafe { ws::setsockopt(raw, ws::SOL_SOCKET, ws::SO_EXCLUSIVEADDRUSE, (&on as *const u32).cast(), 4) };
        if r != 0 {
            return Err(std::io::Error::last_os_error());
        }
        let off: u32 = 0;
        let mut returned: u32 = 0;
        // SAFETY: a live socket; the in-buffer is 4 bytes and outlives the call; no out-buffer, no overlapped.
        let r = unsafe {
            ws::WSAIoctl(raw, SIO_UDP_CONNRESET, (&off as *const u32).cast(), 4, std::ptr::null_mut(), 0, &mut returned, std::ptr::null_mut(), None)
        };
        if r != 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok(())
    }

    pub fn available(socket: &UdpSocket) -> bool {
        let mut n: u32 = 0;
        // SAFETY: a live socket and a u32 for FIONREAD's answer.
        let r = unsafe { ws::ioctlsocket(socket.as_raw_socket() as ws::SOCKET, ws::FIONREAD, &mut n) };
        r == 0 && n > 0
    }

    pub fn poll_readable(socket: &UdpSocket, us: u32) -> bool {
        let mut fd = ws::WSAPOLLFD { fd: socket.as_raw_socket() as ws::SOCKET, events: ws::POLLRDNORM, revents: 0 };
        // SAFETY: one WSAPOLLFD that lives across the call.
        let r = unsafe { ws::WSAPoll(&mut fd, 1, us.div_ceil(1000) as i32) };
        r > 0 && fd.revents & ws::POLLRDNORM != 0
    }

    pub fn is_message_size(e: &std::io::Error) -> bool {
        e.raw_os_error() == Some(ws::WSAEMSGSIZE)
    }

    pub fn set_thread_priority(p: Priority) {
        let v = match p {
            Priority::BelowNormal => th::THREAD_PRIORITY_BELOW_NORMAL,
            Priority::AboveNormal => th::THREAD_PRIORITY_ABOVE_NORMAL,
            Priority::Highest => th::THREAD_PRIORITY_HIGHEST,
        };
        // SAFETY: the pseudo-handle of the calling thread is always valid.
        unsafe {
            th::SetThreadPriority(th::GetCurrentThread(), v);
        }
    }
}

#[cfg(unix)]
mod imp {
    use super::Priority;
    use std::net::UdpSocket;
    use std::os::fd::AsRawFd;

    pub fn before_bind(_s: &socket2::Socket) -> std::io::Result<()> {
        Ok(())
    }

    pub fn available(socket: &UdpSocket) -> bool {
        // FIONREAD says 0 for a waiting empty datagram, so ask poll instead.
        poll_readable(socket, 0)
    }

    pub fn poll_readable(socket: &UdpSocket, us: u32) -> bool {
        let mut fd = libc::pollfd { fd: socket.as_raw_fd(), events: libc::POLLIN, revents: 0 };
        // SAFETY: one pollfd that lives across the call.
        let r = unsafe { libc::poll(&mut fd, 1, us.div_ceil(1000) as i32) };
        r > 0 && fd.revents & libc::POLLIN != 0
    }

    pub fn is_message_size(_e: &std::io::Error) -> bool {
        false
    }

    pub fn set_thread_priority(_p: Priority) {}
}
