using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shapes;

namespace GyroPhone.Ui;

/// <summary>
/// The latency test window: black, with one tall green bar that moves left and right by the mouse
/// movement GyroPhone injects (Windows raw input with no device: SendInput), so it follows what a
/// game gets. A real mouse is ignored. While the phone twists, the bar follows the mouse exactly
/// (1:1: a fixed middle and scale), since any re-centring or squeezing would shift the bar's
/// motion in time and skew the result. The middle and scale are set again only between takes,
/// when the mouse has rested for <see cref="RestS"/>: the bar jumps back to the middle and the
/// next take's swing fills the window. Where the bar would leave the window it is hidden rather
/// than held at the edge, so the analyser leaves those frames out. While it is open the phone
/// shows its markers.
/// </summary>
internal sealed class LatencyTestWindow : Window
{
    private const int WmInput = 0x00FF;
    private const uint RidInput = 0x10000003;
    private const uint RidevInputSink = 0x00000100, RidevRemove = 0x00000001;

    private readonly Canvas _canvas = new() { Background = Brushes.Black, ClipToBounds = true };
    private readonly Rectangle _bar = new() { Fill = new SolidColorBrush(Color.FromRgb(0x2C, 0xFF, 0x8A)) };
    private readonly TextBlock _hint;
    private HwndSource? _source;
    private readonly byte[] _buffer = new byte[64];

    /// <summary>The mouse resting this long ends a take: the middle and scale are set again.</summary>
    private const double RestS = 0.4;

    // Where the injected mouse has gone (counts), the middle it swings about, and how far a swing
    // goes (counts to 40% of the width), kept between windows. Within a take: the take's biggest swing.
    private double _x, _mid;
    private static double s_swing = 200;
    private double _takeSwing;
    private bool _inTake;
    // Where the mouse last moved noticeably, and when.
    private double _restX;
    private long _restTicks = Environment.TickCount64;
    private readonly System.Windows.Threading.DispatcherTimer _rest = new() { Interval = TimeSpan.FromMilliseconds(50) };

    /// <summary>The refresh rate of the screen this window was last on (Hz), 0 before it was shown.</summary>
    public static int LastMonitorHz { get; private set; }

    public LatencyTestWindow(ImageSource? icon)
    {
        Title = "GyroPhone latency test";
        Icon = icon;
        Width = 1100;
        Height = 700;
        Background = Brushes.Black;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        _canvas.Children.Add(_bar);
        _hint = new TextBlock
        {
            Text = "Hold the phone in front of this window, screen toward you, and twist your wrist left and right. Film from behind your hand, along the forearm. If the bar disappears, rest a moment and it comes back. Esc closes.",
            Foreground = new SolidColorBrush(Color.FromRgb(0x6B, 0x73, 0x85)),
            FontSize = 14,
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
        };
        _canvas.Children.Add(_hint);
        Content = _canvas;
        SizeChanged += (_, _) => Place();
        KeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) Close();
        };
        SourceInitialized += (_, _) =>
        {
            Theme.TitleBar(this);
            _source = HwndSource.FromHwnd(new WindowInteropHelper(this).Handle);
            _source.AddHook(Hook);
            Register(_source.Handle, RidevInputSink);
            LastMonitorHz = MonitorHz(_source.Handle);
        };
        LocationChanged += (_, _) =>
        {
            if (_source != null) LastMonitorHz = MonitorHz(_source.Handle);
        };
        _rest.Tick += (_, _) => EndTakeWhenRested();
        _rest.Start();
        Closed += (_, _) =>
        {
            _rest.Stop();
            Register(IntPtr.Zero, RidevRemove);
            _source?.RemoveHook(Hook);
        };
        Place();
    }

    private IntPtr Hook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg != WmInput) return IntPtr.Zero;
        uint size = (uint)_buffer.Length;
        int header = Marshal.SizeOf<RawInputHeader>();
        var h = GCHandle.Alloc(_buffer, GCHandleType.Pinned);
        try
        {
            if (GetRawInputData(lParam, RidInput, h.AddrOfPinnedObject(), ref size, (uint)header) == unchecked((uint)-1)) return IntPtr.Zero;
            var hdr = Marshal.PtrToStructure<RawInputHeader>(h.AddrOfPinnedObject());
            // Mouse (type 0), from SendInput (no device), relative.
            if (hdr.Type != 0 || hdr.Device != IntPtr.Zero) return IntPtr.Zero;
            ushort flags = BitConverter.ToUInt16(_buffer, header);
            if ((flags & 1) != 0) return IntPtr.Zero;
            int dx = BitConverter.ToInt32(_buffer, header + 12);
            if (dx == 0) return IntPtr.Zero;
            _x += dx;
            Place();
        }
        finally
        {
            h.Free();
        }
        return IntPtr.Zero;
    }

    /// <summary>
    /// Ends a take once the mouse has rested for <see cref="RestS"/> (moved under 5% of a swing): the
    /// bar goes back to the middle and the next take's scale fits this take's biggest swing.
    /// </summary>
    private void EndTakeWhenRested()
    {
        long now = Environment.TickCount64;
        if (Math.Abs(_x - _restX) > s_swing * 0.05)
        {
            (_restX, _restTicks) = (_x, now);
            return;
        }
        if (!_inTake || now - _restTicks < RestS * 1000) return;
        _inTake = false;
        if (_takeSwing > 20) s_swing = _takeSwing;
        _takeSwing = 0;
        _mid = _x;
        Place();
    }

    /// <summary>Moves the bar 1:1 with the mouse; hidden where it would leave the window.</summary>
    private void Place()
    {
        double w = _canvas.ActualWidth > 0 ? _canvas.ActualWidth : Width;
        double hgt = _canvas.ActualHeight > 0 ? _canvas.ActualHeight : Height;
        double off = _x - _mid;
        if (Math.Abs(off) > s_swing * 0.05) _inTake = true;
        _takeSwing = Math.Max(_takeSwing, Math.Abs(off));
        double barW = Math.Max(24, w / 30);
        double x = w / 2 + off / s_swing * (w * 0.4) - barW / 2;
        _bar.Width = barW;
        _bar.Height = hgt;
        _bar.Visibility = x < 0 || x > w - barW ? Visibility.Hidden : Visibility.Visible;
        Canvas.SetLeft(_bar, Math.Clamp(x, 0, w - barW));
        Canvas.SetTop(_bar, 0);
        _hint.Width = w - 40;
        Canvas.SetLeft(_hint, 20);
        Canvas.SetTop(_hint, hgt - 60);
    }

    private static void Register(IntPtr hwnd, uint flags)
    {
        var d = new RawInputDevice { UsagePage = 1, Usage = 2, Flags = flags, Target = hwnd };
        RegisterRawInputDevices([d], 1, (uint)Marshal.SizeOf<RawInputDevice>());
    }

    private static int MonitorHz(IntPtr hwnd)
    {
        var mon = MonitorFromWindow(hwnd, 2);
        var info = new MonitorInfoEx { Size = Marshal.SizeOf<MonitorInfoEx>() };
        if (!GetMonitorInfo(mon, ref info)) return 0;
        var mode = new DevMode { Size = (short)Marshal.SizeOf<DevMode>() };
        return EnumDisplaySettings(info.Device, -1, ref mode) ? mode.DisplayFrequency : 0;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RawInputDevice
    {
        public ushort UsagePage;
        public ushort Usage;
        public uint Flags;
        public IntPtr Target;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RawInputHeader
    {
        public uint Type;
        public uint Size;
        public IntPtr Device;
        public IntPtr WParam;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct MonitorInfoEx
    {
        public int Size;
        public int L1, T1, R1, B1, L2, T2, R2, B2;
        public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Device;
    }

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

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterRawInputDevices(RawInputDevice[] devices, uint count, uint size);

    [DllImport("user32.dll")]
    private static extern uint GetRawInputData(IntPtr rawInput, uint command, IntPtr data, ref uint size, uint headerSize);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfoEx info);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool EnumDisplaySettings(string device, int mode, ref DevMode devMode);
}
