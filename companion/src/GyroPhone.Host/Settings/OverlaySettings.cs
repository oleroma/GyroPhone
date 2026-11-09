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

/// <summary>What turns an overlay (as the phone's gyro displays' Turns with).</summary>
public enum OverlayTurnsWith
{
    /// <summary>How the phone is held: gravity, compass, ease back.</summary>
    Phone,
    /// <summary>Where the game's camera points: every camera move (gyro, touchpad, buttons...), as far as the camera turns.</summary>
    Camera,
    /// <summary>As Camera but rolling the other way, like a ball under the hand: still against the world while the gyro aims.</summary>
    Trackball,
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
    /// <summary>A see-through sphere seen from a fixed angle, a laser at its centre shining out of the phone's back onto its inside.</summary>
    Laser,
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
    /// <summary>A camera seen from behind: its lens points into the screen, where the camera looks.</summary>
    Camera,
}

/// <summary>One colour an overlay kind draws with: its settings key, its name and its default (ARGB).</summary>
public sealed record OverlayColor(string Key, string Label, uint Default);

/// <summary>One overlay window: what it shows, where, and how it turns.</summary>
/// <summary>
/// A trackball axis easing back to straight (OverlaySettings.TrackballEase): with OnRatchet while a ratchet is held
/// (stopping when let go), else whenever the phone stops turning; starting DelayMs later; 95% of the way back in
/// Seconds, or with BySpeed at Speed deg/s. As the phone's AxisEase.
/// </summary>
public sealed class AxisEase
{
    public bool On { get; set; }
    public bool OnRatchet { get; set; } = true;
    public int DelayMs { get; set; } = 300;
    public bool BySpeed { get; set; }
    public float Seconds { get; set; } = 2f;
    public int Speed { get; set; } = 90;
}

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
    /// <summary>Phone and 3D shape: seen from a fixed angle (30° round, 30° above) with the world's axes, as the laser pointer is, instead of face-on.</summary>
    public bool Axonometric { get; set; }
    /// <summary>What turns it: the phone, the game's camera, or a trackball (see <see cref="OverlayTurnsWith"/>).</summary>
    public OverlayTurnsWith TurnsWith { get; set; } = OverlayTurnsWith.Phone;
    /// <summary>
    /// Following the camera: with <see cref="TrackballRws"/> as far as the game's camera (once the
    /// game's turn is known on the phone), else <see cref="TrackballSpin"/> times the phone's turn.
    /// </summary>
    public bool TrackballRws { get; set; } = true;
    public float TrackballSpin { get; set; } = 1f;
    /// <summary>
    /// Trackball: which gyro turn rolls it about each of its axes, one byte each: about its up axis
    /// Y (yaw, turning left and right), its right axis X (pitch), the axis along the beam Z (roll, through the screen at rest); each byte
    /// 1 yaw (as sent), 2 pitch, 3 roll (as sent), 0 none, | 0x80 the other way, | TrackballStop stopping at ±90°, | TrackballLock locked. The default turns it
    /// as Camera does with the default gyro mouse. As the phone's GyroDisplay.trackballAxes.
    /// </summary>
    public int TrackballAxes { get; set; } = TrackballAsCamera;

    /// <summary>
    /// Trackball: easing each of its axes (as <see cref="TrackballAxes"/>: up, right, through the screen) back to
    /// straight, each with its own options. Easing any turns it as a gimbal, as a lock does. As the phone's
    /// GyroDisplay.trackballEase.
    /// </summary>
    public AxisEase[] TrackballEase { get; set; } = [new(), new(), new()];

    /// <summary>Trackball axis <paramref name="i"/>'s easing, made when not saved.</summary>
    public AxisEase EaseOf(int i)
    {
        if (TrackballEase is not { Length: 3 })
        {
            var old = TrackballEase ?? [];
            TrackballEase = [.. Enumerable.Range(0, 3).Select(k => k < old.Length && old[k] != null ? old[k] : new AxisEase())];
        }
        return TrackballEase[i] ??= new AxisEase();
    }

    /// <summary><see cref="TrackballAxes"/> turning it as Camera does with the default gyro mouse (roll on mouse X, pitch on mouse Y): left and right by roll (as sent), up and down by pitch, the axis through the screen locked.</summary>
    public const int TrackballAsCamera = 3 | ((2 | TrackballStop) << 8) | (TrackballLock << 16);

    /// <summary>
    /// In a <see cref="TrackballAxes"/> byte: that axis is locked, held at 0 whatever turns the others make, as
    /// Camera's roll is. With any axis locked it turns as a gimbal (left and right about the vertical, then up
    /// and down about its own right, then about where it faces); with the axis through the screen locked it
    /// stops at straight up or down (±89°). As the phone's TRACKBALL_LOCK.
    /// </summary>
    public const int TrackballLock = 0x40;

    /// <summary>
    /// In a <see cref="TrackballAxes"/> byte: that axis stops at ±90° from straight (up and down at ±89°, the poles);
    /// without, it turns all the way round (up and down over the top, left and right then staying as seen). As the
    /// phone's TRACKBALL_STOP.
    /// </summary>
    public const int TrackballStop = 0x20;

    /// <summary><see cref="TrackballAxes"/> turning it as the phone turns: each axis by its own gyro turn.</summary>
    public const int TrackballAsPhone = 1 | (2 << 8) | (3 << 16);

    // Trackball settings of earlier builds: read once by MigrateOld, never written back.
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Trackball { get; set; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? TrackballInvert { get; set; }

    // Bubble disk.
    /// <summary>The tilt that puts the dot on the rim, in degrees.</summary>
    public int DiskRange { get; set; } = 30;
    public bool Crosshair { get; set; } = true;

    // Laser pointer.
    /// <summary>The 15° grid on the sphere, and its three axes.</summary>
    public bool LaserGrid { get; set; } = true;
    /// <summary>Laser pointer: seen from the fixed axonometric angle (30° round, 30° above); off, face-on.</summary>
    public bool LaserAxonometric { get; set; } = true;
    /// <summary>Laser pointer: the beam stays pointing ahead and the sphere (grid, axes, paint) turns under it instead.</summary>
    public bool LaserSphereTurns { get; set; }
    /// <summary>Laser pointer: a rifle aiming instead of the sphere and beam; Draw mode still paints where it points.</summary>
    public bool LaserRifle { get; set; }
    /// <summary>
    /// Laser pointer: a crosshair where the beam lands instead of the sphere and beam (not with LaserRifle): lying
    /// on the sphere facing its centre, shape CrosshairShape (LaserTrace.Crosshairs), arms CrosshairSize degrees
    /// long from a CrosshairGap degree gap, lines CrosshairThickness thick, a centre dot, a dark outline; turning
    /// about the beam as the pose does (a trackball's roll axis as mapped). As the phone's GyroDisplay.laserCrosshair.
    /// </summary>
    public bool LaserCrosshair { get; set; }
    public int CrosshairShape { get; set; } = 1;
    public int CrosshairSize { get; set; } = 6;
    public int CrosshairGap { get; set; } = 2;
    public float CrosshairThickness { get; set; } = 2f;
    public bool CrosshairDot { get; set; } = true;
    public bool CrosshairOutline { get; set; } = true;
    /// <summary>Laser pointer turned as a trackball: which way of it the laser faces (0 −Z into the screen, 1 +Z, 2 +X, 3 −X, 4 +Y, 5 −Y).</summary>
    public int LaserFaces { get; set; }
    public bool LaserAxes { get; set; } = true;
    /// <summary>Laser pointer, Sphere look: the gimbal's yaw, pitch and roll rings (LaserTrace.GimbalRing), a locked axis's faint. As the phone's GyroDisplay.laserGimbal.</summary>
    public bool LaserGimbal { get; set; }
    /// <summary>Draw mode: the beam paints the sphere's inside, <see cref="LaserBrush"/> degrees round; it fades after <see cref="LaserFadeMs"/> ms (0 = never).</summary>
    public bool LaserDraw { get; set; }
    public int LaserBrush { get; set; } = 4;
    public int LaserFadeMs { get; set; }
    /// <summary>Draw mode: a ratchet press clears the drawing.</summary>
    public bool LaserClearOnRatchet { get; set; }
    /// <summary>
    /// Laser pointer Draw mode: while this output is held (LaserFire numbers, default the left mouse button), from the
    /// phone or, with "Use PC controller for gyro ratchet", the PC's controller, it paints in the Fire colours; with
    /// LaserFireOnly only then. As the phone's GyroDisplay.laserFire.
    /// </summary>
    public int LaserFire { get; set; } = GyroPhone.Host.LaserFire.Default;
    public bool LaserFireOnly { get; set; }

    // The pose, made on the PC (the phone's own gyro display settings are separate).
    /// <summary>
    /// The resting pose (Level), as a quaternion w, x, y, z: the phone held this way shows face-on, its
    /// screen flat toward the viewer. With gravity only the tilt is kept (the heading eases back on its
    /// own); null = upright, facing the player.
    /// </summary>
    public float[]? Rest { get; set; }
    /// <summary>
    /// Which way was up at the last Level (the accelerometer, phone frame, unit length), kept with or
    /// without gravity: "Fade when phone rests" counts the phone flat while it lies within its angle of this.
    /// </summary>
    public float[]? LevelUp { get; set; }
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

    // Ratchet settings of earlier builds: read once by MigrateOld, never written back.
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? ResetOnRatchet { get; set; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? ResetHeadingOnly { get; set; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? EaseAfterRatchet { get; set; }

    /// <summary>ARGB per <see cref="OverlayColor.Key"/> of this kind; missing = its default.</summary>
    public Dictionary<string, int> Colors { get; set; } = [];

    /// <summary>A trackball (no gravity, compass or ease back).</summary>
    [JsonIgnore]
    public bool IsTrackball => TurnsWith == OverlayTurnsWith.Trackball;

    /// <summary>The gimbal axes held at 0 (bit 0 yaw, 1 pitch, 2 roll): a trackball's locked rows, Camera's roll, none with the phone. As the phone's GyroDisplay.gimbalLocks.</summary>
    [JsonIgnore]
    public int GimbalLocks => TurnsWith switch
    {
        OverlayTurnsWith.Trackball => Enumerable.Range(0, 3).Aggregate(0, (acc, i) => ((TrackballAxes >> (8 * i)) & TrackballLock) != 0 ? acc | (1 << i) : acc),
        OverlayTurnsWith.Camera => 4,
        _ => 0,
    };

    /// <summary>It turns with the game's camera (no gravity, compass or turn amount).</summary>
    [JsonIgnore]
    public bool FollowsCamera => TurnsWith != OverlayTurnsWith.Phone;

    /// <summary>The phone sends its magnetometer readings for this one.</summary>
    [JsonIgnore]
    public bool NeedsCompass => Gravity && Compass && !FollowsCamera;

    public static string NewId() => Guid.NewGuid().ToString("N")[..12];

    /// <summary>
    /// Carries the trackball and ratchet settings of earlier builds over: Reset on ratchet becomes Ease on ratchet
    /// (its Heading only with it); off, easing stays as it was, when the phone stops ("Ratchet let go" is gone).
    /// </summary>
    public void MigrateOld()
    {
        // The trackball switch (3D shape only) and its Invert became Turns with.
        if (Trackball == true && Kind == OverlayKind.Shape)
            TurnsWith = TrackballInvert == true ? OverlayTurnsWith.Camera : OverlayTurnsWith.Trackball;
        Trackball = TrackballInvert = null;
        if (ResetOnRatchet == null && ResetHeadingOnly == null && EaseAfterRatchet == null) return;
        if (ResetOnRatchet ?? true) // its old default
        {
            Ease = true;
            EaseOnRatchet = true;
            EaseHeadingOnly = ResetHeadingOnly ?? false;
        }
        else
        {
            EaseOnRatchet = false;
            EaseHeadingOnly = false; // easing used to turn the whole pose back without gravity
        }
        ResetOnRatchet = ResetHeadingOnly = EaseAfterRatchet = null;
    }

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
        OverlayKind.Laser => LaserColors,
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
        OverlayKind.Laser => "Laser pointer",
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

    private static readonly OverlayColor[] LaserColors =
    [
        new("sphere", "Sphere", 0x332A3446),
        new("grid", "Grid", 0x66FFFFFF),
        new("x", "Pitch axis (X, right)", 0xFF5ADB6E),
        new("y", "Yaw axis (Y, up)", 0xFFFF5A5A),
        new("z", "Roll axis (Z, along the beam)", 0xFF5AA8FF),
        new("beam", "Beam", 0xFFFF3B3B),
        new("dot", "Dot", 0xFFFFFFFF),
        new("paint", "Paint ahead", 0xFFFFC94D),
        new("paint_behind", "Paint behind", 0xFF4DA6FF),
        new("rifle", "Rifle", 0xFF5A616C),
        new("fire", "Fire", 0xFFFF4A3D),
        new("fire_behind", "Fire behind", 0xFF8A1E18),
        new("crosshair", "Crosshair", 0xFF4CFF6A),
        new("crosshair_behind", "Crosshair behind", 0xFF2E8A43),
        new("crosshair_outline", "Crosshair outline", 0xFF0E1116),
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
