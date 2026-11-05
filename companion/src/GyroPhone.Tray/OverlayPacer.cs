using System.Diagnostics;
using System.Runtime.InteropServices;

namespace GyroPhone.Tray;

/// <summary>
/// Paces the overlay windows to the screen: a background thread waits for each refresh of the
/// desktop compositor (DwmFlush) and runs <see cref="_tick"/> on itself, so drawing the overlays
/// never holds up the main window (it glitched while scrolling when they drew on the UI thread).
/// It sleeps while no overlay window is open.
/// <para>
/// DwmFlush can return at once (nothing for the compositor to do), so ticks are also spaced by the
/// display's refresh period at least (high-resolution waitable timer), and a fifth of a period
/// after the last one ended.
/// </para>
/// </summary>
internal sealed class OverlayPacer : IDisposable
{
    private readonly Action _tick;
    // The fastest frame rate any open window wants (0: every screen refresh).
    private readonly Func<int> _wantHz;
    private readonly Action<string> _log;
    private readonly ManualResetEventSlim _running = new(false);
    private readonly Thread _thread;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private volatile bool _stop;

    public OverlayPacer(Action tick, Func<int> wantHz, Action<string> log)
    {
        _tick = tick;
        _wantHz = wantHz;
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
        int refreshHz = Math.Clamp(RefreshHz(), 30, 500);
        _log($"overlay pacer: screen {refreshHz} Hz, {(timer == IntPtr.Zero ? "sleep" : "high-resolution timer")}");
        bool dwmFailed = false;
        double lastPost = -1000, ended = -1000, lastRefreshCheck = 0;
        while (!_stop)
        {
            _running.Wait();
            if (_stop) return;
            // The refresh rate changes with the display mode (another screen, a game's mode): read it again every few seconds.
            if (_clock.Elapsed.TotalMilliseconds - lastRefreshCheck > 5000)
            {
                lastRefreshCheck = _clock.Elapsed.TotalMilliseconds;
                int hz = Math.Clamp(RefreshHz(), 30, 500);
                if (hz != refreshHz)
                {
                    _log($"overlay pacer: screen now {hz} Hz");
                    refreshHz = hz;
                }
            }
            // As often as the fastest window draws, never faster than the screen: waking at every
            // refresh of a 165 Hz screen for 60 pictures a second cost CPU for nothing.
            int want = _wantHz();
            bool everyRefresh = want <= 0 || want >= refreshHz * 0.9;
            double period = 1000.0 / (everyRefresh ? refreshHz : Math.Max(want, 1));
            if (everyRefresh && !dwmFailed && DwmFlush() != 0)
            {
                _log("overlay: no DwmFlush, pacing by the refresh rate only");
                dwmFailed = true;
            }
            // Never sooner than ~a period after the last tick, whatever DwmFlush did, and a fifth
            // of a period after it ended, so a slow draw still leaves the CPU some rest.
            double now = _clock.Elapsed.TotalMilliseconds;
            double wait = Math.Max(lastPost + period * (everyRefresh ? 0.9 : 1.0), ended + period * 0.2) - now;
            if (wait > 0.2) Wait(timer, wait);
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

    /// <summary>The primary display's refresh rate (Hz), or 60.</summary>
    private static int RefreshHz()
    {
        var mode = new DevMode { Size = (short)Marshal.SizeOf<DevMode>() };
        return EnumDisplaySettingsW(null, EnumCurrentSettings, ref mode) && mode.DisplayFrequency > 1 ? mode.DisplayFrequency : 60;
    }

    public void Dispose()
    {
        _stop = true;
        _running.Set();
    }

    private const uint CreateWaitableTimerHighResolution = 0x2;
    private const uint TimerAllAccess = 0x1F0003;
    private const int EnumCurrentSettings = -1;

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

    [DllImport("dwmapi.dll")]
    private static extern int DwmFlush();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumDisplaySettingsW(string? deviceName, int modeNum, ref DevMode mode);

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
