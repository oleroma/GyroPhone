namespace GyroPhone.Protocol.Mapping;

/// <summary>
/// Turns one hand-off's keyboard and mouse events into the GyroPhone input driver's HID input reports
/// (input-driver/src/gpinput_reports.h keeps the same layout), written back to back for one SUBMIT:
/// <list type="bullet">
/// <item>1, mouse (10 bytes): id, buttons (bit 0 L, 1 R, 2 M, 3 back, 4 forward), X, Y, wheel, horizontal wheel (int16 LE; wheels in 1/120 notch);</item>
/// <item>2, keyboard (30 bytes): id, one bit per keyboard usage 0x00..0xE7, modifiers included;</item>
/// <item>3, media keys (2 bytes): id, bit 0 play/pause, 1 previous, 2 next, 3 mute, 4 volume up, 5 volume down.</item>
/// </list>
/// A report holds the whole state, so events that happen at the same moment share one report: the usual hand-off
/// (a move, maybe a click) is one mouse report. A new report starts only where merging would change what Windows
/// sees: a key or button changing twice, a modifier that must go down before (or up after) other keys, a move
/// before a click, and a switch between keyboard and mouse with presses pending, so the order of presses stays
/// the order <see cref="InputDiff"/> wrote them in. Held keys and buttons are remembered between calls.
/// </summary>
public sealed class HidInputReports
{
    public const byte MouseId = 1, KeyboardId = 2, MediaId = 3;
    public const int MouseSize = 10, KeyboardSize = 30, MediaSize = 2;
    /// <summary>Room for every report one hand-off can need (a release of every key is 2 keyboard reports).</summary>
    public const int MaxBytes = 4096;
    private const int KeyBytes = KeyboardSize - 1;
    private const int MaxUsage = 0xE7;

    // What Windows has been sent.
    private readonly byte[] _keys = new byte[KeyBytes];
    private byte _buttons, _media;

    // The report being built, per kind, and what it changed so far.
    private readonly byte[] _pendKeys = new byte[KeyBytes];
    private byte _pendButtons, _pendMedia;
    private readonly byte[] _keysTouched = new byte[KeyBytes];
    private byte _buttonsTouched, _mediaTouched;
    private int _keyPhase = -1; // -1: nothing pending; 0 modifier downs, 1 other keys, 2 modifier ups
    private int _dx, _dy, _wheel, _hwheel;
    private bool _mouseDirty, _moved;

    private enum Kind { None, Keyboard, Mouse, Media }
    private Kind _last;

    /// <summary>Keys, media keys and mouse buttons held as far as Windows knows.</summary>
    public bool AnyHeld => _buttons != 0 || _media != 0 || _keys.AsSpan().IndexOfAnyExcept((byte)0) >= 0;

    /// <summary>
    /// Writes the reports for <paramref name="events"/> into <paramref name="output"/> and returns the bytes written
    /// (0 when nothing changes). <paramref name="output"/> must hold <see cref="MaxBytes"/>.
    /// </summary>
    public int Build(ReadOnlySpan<InjectEvent> events, Span<byte> output)
    {
        int n = 0;
        BeginPending();
        foreach (ref readonly var e in events)
        {
            if (e.Kind == InjectKind.Key) Key(e, output, ref n);
            else Mouse(e, output, ref n);
        }
        FlushAll(output, ref n);
        return n;
    }

    /// <summary>Forgets what was sent: the device went away, and Windows let go of everything it held.</summary>
    public void Reset()
    {
        Array.Clear(_keys);
        _buttons = _media = 0;
        BeginPending();
    }

    /// <summary>The media key bit of a keyboard usage <see cref="HidScanCodes"/> sends as a virtual key, or -1.</summary>
    public static int MediaBit(int usage) => usage switch
    {
        0xE8 => 0, // play/pause
        0xEA => 1, // previous
        0xEB => 2, // next
        0x7F => 3, // mute
        0x80 => 4, // volume up
        0x81 => 5, // volume down
        _ => -1,
    };

    private void BeginPending()
    {
        _keys.CopyTo(_pendKeys, 0);
        Array.Clear(_keysTouched);
        _pendButtons = _buttons;
        _pendMedia = _media;
        _buttonsTouched = _mediaTouched = 0;
        _keyPhase = -1;
        _dx = _dy = _wheel = _hwheel = 0;
        _mouseDirty = _moved = false;
        _last = Kind.None;
    }

    private void Key(in InjectEvent e, Span<byte> output, ref int n)
    {
        int usage = e.Usage;
        bool down = !e.IsKeyUp;
        int media = MediaBit(usage);
        if (media >= 0)
        {
            SwitchTo(Kind.Media, output, ref n);
            byte bit = (byte)(1 << media);
            if ((_mediaTouched & bit) != 0) FlushMedia(output, ref n);
            _pendMedia = down ? (byte)(_pendMedia | bit) : (byte)(_pendMedia & ~bit);
            _mediaTouched |= bit;
            return;
        }
        if (usage < 4 || usage > MaxUsage) return; // not a key the report has (InputDiff never sends one)
        SwitchTo(Kind.Keyboard, output, ref n);
        bool modifier = usage >= 0xE0;
        int phase = modifier ? (down ? 0 : 2) : 1;
        int i = usage >> 3;
        byte mask = (byte)(1 << (usage & 7));
        // Modifiers go down in a report before the keys they shift and up in one after them;
        // a key changing twice needs a report in between.
        if (_keyPhase >= 0 && (phase != _keyPhase || (_keysTouched[i] & mask) != 0)) FlushKeyboard(output, ref n);
        _pendKeys[i] = down ? (byte)(_pendKeys[i] | mask) : (byte)(_pendKeys[i] & ~mask);
        _keysTouched[i] |= mask;
        _keyPhase = phase;
    }

    private void Mouse(in InjectEvent e, Span<byte> output, ref int n)
    {
        uint f = e.Flags;
        SwitchTo(Kind.Mouse, output, ref n);
        int bit = ButtonBit(f, e.MouseData);
        if (bit >= 0)
        {
            byte mask = (byte)(1 << bit);
            // A click after a move, or a button changing twice, goes in its own report.
            if (_moved || (_buttonsTouched & mask) != 0) FlushMouse(output, ref n);
            bool down = (f & (InjectEvent.MouseLeftDown | InjectEvent.MouseRightDown | InjectEvent.MouseMiddleDown | InjectEvent.MouseXDown)) != 0;
            _pendButtons = down ? (byte)(_pendButtons | mask) : (byte)(_pendButtons & ~mask);
            _buttonsTouched |= mask;
            _mouseDirty = true;
        }
        if ((f & InjectEvent.MouseMove) != 0 && (e.Dx != 0 || e.Dy != 0))
        {
            if (Overflows(_dx, e.Dx) || Overflows(_dy, e.Dy)) FlushMouse(output, ref n);
            _dx += e.Dx;
            _dy += e.Dy;
            _moved = _mouseDirty = true;
        }
        if ((f & InjectEvent.MouseWheel) != 0 && e.MouseData != 0)
        {
            if (Overflows(_wheel, e.MouseData)) FlushMouse(output, ref n);
            _wheel += e.MouseData;
            _mouseDirty = true;
        }
        if ((f & InjectEvent.MouseHWheel) != 0 && e.MouseData != 0)
        {
            if (Overflows(_hwheel, e.MouseData)) FlushMouse(output, ref n);
            _hwheel += e.MouseData;
            _mouseDirty = true;
        }
    }

    private static int ButtonBit(uint f, int data)
    {
        if ((f & (InjectEvent.MouseLeftDown | InjectEvent.MouseLeftUp)) != 0) return 0;
        if ((f & (InjectEvent.MouseRightDown | InjectEvent.MouseRightUp)) != 0) return 1;
        if ((f & (InjectEvent.MouseMiddleDown | InjectEvent.MouseMiddleUp)) != 0) return 2;
        if ((f & (InjectEvent.MouseXDown | InjectEvent.MouseXUp)) != 0) return data == InjectEvent.XButton2 ? 4 : 3;
        return -1;
    }

    // The report fields are int16; a move past them (never from InputDiff, whose steps are smaller) splits.
    private static bool Overflows(int acc, int add) => Math.Abs((long)acc + add) > short.MaxValue;

    /// <summary>Presses of another kind are sent first, so presses keep their order across devices.</summary>
    private void SwitchTo(Kind kind, Span<byte> output, ref int n)
    {
        if (_last == kind) return;
        if (_last == Kind.Keyboard) FlushKeyboard(output, ref n);
        else if (_last == Kind.Media) FlushMedia(output, ref n);
        else if (_last == Kind.Mouse && _buttonsTouched != 0) FlushMouse(output, ref n);
        _last = kind;
    }

    private void FlushAll(Span<byte> output, ref int n)
    {
        FlushKeyboard(output, ref n);
        FlushMedia(output, ref n);
        FlushMouse(output, ref n);
    }

    private void FlushKeyboard(Span<byte> output, ref int n)
    {
        if (_keyPhase < 0) return;
        _keyPhase = -1;
        Array.Clear(_keysTouched);
        if (_pendKeys.AsSpan().SequenceEqual(_keys)) return;
        _pendKeys.CopyTo(_keys, 0);
        output[n] = KeyboardId;
        _keys.CopyTo(output.Slice(n + 1, KeyBytes));
        n += KeyboardSize;
    }

    private void FlushMedia(Span<byte> output, ref int n)
    {
        if (_mediaTouched == 0) return;
        _mediaTouched = 0;
        if (_pendMedia == _media) return;
        _media = _pendMedia;
        output[n] = MediaId;
        output[n + 1] = _media;
        n += MediaSize;
    }

    private void FlushMouse(Span<byte> output, ref int n)
    {
        if (!_mouseDirty) return;
        bool buttons = _pendButtons != _buttons;
        bool motion = _dx != 0 || _dy != 0 || _wheel != 0 || _hwheel != 0;
        if (buttons || motion)
        {
            _buttons = _pendButtons;
            var r = output.Slice(n, MouseSize);
            r[0] = MouseId;
            r[1] = _buttons;
            Put(r[2..], _dx);
            Put(r[4..], _dy);
            Put(r[6..], _wheel);
            Put(r[8..], _hwheel);
            n += MouseSize;
        }
        _dx = _dy = _wheel = _hwheel = 0;
        _buttonsTouched = 0;
        _mouseDirty = _moved = false;
    }

    private static void Put(Span<byte> at, int v)
    {
        at[0] = (byte)v;
        at[1] = (byte)(v >> 8);
    }
}
