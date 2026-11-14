using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;

namespace GyroPhone.Windows;

/// <summary>
/// Diagnostics' delivery probe: how long each of GyroPhone's mouse moves takes from the start of its hand-off
/// (SendInput, or the input driver's DeviceIoControl) to Windows delivering it as raw input, the way a game reading raw
/// input gets it. So SendInput and the input driver compare end to end, not only by how long the call took.
/// <para>
/// Runs only while Diagnostics is on: its own thread with a hidden message-only window that reads raw mouse input
/// (RIDEV_INPUTSINK), read-only: nothing is held back or changed. The input path only stores the move's start time and
/// size in a preallocated ring (<see cref="Sent"/>: no lock, no allocation); this thread matches each raw input move from
/// GyroPhone (no device behind it: SendInput; or the driver's own mouse) to the oldest stored move of the same size.
/// Once a second the input log's second line gets
/// " delivery[driver] n 950 p50 0.12 p99 0.40 max 6.10 over2 3 unmatched 0" (ms; "over2": moves over 2 ms).
/// </para>
/// <para>
/// A process has one raw mouse input target: "Press a key" listening and the video latency test window take it while
/// they run, and the probe registers again when they give it up (<see cref="RawMouseReleased"/>).
/// </para>
/// </summary>
public sealed class DeliveryProbe : IDisposable
{
    private const int WmInput = 0x00FF, WmClose = 0x0010, WmTimer = 0x0113, WmRegister = 0x0400 + 0x47;
    private const uint RidInput = 0x10000003, RidevInputSink = 0x00000100, RidevRemove = 0x00000001;
    private const int RingSize = 4096;
    private static readonly IntPtr HwndMessage = new(-3);
    private static readonly long StaleTicks = Stopwatch.Frequency / 20; // 50 ms
    private static readonly long Over2Ticks = Stopwatch.Frequency / 500;

    private static volatile DeliveryProbe? s_current;

    private readonly Action<string>? _log;
    // The ring: written by the input path (one writer, under the engine lock), read by the probe thread.
    private readonly long[] _ticks = new long[RingSize];
    private readonly int[] _dx = new int[RingSize], _dy = new int[RingSize];
    private long _head, _tail;
    private long _overflow;

    // The probe thread's own numbers for the current second, and the last second's text.
    private readonly List<long> _lat = new(4096);
    private string? _text;
    private long _unmatched, _over2, _max;
    private bool _sawDriver, _sawSendInput;
    private readonly byte[] _buffer = new byte[128];

    private Thread? _thread;
    private IntPtr _hwnd;
    private WndProc? _proc;

    public DeliveryProbe(Action<string>? log) => _log = log;

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
    }

    /// <summary>A raw input reader gave the process's raw mouse input up: the running probe takes it again.</summary>
    public static void RawMouseReleased()
    {
        var p = s_current;
        if (p != null && p._hwnd != IntPtr.Zero) PostMessage(p._hwnd, WmRegister, IntPtr.Zero, IntPtr.Zero);
    }

    /// <summary>A move's hand-off started at Stopwatch <paramref name="ticks"/>. Input path: no lock, no allocation.</summary>
    public void Sent(long ticks, int dx, int dy)
    {
        long h = _head;
        if (h - Volatile.Read(ref _tail) >= RingSize)
        {
            Interlocked.Increment(ref _overflow);
            return;
        }
        int i = (int)(h & (RingSize - 1));
        _ticks[i] = ticks;
        _dx[i] = dx;
        _dy[i] = dy;
        Volatile.Write(ref _head, h + 1);
    }

    /// <summary>
    /// The last second's delivery numbers for the input log's second line, or null when no move was matched or missed.
    /// Made on the probe thread; this only hands the text over (once).
    /// </summary>
    public string? SecondText() => Interlocked.Exchange(ref _text, null);

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
            while (GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                if (msg.Message == WmClose && msg.Hwnd == hwnd) break;
                DispatchMessage(ref msg);
            }
            var remove = new RawInputDevice { UsagePage = 1, Usage = 2, Flags = RidevRemove, Target = IntPtr.Zero };
            RegisterRawInputDevices([remove], 1, (uint)Marshal.SizeOf<RawInputDevice>());
            DestroyWindow(hwnd);
            _hwnd = IntPtr.Zero;
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
        if (!RegisterRawInputDevices([d], 1, (uint)Marshal.SizeOf<RawInputDevice>()))
            _log?.Invoke($"delivery probe: raw mouse input not registered (error {Marshal.GetLastWin32Error()})");
    }

    private IntPtr WindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        try
        {
            if (msg == WmInput) OnInput(lParam);
            else if (msg == WmTimer) Roll();
            else if (msg == WmRegister) Register(hwnd);
        }
        catch (Exception)
        {
            // never let an exception unwind into the window procedure
        }
        return DefWindowProc(hwnd, msg, wParam, lParam);
    }

    private unsafe void OnInput(IntPtr lParam)
    {
        long now = Stopwatch.GetTimestamp();
        int header = 8 + 2 * IntPtr.Size;
        uint size = (uint)_buffer.Length;
        fixed (byte* p = _buffer)
        {
            if (GetRawInputData(lParam, RidInput, (IntPtr)p, ref size, (uint)header) == unchecked((uint)-1)) return;
        }
        if (BitConverter.ToUInt32(_buffer, 0) != 0) return; // mouse only
        IntPtr device = IntPtr.Size == 8 ? (IntPtr)BitConverter.ToInt64(_buffer, 8) : BitConverter.ToInt32(_buffer, 8);
        bool driver = device != IntPtr.Zero;
        if (driver && !OwnInputDevice.Is(device)) return; // the PC's own mouse
        // RAWMOUSE: usFlags u16, (pad) u16, ulButtons u32, ulRawButtons u32, lLastX i32, lLastY i32.
        if ((BitConverter.ToUInt16(_buffer, header) & 1) != 0) return; // absolute: not ours
        int x = BitConverter.ToInt32(_buffer, header + 12), y = BitConverter.ToInt32(_buffer, header + 16);
        if (x == 0 && y == 0) return;
        Match(now, x, y, driver);
    }

    private void Match(long now, int x, int y, bool driver)
    {
        long tail = _tail, head = Volatile.Read(ref _head);
        for (long k = tail; k < head; k++)
        {
            int i = (int)(k & (RingSize - 1));
            if (_dx[i] != x || _dy[i] != y) continue;
            // Raw input comes in the order sent: moves before this one never showed up (clipped at the screen's edge,
            // or sent before the probe started).
            Volatile.Write(ref _tail, k + 1);
            Record(now - _ticks[i], k - tail, driver);
            return;
        }
        // Not one of the stored moves: drop the ones too old to still come.
        long dropped = 0;
        while (tail < head && now - _ticks[(int)(tail & (RingSize - 1))] > StaleTicks)
        {
            tail++;
            dropped++;
        }
        Volatile.Write(ref _tail, tail);
        if (dropped > 0) Record(-1, dropped, driver);
    }

    private void Record(long lat, long unmatched, bool driver)
    {
        _unmatched += unmatched;
        if (lat < 0) return;
        _lat.Add(lat);
        if (lat > _max) _max = lat;
        if (lat > Over2Ticks) _over2++;
        if (driver) _sawDriver = true;
        else _sawSendInput = true;
    }

    /// <summary>Each second on the probe thread: the second's numbers become the text <see cref="SecondText"/> hands out.</summary>
    private void Roll()
    {
        if (_lat.Count == 0 && _unmatched == 0)
        {
            _text = null;
            return;
        }
        _lat.Sort();
        double Ms(long t) => t * 1000.0 / Stopwatch.Frequency;
        int n = _lat.Count;
        double p50 = n > 0 ? Ms(_lat[n / 2]) : 0, p99 = n > 0 ? Ms(_lat[Math.Min(n - 1, n * 99 / 100)]) : 0;
        string route = _sawDriver && _sawSendInput ? "both" : _sawDriver ? "driver" : "SendInput";
        long over = Interlocked.Exchange(ref _overflow, 0);
        _text = string.Create(CultureInfo.InvariantCulture,
            $" delivery[{route}] n {n} p50 {p50:0.00} p99 {p99:0.00} max {Ms(_max):0.00} over2 {_over2} unmatched {_unmatched}{(over > 0 ? $" full {over}" : "")}");
        _lat.Clear();
        _unmatched = _over2 = _max = 0;
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

    [DllImport("user32.dll")]
    private static extern uint GetRawInputData(IntPtr rawInput, uint command, IntPtr data, ref uint size, uint headerSize);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassEx(ref WndClassEx cls);

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
}
