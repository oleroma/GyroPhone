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
/// game gets. A real mouse is ignored. The bar's travel is scaled to fit by itself, slowly, so a
/// twist of any size fills the window without the scale changing within a twist. While it is open
/// the phone shows its markers.
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

    // Where the injected mouse has gone (counts), the middle it swings about and how far it swings.
    private double _x, _mid, _swing = 200;
    private long _lastTicks = Environment.TickCount64;

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
            Text = "Hold the phone in front of this window, screen toward you, and twist your wrist left and right. Esc closes.",
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
        Closed += (_, _) =>
        {
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

    /// <summary>Moves the bar: the middle and the swing follow the motion slowly (seconds), the bar at once.</summary>
    private void Place()
    {
        double w = _canvas.ActualWidth > 0 ? _canvas.ActualWidth : Width;
        double hgt = _canvas.ActualHeight > 0 ? _canvas.ActualHeight : Height;
        long now = Environment.TickCount64;
        double dt = Math.Clamp((now - _lastTicks) / 1000.0, 0, 0.5);
        _lastTicks = now;
        _mid += (_x - _mid) * Math.Min(1, dt / 1.5);
        double off = _x - _mid;
        _swing = Math.Max(Math.Abs(off), _swing - _swing * Math.Min(1, dt / 4));
        _swing = Math.Max(_swing, 20);
        double barW = Math.Max(24, w / 30);
        double x = w / 2 + off / _swing * (w * 0.4) - barW / 2;
        _bar.Width = barW;
        _bar.Height = hgt;
        Canvas.SetLeft(_bar, Math.Clamp(x, 0, w - barW));
        Canvas.SetTop(_bar, 0);
        _hint.Width = w - 40;
        Canvas.SetLeft(_hint, 20);
        Canvas.SetTop(_hint, hgt - 40);
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
