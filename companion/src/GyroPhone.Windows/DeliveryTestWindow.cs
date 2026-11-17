using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using GyroPhone.Host;

namespace GyroPhone.Windows;

/// <summary>
/// Performance › Delivery test: a plain window that reads raw mouse input the way a game does (a normal reader in
/// front: no RIDEV_INPUTSINK, so Windows hands it every report, never merged or held back for being in the background),
/// and times each of GyroPhone's moves from the start of its hand-off in the input process (<see cref="MoveRing"/> in
/// the shared memory) to this window reading it. Next to it, Diagnostics' delivery probe's numbers for the same moves
/// (a background reader), so a slow move the probe saw can be told apart from one a game would see.
/// <para>
/// Its own thread at normal priority with a plain Win32 window (no WPF or WinForms, so nothing else on the thread delays
/// reading). Its moves count only while it is in front: clicking elsewhere pauses it. Runs only while it is open.
/// </para>
/// </summary>
public sealed class DeliveryTestWindow
{
    private const int WmDestroy = 0x0002, WmActivate = 0x0006, WmPaint = 0x000F, WmClose = 0x0010, WmInput = 0x00FF,
        WmTimer = 0x0113, WmDpiChanged = 0x02E0;
    private const uint RidevRemove = 0x00000001;
    private const uint WsOverlappedWindow = 0x00CF0000, WsVisible = 0x10000000;
    private const int CwUseDefault = unchecked((int)0x80000000);
    private const uint DtWordBreak = 0x10, DtCalcRect = 0x400, DtNoPrefix = 0x800, DtSingleLine = 0x20;

    private readonly MoveRing _ring;
    private readonly Func<DeliveryNumbers?> _probe;
    private readonly Action<bool> _front;
    private readonly Action<string> _closed;
    private readonly DeliveryMatcher _matcher;
    private readonly DeliveryTally _tally = new(16_384);
    private readonly RawMouse _raw = new();
    private bool _counting;
    private long _countedTicks, _countingSince;
    private DeliveryNumbers? _mine, _theirs;

    private Thread? _thread;
    private volatile IntPtr _hwnd;
    private WndProc? _proc;
    private IntPtr _titleFont, _bodyFont;
    private int _dpi = 96;

    /// <param name="ring">The moves the input process hands off while the test runs.</param>
    /// <param name="probe">The delivery probe's numbers for the test so far (cached: never waits).</param>
    /// <param name="front">The window came in front (its moves count) or went behind (paused).</param>
    /// <param name="closed">The window closed, with a one-line summary of both readers.</param>
    public DeliveryTestWindow(MoveRing ring, Func<DeliveryNumbers?> probe, Action<bool> front, Action<string> closed)
    {
        _ring = ring;
        _probe = probe;
        _front = front;
        _closed = closed;
        _matcher = new DeliveryMatcher(ring, d => _tally.Add(d), n => _tally.Missed(n), () => _tally.Stray());
    }

    public bool IsOpen => _thread != null && _hwnd != IntPtr.Zero;

    public void Show()
    {
        if (_thread != null)
        {
            var h = _hwnd;
            if (h != IntPtr.Zero) SetForegroundWindow(h);
            return;
        }
        _thread = new Thread(Run) { IsBackground = true, Name = "gp-delivery-test" };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
    }

    /// <summary>Closes it (any thread).</summary>
    public void Close()
    {
        var h = _hwnd;
        if (h != IntPtr.Zero) PostMessage(h, WmClose, IntPtr.Zero, IntPtr.Zero);
    }

    private void Run()
    {
        string summary = "delivery test: no window";
        try
        {
            _proc = WindowProc;
            var cls = new WndClassEx
            {
                Size = (uint)Marshal.SizeOf<WndClassEx>(),
                WndProc = Marshal.GetFunctionPointerForDelegate(_proc),
                Instance = GetModuleHandle(null),
                Cursor = LoadCursor(IntPtr.Zero, 32512), // IDC_ARROW
                ClassName = "GyroPhoneDeliveryTest",
            };
            RegisterClassEx(ref cls);
            _dpi = (int)GetDpiForSystem();
            var hwnd = CreateWindowEx(0, cls.ClassName, "GyroPhone delivery test", WsOverlappedWindow | WsVisible,
                CwUseDefault, CwUseDefault, Px(680), Px(340), IntPtr.Zero, IntPtr.Zero, cls.Instance, IntPtr.Zero);
            if (hwnd == IntPtr.Zero) return;
            _hwnd = hwnd;
            _dpi = (int)GetDpiForWindow(hwnd);
            MakeFonts();
            // A normal reader: raw mouse input only while this window is in front, every report, like a game.
            var d = new RawInputDevice { UsagePage = 1, Usage = 2, Flags = 0, Target = hwnd };
            RegisterRawInputDevices([d], 1, (uint)Marshal.SizeOf<RawInputDevice>());
            SetTimer(hwnd, (UIntPtr)1, 500, IntPtr.Zero);
            SetForegroundWindow(hwnd);
            while (GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                TranslateMessage(ref msg);
                DispatchMessage(ref msg);
            }
            summary = Summary();
            UnregisterClass(cls.ClassName, cls.Instance);
        }
        catch (Exception e)
        {
            summary = $"delivery test stopped: {e.GetType().Name}: {e.Message}";
        }
        finally
        {
            _hwnd = IntPtr.Zero;
            if (_titleFont != IntPtr.Zero) DeleteObject(_titleFont);
            if (_bodyFont != IntPtr.Zero) DeleteObject(_bodyFont);
            _thread = null;
            _closed(summary);
        }
    }

    private int Px(int dip) => dip * _dpi / 96;

    private void MakeFonts()
    {
        if (_titleFont != IntPtr.Zero) DeleteObject(_titleFont);
        if (_bodyFont != IntPtr.Zero) DeleteObject(_bodyFont);
        _titleFont = CreateFont(-Px(22), 0, 0, 0, 600, 0, 0, 0, 1, 0, 0, 5, 0, "Segoe UI");
        _bodyFont = CreateFont(-Px(15), 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, "Segoe UI");
    }

    private IntPtr WindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        try
        {
            switch (msg)
            {
                case WmInput:
                {
                    long now = Stopwatch.GetTimestamp();
                    if (_counting && _raw.Read(lParam, out int x, out int y, out bool driver)) _matcher.Raw(now, x, y, driver);
                    break;
                }
                case WmTimer:
                    if (_counting) _matcher.Settle(Stopwatch.GetTimestamp());
                    else _matcher.Skip();
                    _mine = _tally.Numbers();
                    _theirs = _probe();
                    InvalidateRect(hwnd, IntPtr.Zero, false);
                    break;
                case WmActivate:
                {
                    bool on = (wParam.ToInt64() & 0xFFFF) != 0;
                    if (on == _counting) break;
                    long now = Stopwatch.GetTimestamp();
                    if (on)
                    {
                        _matcher.Skip(); // moves made while behind were never this window's
                        _countingSince = now;
                    }
                    else _countedTicks += now - _countingSince;
                    _counting = on;
                    _front(on);
                    InvalidateRect(hwnd, IntPtr.Zero, false);
                    break;
                }
                case WmPaint:
                    Paint(hwnd);
                    return IntPtr.Zero;
                case WmDpiChanged:
                {
                    _dpi = (int)(wParam.ToInt64() & 0xFFFF);
                    MakeFonts();
                    var r = Marshal.PtrToStructure<Rect>(lParam);
                    SetWindowPos(hwnd, IntPtr.Zero, r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top, 0x0014); // NOZORDER|NOACTIVATE
                    return IntPtr.Zero;
                }
                case WmDestroy:
                {
                    if (_counting) _countedTicks += Stopwatch.GetTimestamp() - _countingSince;
                    _counting = false;
                    _matcher.Settle(Stopwatch.GetTimestamp());
                    _mine = _tally.Numbers();
                    _theirs = _probe();
                    var remove = new RawInputDevice { UsagePage = 1, Usage = 2, Flags = RidevRemove, Target = IntPtr.Zero };
                    RegisterRawInputDevices([remove], 1, (uint)Marshal.SizeOf<RawInputDevice>());
                    PostQuitMessage(0);
                    break;
                }
            }
        }
        catch (Exception)
        {
            // never let an exception unwind into the window procedure
        }
        return DefWindowProc(hwnd, msg, wParam, lParam);
    }

    private TimeSpan Counted()
    {
        long t = _countedTicks + (_counting ? Stopwatch.GetTimestamp() - _countingSince : 0);
        return TimeSpan.FromSeconds(t / (double)Stopwatch.Frequency);
    }

    private static string Line(DeliveryNumbers? n) => n == null || n.N == 0 && n.Unmatched == 0
        ? "no moves yet"
        : string.Create(CultureInfo.InvariantCulture,
            $"{n.SlowPer1000:0.0} slow per 1000 ({n.Slow} of {n.N}) · p99 {n.P99:0.00} ms · max {n.Max:0.00} ms{(n.Unmatched > 0 ? $" · {n.Unmatched} never came" : "")}");

    private string Summary()
    {
        var t = Counted();
        return $"delivery test {(int)t.TotalMinutes}:{t.Seconds:00} in front: front reader {Line(_mine)}; background probe {Line(_theirs)}";
    }

    private void Paint(IntPtr hwnd)
    {
        var dc = BeginPaint(hwnd, out var ps);
        try
        {
            GetClientRect(hwnd, out var client);
            var bg = CreateSolidBrush(0x202020);
            FillRect(dc, ref client, bg);
            DeleteObject(bg);
            SetBkMode(dc, 1); // transparent
            int pad = Px(20), y = pad, width = client.Right - pad;
            var old = SelectObject(dc, _titleFont);
            SetTextColor(dc, 0xFFFFFF);
            y += Draw(dc, "Delivery test", pad, y, width, DtSingleLine) + Px(8);
            SelectObject(dc, _bodyFont);
            SetTextColor(dc, 0xC8C8C8);
            y += Draw(dc, "Wave the phone so the mouse moves, for about a minute. Keep this window in front: clicking " +
                "anywhere else pauses it. Slow: over 2 ms from GyroPhone handing a move to Windows to the reader getting it.",
                pad, y, width, DtWordBreak) + Px(16);
            int col = pad + Px(200);
            SetTextColor(dc, 0xFFFFFF);
            Draw(dc, "In front, like a game", pad, y, col - Px(8), DtSingleLine);
            y += Draw(dc, Line(_mine), col, y, width, DtWordBreak) + Px(6);
            Draw(dc, "Background probe", pad, y, col - Px(8), DtSingleLine);
            y += Draw(dc, Line(_theirs) + (_theirs == null ? " (starting)" : ""), col, y, width, DtWordBreak) + Px(16);
            var t = Counted();
            SetTextColor(dc, _counting ? 0xC8C8C8 : 0x40B0FF);
            Draw(dc, $"{(int)t.TotalMinutes}:{t.Seconds:00} counted" + (_counting ? "" : " · paused: this window isn't in front"),
                pad, y, width, DtSingleLine);
            SelectObject(dc, old);
        }
        finally
        {
            EndPaint(hwnd, ref ps);
        }
    }

    /// <summary>Draws text at x, y within width; its height.</summary>
    private static int Draw(IntPtr dc, string text, int x, int y, int right, uint flags)
    {
        var r = new Rect { Left = x, Top = y, Right = right, Bottom = y + 10_000 };
        DrawText(dc, text, text.Length, ref r, flags | DtNoPrefix | DtCalcRect);
        int h = r.Bottom - r.Top;
        r = new Rect { Left = x, Top = y, Right = right, Bottom = y + h };
        DrawText(dc, text, text.Length, ref r, flags | DtNoPrefix);
        return h;
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

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect
    {
        public int Left, Top, Right, Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PaintStruct
    {
        public IntPtr Hdc;
        public int Erase;
        public Rect Paint;
        public int Restore;
        public int IncUpdate;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 32)]
        public byte[] Reserved;
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
    private static extern IntPtr LoadCursor(IntPtr instance, int name);

    [DllImport("user32.dll")]
    private static extern UIntPtr SetTimer(IntPtr hwnd, UIntPtr id, uint ms, IntPtr proc);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern int GetMessage(out Msg msg, IntPtr hwnd, uint min, uint max);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref Msg msg);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage(ref Msg msg);

    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern void PostQuitMessage(int code);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);

    [DllImport("user32.dll")]
    private static extern bool InvalidateRect(IntPtr hwnd, IntPtr rect, bool erase);

    [DllImport("user32.dll")]
    private static extern bool GetClientRect(IntPtr hwnd, out Rect rect);

    [DllImport("user32.dll")]
    private static extern uint GetDpiForSystem();

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern IntPtr BeginPaint(IntPtr hwnd, out PaintStruct ps);

    [DllImport("user32.dll")]
    private static extern bool EndPaint(IntPtr hwnd, ref PaintStruct ps);

    [DllImport("user32.dll")]
    private static extern int FillRect(IntPtr dc, ref Rect rect, IntPtr brush);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int DrawText(IntPtr dc, string text, int count, ref Rect rect, uint format);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateSolidBrush(int color);

    [DllImport("gdi32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateFont(int height, int width, int escapement, int orientation, int weight, uint italic,
        uint underline, uint strikeOut, uint charSet, uint outPrecision, uint clipPrecision, uint quality, uint pitchAndFamily, string face);

    [DllImport("gdi32.dll")]
    private static extern IntPtr SelectObject(IntPtr dc, IntPtr obj);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr obj);

    [DllImport("gdi32.dll")]
    private static extern int SetBkMode(IntPtr dc, int mode);

    [DllImport("gdi32.dll")]
    private static extern int SetTextColor(IntPtr dc, int color);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? name);
}
