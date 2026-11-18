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
    /// <summary>The touch buttons' mappings (the phone's OverlayTouchKey): shown while the OVERLAY has the button pressed.</summary>
    public IReadOnlyList<OverlayTouchKey> Touch { get; init; } = [];

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
        // Touch buttons (older phones of this feature stop here).
        var touch = new List<OverlayTouchKey>();
        if (at < b.Length)
        {
            int tc = b[at++];
            for (int i = 0; i < tc; i++)
            {
                if (at + 2 > b.Length) return null;
                int control = b[at], flags = b[at + 1];
                at += 2;
                var target = Str(b);
                if (target == null) return null;
                touch.Add(new OverlayTouchKey(control, flags, target));
            }
        }
        p = at;
        return new OverlayKeys(tap, sticks, triggers, keys) { Touch = touch };
    }
}

/// <summary>A touch button's mapping: the button by its layout index, flags as <see cref="OverlayKey.Flags"/>, what it sends.</summary>
public sealed record OverlayTouchKey(int Control, int Flags, string Target);

/// <summary>
/// The picture on a stick's or the D-pad's row in the Keys overlay: <paramref name="Stick"/> 0 left, 1 right stick, 2 the
/// D-pad; what its whole axes send (<paramref name="Analog"/>, "Mouse", "" none); what up, right, down, left send
/// (<paramref name="Keys"/>, "" none) and the ways firing now (<paramref name="Lit"/>: <see cref="Up"/>, <see cref="Right"/>,
/// <see cref="Down"/>, <see cref="Left"/>); what its Outer ring sends up, right, down, left and any way
/// (<paramref name="Outer"/>) and which of those fire (<paramref name="OuterLit"/>, the same bits, <see cref="Any"/>).
/// </summary>
public sealed record OverlayKeyStick(int Stick, string Analog, IReadOnlyList<string> Keys, int Lit, IReadOnlyList<string> Outer, int OuterLit)
{
    public const int Up = 1, Right = 2, Down = 4, Left = 8, Any = 16;
    public const int DPad = 2;

    /// <summary>Something on it fires, or (a stick that sends its axes) it is pushed.</summary>
    public bool Active(bool moved) => Lit != 0 || OuterLit != 0 || moved && Analog.Length > 0;

    public bool Equals(OverlayKeyStick? o) => o is not null && Stick == o.Stick && Analog == o.Analog && Lit == o.Lit && OuterLit == o.OuterLit
        && Keys.SequenceEqual(o.Keys) && Outer.SequenceEqual(o.Outer);
    public override int GetHashCode() => HashCode.Combine(Stick, Analog, Lit, OuterLit);
}

/// <summary>
/// A row in the Keys overlay: its name as the phone's controller editor shows it ("A", "LS click") and what it
/// sends ("Space", several joined by " + "); <see cref="Toggle"/>: on only by a Toggle that is on;
/// <see cref="LeftTrigger"/> or <see cref="RightTrigger"/>: it is that trigger (a pull bar shows); <see cref="Touch"/>: a
/// touch button's row, shown without its name (its <see cref="Name"/> only tells rows apart). A stick's or the D-pad's
/// row has its <see cref="Stick"/> picture; an always-shown row that fires nothing now is not <see cref="Lit"/>.
/// </summary>
public sealed record OverlayKeyRow(string Name, string Target, int Flags)
{
    public const int Toggle = 1, LeftTrigger = 2, RightTrigger = 4, Touch = 8;

    public OverlayKeyStick? Stick { get; init; }
    public bool Lit { get; init; } = true;
}

/// <summary>
/// What the Keys overlay shows now: the always-shown controls on top (<paramref name="Shown"/>, in a fixed order, lit while
/// they fire), then the rows firing now or with a Toggle on (<paramref name="Rows"/>).
/// </summary>
public sealed record OverlayKeysView(IReadOnlyList<OverlayKeyRow> Shown, IReadOnlyList<OverlayKeyRow> Rows)
{
    public bool Equals(OverlayKeysView? o) => o is not null && Shown.SequenceEqual(o.Shown) && Rows.SequenceEqual(o.Rows);
    public override int GetHashCode() => HashCode.Combine(Shown.Count, Rows.Count);
}

/// <summary>The controller's controls the Keys overlay can always show, by <see cref="OverlayKeysReader.ControlOf"/> index.</summary>
public static class OverlayControls
{
    public const int DPad = 14, LeftStick = 15, RightStick = 16, Count = 17;

    /// <summary>Their ids in settings and their names.</summary>
    public static readonly string[] Ids = ["a", "b", "x", "y", "lb", "rb", "lt", "rt", "select", "start", "home", "capture", "l3", "r3", "dpad", "ls", "rs"];
    public static readonly string[] Names = ["A", "B", "X", "Y", "LB", "RB", "LT", "RT", "Select", "Start", "Home", "Capture", "LS click", "RS click", "D-pad", "LS", "RS"];

    /// <summary>The order they show in: sticks and D-pad first, then the buttons.</summary>
    public static readonly int[] Order = [LeftStick, RightStick, DPad, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13];

    /// <summary>The mask of <paramref name="ids"/> (bit per control).</summary>
    public static int Mask(IEnumerable<string>? ids)
    {
        int m = 0;
        if (ids == null) return m;
        foreach (var id in ids)
        {
            int i = Array.IndexOf(Ids, id);
            if (i >= 0) m |= 1 << i;
        }
        return m;
    }
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

    // What the last read was made from: the same again, with no pulse or long press waiting, gives the same view.
    private bool _settled;
    private int _lastShown;
    private uint _lastButtons;
    private float _lastL2, _lastR2;
    private readonly float[] _lastSticks = new float[4];
    private readonly byte[] _lastPressed = new byte[16], _lastLatched = new byte[16];

    /// <summary>
    /// The view for <paramref name="table"/> (null: none) and frame <paramref name="f"/> at <paramref name="now"/> (ms),
    /// with the controls of <paramref name="shown"/> (<see cref="OverlayControls.Mask"/>) always on top; the same instance
    /// while nothing changed.
    /// </summary>
    public OverlayKeysView? Read(OverlayKeys? table, OverlayFrame f, long now, int shown = 1 << OverlayControls.LeftStick | 1 << OverlayControls.RightStick)
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
            int n = table.Keys.Count + table.Touch.Count;
            _down = new bool[n]; _shortUp = new bool[n]; _toggled = new bool[n]; _was = new bool[n];
            _downAt = new long[n]; _upAt = new long[n]; _pulseUntil = new long[n];
            for (int i = 0; i < n; i++) _upAt[i] = long.MinValue / 2;
            _settled = false;
        }
        // Nothing changed since the last read and nothing waits on time: the same view, made again for nothing.
        if (_settled && _view != null && shown == _lastShown && SameInputs(f)) return _view;
        _lastShown = shown;
        _lastButtons = f.PadButtons;
        _lastL2 = f.PadL2Pull;
        _lastR2 = f.PadR2Pull;
        Array.Copy(f.PadSticks, _lastSticks, 4);
        Array.Copy(f.Pressed, _lastPressed, 16);
        Array.Copy(f.Latched, _lastLatched, 16);
        var keys = table.Keys;
        // Per picture (left stick, right stick, D-pad while always shown): its keys, outer keys, analog and what fires.
        var lit = new int[3];
        var outerLit = new int[3];
        var wayKeys = new string[]?[3];
        var outerKeys = new string[]?[3];
        var analog = new string?[3];
        // Per always-shown button: what it sends, whether it fires, its trigger flag.
        var shownTarget = new string?[OverlayControls.Count];
        var shownLit = new bool[OverlayControls.Count];
        var shownFlags = new int[OverlayControls.Count];
        List<OverlayKeyRow>? rows = null;
        bool dpadPie = (shown & 1 << OverlayControls.DPad) != 0;
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
            int control = ControlOf(k.Input);
            // A stick (or the D-pad while always shown): its picture.
            int pic = kind is OverlayKey.Way or OverlayKey.AnyWay or OverlayKey.Stick ? (k.Input >> (kind == OverlayKey.Way ? 2 : 0)) & 1
                : control == OverlayControls.DPad && dpadPie ? OverlayKeyStick.DPad : -1;
            if (pic >= 0)
            {
                if (kind == OverlayKey.Stick)
                {
                    analog[pic] ??= k.Target;
                    continue;
                }
                bool outer = kind == OverlayKey.AnyWay || (k.Flags & OverlayKey.Outer) != 0;
                int way = kind == OverlayKey.AnyWay ? 4 : pic == OverlayKeyStick.DPad ? DPadWay(k.Input) : k.Input & 3;
                var ks = outer ? outerKeys[pic] ??= ["", "", "", "", ""] : wayKeys[pic] ??= ["", "", "", ""];
                if (!HasPart(ks[way], k.Target)) ks[way] = ks[way].Length == 0 ? k.Target : ks[way] + " + " + k.Target;
                if (on)
                {
                    if (outer) outerLit[pic] |= 1 << way;
                    else lit[pic] |= 1 << way;
                }
                continue;
            }
            string target = toggle ? k.Target + " on" : k.Target;
            int flags = (toggle ? OverlayKeyRow.Toggle : 0) | TriggerFlag(k.Input);
            // An always-shown button: its one row, lit while something on it fires.
            if (control >= 0 && (shown & 1 << control) != 0)
            {
                var t = shownTarget[control];
                if (t == null) shownTarget[control] = target;
                else if (!HasPart(t, target)) shownTarget[control] = t + " + " + target;
                shownLit[control] |= on;
                shownFlags[control] |= flags;
                continue;
            }
            if (!on) continue;
            rows ??= new List<OverlayKeyRow>();
            int at = rows.FindIndex(r => r.Name == k.Name);
            if (at < 0) rows.Add(new OverlayKeyRow(k.Name, target, flags));
            // The same thing sent twice (a button mapped by its key and its axis, such as the D-pad or a trigger) shows once.
            else if (!HasPart(rows[at].Target, target)) rows[at] = rows[at] with { Target = rows[at].Target + " + " + target, Flags = rows[at].Flags | flags };
            else rows[at] = rows[at] with { Flags = rows[at].Flags | flags };
        }
        // Touch buttons: pressed and Toggles on (latched) as the OVERLAY has them.
        var touch = table.Touch;
        for (int j = 0; j < touch.Count; j++)
        {
            var t = touch[j];
            int i = keys.Count + j;
            bool toggle = (t.Flags & OverlayKey.Toggle) != 0;
            bool on = Fired(i, t.Flags & OverlayKey.GestureMask, f.IsPressed(t.Control), table.TapMs, now);
            if (toggle) on = f.IsLatched(t.Control);
            if (!on) continue;
            string name = TouchName(t.Control);
            string target = toggle ? t.Target + " on" : t.Target;
            int flags = OverlayKeyRow.Touch | (toggle ? OverlayKeyRow.Toggle : 0);
            rows ??= new List<OverlayKeyRow>();
            int at = rows.FindIndex(r => r.Name == name);
            if (at < 0) rows.Add(new OverlayKeyRow(name, target, flags));
            else if (!HasPart(rows[at].Target, target)) rows[at] = rows[at] with { Target = rows[at].Target + " + " + target, Flags = rows[at].Flags | flags };
        }
        // The pictures: always shown on top, or a row of their own while something on them fires.
        var shownRows = new List<OverlayKeyRow>();
        var picRows = new List<OverlayKeyRow>();
        foreach (int c in OverlayControls.Order)
        {
            if (c is OverlayControls.LeftStick or OverlayControls.RightStick or OverlayControls.DPad)
            {
                int pic = c == OverlayControls.DPad ? OverlayKeyStick.DPad : c - OverlayControls.LeftStick;
                if (wayKeys[pic] == null && outerKeys[pic] == null && analog[pic] == null) continue;
                var st = new OverlayKeyStick(pic, analog[pic] ?? "", wayKeys[pic] ?? ["", "", "", ""], lit[pic], outerKeys[pic] ?? ["", "", "", "", ""], outerLit[pic]);
                bool moved = pic < 2 && (Math.Abs(f.PadSticks[pic * 2]) > Moved || Math.Abs(f.PadSticks[pic * 2 + 1]) > Moved);
                var row = new OverlayKeyRow(OverlayControls.Names[c], "", 0) { Stick = st, Lit = st.Active(moved) };
                if ((shown & 1 << c) != 0) shownRows.Add(row);
                else if (row.Lit) picRows.Add(row);
                continue;
            }
            if (shownTarget[c] is { } target)
                shownRows.Add(new OverlayKeyRow(OverlayControls.Names[c], target, shownFlags[c]) { Lit = shownLit[c] });
        }
        if (picRows.Count > 0) (rows ??= new List<OverlayKeyRow>()).InsertRange(0, picRows);
        var view = new OverlayKeysView(shownRows, (IReadOnlyList<OverlayKeyRow>?)rows ?? []);
        _settled = !Timed(now);
        if (_view != null && _view.Equals(view)) return _view;
        return _view = view;
    }

    /// <summary>Whether <paramref name="f"/>'s controller and touch state is what the last read was made from.</summary>
    private bool SameInputs(OverlayFrame f) =>
        f.PadButtons == _lastButtons && f.PadL2Pull == _lastL2 && f.PadR2Pull == _lastR2
        && f.PadSticks.AsSpan(0, 4).SequenceEqual(_lastSticks)
        && f.Pressed.AsSpan().SequenceEqual(_lastPressed) && f.Latched.AsSpan().SequenceEqual(_lastLatched);

    /// <summary>The always-showable control (<see cref="OverlayControls"/>) of an <see cref="OverlayKey.Input"/>, or -1.</summary>
    public static int ControlOf(int input)
    {
        if (input < 14) return input;
        if (input < 18) return OverlayControls.DPad;
        if (input < 32) return -1;
        return (input & 0xF0) switch
        {
            OverlayKey.Way => OverlayControls.LeftStick + ((input >> 2) & 1),
            OverlayKey.AnyWay or OverlayKey.Stick => OverlayControls.LeftStick + (input & 1),
            OverlayKey.Trigger => 6 + ((input >> 2) & 1),
            _ => -1,
        };
    }

    /// <summary>The way (0 up, 1 right, 2 down, 3 left) of a D-pad button's bit (up 14, down 15, left 16, right 17).</summary>
    private static int DPadWay(int bit) => bit switch { 14 => 0, 15 => 2, 16 => 3, _ => 1 };

    /// <summary>Whether <paramref name="joined"/> ("Space + Mouse R") already has <paramref name="part"/>.</summary>
    private static bool HasPart(string joined, string part)
    {
        // As Split(" + "), without making the pieces.
        int start = 0;
        while (true)
        {
            int end = joined.IndexOf(" + ", start, StringComparison.Ordinal);
            int len = (end < 0 ? joined.Length : end) - start;
            if (len == part.Length && string.CompareOrdinal(joined, start, part, 0, len) == 0) return true;
            if (end < 0) return false;
            start = end + 3;
        }
    }

    private static readonly string[] TouchNames = Enumerable.Range(0, 128).Select(i => "touch " + i).ToArray();

    /// <summary>A touch button's row name, by its layout index: never shown, it keeps its row apart from others.</summary>
    private static string TouchName(int control) => (uint)control < 128 ? TouchNames[control] : "touch " + control;

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
            // Mappings, then touch buttons (as Read numbers them).
            int flags = i < t.Keys.Count ? t.Keys[i].Flags : t.Touch[i - t.Keys.Count].Flags;
            if (_down[i] && (flags & OverlayKey.GestureMask) == OverlayKey.LongPress) return true;
        }
        return false;
    }
}
