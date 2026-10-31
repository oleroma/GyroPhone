using System.Text;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host;

/// <summary>
/// The companion's debug log: what was injected (keys, mouse, the virtual pad), link lines and
/// session events, in a ring of the newest events. Logs only while <see cref="Enabled"/> (the
/// Debug window is open, or the phone's debug tools asked for it within the last few seconds),
/// so the input path costs one bool check otherwise. Thread-safe.
/// </summary>
public sealed class DebugTrace
{
    /// <summary>Move: stick and trigger moves and summed mouse motion, which the window can hide.</summary>
    public enum Kind { In, Out, Move, State, Link, Note }

    public readonly record struct Event(long Us, Kind Kind, string Text);

    public const long RecordUs = 30_000_000;
    private const long AxisEveryUs = 100_000;
    private const long MotionEveryUs = 100_000;
    private const int AxisStepPct = 5;
    private const long PhoneAskHoldUs = 5_000_000;

    private readonly object _lock = new();
    private readonly Event[] _ring;
    private int _head, _count;
    private readonly Func<long> _nowUs;

    private bool _windowOpen;
    private long _phoneAskedUntilUs = long.MinValue;

    public DebugTrace(Func<long> nowUs, int capacity = 16_384)
    {
        _nowUs = nowUs;
        _ring = new Event[capacity];
    }

    /// <summary>Bumped on every event, so a view redraws only when something happened.</summary>
    public long Version { get; private set; }

    /// <summary>Logging now: the Debug window is open or the phone asked within the last 5 s.</summary>
    public bool Enabled => _windowOpen || _nowUs() < Volatile.Read(ref _phoneAskedUntilUs);

    /// <summary>The Debug window opened or closed.</summary>
    public void SetWindowOpen(bool open)
    {
        _windowOpen = open;
        if (open) Add(Kind.Note, "debug window opened");
    }

    /// <summary>A LINK_INFO with the phone's debug flag: keep logging for the next few seconds.</summary>
    public void PhoneAsked(long nowUs)
    {
        if (nowUs >= Volatile.Read(ref _phoneAskedUntilUs)) Add(Kind.Note, "the phone's debug tools are on", nowUs);
        Volatile.Write(ref _phoneAskedUntilUs, nowUs + PhoneAskHoldUs);
    }

    public void Add(Kind kind, string text, long us = long.MinValue)
    {
        if (us == long.MinValue) us = _nowUs();
        lock (_lock)
        {
            _ring[_head] = new Event(us, kind, text);
            _head = (_head + 1) % _ring.Length;
            if (_count < _ring.Length) _count++;
            Version++;
        }
    }

    /// <summary>The newest <paramref name="max"/> events, oldest first.</summary>
    public List<Event> Latest(int max)
    {
        lock (_lock)
        {
            int n = Math.Min(max, _count);
            var list = new List<Event>(n);
            for (int i = 0; i < n; i++) list.Add(_ring[Mod(_head - n + i, _ring.Length)]);
            return list;
        }
    }

    /// <summary>The newest <paramref name="max"/> events whose kind <paramref name="shown"/> accepts, oldest first.</summary>
    public List<Event> Latest(int max, Func<Kind, bool> shown)
    {
        lock (_lock)
        {
            var list = new List<Event>(max);
            for (int i = 0; i < _count && list.Count < max; i++)
            {
                var e = _ring[Mod(_head - 1 - i, _ring.Length)];
                if (shown(e.Kind)) list.Add(e);
            }
            list.Reverse();
            return list;
        }
    }

    private List<Event> Since(long fromUs)
    {
        lock (_lock)
        {
            var list = new List<Event>();
            for (int i = 0; i < _count; i++)
            {
                var e = _ring[Mod(_head - _count + i, _ring.Length)];
                if (e.Us >= fromUs) list.Add(e);
            }
            return list;
        }
    }

    private static int Mod(int a, int m) => ((a % m) + m) % m;

    // ---- output ----------------------------------------------------------------------------

    private int _dx, _dy, _wheel, _hwheel;
    private long _motionSinceUs;
    private X360Frame _lastX360;
    private Ds4Frame _lastDs4 = Ds4Frame.Neutral;
    private readonly int[] _shownAxis = new int[6];
    private readonly long[] _axisAtUs = new long[6];

    /// <summary>The keyboard and mouse events one flush injected.</summary>
    public void OnInject(ReadOnlySpan<InjectEvent> events, long nowUs)
    {
        foreach (var e in events)
        {
            if (e.Kind == InjectKind.Key)
            {
                bool up = e.IsKeyUp;
                Add(Kind.Out, $"key {UsageName(e.Usage)} {(up ? "up" : "down")}", nowUs);
                continue;
            }
            uint f = e.Flags;
            if ((f & InjectEvent.MouseMove) != 0)
            {
                if (_dx == 0 && _dy == 0 && _wheel == 0 && _hwheel == 0) _motionSinceUs = nowUs;
                _dx += e.Dx; _dy += e.Dy;
            }
            if ((f & InjectEvent.MouseWheel) != 0) { if (IsIdle()) _motionSinceUs = nowUs; _wheel += e.MouseData; }
            if ((f & InjectEvent.MouseHWheel) != 0) { if (IsIdle()) _motionSinceUs = nowUs; _hwheel += e.MouseData; }
            string? b = MouseButtonText(e);
            if (b != null) Add(Kind.Out, "mouse " + b, nowUs);
        }
        if (!IsIdle() && nowUs - _motionSinceUs >= MotionEveryUs) FlushMotion(nowUs);
    }

    private bool IsIdle() => _dx == 0 && _dy == 0 && _wheel == 0 && _hwheel == 0;

    private void FlushMotion(long nowUs)
    {
        var parts = new List<string>(3);
        if (_dx != 0 || _dy != 0) parts.Add($"move {_dx},{_dy}");
        if (_wheel != 0) parts.Add($"wheel {_wheel / 120.0:0.#}");
        if (_hwheel != 0) parts.Add($"pan {_hwheel / 120.0:0.#}");
        Add(Kind.Move, $"mouse {string.Join(", ", parts)} in {(nowUs - _motionSinceUs) / 1000} ms", nowUs);
        _dx = _dy = _wheel = _hwheel = 0;
    }

    private static string? MouseButtonText(in InjectEvent e) => e.Flags switch
    {
        var f when (f & InjectEvent.MouseLeftDown) != 0 => "left down",
        var f when (f & InjectEvent.MouseLeftUp) != 0 => "left up",
        var f when (f & InjectEvent.MouseRightDown) != 0 => "right down",
        var f when (f & InjectEvent.MouseRightUp) != 0 => "right up",
        var f when (f & InjectEvent.MouseMiddleDown) != 0 => "middle down",
        var f when (f & InjectEvent.MouseMiddleUp) != 0 => "middle up",
        var f when (f & InjectEvent.MouseXDown) != 0 => (e.MouseData == InjectEvent.XButton1 ? "back" : "forward") + " down",
        var f when (f & InjectEvent.MouseXUp) != 0 => (e.MouseData == InjectEvent.XButton1 ? "back" : "forward") + " up",
        _ => null,
    };

    private static readonly (ushort Bit, string Name)[] XusbNames =
    [
        (XusbButtons.A, "A"), (XusbButtons.B, "B"), (XusbButtons.X, "X"), (XusbButtons.Y, "Y"),
        (XusbButtons.LeftShoulder, "LB"), (XusbButtons.RightShoulder, "RB"), (XusbButtons.Back, "View"),
        (XusbButtons.Start, "Menu"), (XusbButtons.LeftThumb, "LS"), (XusbButtons.RightThumb, "RS"),
        (XusbButtons.Guide, "Guide"), (XusbButtons.DpadUp, "Up"), (XusbButtons.DpadDown, "Down"),
        (XusbButtons.DpadLeft, "Left"), (XusbButtons.DpadRight, "Right"),
    ];

    /// <summary>What the virtual pad was last given, for the Debug window.</summary>
    public string CurrentPad { get; private set; } = "nothing yet";

    /// <summary>The virtual Xbox 360 pad was given <paramref name="f"/>.</summary>
    public void OnX360(in X360Frame f, long nowUs)
    {
        var held = new List<string>();
        foreach (var (bit, name) in XusbNames) if ((f.Buttons & bit) != 0) held.Add(name);
        CurrentPad = $"xbox {(held.Count == 0 ? "no buttons" : string.Join(" ", held))} · L {f.Lx * 100 / 32767},{f.Ly * 100 / 32767}%" +
            $" · R {f.Rx * 100 / 32767},{f.Ry * 100 / 32767}% · LT {f.Lt * 100 / 255}% · RT {f.Rt * 100 / 255}%";
        ushort changed = (ushort)(f.Buttons ^ _lastX360.Buttons);
        foreach (var (bit, name) in XusbNames)
            if ((changed & bit) != 0) Add(Kind.Out, $"xbox {name} {((f.Buttons & bit) != 0 ? "down" : "up")}", nowUs);
        Axis(0, "LX", f.Lx * 100 / 32767, nowUs);
        Axis(1, "LY", f.Ly * 100 / 32767, nowUs);
        Axis(2, "RX", f.Rx * 100 / 32767, nowUs);
        Axis(3, "RY", f.Ry * 100 / 32767, nowUs);
        Axis(4, "LT", f.Lt * 100 / 255, nowUs);
        Axis(5, "RT", f.Rt * 100 / 255, nowUs);
        _lastX360 = f;
    }

    /// <summary>The virtual DualShock 4 was given <paramref name="f"/>.</summary>
    public void OnDs4(in Ds4Frame f, long nowUs)
    {
        CurrentPad = $"ds4 buttons 0x{f.Buttons:X4} hat {f.Hat} · L {f.Lx},{f.Ly} · R {f.Rx},{f.Ry} · LT {f.Lt} · RT {f.Rt}";
        if (f.Buttons != _lastDs4.Buttons || f.Special != _lastDs4.Special || f.Hat != _lastDs4.Hat)
            Add(Kind.Out, $"ds4 buttons 0x{f.Buttons:X4} special {f.Special} hat {f.Hat}", nowUs);
        Axis(0, "LX", (f.Lx - 128) * 100 / 127, nowUs);
        Axis(1, "LY", (128 - f.Ly) * 100 / 127, nowUs);
        Axis(2, "RX", (f.Rx - 128) * 100 / 127, nowUs);
        Axis(3, "RY", (128 - f.Ry) * 100 / 127, nowUs);
        Axis(4, "LT", f.Lt * 100 / 255, nowUs);
        Axis(5, "RT", f.Rt * 100 / 255, nowUs);
        _lastDs4 = f;
    }

    private void Axis(int i, string name, int pct, long nowUs)
    {
        if (pct == _shownAxis[i]) return;
        bool big = Math.Abs(pct - _shownAxis[i]) >= AxisStepPct || pct == 0 || Math.Abs(pct) == 100;
        if (!big || (nowUs - _axisAtUs[i] < AxisEveryUs && pct != 0)) return;
        _shownAxis[i] = pct;
        _axisAtUs[i] = nowUs;
        Add(Kind.Move, $"pad {name} {pct}%", nowUs);
    }

    /// <summary>Once a second: flushes summed motion and logs <paramref name="linkLine"/>.</summary>
    public void SecondTick(string? linkLine, long nowUs)
    {
        if (!IsIdle()) FlushMotion(nowUs);
        if (linkLine != null) Add(Kind.Link, linkLine, nowUs);
    }

    // ---- saving ----------------------------------------------------------------------------

    /// <summary>
    /// The last 30 s as text: '#' header lines, then <c>wall ms \t pc \t kind \t text</c> per event,
    /// the same format as the phone's recordings so both sort together.
    /// </summary>
    public string Save(IEnumerable<string> header)
    {
        long nowUs = _nowUs();
        long wallMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var sb = new StringBuilder();
        sb.Append("# GyroPhone debug recording, side pc\n");
        sb.Append("# saved at wall ms ").Append(wallMs).Append('\n');
        foreach (var h in header) sb.Append("# ").Append(h.Replace('\n', ' ')).Append('\n');
        sb.Append("# clock-pair: wall_ms=").Append(wallMs).Append(" host_us=").Append(nowUs).Append('\n');
        foreach (var e in Since(nowUs - RecordUs))
        {
            long ms = wallMs - (nowUs - e.Us) / 1000;
            sb.Append(ms).Append("\tpc\t").Append(Tag(e.Kind)).Append('\t').Append(e.Text).Append('\n');
        }
        return sb.ToString();
    }

    public static string Tag(Kind k) => k switch
    {
        Kind.In => "in",
        Kind.Out => "out",
        Kind.Move => "move",
        Kind.State => "state",
        Kind.Link => "link",
        _ => "note",
    };

    private static readonly Dictionary<byte, string> Names = BuildNames();

    private static Dictionary<byte, string> BuildNames()
    {
        var d = new Dictionary<byte, string>();
        for (int i = 0; i < 26; i++) d[(byte)(0x04 + i)] = ((char)('A' + i)).ToString();
        const string digits = "1234567890";
        for (int i = 0; i < 10; i++) d[(byte)(0x1E + i)] = digits[i].ToString();
        d[0x28] = "Enter"; d[0x29] = "Esc"; d[0x2A] = "Backspace"; d[0x2B] = "Tab"; d[0x2C] = "Space";
        for (int i = 1; i <= 12; i++) d[(byte)(0x3A + i - 1)] = "F" + i;
        d[0x4F] = "Right"; d[0x50] = "Left"; d[0x51] = "Down"; d[0x52] = "Up";
        d[0xE0] = "Left Ctrl"; d[0xE1] = "Left Shift"; d[0xE2] = "Left Alt"; d[0xE3] = "Left Win";
        d[0xE4] = "Right Ctrl"; d[0xE5] = "Right Shift"; d[0xE6] = "Right Alt"; d[0xE7] = "Right Win";
        return d;
    }

    public static string UsageName(byte usage) => Names.TryGetValue(usage, out var n) ? n : $"0x{usage:X2}";
}
