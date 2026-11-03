using System.Text.Json.Serialization;

namespace GyroPhone.Host.Settings;

/// <summary>What fills an overlay window around its drawing.</summary>
public enum OverlayBackground
{
    /// <summary>See-through: only the drawing shows over the game.</summary>
    Transparent,
    /// <summary>Solid green, for an OBS chroma key.</summary>
    Green,
    Dark,
}

/// <summary>How carefully an overlay window draws.</summary>
public enum OverlayQuality
{
    /// <summary>Smooth edges and text, careful blending.</summary>
    High,
    /// <summary>Smooth edges; plain text and quicker blending.</summary>
    Balanced,
    /// <summary>No smoothing at all: the quickest.</summary>
    Fast,
}

/// <summary>When the phone overlay draws controller handles on the phone's sides.</summary>
public enum OverlayHandles
{
    /// <summary>While a controller is attached to the phone.</summary>
    Auto,
    Always,
    Off,
}

/// <summary>Which controller the phone overlay's handles show.</summary>
public enum OverlayHandleSource
{
    /// <summary>The controller attached to the phone.</summary>
    Phone,
    /// <summary>The controller plugged into this PC, while the phone's "Use PC controller for gyro ratchet" is on.</summary>
    Pc,
}

/// <summary>What an overlay window shows (as the phone's gyro displays).</summary>
public enum OverlayKind
{
    /// <summary>The phone in 3D with the layout, presses and fingers on its screen.</summary>
    Phone,
    /// <summary>A 3D shape (<see cref="OverlayShape"/>) turned with the phone, or a trackball.</summary>
    Shape,
    /// <summary>An aircraft-style horizon with a heading tape.</summary>
    Horizon,
    /// <summary>A disk with clock marks that spins with the heading, a dot showing the tilt.</summary>
    Disk,
}

public enum OverlayShape
{
    Globe,
    Icosphere,
    Cube,
    Tetrahedron,
    Dodecahedron,
    Torus,
    /// <summary>Three rings, one per axis, lit while that axis moves the mouse.</summary>
    Gimbal,
    Plane,
}

/// <summary>One colour an overlay kind draws with: its settings key, its name and its default (ARGB).</summary>
public sealed record OverlayColor(string Key, string Label, uint Default);

/// <summary>One overlay window: what it shows, where, and how it turns.</summary>
public sealed class OverlaySettings
{
    public string Id { get; set; } = NewId();
    public OverlayKind Kind { get; set; } = OverlayKind.Phone;
    public bool Shown { get; set; }
    public OverlayBackground Background { get; set; } = OverlayBackground.Transparent;
    /// <summary>Turns are shown this many times larger (0.5..3).</summary>
    public float MotionScale { get; set; } = 1f;
    /// <summary>Window place and size; X, Y = -1 until placed.</summary>
    public int X { get; set; } = -1;
    public int Y { get; set; } = -1;
    public int Width { get; set; } = 420;
    public int Height { get; set; } = 300;
    /// <summary>0.1..1, applied when the window is drawn (costs nothing extra).</summary>
    public float Opacity { get; set; } = 1f;
    /// <summary>How carefully it draws (smooth edges cost the most on big windows).</summary>
    public OverlayQuality Quality { get; set; } = OverlayQuality.High;
    /// <summary>Most pictures per second: 60 (default) or 30, or 0 = every screen refresh (costs more on fast screens).</summary>
    public int MaxFps { get; set; } = 60;

    // Phone.
    /// <summary>Dots where the fingers touch the screen.</summary>
    public bool FingerDots { get; set; }
    /// <summary>A fading line behind each finger.</summary>
    public bool FingerTrails { get; set; } = true;
    /// <summary>Switch-style controller handles on the phone's sides, lit by the attached controller's presses.</summary>
    public OverlayHandles Handles { get; set; } = OverlayHandles.Auto;
    /// <summary>Which controller the handles show; with <see cref="OverlayHandles.Auto"/>, the one that must be there.</summary>
    public OverlayHandleSource HandleSource { get; set; } = OverlayHandleSource.Phone;

    // 3D shape.
    public OverlayShape Shape { get; set; } = OverlayShape.Globe;
    /// <summary>Icosphere: 0 to 3.</summary>
    public int Subdivision { get; set; } = 1;
    /// <summary>Edges bent onto a ball (round rings for the torus and gimbal), or straight.</summary>
    public bool Smooth { get; set; } = true;
    /// <summary>The edges round the back, faint.</summary>
    public bool HiddenEdges { get; set; } = true;
    /// <summary>The faces filled, shaded by how squarely they face the viewer.</summary>
    public bool FillFaces { get; set; } = true;
    public bool Equator { get; set; } = true;
    public bool ScreenMark { get; set; } = true;
    public bool TopMark { get; set; } = true;
    /// <summary>
    /// A trackball: while the gyro moves the mouse it holds still against the world; every other mouse
    /// move (touchpad, buttons...) turns it as the gyro turn that would send it. With <see cref="TrackballRws"/>
    /// as far as the game's camera (once the game's turn is known on the phone), else <see cref="TrackballSpin"/>
    /// times the phone's turn. No gravity, compass, reset or ease back.
    /// </summary>
    public bool Trackball { get; set; }
    public bool TrackballRws { get; set; } = true;
    public float TrackballSpin { get; set; } = 1f;

    // Bubble disk.
    /// <summary>The tilt that puts the dot on the rim, in degrees.</summary>
    public int DiskRange { get; set; } = 30;
    public bool Crosshair { get; set; } = true;

    // The pose, made on the PC (the phone's own gyro display settings are separate).
    /// <summary>
    /// The resting pose (Level), as a quaternion w, x, y, z: the phone held this way shows face-on, its
    /// screen flat toward the viewer. With gravity only the tilt is kept (the heading eases back on its
    /// own); null = upright, facing the player.
    /// </summary>
    public float[]? Rest { get; set; }
    /// <summary>Tilt held to gravity (never drifts); off, turned by the gyro axes that move the mouse.</summary>
    public bool Gravity { get; set; } = true;
    /// <summary>With gravity: the heading held by the phone's compass to the way it faced at the start.</summary>
    public bool Compass { get; set; }
    /// <summary>Goes back to straight on its own (the heading only with gravity).</summary>
    public bool Ease { get; set; } = true;
    /// <summary>
    /// While a ratchet is held it eases back until straight (stopping when let go), instead of easing whenever the phone stops turning.
    /// (Replaces Reset on ratchet and "Ratchet let go"; a ratchet press still aims the compass again.)
    /// </summary>
    public bool EaseOnRatchet { get; set; } = true;
    /// <summary>Without gravity, only the heading eases back (with gravity it always is).</summary>
    public bool EaseHeadingOnly { get; set; }
    public int EaseDelayMs { get; set; } = 300;
    /// <summary>Back at <see cref="EaseSpeed"/> deg/s, instead of over <see cref="EaseSeconds"/>.</summary>
    public bool EaseBySpeed { get; set; }
    public float EaseSeconds { get; set; } = 2f;
    public int EaseSpeed { get; set; } = 90;

    /// <summary>ARGB per <see cref="OverlayColor.Key"/> of this kind; missing = its default.</summary>
    public Dictionary<string, int> Colors { get; set; } = [];

    /// <summary>A trackball 3D shape (no gravity, compass, reset or ease back).</summary>
    [JsonIgnore]
    public bool IsTrackball => Kind == OverlayKind.Shape && Trackball;

    /// <summary>The phone sends its magnetometer readings for this one.</summary>
    [JsonIgnore]
    public bool NeedsCompass => Gravity && Compass && !IsTrackball;

    public static string NewId() => Guid.NewGuid().ToString("N")[..12];

    /// <summary>The colour <paramref name="key"/> (ARGB), or its default.</summary>
    public int Color(string key)
    {
        if (Colors.TryGetValue(key, out var c)) return c;
        foreach (var k in ColorsOf(Kind)) if (k.Key == key) return unchecked((int)k.Default);
        return unchecked((int)0xFFFFFFFF);
    }

    /// <summary>The colours <paramref name="kind"/> draws with (the 3D shape: those its <paramref name="shape"/> uses).</summary>
    public static IReadOnlyList<OverlayColor> ColorsOf(OverlayKind kind, OverlayShape? shape = null) => kind switch
    {
        OverlayKind.Phone => PhoneColors,
        OverlayKind.Horizon => HorizonColors,
        OverlayKind.Disk => DiskColors,
        _ => shape switch
        {
            null => ShapeColors,
            OverlayShape.Gimbal => ShapeColors.Where(c => c.Key.StartsWith('g')).ToArray(),
            OverlayShape.Globe => ShapeColors.Where(c => !c.Key.StartsWith('g')).ToArray(),
            _ => ShapeColors.Where(c => !c.Key.StartsWith('g') && c.Key != "equator").ToArray(),
        },
    };

    public static string KindName(OverlayKind kind) => kind switch
    {
        OverlayKind.Phone => "Phone",
        OverlayKind.Shape => "3D shape",
        OverlayKind.Horizon => "Attitude indicator",
        _ => "Bubble disk",
    };

    public static string ShapeName(OverlayShape s) => s switch
    {
        OverlayShape.Gimbal => "Gimbal rings",
        OverlayShape.Plane => "Paper plane",
        _ => s.ToString(),
    };

    private static readonly OverlayColor[] PhoneColors =
    [
        new("body", "Body", 0xFF2A2E35),
        new("screen", "Screen", 0xFF101318),
        new("lit", "Pressed", 0xFF7CC4FF),
        new("latched", "Latched", 0xFF8FE39A),
        new("fingers", "Fingers", 0xFFFFFFFF),
        new("handles", "Controller handles", 0xFF353A42),
        new("handlesLit", "Controller pressed", 0xFF7CC4FF),
    ];

    private static readonly OverlayColor[] ShapeColors =
    [
        new("body", "Fill", 0xFF2A3446),
        new("lines", "Lines", 0x99FFFFFF),
        new("equator", "Equator", 0xFF5AA8FF),
        new("mark", "Screen mark", 0xFFFFC94D),
        new("pole", "Top mark", 0xFFFF5A5A),
        new("gyaw", "Yaw ring", 0xFFFF5A5A),
        new("gpitch", "Pitch ring", 0xFF5ADB6E),
        new("groll", "Roll ring", 0xFF5AA8FF),
    ];

    private static readonly OverlayColor[] HorizonColors =
    [
        new("sky", "Sky", 0xFF2F6DB5),
        new("ground", "Ground", 0xFF7A5534),
        new("line", "Horizon line", 0xFFFFFFFF),
        new("ladder", "Pitch ladder", 0xB3FFFFFF),
        new("aircraft", "Aircraft symbol", 0xFFFFC94D),
        new("rim", "Rim", 0xCCFFFFFF),
        new("tape", "Heading tape", 0x66000000),
        new("text", "Heading numbers", 0xFFFFFFFF),
    ];

    private static readonly OverlayColor[] DiskColors =
    [
        new("face", "Disk", 0x66000000),
        new("rim", "Rim", 0xCCFFFFFF),
        new("marks", "Marks", 0xCCFFFFFF),
        new("north", "12 o'clock mark", 0xFFFF5A5A),
        new("cross", "Crosshair", 0x80FFFFFF),
        new("dot", "Dot", 0xFFFFC94D),
    ];
}
