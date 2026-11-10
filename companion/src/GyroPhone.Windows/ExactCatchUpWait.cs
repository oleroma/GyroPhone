using System.Net.Sockets;
using System.Runtime.InteropServices;
using GyroPhone.Host.Abstractions;

namespace GyroPhone.Windows;

/// <summary>
/// gp-rx's wait while a mouse catch-up runs, exact to well under a millisecond without asking Windows for a
/// finer timer: the socket's read event (WSAEventSelect) and a high-resolution waitable timer
/// (CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, as the overlay pacer and XInputPcPad use), waited on together. A
/// datagram wakes it at once, as socket.Poll does; only the timeout is exact instead of the 15.6 ms tick.
/// WSAEventSelect makes the socket non-blocking, so each wait ends by undoing it (gp-rx then reads it
/// blocking as always). Anything failing falls back to socket.Poll for good. gp-rx only.
/// </summary>
public sealed class ExactCatchUpWait : ICatchUpWait, IDisposable
{
    private const int FdRead = 1;
    private const int Fionbio = unchecked((int)0x8004667E);
    private const uint WaitObject0 = 0;
    private const uint WaitFailed = 0xFFFFFFFF;
    private const uint CreateWaitableTimerHighResolution = 0x2;
    private const uint TimerAllAccess = 0x1F0003;

    private readonly Action<string>? _log;
    private readonly IntPtr[] _handles = new IntPtr[2]; // the socket's read event, the timer
    private bool _broken;

    public ExactCatchUpWait(Action<string>? log = null) => _log = log;

    public bool Wait(Socket socket, int us)
    {
        if (_broken || !Ready()) return socket.Poll(us, SelectMode.SelectRead);
        IntPtr s = socket.Handle;
        ResetEvent(_handles[0]);
        if (WSAEventSelect(s, _handles[0], FdRead) != 0)
        {
            _ = socket.Available; // a socket closed by Stop throws here (ends gp-rx), and is no reason to give up on this
            Broke($"WSAEventSelect failed ({WSAGetLastError()})");
            Restore(socket, s);
            return socket.Poll(us, SelectMode.SelectRead);
        }
        try
        {
            // A datagram already waiting sets the event too; this only saves the wait.
            if (socket.Available > 0) return true;
            long due = -(long)us * 10; // relative, 100 ns units
            if (!SetWaitableTimer(_handles[1], ref due, 0, IntPtr.Zero, IntPtr.Zero, false))
            {
                Broke($"SetWaitableTimer failed ({Marshal.GetLastWin32Error()})");
                return socket.Poll(us, SelectMode.SelectRead);
            }
            uint r = WaitForMultipleObjects(2, _handles, false, (uint)(us / 1000 + 50));
            if (r == WaitFailed) Broke($"WaitForMultipleObjects failed ({Marshal.GetLastWin32Error()})");
            return r == WaitObject0;
        }
        finally
        {
            Restore(socket, s);
        }
    }

    /// <summary>Back to a plain blocking socket: the event selection off first, then blocking (as Winsock wants).</summary>
    private void Restore(Socket socket, IntPtr s)
    {
        int blocking = 0;
        if (WSAEventSelect(s, IntPtr.Zero, 0) == 0 && ioctlsocket(s, Fionbio, ref blocking) == 0) return;
        _ = socket.Available; // closed by Stop meanwhile: throws (ends gp-rx) without giving up on this for a restart
        Broke($"could not make the socket blocking again ({WSAGetLastError()})");
        socket.Blocking = true; // throws on a closed socket, which ends gp-rx's loop
    }

    private bool Ready()
    {
        if (_handles[0] != IntPtr.Zero) return true;
        IntPtr ev = CreateEventW(IntPtr.Zero, true, false, IntPtr.Zero);
        IntPtr timer = CreateWaitableTimerExW(IntPtr.Zero, IntPtr.Zero, CreateWaitableTimerHighResolution, TimerAllAccess);
        if (ev == IntPtr.Zero || timer == IntPtr.Zero)
        {
            if (ev != IntPtr.Zero) CloseHandle(ev);
            if (timer != IntPtr.Zero) CloseHandle(timer);
            Broke("no high-resolution timer");
            return false;
        }
        _handles[0] = ev;
        _handles[1] = timer;
        return true;
    }

    private void Broke(string why)
    {
        if (_broken) return;
        _broken = true;
        _log?.Invoke($"catch-up wake: {why}; using socket.Poll (about 15 ms steps)");
    }

    public void Dispose()
    {
        for (int i = 0; i < _handles.Length; i++)
        {
            if (_handles[i] != IntPtr.Zero) CloseHandle(_handles[i]);
            _handles[i] = IntPtr.Zero;
        }
    }

    [DllImport("ws2_32.dll")]
    private static extern int WSAEventSelect(IntPtr socket, IntPtr eventObject, int networkEvents);

    [DllImport("ws2_32.dll")]
    private static extern int ioctlsocket(IntPtr socket, int cmd, ref int arg);

    [DllImport("ws2_32.dll")]
    private static extern int WSAGetLastError();

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateEventW(IntPtr attributes, bool manualReset, bool initialState, IntPtr name);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ResetEvent(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateWaitableTimerExW(IntPtr attributes, IntPtr name, uint flags, uint access);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWaitableTimer(IntPtr timer, ref long dueTime, int period, IntPtr completion, IntPtr arg, bool resume);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForMultipleObjects(uint count, IntPtr[] handles, bool waitAll, uint ms);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);
}
