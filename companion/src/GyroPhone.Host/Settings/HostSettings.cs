using System.Text.Json.Serialization;
using GyroPhone.Protocol;

namespace GyroPhone.Host.Settings;

/// <summary>Persisted host settings (%APPDATA%\GyroPhone\settings.json). byte[] fields serialize as base64.</summary>
public sealed class HostSettings
{
    public const int CurrentVersion = 1;

    public int Version { get; set; } = CurrentVersion;
    public byte[] HostId { get; set; } = [];
    public string HostName { get; set; } = "";
    public OutputMode OutputMode { get; set; } = OutputMode.Xbox360;
    public bool StartWithWindows { get; set; }
    public TrayClickAction TrayClick { get; set; } = TrayClickAction.OpenMainWindow;
    /// <summary>Switch a phone on the USB cable to accessory mode and use it as a link (Windows only).</summary>
    public bool UsbDirect { get; set; } = true;
    public List<PairedPhone> PairedPhones { get; set; } = [];
    /// <summary>The phone overlay window (tray menu "Phone overlay").</summary>
    public OverlaySettings Overlay { get; set; } = new();

    public static HostSettings CreateDefault(string hostName)
    {
        var id = new byte[Wire.IdSize];
        System.Security.Cryptography.RandomNumberGenerator.Fill(id);
        return new HostSettings { HostId = id, HostName = hostName };
    }
}

/// <summary>What a left click on the tray icon does.</summary>
public enum TrayClickAction
{
    OpenMainWindow,
    PairNewPhone,
}

/// <summary>What fills the phone overlay around the phone.</summary>
public enum OverlayBackground
{
    /// <summary>See-through: only the phone shows over the game.</summary>
    Transparent,
    /// <summary>Solid green, for an OBS chroma key.</summary>
    Green,
    Dark,
}

/// <summary>The phone overlay: a window showing the phone in 3D with its layout and presses.</summary>
public sealed class OverlaySettings
{
    public bool Shown { get; set; }
    /// <summary>The mouse goes through the window to the game; off: drag to move, drag the corner to resize.</summary>
    public bool ClickThrough { get; set; } = true;
    public OverlayBackground Background { get; set; } = OverlayBackground.Transparent;
    /// <summary>Turns are shown this many times larger (0.5..3).</summary>
    public float MotionScale { get; set; } = 1f;
    /// <summary>Dots where the fingers touch the screen.</summary>
    public bool FingerDots { get; set; }
    /// <summary>Window place and size; X, Y = -1 until placed.</summary>
    public int X { get; set; } = -1;
    public int Y { get; set; } = -1;
    public int Width { get; set; } = 420;
    public int Height { get; set; } = 300;
    /// <summary>The pose shown square on (Recenter), as a quaternion w, x, y, z; null = level, facing the player.</summary>
    public float[]? Center { get; set; }
}

public sealed class PairedPhone
{
    public byte[] PhoneId { get; set; } = [];
    public string Name { get; set; } = "";
    /// <summary>pairKey wrapped by the IKeyProtector.</summary>
    public byte[] ProtectedKey { get; set; } = [];
    public DateTime PairedUtc { get; set; }
    public DateTime LastSeenUtc { get; set; }
}

[JsonSourceGenerationOptions(WriteIndented = true, UseStringEnumConverter = true)]
[JsonSerializable(typeof(HostSettings))]
internal sealed partial class HostSettingsJson : JsonSerializerContext;
