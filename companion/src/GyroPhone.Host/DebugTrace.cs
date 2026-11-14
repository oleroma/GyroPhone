using System.Text;
using GyroPhone.Protocol.Mapping;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// Diagnostics' log on the PC (Performance › Diagnostics): what was injected (keys, mouse, the virtual pad),
/// the input log's lines, overlay draw times, link lines and session events, in a ring of the newest events.
/// Logs only while <see cref="Enabled"/>, so the input path costs one bool check otherwise.
/// <para>
/// The input path never formats text: <see cref="OnInject"/>, <see cref="OnX360"/> and <see cref="OnDs4"/>
/// only copy the raw events into a ready-made slot; <see cref="Drain"/> (the gp-diag thread, every 50 ms)
/// turns them into lines. Thread-safe.
/// </para>
/// </summary>
public sealed class DebugTrace
{
    /// <summary>Move: stick and trigger moves and summed mouse motion.</summary>
    public enum Kind { In, Out, Move, State, Link, Note }

    public readonly record struct Event(long Us, Kind Kind, string Text);

    /// <summary>The longest a Save covers (the Keep slider's top).</summary>
    public const int MaxKeepMinutes = 30;
    private const long AxisEveryUs = 100_000;
    private const long MotionEveryUs = 100_000;
    private const int AxisStepPct = 5;
    /// <summary>Raw events the input path may queue between two drains.</summary>
    public const int Queue = 16_384;

    private readonly object _lock = new();
    private readonly Event[] _ring;
    private int _head, _count;
    private readonly Func<long> _nowUs;

    private volatile bool _on;

    public DebugTrace(Func<long> nowUs, int capacity = 200_000)
    {
        _nowUs = nowUs;
        _ring = new Event[capacity];
    }

    /// <summary>Bumped on every event.</summary>
    public long Version { get; private set; }

    /// <summary>Logging now: Diagnostics is on.</summary>
    public bool Enabled => _on;

    /// <summary>Diagnostics switched; off clears the log and the queue.</summary>
    public void SetOn(bool on, string why)
    {
        lock (_switchLock)
        {
            if (on == _on) return;
            if (on)
            {
                lock (_lock) { _head = _count = 0; Version++; }
                lock (_qLock) _qCount = 0;
                _on = true;
                Add(Kind.Note, "diagnostics on (" + why + ")");
                // gp-diag: makes the queued events into lines every 50 ms, off the input path and the engine lock.
                _timer = new Timer(_ => Tick(), null, DrainMs, DrainMs);
            }
            else
            {
                _on = false;
                _timer?.Dispose();
                _timer = null;
                lock (_qLock) _qCount = 0;
            }
        }
    }

    private const int DrainMs = 50;
    private readonly object _switchLock = new();
    private Timer? _timer;
    private long _lastFlushUs;

    private void Tick()
    {
        try
        {
            Drain();
            long now = _nowUs();
            if (now - _lastFlushUs < 1_000_000) return;
            _lastFlushUs = now;
            lock (_drainLock)
                if (!IsIdle()) FlushMotion(now);
        }
        catch (Exception)
        {
            // A bad line must not stop the log; the next tick goes on.
        }
    }

    // ---- the input path's queue ---------------------------------------------------------------

    private enum Raw : byte { Inject, X360, Ds4 }

    private struct Pending
    {
        public long Us;
        public Raw Type;
        public InjectEvent Inject;
        public X360Frame X360;
        public Ds4Frame Ds4;
    }

    private readonly object _qLock = new();
    private readonly Pending[] _q = new Pending[Queue];
    private int _qHead, _qCount;

    /// <summary>Raw events dropped because the queue was full (the diagnostics thread fell behind).</summary>
    public long Dropped { get; private set; }

    private ref Pending Slot()
    {
        // Under _qLock: the next free slot, or the last one (overwritten and counted) when full.
        if (_qCount == Queue) { Dropped++; return ref _q[(_qHead + Queue - 1) % Queue]; }
        return ref _q[(_qHead + _qCount++) % Queue];
    }

    /// <summary>The keyboard and mouse events one flush injected (copied; made into lines by <see cref="Drain"/>).</summary>
    public void OnInject(ReadOnlySpan<InjectEvent> events, long nowUs)
    {
        lock (_qLock)
            foreach (var e in events)
            {
                ref var p = ref Slot();
                p.Us = nowUs; p.Type = Raw.Inject; p.Inject = e;
            }
    }

    /// <summary>The virtual Xbox 360 pad was given <paramref name="f"/> (copied).</summary>
    public void OnX360(in X360Frame f, long nowUs)
    {
        lock (_qLock)
        {
            ref var p = ref Slot();
            p.Us = nowUs; p.Type = Raw.X360; p.X360 = f;
        }
    }

    /// <summary>The virtual DualShock 4 was given <paramref name="f"/> (copied).</summary>
    public void OnDs4(in Ds4Frame f, long nowUs)
    {
        lock (_qLock)
        {
            ref var p = ref Slot();
            p.Us = nowUs; p.Type = Raw.Ds4; p.Ds4 = f;
        }
    }

    private readonly InjectEvent[] _one = new InjectEvent[1];

    /// <summary>Turns the queued raw events into lines, oldest first (one thread at a time).</summary>
    public void Drain()
    {
        lock (_drainLock)
            while (true)
            {
                Pending p;
                lock (_qLock)
                {
                    if (_qCount == 0) return;
                    p = _q[_qHead];
                    _qHead = (_qHead + 1) % Queue;
                    _qCount--;
                }
                switch (p.Type)
                {
                    case Raw.Inject: _one[0] = p.Inject; FormatInject(_one, p.Us); break;
                    case Raw.X360: FormatX360(p.X360, p.Us); break;
                    case Raw.Ds4: FormatDs4(p.Ds4, p.Us); break;
                }
            }
    }

    private readonly object _drainLock = new();

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

    private void FormatInject(ReadOnlySpan<InjectEvent> events, long nowUs)
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

    /// <summary>What the virtual pad was last given (as drained), for a save's header.</summary>
    public string CurrentPad { get; private set; } = "nothing yet";

    private void FormatX360(in X360Frame f, long nowUs)
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

    private void FormatDs4(in Ds4Frame f, long nowUs)
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

    /// <summary>Once a second: logs <paramref name="linkLine"/> (summed motion is flushed by the diagnostics timer).</summary>
    public void SecondTick(string? linkLine, long nowUs)
    {
        if (linkLine != null) Add(Kind.Link, linkLine, nowUs);
    }

    // ---- saving ----------------------------------------------------------------------------

    /// <summary>
    /// The last <paramref name="minutes"/> as text: '#' header lines, then <c>wall ms \t pc \t kind \t text</c> per event,
    /// the same format as the phone's saves so both sort together.
    /// </summary>
    public string Save(IEnumerable<string> header, int minutes)
    {
        Drain();
        long nowUs = _nowUs();
        long wallMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var sb = new StringBuilder();
        sb.Append("# GyroPhone diagnostics, side pc\n");
        sb.Append("# saved at wall ms ").Append(wallMs).Append('\n');
        foreach (var h in header) sb.Append("# ").Append(h.Replace('\n', ' ')).Append('\n');
        sb.Append("# clock-pair: wall_ms=").Append(wallMs).Append(" host_us=").Append(nowUs).Append('\n');
        if (Dropped > 0) sb.Append("# raw events dropped (the diagnostics thread fell behind): ").Append(Dropped).Append('\n');
        foreach (var e in Since(nowUs - minutes * 60_000_000L))
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
        d[0x2D] = "-"; d[0x2E] = "="; d[0x2F] = "["; d[0x30] = "]"; d[0x31] = "\\"; d[0x33] = ";";
        d[0x34] = "'"; d[0x35] = "`"; d[0x36] = ","; d[0x37] = "."; d[0x38] = "/"; d[0x39] = "Caps Lock";
        d[0x64] = "Non-US \\";
        for (int i = 13; i <= 24; i++) d[(byte)(0x68 + i - 13)] = "F" + i;
        d[0x4F] = "Right"; d[0x50] = "Left"; d[0x51] = "Down"; d[0x52] = "Up";
        d[0xE0] = "Left Ctrl"; d[0xE1] = "Left Shift"; d[0xE2] = "Left Alt"; d[0xE3] = "Left Win";
        d[0xE4] = "Right Ctrl"; d[0xE5] = "Right Shift"; d[0xE6] = "Right Alt"; d[0xE7] = "Right Win";
        d[0x7F] = "Mute"; d[0x80] = "Volume Up"; d[0x81] = "Volume Down";
        d[0xE8] = "Play/Pause"; d[0xEA] = "Previous Track"; d[0xEB] = "Next Track";
        return d;
    }

    private static volatile Dictionary<byte, string>? _pcNames;

    /// <summary>Names character keys by what they type on the PC's layout (KEY_LABELS), as the phone does.</summary>
    public static void SetLayout(KeyLabels labels)
    {
        var d = new Dictionary<byte, string>();
        for (int i = 0; i < KeyLabels.Usages.Length && i < labels.Chars.Length; i++)
        {
            ushort c = labels.Chars[i];
            if (c > 0x20 && c != 0x7F && !char.IsSurrogate((char)c)) d[KeyLabels.Usages[i]] = char.ToUpperInvariant((char)c).ToString();
        }
        _pcNames = d;
    }

    public static string UsageName(byte usage) =>
        _pcNames is { } pc && pc.TryGetValue(usage, out var p) ? p
        : Names.TryGetValue(usage, out var n) ? n : $"0x{usage:X2}";
}
