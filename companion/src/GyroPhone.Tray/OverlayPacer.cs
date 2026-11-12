using System.Diagnostics;
using System.Runtime.InteropServices;
using GyroPhone.Host;

namespace GyroPhone.Tray;

/// <summary>
/// Paces the overlay windows to the screen: a background thread waits for each refresh of the
/// desktop compositor (DwmFlush) and runs <see cref="_tick"/> on itself, so drawing the overlays
/// never holds up the main window (it glitched while scrolling when they drew on the UI thread).
/// It sleeps while no overlay window is open.
/// <para>
/// DwmFlush can return at once (nothing for the compositor to do), so ticks are also spaced by the
/// display's refresh period at least (high-resolution waitable timer), and a fifth of a picture's
/// time after the last one ended.
/// </para>
/// <para>
/// A frame-rate cap below the refresh rate ticks every Nth refresh (N = refresh / cap rounded,
/// <see cref="OverlayPacing.RefreshesPerPicture"/>), still at a DwmFlush, so the pictures stay tied to
/// the screen: it sleeps through the refreshes it skips and waits for the screen's next one.
/// The refresh rate is the fastest of the screens the open windows are on.
/// </para>
/// </summary>
internal sealed class OverlayPacer : IDisposable
{
    private readonly Action _tick;
    // The fastest frame rate any open window wants (0: every screen refresh).
    private readonly Func<int> _wantHz;
    // The fastest refresh rate of the screens the open windows are on (0: not known yet).
    private readonly Func<int> _screenHz;
    private readonly Action<string> _log;
    private readonly ManualResetEventSlim _running = new(false);
    private readonly Thread _thread;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private volatile bool _stop;

    public OverlayPacer(Action tick, Func<int> wantHz, Func<int> screenHz, Action<string> log)
    {
        _tick = tick;
        _wantHz = wantHz;
        _screenHz = screenHz;
        _log = log;
        _thread = new Thread(Loop) { IsBackground = true, Name = "overlay pacer" };
        _thread.Start();
    }

    /// <summary>On while any overlay window is open.</summary>
    public void SetRunning(bool on)
    {
        if (on) _running.Set();
        else _running.Reset();
    }

    private void Loop()
    {
        try
        {
            Run();
        }
        catch (Exception e)
        {
            _log($"overlay pacer stopped: {e.GetType().Name}: {e.Message}");
        }
    }

    private void Run()
    {
        IntPtr timer = CreateWaitableTimerExW(IntPtr.Zero, IntPtr.Zero, CreateWaitableTimerHighResolution, TimerAllAccess);
        try
        {
            Run(timer);
        }
        finally
        {
            if (timer != IntPtr.Zero) CloseHandle(timer);
        }
    }

    private void Run(IntPtr timer)
    {
        int refreshHz = ScreenHz();
        _log($"overlay pacer: screen {refreshHz} Hz, {(timer == IntPtr.Zero ? "sleep" : "high-resolution timer")}");
        bool dwmFailed = false;
        double lastPost = -1000, ended = -1000;
        while (!_stop)
        {
            _running.Wait();
            if (_stop) return;
            // The refresh rate changes with the screens the windows are on and the display mode (a game's mode).
            int hz = ScreenHz();
            if (hz != refreshHz)
            {
                _log($"overlay pacer: screen now {hz} Hz");
                refreshHz = hz;
            }
            // As often as the fastest window draws, in whole refreshes, never faster than the screen:
            // waking at every refresh of a 165 Hz screen for 60 pictures a second cost CPU for nothing.
            int every = OverlayPacing.RefreshesPerPicture(refreshHz, _wantHz());
            double period = 1000.0 / refreshHz, frame = period * every;
            // Refreshes this picture skips: slept through, waking half a refresh before the one it shows at.
            if (every > 1 && !dwmFailed)
            {
                double sleep = lastPost + frame - period * 0.5 - _clock.Elapsed.TotalMilliseconds;
                if (sleep > 0.2) Wait(timer, Math.Min(sleep, 1000));
            }
            if (!dwmFailed && DwmFlush() != 0)
            {
                _log("overlay: no DwmFlush, pacing by the refresh rate only");
                dwmFailed = true;
            }
            // Never sooner than ~a picture's time after the last tick, whatever DwmFlush did, and a fifth
            // of it after it ended, so a slow draw still leaves the CPU some rest.
            double now = _clock.Elapsed.TotalMilliseconds;
            double wait = Math.Max(lastPost + (dwmFailed ? frame : frame - period * 0.1), ended + frame * 0.2) - now;
            if (wait > 0.2) Wait(timer, Math.Min(wait, 1000));
            if (_stop) return;
            lastPost = _clock.Elapsed.TotalMilliseconds;
            try
            {
                _tick();
            }
            catch (Exception e)
            {
                _log($"overlay tick: {e.GetType().Name}: {e.Message}");
            }
            ended = _clock.Elapsed.TotalMilliseconds;
        }
    }

    // The primary screen's rate, read again every few seconds, until a window knows its own screen's.
    private int _primaryHz;
    private double _primaryAt = double.MinValue;

    /// <summary>The refresh rate to pace by: the open windows' fastest screen, else the primary one's.</summary>
    private int ScreenHz()
    {
        int hz = _screenHz();
        if (hz <= 0)
        {
            double now = _clock.Elapsed.TotalMilliseconds;
            if (now - _primaryAt > 5000)
            {
                _primaryAt = now;
                _primaryHz = ScreenRate.Primary();
            }
            hz = _primaryHz;
        }
        return Math.Clamp(hz, 30, 500);
    }

    private static void Wait(IntPtr timer, double ms)
    {
        if (timer != IntPtr.Zero)
        {
            long due = -(long)(ms * 10_000); // relative, 100 ns units
            if (SetWaitableTimer(timer, ref due, 0, IntPtr.Zero, IntPtr.Zero, false))
            {
                WaitForSingleObject(timer, 1000);
                return;
            }
        }
        Thread.Sleep(Math.Max(1, (int)ms));
    }

    public void Dispose()
    {
        _stop = true;
        _running.Set();
    }

    private const uint CreateWaitableTimerHighResolution = 0x2;
    private const uint TimerAllAccess = 0x1F0003;

    [DllImport("dwmapi.dll")]
    private static extern int DwmFlush();

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateWaitableTimerExW(IntPtr attributes, IntPtr name, uint flags, uint access);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWaitableTimer(IntPtr timer, ref long dueTime, int period, IntPtr completion, IntPtr arg, bool resume);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll")]
    private static extern uint WaitForSingleObject(IntPtr handle, uint ms);
}

/// <summary>Screens' refresh rates: the one a window is on (MonitorFromWindow, then that device's mode) or the primary one.</summary>
internal static class ScreenRate
{
    /// <summary>The refresh rate (Hz) of the screen most of <paramref name="hwnd"/> is on, or 0 when unknown.</summary>
    public static int Of(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return 0;
        IntPtr monitor = MonitorFromWindow(hwnd, MonitorDefaultToNearest);
        var info = new MonitorInfoEx { Size = Marshal.SizeOf<MonitorInfoEx>() };
        if (monitor == IntPtr.Zero || !GetMonitorInfoW(monitor, ref info)) return 0;
        return Rate(info.Device);
    }

    /// <summary>The primary screen's refresh rate (Hz), or 60.</summary>
    public static int Primary() => Rate(null) is > 0 and var hz ? hz : 60;

    private static int Rate(string? device)
    {
        var mode = new DevMode { Size = (short)Marshal.SizeOf<DevMode>() };
        return EnumDisplaySettingsW(device, EnumCurrentSettings, ref mode) && mode.DisplayFrequency > 1 ? mode.DisplayFrequency : 0;
    }

    private const int EnumCurrentSettings = -1;
    private const uint MonitorDefaultToNearest = 2;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DevMode
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        public short SpecVersion, DriverVersion, Size, DriverExtra;
        public int Fields;
        public int PositionX, PositionY, DisplayOrientation, DisplayFixedOutput;
        public short Color, Duplex, YResolution, TTOption, Collate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string FormName;
        public short LogPixels;
        public int BitsPerPel, PelsWidth, PelsHeight, DisplayFlags, DisplayFrequency;
        public int IcmMethod, IcmIntent, MediaType, DitherType, Reserved1, Reserved2, PanningWidth, PanningHeight;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct MonitorInfoEx
    {
        public int Size;
        public int MonitorLeft, MonitorTop, MonitorRight, MonitorBottom;
        public int WorkLeft, WorkTop, WorkRight, WorkBottom;
        public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Device;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumDisplaySettingsW(string? deviceName, int modeNum, ref DevMode mode);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfoW(IntPtr monitor, ref MonitorInfoEx info);
}
