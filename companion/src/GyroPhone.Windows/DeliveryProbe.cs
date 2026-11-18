using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using GyroPhone.Host;

namespace GyroPhone.Windows;

/// <summary>
/// Diagnostics' delivery probe: how long each of GyroPhone's mouse moves takes from the start of its hand-off
/// (SendInput, or the input driver's DeviceIoControl) to Windows delivering it as raw input, the way a game reading raw
/// input gets it. So SendInput and the input driver compare end to end, not only by how long the call took.
/// <para>
/// Runs only while Diagnostics is on: its own thread with a hidden message-only window that reads raw mouse input
/// (RIDEV_INPUTSINK), read-only: nothing is held back or changed. The input path only stores the move's start time and
/// size in a preallocated ring (<see cref="Sent"/>: no lock, no allocation); this thread matches each raw input move from
/// GyroPhone (no device behind it: SendInput; or the driver's own mouse) to the oldest run of stored moves in a row that
/// adds up to it. Windows 11 merges a device's moves for background raw input readers like this one (at most ~125 a
/// second; the game in front gets each one), so on the input driver one raw move usually carries several of GyroPhone's,
/// and its delay is timed from the newest of them.
/// Once a second the input log's second line gets
/// " delivery[driver] n 125 p50 0.12 p99 0.40 max 6.10 over2 3 unmatched 0 merged 820" (ms; "n": raw moves matched;
/// "over2": over 2 ms; "unmatched": GyroPhone moves that never showed up, which on a steady link means lost moves;
/// "merged": GyroPhone moves that came inside another raw move; "stray": raw moves from GyroPhone's device that matched
/// nothing).
/// </para>
/// <para>
/// While Diagnostics is on, each raw move over 2 ms also gets a line of its own (at most 20 a second, the rest counted
/// in the second's text): "delivery slow 3.42 ms (driver): gap 12.0 ms since the move before, merged 3, front
/// Discovery, probe slept 11.8 ms, read 0.02 ms after waking, 1st since waking". Made on the probe thread, never the
/// input path. While a Delivery test runs it also adds up the test's moves (<see cref="StartTest"/>), so the test window
/// in front can show both readers' numbers for the same moves.
/// </para>
/// <para>
/// A process has one raw mouse input target: "Press a key" listening takes it while it runs (the probe neither takes
/// nor removes it meanwhile, <see cref="RawMouseOwner"/>), and the probe registers again when it gives it up
/// (<see cref="RawMouseReleased"/>).
/// </para>
/// </summary>
public sealed class DeliveryProbe : IDisposable
{
    private const int WmInput = 0x00FF, WmClose = 0x0010, WmTimer = 0x0113, WmRegister = 0x0400 + 0x47;
    private const uint RidevInputSink = 0x00000100, RidevRemove = 0x00000001;
    private const uint QsAllInput = 0x04FF;
    private const int MaxSlowLines = 20;
    private static readonly IntPtr HwndMessage = new(-3);

    private static volatile DeliveryProbe? s_current;

    private readonly Action<string>? _log;
    // The moves the input path handed off: written by the input path (one writer, under the engine lock), read here.
    private readonly MoveRing _ring = new();
    private readonly DeliveryMatcher _matcher;

    // The probe thread's own numbers for the current second, and the last second's text.
    private readonly DeliveryTally _second = new();
    private string? _text;
    private bool _sawDriver, _sawSendInput;
    private int _slowLines, _slowSkipped;
    // A Delivery test's numbers: added up here, read by the companion's call (another thread).
    private readonly object _testLock = new();
    private DeliveryTally? _test;
    private volatile bool _testCounting;
    private readonly RawMouse _raw = new();

    // When this thread last woke from waiting for messages, how long it had waited, and messages handled since.
    private long _wokeAt, _slept;
    private int _sinceWake;
    private long _readAt;
    private IntPtr _lastFrontWindow;
    private string _lastFront = "?";

    private Thread? _thread;
    private IntPtr _hwnd;
    private WndProc? _proc;

    public DeliveryProbe(Action<string>? log)
    {
        _log = log;
        _matcher = new DeliveryMatcher(_ring, Delivered, Missed, Stray);
    }

    /// <summary>
    /// While Diagnostics is on: takes one line per move over 2 ms (on the probe thread). Null: none made.
    /// </summary>
    public Action<string>? SlowLine { get; set; }

    public void Start()
    {
        if (_thread != null) return;
        var ready = new ManualResetEventSlim();
        _thread = new Thread(() => Run(ready)) { IsBackground = true, Name = "gp-delivery", Priority = ThreadPriority.Highest };
        _thread.Start();
        ready.Wait(1000);
        s_current = this;
    }

    public void Dispose()
    {
        if (s_current == this) s_current = null;
        var h = _hwnd;
        if (h != IntPtr.Zero) PostMessage(h, WmClose, IntPtr.Zero, IntPtr.Zero);
        _thread?.Join(1000);
        _thread = null;
        // The ring stays until nothing holds it: the input path may still be storing a move that came just now.
    }

    /// <summary>A raw input reader gave the process's raw mouse input up: the running probe takes it again.</summary>
    public static void RawMouseReleased()
    {
        var p = s_current;
        if (p != null && p._hwnd != IntPtr.Zero) PostMessage(p._hwnd, WmRegister, IntPtr.Zero, IntPtr.Zero);
    }

    /// <summary>A move's hand-off started at Stopwatch <paramref name="ticks"/>. Input path: no lock, no allocation.</summary>
    public void Sent(long ticks, int dx, int dy) => _ring.Put(ticks, dx, dy);

    /// <summary>
    /// The last second's delivery numbers for the input log's second line, or null when no move was matched or missed.
    /// Made on the probe thread; this only hands the text over (once).
    /// </summary>
    public string? SecondText() => Interlocked.Exchange(ref _text, null);

    /// <summary>A Delivery test starts: its moves are added up from now (while <paramref name="counting"/>).</summary>
    public void StartTest(bool counting)
    {
        lock (_testLock) _test = new DeliveryTally(16_384);
        _testCounting = counting;
    }

    /// <summary>The test window is in front (its moves count) or not (paused).</summary>
    public void CountTest(bool counting) => _testCounting = counting;

    /// <summary>The Delivery test's numbers so far, or null without one.</summary>
    public DeliveryNumbers? TestNumbers()
    {
        lock (_testLock) return _test?.Numbers();
    }

    public void StopTest()
    {
        lock (_testLock) _test = null;
    }

    private void Run(ManualResetEventSlim ready)
    {
        try
        {
            _proc = WindowProc;
            var cls = new WndClassEx
            {
                Size = (uint)Marshal.SizeOf<WndClassEx>(),
                WndProc = Marshal.GetFunctionPointerForDelegate(_proc),
                Instance = GetModuleHandle(null),
                ClassName = "GyroPhoneDeliveryProbe",
            };
            RegisterClassEx(ref cls);
            var hwnd = CreateWindowEx(0, cls.ClassName, "", 0, 0, 0, 0, 0, HwndMessage, IntPtr.Zero, cls.Instance, IntPtr.Zero);
            if (hwnd == IntPtr.Zero)
            {
                _log?.Invoke($"delivery probe: no window (error {Marshal.GetLastWin32Error()})");
                return;
            }
            _hwnd = hwnd;
            Register(hwnd);
            SetTimer(hwnd, (UIntPtr)1, 1000, IntPtr.Zero);
            ready.Set();
            while (true)
            {
                // Nothing waiting: GetMessage sleeps until something comes, so note when it went to sleep and woke.
                bool sleeps = (GetQueueStatus(QsAllInput) >> 16) == 0;
                long before = Stopwatch.GetTimestamp();
                if (GetMessage(out var msg, IntPtr.Zero, 0, 0) <= 0) break;
                _readAt = Stopwatch.GetTimestamp();
                if (sleeps)
                {
                    _wokeAt = _readAt;
                    _slept = _readAt - before;
                    _sinceWake = 0;
                }
                else _sinceWake++;
                if (msg.Message == WmClose && msg.Hwnd == hwnd) break;
                DispatchMessage(ref msg);
            }
            var remove = new RawInputDevice { UsagePage = 1, Usage = 2, Flags = RidevRemove, Target = IntPtr.Zero };
            // While "Press a key" holds the raw mouse input, it is the listener's: removing it would take it from it.
            lock (RawMouseOwner.Gate)
                if (!RawMouseOwner.Listener) RegisterRawInputDevices([remove], 1, (uint)Marshal.SizeOf<RawInputDevice>());
            DestroyWindow(hwnd);
            _hwnd = IntPtr.Zero;
            // The class holds this probe's window procedure: the next probe registers its own.
            UnregisterClass(cls.ClassName, cls.Instance);
        }
        catch (Exception e)
        {
            _log?.Invoke($"delivery probe stopped: {e.GetType().Name}: {e.Message}");
        }
        finally
        {
            ready.Set();
        }
    }

    private void Register(IntPtr hwnd)
    {
        var d = new RawInputDevice { UsagePage = 1, Usage = 2, Flags = RidevInputSink, Target = hwnd };
        int error = 0;
        lock (RawMouseOwner.Gate)
        {
            // "Press a key" holds it: the probe takes it when the listener lets go (RawMouseReleased).
            if (RawMouseOwner.Listener) return;
            if (!RegisterRawInputDevices([d], 1, (uint)Marshal.SizeOf<RawInputDevice>())) error = Marshal.GetLastWin32Error();
        }
        if (error != 0) _log?.Invoke($"delivery probe: raw mouse input not registered (error {error})");
    }

    private IntPtr WindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        try
        {
            if (msg == WmInput)
            {
                long now = Stopwatch.GetTimestamp();
                if (_raw.Read(lParam, out int x, out int y, out bool driver)) _matcher.Raw(now, x, y, driver);
            }
            else if (msg == WmTimer)
            {
                _matcher.Settle(Stopwatch.GetTimestamp());
                Roll();
            }
            else if (msg == WmRegister) Register(hwnd);
        }
        catch (Exception)
        {
            // never let an exception unwind into the window procedure
        }
        return DefWindowProc(hwnd, msg, wParam, lParam);
    }

    private void Delivered(Delivery d)
    {
        _second.Add(d);
        if (d.Driver) _sawDriver = true;
        else _sawSendInput = true;
        if (_testCounting)
            lock (_testLock) _test?.Add(d);
        if (d.Ticks > DeliveryTally.SlowTicks && SlowLine is { } line)
        {
            if (_slowLines >= MaxSlowLines) _slowSkipped++;
            else
            {
                _slowLines++;
                line(SlowText(d));
            }
        }
    }

    private void Missed(long n)
    {
        _second.Missed(n);
        if (_testCounting)
            lock (_testLock) _test?.Missed(n);
    }

    private void Stray()
    {
        _second.Stray();
        if (_testCounting)
            lock (_testLock) _test?.Stray();
    }

    /// <summary>One slow move's line, with what may explain it.</summary>
    private string SlowText(in Delivery d)
    {
        double Ms(long t) => DeliveryTally.Ms(t);
        string nth = _sinceWake switch { 0 => "1st", 1 => "2nd", 2 => "3rd", _ => $"{_sinceWake + 1}th" };
        return string.Create(CultureInfo.InvariantCulture,
            $"delivery slow {Ms(d.Ticks):0.00} ms ({(d.Driver ? "driver" : "SendInput")}): gap {Ms(d.GapTicks):0.0} ms since the move before, " +
            $"merged {d.Merged}, front {Front()}, probe slept {Ms(_slept):0.0} ms, read {Ms(_readAt - _wokeAt):0.00} ms after waking, {nth} since waking");
    }

    /// <summary>The program in front (looked up again only when the front window changed).</summary>
    private string Front()
    {
        var w = GetForegroundWindow();
        if (w == _lastFrontWindow) return _lastFront;
        _lastFrontWindow = w;
        _lastFront = "none";
        if (w == IntPtr.Zero) return _lastFront;
        GetWindowThreadProcessId(w, out uint pid);
        try
        {
            using var p = Process.GetProcessById((int)pid);
            _lastFront = p.ProcessName;
        }
        catch (Exception)
        {
            _lastFront = $"pid {pid}";
        }
        return _lastFront;
    }

    /// <summary>Each second on the probe thread: the second's numbers become the text <see cref="SecondText"/> hands out.</summary>
    private void Roll()
    {
        long over = _ring.TakeOverflow();
        int skipped = _slowSkipped;
        _slowLines = _slowSkipped = 0;
        if (_second.Empty)
        {
            _text = null;
            return;
        }
        var n = _second.Numbers();
        string route = _sawDriver && _sawSendInput ? "both" : _sawDriver ? "driver" : "SendInput";
        _text = string.Create(CultureInfo.InvariantCulture,
            $" delivery[{route}] n {n.N} p50 {n.P50:0.00} p99 {n.P99:0.00} max {n.Max:0.00} over2 {n.Slow} unmatched {n.Unmatched} merged {n.Merged}{(n.Stray > 0 ? $" stray {n.Stray}" : "")}{(over > 0 ? $" full {over}" : "")}{(skipped > 0 ? $" slowlines-skipped {skipped}" : "")}");
        _second.Clear();
        _sawDriver = _sawSendInput = false;
    }

    // ---- Win32 ----

    private delegate IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct RawInputDevice
    {
        public ushort UsagePage;
        public ushort Usage;
        public uint Flags;
        public IntPtr Target;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WndClassEx
    {
        public uint Size;
        public uint Style;
        public IntPtr WndProc;
        public int ClsExtra;
        public int WndExtra;
        public IntPtr Instance;
        public IntPtr Icon;
        public IntPtr Cursor;
        public IntPtr Background;
        public string? MenuName;
        public string ClassName;
        public IntPtr IconSmall;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Msg
    {
        public IntPtr Hwnd;
        public uint Message;
        public IntPtr WParam;
        public IntPtr LParam;
        public uint Time;
        public int X, Y;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterRawInputDevices(RawInputDevice[] devices, uint count, uint size);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassEx(ref WndClassEx cls);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool UnregisterClass(string className, IntPtr instance);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowEx(uint exStyle, string className, string windowName, uint style,
        int x, int y, int width, int height, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);

    [DllImport("user32.dll")]
    private static extern bool DestroyWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern UIntPtr SetTimer(IntPtr hwnd, UIntPtr id, uint ms, IntPtr proc);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern int GetMessage(out Msg msg, IntPtr hwnd, uint min, uint max);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage(ref Msg msg);

    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? name);

    [DllImport("user32.dll")]
    private static extern uint GetQueueStatus(uint flags);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
}
