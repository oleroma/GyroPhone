using System.Text;

namespace GyroPhone.Protocol;

/// <summary>
/// One mapping of the controller attached to the phone, for the Keys overlay (the phone's OverlayKey):
/// <paramref name="Input"/> is a button by its OVERLAY bit number (0..31, <see cref="OverlayFrame.PadSouth"/> is 0),
/// <see cref="Way"/> | stick &lt;&lt; 2 | way (0 up, 1 right, 2 down, 3 left), <see cref="AnyWay"/> | stick (a whole
/// stick's Outer, any way), <see cref="Stick"/> | stick (the whole stick) or <see cref="Trigger"/> | trigger &lt;&lt; 2 | stage
/// (0 pulled at all, 1 Press, 2 Full pull); <paramref name="Flags"/> the gesture in the low bits, <see cref="Outer"/>,
/// <see cref="Toggle"/>; its name as the phone's controller editor shows it and what it sends.
/// </summary>
public sealed record OverlayKey(int Input, int Flags, string Name, string Target)
{
    public const int Way = 0x40, AnyWay = 0x50, Stick = 0x60, Trigger = 0x70;
    public const int Held = 0, Tap = 1, DoubleTap = 2, LongPress = 3, Release = 4, Press = 5, GestureMask = 7;
    public const int Outer = 0x40, Toggle = 0x80;
}

/// <summary>A stick's tuning as the phone uses it: inner deadzone, outer edge, Outer ring (0..1), Left/Right and Up/Down pies (degrees).</summary>
public readonly record struct OverlayStickTuning(float Inner, float Outer, float Ring, float PieX, float PieY);

/// <summary>A trigger's tuning as the phone uses it: where Press and Full pull start (0..1).</summary>
public readonly record struct OverlayTriggerTuning(float Press, float Full);

/// <summary>
/// The Keys overlay's table (the phone's OverlayKeys): what each input of the controller attached to the phone sends in
/// the set in use, with its sticks' and triggers' tuning and the tap time. The phone sends it again only when its
/// workspace, set in use or controller changes; <see cref="OverlayKeysReader"/> works out what fires from each OVERLAY.
/// </summary>
public sealed record OverlayKeys(int TapMs, OverlayStickTuning[] Sticks, OverlayTriggerTuning[] Triggers, IReadOnlyList<OverlayKey> Keys)
{
    /// <summary>Marks the keys part of a frames blob (the phone's OverlayFeed.KEYS_SECTION).</summary>
    public const byte Section = 0x4B;

    /// <summary>Reads the keys part from <paramref name="p"/> (after its section byte), or null when it is malformed.</summary>
    public static OverlayKeys? Decode(ReadOnlySpan<byte> b, ref int p)
    {
        int at = p;
        string? Str(ReadOnlySpan<byte> s)
        {
            if (at + 1 > s.Length) return null;
            int n = s[at];
            if (at + 1 + n > s.Length) return null;
            string v = Encoding.UTF8.GetString(s.Slice(at + 1, n));
            at += 1 + n;
            return v;
        }
        if (at + 2 + 2 * 5 + 2 * 2 + 1 > b.Length) return null;
        int tap = b[at] | b[at + 1] << 8;
        at += 2;
        var sticks = new OverlayStickTuning[2];
        for (int i = 0; i < 2; i++, at += 5)
            sticks[i] = new OverlayStickTuning(b[at] / 255f, b[at + 1] / 255f, b[at + 2] / 255f, b[at + 3], b[at + 4]);
        var triggers = new OverlayTriggerTuning[2];
        for (int i = 0; i < 2; i++, at += 2) triggers[i] = new OverlayTriggerTuning(b[at] / 255f, b[at + 1] / 255f);
        int count = b[at++];
        var keys = new List<OverlayKey>(count);
        for (int i = 0; i < count; i++)
        {
            if (at + 2 > b.Length) return null;
            int input = b[at], flags = b[at + 1];
            at += 2;
            var name = Str(b);
            var target = Str(b);
            if (name == null || target == null) return null;
            keys.Add(new OverlayKey(input, flags, name, target));
        }
        p = at;
        return new OverlayKeys(tap, sticks, triggers, keys);
    }
}

/// <summary>
/// A stick's row in the Keys overlay: <paramref name="Stick"/> 0 left, 1 right; what its whole axes send
/// (<paramref name="Analog"/>, "Mouse", "" none); what up, right, down, left send (<paramref name="Keys"/>, "" none) and the
/// ways firing now (<paramref name="Lit"/>: <see cref="Up"/>, <see cref="Right"/>, <see cref="Down"/>, <see cref="Left"/>).
/// </summary>
public sealed record OverlayKeyStick(int Stick, string Analog, IReadOnlyList<string> Keys, int Lit)
{
    public const int Up = 1, Right = 2, Down = 4, Left = 8;

    public bool Equals(OverlayKeyStick? o) => o is not null && Stick == o.Stick && Analog == o.Analog && Lit == o.Lit && Keys.SequenceEqual(o.Keys);
    public override int GetHashCode() => HashCode.Combine(Stick, Analog, Lit, Keys.Count > 0 ? Keys[0] : "");
}

/// <summary>
/// A button's row in the Keys overlay: its name as the phone's controller editor shows it ("A", "LS click") and what it
/// sends now ("Space", several joined by " + "); <see cref="Toggle"/>: on only by a Toggle that is on;
/// <see cref="LeftTrigger"/> or <see cref="RightTrigger"/>: it is that trigger (a pull bar shows).
/// </summary>
public sealed record OverlayKeyRow(string Name, string Target, int Flags)
{
    public const int Toggle = 1, LeftTrigger = 2, RightTrigger = 4;
}

/// <summary>What the Keys overlay shows now: the sticks that send something, and the buttons firing or with a Toggle on.</summary>
public sealed record OverlayKeysView(IReadOnlyList<OverlayKeyStick> Sticks, IReadOnlyList<OverlayKeyRow> Rows)
{
    public bool Equals(OverlayKeysView? o) => o is not null && Sticks.SequenceEqual(o.Sticks) && Rows.SequenceEqual(o.Rows);
    public override int GetHashCode() => HashCode.Combine(Sticks.Count, Rows.Count);
}

/// <summary>
/// Works out on the PC what the controller attached to the phone fires, from the phone's table
/// (<see cref="OverlayKeys"/>) and the controller state every OVERLAY carries (buttons, trigger pulls, sticks), so the
/// phone reads nothing for the Keys overlay. Held mappings, triggers and stick ways follow the phone's own thresholds;
/// taps, double taps, long presses, presses and releases are worked out with the phone's tap time and shown briefly,
/// and Toggles flip on each press: close to what the phone does, not read from it. Runs on the overlay window's
/// thread; a new view is made only when what it shows changes.
/// </summary>
public sealed class OverlayKeysReader
{
    /// <summary>How long a tap, double tap, press or release shows, ms.</summary>
    public const int PulseMs = 150;

    /// <summary>A whole stick counts as moving past this (0..1).</summary>
    public const float Moved = 0.05f;

    /// <summary>A stick's way presses its key past this much of its travel, as the phone's.</summary>
    public const float WayPressed = 0.5f;

    private OverlayKeys? _table;
    private bool[] _down = [];
    private long[] _downAt = [], _upAt = [], _pulseUntil = [];
    private bool[] _shortUp = [], _toggled = [], _was = [];
    private OverlayKeysView? _view;

    /// <summary>The view for <paramref name="table"/> (null: none) and frame <paramref name="f"/> at <paramref name="now"/> (ms); the same instance while nothing changed.</summary>
    public OverlayKeysView? Read(OverlayKeys? table, OverlayFrame f, long now)
    {
        if (table == null)
        {
            _table = null;
            _view = null;
            return null;
        }
        if (!ReferenceEquals(table, _table))
        {
            _table = table;
            int n = table.Keys.Count;
            _down = new bool[n]; _shortUp = new bool[n]; _toggled = new bool[n]; _was = new bool[n];
            _downAt = new long[n]; _upAt = new long[n]; _pulseUntil = new long[n];
            for (int i = 0; i < n; i++) _upAt[i] = long.MinValue / 2;
        }
        var keys = table.Keys;
        var lit = new int[2];
        var stickKeys = new string[]?[2];
        var analog = new string?[2];
        List<OverlayKeyRow>? rows = null;
        for (int i = 0; i < keys.Count; i++)
        {
            var k = keys[i];
            bool down = Down(table, k, f);
            bool on = Fired(i, k.Flags & OverlayKey.GestureMask, down, table.TapMs, now);
            bool toggle = (k.Flags & OverlayKey.Toggle) != 0;
            if (toggle)
            {
                if (on && !_was[i]) _toggled[i] = !_toggled[i];
                _was[i] = on;
                on = _toggled[i];
            }
            int kind = k.Input & 0xF0;
            // A whole stick: its row shows what it moves.
            if (kind == OverlayKey.Stick)
            {
                int s = k.Input & 1;
                analog[s] ??= k.Target;
                continue;
            }
            // A stick's way held (not Outer, a gesture or a Toggle): a key on its plus.
            bool plainWay = kind == OverlayKey.Way
                && (k.Flags & (OverlayKey.Outer | OverlayKey.Toggle | OverlayKey.GestureMask)) == 0;
            if (plainWay)
            {
                int s = (k.Input >> 2) & 1, way = k.Input & 3;
                var ks = stickKeys[s] ??= ["", "", "", ""];
                if (!HasPart(ks[way], k.Target)) ks[way] = ks[way].Length == 0 ? k.Target : ks[way] + " + " + k.Target;
                if (on) lit[s] |= 1 << way;
                continue;
            }
            if (!on) continue;
            int flags = (toggle ? OverlayKeyRow.Toggle : 0) | TriggerFlag(k.Input);
            string target = toggle ? k.Target + " on" : k.Target;
            rows ??= new List<OverlayKeyRow>();
            int at = rows.FindIndex(r => r.Name == k.Name);
            if (at < 0) rows.Add(new OverlayKeyRow(k.Name, target, flags));
            // The same thing sent twice (a button mapped by its key and its axis, such as the D-pad or a trigger) shows once.
            else if (!HasPart(rows[at].Target, target)) rows[at] = rows[at] with { Target = rows[at].Target + " + " + target, Flags = rows[at].Flags | flags };
            else rows[at] = rows[at] with { Flags = rows[at].Flags | flags };
        }
        var sticks = new List<OverlayKeyStick>(2);
        for (int s = 0; s < 2; s++)
        {
            if (stickKeys[s] == null && analog[s] == null) continue;
            sticks.Add(new OverlayKeyStick(s, analog[s] ?? "", stickKeys[s] ?? ["", "", "", ""], lit[s]));
        }
        var view = new OverlayKeysView(sticks, (IReadOnlyList<OverlayKeyRow>?)rows ?? []);
        if (_view != null && _view.Equals(view)) return _view;
        return _view = view;
    }

    /// <summary>Whether <paramref name="joined"/> ("Space + Mouse R") already has <paramref name="part"/>.</summary>
    private static bool HasPart(string joined, string part)
    {
        foreach (var p in joined.Split(" + ")) if (p == part) return true;
        return false;
    }

    private static int TriggerFlag(int input)
    {
        if ((input & 0xF0) == OverlayKey.Trigger) return (input & 4) == 0 ? OverlayKeyRow.LeftTrigger : OverlayKeyRow.RightTrigger;
        if (input == 6) return OverlayKeyRow.LeftTrigger; // PadL2's bit
        if (input == 7) return OverlayKeyRow.RightTrigger;
        return 0;
    }

    /// <summary>Whether <paramref name="k"/>'s input is pressed in <paramref name="f"/>.</summary>
    public static bool Down(OverlayKeys t, OverlayKey k, OverlayFrame f)
    {
        int input = k.Input;
        if (input < 32) return (f.PadButtons & (1u << input)) != 0;
        switch (input & 0xF0)
        {
            case OverlayKey.Trigger:
            {
                int tr = (input >> 2) & 1;
                float pull = tr == 0 ? f.PadL2Pull : f.PadR2Pull;
                var tt = t.Triggers[tr];
                return (input & 3) switch
                {
                    1 => pull > 0f && pull >= tt.Press,
                    2 => pull > 0f && pull >= tt.Full,
                    _ => pull >= Moved,
                };
            }
            case OverlayKey.Stick:
            {
                int s = input & 1;
                return Math.Abs(f.PadSticks[s * 2]) > Moved || Math.Abs(f.PadSticks[s * 2 + 1]) > Moved;
            }
        }
        if ((input & 0xE0) != OverlayKey.Way) return false;
        {
            int s = (input >> 2) & 1;
            float x = f.PadSticks[s * 2], y = f.PadSticks[s * 2 + 1];
            var st = t.Sticks[s];
            float r = MathF.Sqrt(x * x + y * y);
            float travel = Math.Clamp((r - st.Inner) / Math.Max(0.01f, st.Outer - st.Inner), 0f, 1f);
            if (travel <= 0f) return false;
            bool outer = (k.Flags & OverlayKey.Outer) != 0;
            if ((input & 0xF0) == OverlayKey.AnyWay) return travel >= st.Ring;
            if (travel < (outer ? st.Ring : WayPressed)) return false;
            // Its pie: up and down (y down) use the Up/Down pie, left and right the Left/Right one.
            int way = input & 3;
            float along = way switch { 0 => -y, 1 => x, 2 => y, _ => -x };
            float other = way is 0 or 2 ? Math.Abs(x) : Math.Abs(y);
            if (along <= 0f) return false;
            float pie = Math.Clamp(way is 0 or 2 ? st.PieY : st.PieX, 30f, 180f);
            return MathF.Atan2(other, along) * (180f / MathF.PI) <= pie / 2f;
        }
    }

    /// <summary>Whether mapping <paramref name="i"/> with <paramref name="gesture"/> fires now, its input <paramref name="down"/>.</summary>
    private bool Fired(int i, int gesture, bool down, int tapMs, long now)
    {
        bool was = _down[i];
        if (down && !was)
        {
            // A second press soon after a short one: a double tap.
            if (gesture == OverlayKey.DoubleTap && _shortUp[i] && now - _upAt[i] <= tapMs) _pulseUntil[i] = now + PulseMs;
            if (gesture == OverlayKey.Press) _pulseUntil[i] = now + PulseMs;
            _downAt[i] = now;
        }
        else if (!down && was)
        {
            bool shortPress = now - _downAt[i] < tapMs;
            if (gesture == OverlayKey.Tap && shortPress) _pulseUntil[i] = now + PulseMs;
            if (gesture == OverlayKey.Release) _pulseUntil[i] = now + PulseMs;
            _shortUp[i] = shortPress;
            _upAt[i] = now;
        }
        _down[i] = down;
        return gesture switch
        {
            OverlayKey.Held => down,
            OverlayKey.LongPress => down && now - _downAt[i] >= tapMs,
            _ => now < _pulseUntil[i],
        };
    }

    /// <summary>Some mapping shows for a while or waits on time (a pulse, a long press): read again next frame even without a new OVERLAY.</summary>
    public bool Timed(long now)
    {
        var t = _table;
        if (t == null) return false;
        for (int i = 0; i < _pulseUntil.Length; i++)
        {
            if (now < _pulseUntil[i]) return true;
            if (_down[i] && (t.Keys[i].Flags & OverlayKey.GestureMask) == OverlayKey.LongPress) return true;
        }
        return false;
    }
}
