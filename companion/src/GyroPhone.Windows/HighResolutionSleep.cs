using System.Runtime.InteropServices;
using GyroPhone.Host.Abstractions;

namespace GyroPhone.Windows;

/// <summary>
/// A high-resolution waitable timer (Windows 10 1803 and later) for one thread's ticks: exact to well
/// under a millisecond without raising the system timer resolution. Falls back to Thread.Sleep.
/// </summary>
public sealed class HighResolutionSleep : ITickSleep
{
    private IntPtr _timer = CreateWaitableTimerExW(IntPtr.Zero, IntPtr.Zero, CreateWaitableTimerHighResolution, TimerAllAccess);

    public bool Exact => _timer != IntPtr.Zero;

    public void Sleep(long us)
    {
        if (_timer != IntPtr.Zero)
        {
            long due = -us * 10; // relative, 100 ns units
            if (SetWaitableTimer(_timer, ref due, 0, IntPtr.Zero, IntPtr.Zero, false))
            {
                WaitForSingleObject(_timer, (uint)Math.Clamp(us / 1000 + 1000, 0, uint.MaxValue - 1));
                return;
            }
        }
        Thread.Sleep((int)Math.Clamp((us + 999) / 1000, 1, int.MaxValue));
    }

    public void Dispose()
    {
        if (_timer != IntPtr.Zero) CloseHandle(_timer);
        _timer = IntPtr.Zero;
    }

    private const uint CreateWaitableTimerHighResolution = 0x2;
    private const uint TimerAllAccess = 0x1F0003;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateWaitableTimerExW(IntPtr attributes, IntPtr name, uint flags, uint access);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWaitableTimer(IntPtr timer, ref long dueTime, int period, IntPtr completion, IntPtr arg, bool resume);

    [DllImport("kernel32.dll")]
    private static extern uint WaitForSingleObject(IntPtr handle, uint ms);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);
}
