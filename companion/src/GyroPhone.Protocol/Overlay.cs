using System.Text;

namespace GyroPhone.Protocol;

/// <summary>
/// OVERLAY from the phone (see the phone's OverlayFeed for its 152 bytes): the phone's motion since
/// the last one (gyro turn, gravity, compass, ratchet), fingers, pressed and latched controls and
/// stick knobs, for the phone overlay. The PC turns the motion into a pose (<see cref="Qw"/>..)
/// with its own settings.
/// </summary>
public sealed class OverlayFrame
{
    public const int MaxFingers = 5;
    public const int MaxSticks = 8;
    public const byte FlagPose = 1;
    public const byte FlagPadShown = 2;
    public const byte FlagGyroActive = 4;
    public const byte FlagUp = 8;
    public const byte FlagNorth = 16;
    public const byte FlagRatchetHeld = 32;
    public const byte NoControl = 0xFF;
    /// <summary>The accelerometer's 1 g in OVERLAY.</summary>
    public const float GScale = 8192f;

    public uint PhoneTimeUs;
    /// <summary>Yaw, pitch and roll turned since the last OVERLAY (degrees) over <see cref="TurnSeconds"/>.</summary>
    public float TurnYaw, TurnPitch, TurnRoll, TurnSeconds;
    /// <summary>The newest accelerometer reading in the phone frame (right, up, toward the player), in g.</summary>
    public float UpX, UpY, UpZ;
    /// <summary>The newest magnetometer reading in the phone frame, unit length.</summary>
    public float NorthX, NorthY, NorthZ;
    /// <summary>The gyro axes moving the mouse: 1 yaw, 2 pitch, 4 roll.</summary>
    public byte Axes;
    /// <summary>Ratchet presses so far (wraps at 256): a change is a press.</summary>
    public byte RatchetPresses;
    /// <summary>The phone turn (degrees on yaw, pitch, roll) that would send the mouse counts sent since the last OVERLAY by anything but the live gyro (a trackball turns by it).</summary>
    public float CameraYaw, CameraPitch, CameraRoll;
    /// <summary>The game's camera degrees per phone degree on yaw, pitch, roll; 0 = unknown.</summary>
    public float RwsYaw, RwsPitch, RwsRoll;
    /// <summary>The pose the PC made (not on the wire): phone frame (x right, y up, z out of the screen) to the player's view.</summary>
    public float Qw = 1, Qx, Qy, Qz;
    public ushort LayoutVersion;
    public byte Flags;
    public int FingerCount;
    /// <summary>Finger x, y pairs, 0..1 of the pad area.</summary>
    public readonly float[] Fingers = new float[MaxFingers * 2];
    public readonly byte[] Pressed = new byte[16];
    public readonly byte[] Latched = new byte[16];
    public int StickCount;
    public readonly byte[] StickIndex = new byte[MaxSticks];
    /// <summary>Stick knob x, y pairs, -1..1 of the radius, y down.</summary>
    public readonly float[] StickXY = new float[MaxSticks * 2];

    // The controller attached to the phone (the phone overlay's handles).
    public const byte PadAttachedFlag = 1;
    public const uint PadSouth = 1, PadEast = 2, PadWest = 4, PadNorth = 8, PadL1 = 16, PadR1 = 32, PadL2 = 64, PadR2 = 128,
        PadMinus = 256, PadPlus = 512, PadHome = 1024, PadCapture = 2048, PadL3 = 4096, PadR3 = 8192,
        PadUp = 16384, PadDown = 32768, PadLeft = 65536, PadRight = 131072;
    public byte PadFlags;
    /// <summary>Buttons held (<see cref="PadSouth"/>.., by place).</summary>
    public uint PadButtons;
    /// <summary>Trigger pulls, 0..1.</summary>
    public float PadL2Pull, PadR2Pull;
    /// <summary>Sticks, -1..1 (y down): left x, y, right x, y.</summary>
    public readonly float[] PadSticks = new float[4];

    public bool PadAttached => (PadFlags & PadAttachedFlag) != 0;
    public bool PadHeld(uint button) => (PadButtons & button) != 0;

    public bool PoseLive => (Flags & FlagPose) != 0;
    public bool PadShown => (Flags & FlagPadShown) != 0;
    public bool GyroActive => (Flags & FlagGyroActive) != 0;
    public bool HasUp => (Flags & FlagUp) != 0;
    public bool HasNorth => (Flags & FlagNorth) != 0;
    public bool RatchetHeld => (Flags & FlagRatchetHeld) != 0;

    public bool IsPressed(int i) => i is >= 0 and < 128 && (Pressed[i >> 3] & (1 << (i & 7))) != 0;
    public bool IsLatched(int i) => i is >= 0 and < 128 && (Latched[i >> 3] & (1 << (i & 7))) != 0;

    /// <summary>Decodes the wire fields; the pose (<see cref="Qw"/>..) is left as it is.</summary>
    public void Decode(ReadOnlySpan<byte> b)
    {
        PhoneTimeUs = Le.U32(b, 0);
        TurnYaw = Finite(BitConverter.Int32BitsToSingle(Le.I32(b, 4)));
        TurnPitch = Finite(BitConverter.Int32BitsToSingle(Le.I32(b, 8)));
        TurnRoll = Finite(BitConverter.Int32BitsToSingle(Le.I32(b, 12)));
        TurnSeconds = Le.U16(b, 16) / 100_000f;
        UpX = Le.I16(b, 18) / GScale;
        UpY = Le.I16(b, 20) / GScale;
        UpZ = Le.I16(b, 22) / GScale;
        NorthX = Le.I16(b, 24) / 32767f;
        NorthY = Le.I16(b, 26) / 32767f;
        NorthZ = Le.I16(b, 28) / 32767f;
        Axes = b[30];
        RatchetPresses = b[31];
        LayoutVersion = Le.U16(b, 32);
        Flags = b[34];
        FingerCount = Math.Min((int)b[35], MaxFingers);
        for (int i = 0; i < FingerCount; i++)
        {
            Fingers[i * 2] = Le.U16(b, 36 + i * 4) / 65535f;
            Fingers[i * 2 + 1] = Le.U16(b, 38 + i * 4) / 65535f;
        }
        b.Slice(56, 16).CopyTo(Pressed);
        b.Slice(72, 16).CopyTo(Latched);
        StickCount = 0;
        for (int k = 0; k < MaxSticks; k++)
        {
            int at = 88 + k * 3;
            if (b[at] == NoControl) continue;
            StickIndex[StickCount] = b[at];
            StickXY[StickCount * 2] = (sbyte)b[at + 1] / 127f;
            StickXY[StickCount * 2 + 1] = (sbyte)b[at + 2] / 127f;
            StickCount++;
        }
        CameraYaw = Finite(BitConverter.Int32BitsToSingle(Le.I32(b, 112)));
        CameraPitch = Finite(BitConverter.Int32BitsToSingle(Le.I32(b, 116)));
        CameraRoll = Finite(BitConverter.Int32BitsToSingle(Le.I32(b, 120)));
        RwsYaw = Gain(BitConverter.Int32BitsToSingle(Le.I32(b, 124)));
        RwsPitch = Gain(BitConverter.Int32BitsToSingle(Le.I32(b, 128)));
        RwsRoll = Gain(BitConverter.Int32BitsToSingle(Le.I32(b, 132)));
        PadFlags = b[136];
        PadL2Pull = b[137] / 255f;
        PadR2Pull = b[138] / 255f;
        PadButtons = Le.U32(b, 140);
        for (int i = 0; i < 4; i++) PadSticks[i] = Math.Max(-1f, (sbyte)b[144 + i] / 127f);
    }

    private static float Gain(float v) => float.IsFinite(v) && v > 0f ? Math.Min(v, 100f) : 0f;

    private static float Finite(float v) => float.IsFinite(v) ? Math.Clamp(v, -3600f, 3600f) : 0f;

    public void CopyTo(OverlayFrame o)
    {
        o.PhoneTimeUs = PhoneTimeUs;
        o.TurnYaw = TurnYaw; o.TurnPitch = TurnPitch; o.TurnRoll = TurnRoll; o.TurnSeconds = TurnSeconds;
        o.UpX = UpX; o.UpY = UpY; o.UpZ = UpZ;
        o.NorthX = NorthX; o.NorthY = NorthY; o.NorthZ = NorthZ;
        o.Axes = Axes;
        o.RatchetPresses = RatchetPresses;
        o.CameraYaw = CameraYaw; o.CameraPitch = CameraPitch; o.CameraRoll = CameraRoll;
        o.RwsYaw = RwsYaw; o.RwsPitch = RwsPitch; o.RwsRoll = RwsRoll;
        o.Qw = Qw; o.Qx = Qx; o.Qy = Qy; o.Qz = Qz;
        o.LayoutVersion = LayoutVersion;
        o.Flags = Flags;
        o.FingerCount = FingerCount;
        Array.Copy(Fingers, o.Fingers, Fingers.Length);
        Array.Copy(Pressed, o.Pressed, Pressed.Length);
        Array.Copy(Latched, o.Latched, Latched.Length);
        o.StickCount = StickCount;
        o.PadFlags = PadFlags; o.PadButtons = PadButtons; o.PadL2Pull = PadL2Pull; o.PadR2Pull = PadR2Pull;
        Array.Copy(PadSticks, o.PadSticks, PadSticks.Length);
        Array.Copy(StickIndex, o.StickIndex, StickIndex.Length);
        Array.Copy(StickXY, o.StickXY, StickXY.Length);
    }
}

public enum OverlayControlKind : byte { Button = 0, Stick = 1, Corner = 2, Display = 3 }

/// <summary>One control of <see cref="OverlayLayout"/>: centre and size as fractions of the pad area.</summary>
public sealed record OverlayControl(OverlayControlKind Kind, bool Round, float X, float Y, float W, float H, float Opacity, string Label);

/// <summary>What the phone's screen shows: the pad area's pixel size, names, and the controls back to front.</summary>
public sealed record OverlayLayout(ushort Version, int WidthPx, int HeightPx, string Workspace, string Layer, IReadOnlyList<OverlayControl> Controls)
{
    public const float CoordScale = 8192f;

    /// <summary>Decodes a layout blob, or null when it is malformed.</summary>
    public static OverlayLayout? Decode(ushort version, ReadOnlySpan<byte> b)
    {
        int p = 0;
        int total = b.Length;
        bool Has(int n) => p + n <= total;
        if (!Has(5) || b[0] != 1) return null;
        p = 1;
        int w = Le.U16(b, p); p += 2;
        int h = Le.U16(b, p); p += 2;
        string? Str(ReadOnlySpan<byte> s)
        {
            if (p + 1 > s.Length) return null;
            int n = s[p];
            if (p + 1 + n > s.Length) return null;
            string v = Encoding.UTF8.GetString(s.Slice(p + 1, n));
            p += 1 + n;
            return v;
        }
        var ws = Str(b);
        var layer = Str(b);
        if (ws == null || layer == null || !Has(1)) return null;
        int count = b[p++];
        var list = new List<OverlayControl>(count);
        for (int i = 0; i < count; i++)
        {
            if (!Has(11)) return null;
            var kind = (OverlayControlKind)b[p];
            bool round = (b[p + 1] & 1) != 0;
            float x = Le.I16(b, p + 2) / CoordScale;
            float y = Le.I16(b, p + 4) / CoordScale;
            float cw = Le.I16(b, p + 6) / CoordScale;
            float ch = Le.I16(b, p + 8) / CoordScale;
            float op = b[p + 10] / 255f;
            p += 11;
            var label = Str(b);
            if (label == null) return null;
            list.Add(new OverlayControl(kind, round, x, y, cw, ch, op, label));
        }
        return new OverlayLayout(version, w, h, ws, layer, list);
    }
}

/// <summary>Puts OVERLAY_LAYOUT pieces back together; a new version starts over.</summary>
public sealed class OverlayLayoutAssembler
{
    public const int PieceData = Wire.OverlayLayoutPlain - 5;

    private int _version = -1;
    private int _count;
    private byte[]?[] _pieces = [];

    /// <summary>The newest complete layout, or null before one arrived.</summary>
    public OverlayLayout? Layout { get; private set; }

    /// <summary>Takes one piece; returns true when it completed a layout (now <see cref="Layout"/>).</summary>
    public bool Add(ReadOnlySpan<byte> plain)
    {
        ushort version = Le.U16(plain, 0);
        int index = plain[2];
        int count = plain[3];
        int len = plain[4];
        if (count == 0 || index >= count || len > PieceData) return false;
        if (Layout != null && Layout.Version == version) return false; // already have it
        if (version != _version || count != _count)
        {
            _version = version;
            _count = count;
            _pieces = new byte[]?[count];
        }
        _pieces[index] ??= plain.Slice(5, len).ToArray();
        foreach (var piece in _pieces) if (piece == null) return false;
        var blob = new byte[_pieces.Sum(x => x!.Length)];
        int at = 0;
        foreach (var piece in _pieces)
        {
            piece!.CopyTo(blob, at);
            at += piece.Length;
        }
        var l = OverlayLayout.Decode(version, blob);
        _pieces = new byte[]?[count];
        if (l == null) return false;
        Layout = l;
        return true;
    }

    public void Reset()
    {
        _version = -1;
        _count = 0;
        _pieces = [];
        Layout = null;
    }
}
