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
    /// <summary>The overlay windows (phone, 3D shape, attitude indicator, bubble disk), each with its own settings.</summary>
    public List<OverlaySettings> Overlays { get; set; } = [];
    /// <summary>Overlay windows can be dragged to move and resize; off, the mouse goes through them to the game.</summary>
    public bool OverlayEdit { get; set; }
    /// <summary>Every overlay window is hidden (shortcut or tray), each keeping its own Show for when this is off.</summary>
    public bool OverlaysHidden { get; set; }

    /// <summary>"Measure buttons" on Home's latency card: the phone sends PRESS_TIME.</summary>
    public bool MeasureButtons { get; set; }
    /// <summary>"Record input log" on the App page: the engine writes input.log (<see cref="InputRecorder"/>).</summary>
    public bool InputLog { get; set; } = true;
    /// <summary>"Catch-up time" on the App page, ms: the longest a lost or late stretch of mouse motion is spread over (<see cref="CatchUpChoices"/>; 0 = off).</summary>
    public int CatchUpMs { get; set; } = (int)(MouseCatchUp.DefaultMaxUs / 1000);
    /// <summary>The "Catch-up time" choices, ms.</summary>
    public static readonly int[] CatchUpChoices = [0, 8, 16, 32];
    /// <summary>The nearest of <see cref="CatchUpChoices"/> (an edited file may hold anything).</summary>
    public static int ClampCatchUpMs(int ms) => CatchUpChoices.MinBy(c => Math.Abs(c - ms));
    /// <summary>The overlays' global shortcuts.</summary>
    public OverlayShortcuts OverlayShortcuts { get; set; } = new();
    /// <summary>"Fade when phone rests": every overlay fades while the phone lies still at its Level, untouched.</summary>
    public OverlayRestFade OverlayRestFade { get; set; } = new();
    /// <summary>The one phone overlay of earlier builds: read once into <see cref="Overlays"/>.</summary>
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public OverlaySettings? Overlay { get; set; }

    /// <summary>Fills in what an older or hand-edited file lacks.</summary>
    public void Normalize()
    {
        PairedPhones ??= [];
        CatchUpMs = ClampCatchUpMs(CatchUpMs);
        Overlays ??= [];
        OverlayShortcuts ??= new();
        OverlayShortcuts.Normalize();
        OverlayRestFade ??= new();
        OverlayRestFade.Normalize();
        if (Overlay != null)
        {
            if (Overlays.Count == 0) Overlays.Add(Overlay);
            Overlay = null;
        }
        Overlays.RemoveAll(o => o == null);
        if (Overlays.Count == 0) Overlays.Add(new OverlaySettings());
        var ids = new HashSet<string>();
        foreach (var o in Overlays)
        {
            if (string.IsNullOrEmpty(o.Id) || !ids.Add(o.Id))
            {
                o.Id = OverlaySettings.NewId();
                ids.Add(o.Id);
            }
            o.Colors ??= [];
            o.MigrateOld();
        }
    }

    public static HostSettings CreateDefault(string hostName)
    {
        var id = new byte[Wire.IdSize];
        System.Security.Cryptography.RandomNumberGenerator.Fill(id);
        var s = new HostSettings { HostId = id, HostName = hostName };
        s.Normalize();
        return s;
    }
}

/// <summary>What an overlay shortcut does.</summary>
public enum OverlayAction
{
    LevelAll,
    HideAll,
    Edit,
}

/// <summary>
/// The overlays' global shortcuts, as "Ctrl+Alt+R" (modifiers Ctrl, Alt, Shift, Win, then one key);
/// empty = none.
/// </summary>
public sealed class OverlayShortcuts
{
    public const string DefaultLevelAll = "Ctrl+Alt+R";
    public const string DefaultHideAll = "Ctrl+Alt+O";
    public const string DefaultEdit = "Ctrl+Alt+E";

    public string? LevelAll { get; set; } = DefaultLevelAll;
    public string? HideAll { get; set; } = DefaultHideAll;
    public string? Edit { get; set; } = DefaultEdit;

    public static IReadOnlyList<OverlayAction> All { get; } = [OverlayAction.LevelAll, OverlayAction.HideAll, OverlayAction.Edit];

    public static string ActionName(OverlayAction a) => a switch
    {
        OverlayAction.LevelAll => "Level all",
        OverlayAction.HideAll => "Hide or show all overlays",
        _ => "Edit on or off",
    };

    public string Get(OverlayAction a) => (a switch
    {
        OverlayAction.LevelAll => LevelAll,
        OverlayAction.HideAll => HideAll,
        _ => Edit,
    }) ?? "";

    public void Set(OverlayAction a, string keys)
    {
        switch (a)
        {
            case OverlayAction.LevelAll: LevelAll = keys; break;
            case OverlayAction.HideAll: HideAll = keys; break;
            default: Edit = keys; break;
        }
    }

    /// <summary>A file without them gets the defaults (an empty one stays cleared).</summary>
    public void Normalize()
    {
        LevelAll ??= DefaultLevelAll;
        HideAll ??= DefaultHideAll;
        Edit ??= DefaultEdit;
    }
}

/// <summary>What a left click on the tray icon does.</summary>
public enum TrayClickAction
{
    OpenMainWindow,
    PairNewPhone,
}

/// <summary>
/// "Fade when phone rests" (all overlays): the phone lies at an overlay's Level tilt (within
/// <see cref="FlatWithinDeg"/>), turns slower than <see cref="StillBelowDps"/> and gets no input,
/// all for <see cref="QuietMs"/>: that overlay fades to <see cref="FadeTo"/> of its opacity over
/// <see cref="FadeMs"/>, and comes back at once on any input or movement.
/// </summary>
public sealed class OverlayRestFade
{
    public bool On { get; set; } = true;
    public float FlatWithinDeg { get; set; } = 10f;
    public float StillBelowDps { get; set; } = 0.5f;
    public int QuietMs { get; set; } = 3000;
    /// <summary>The opacity faded to, as a share of the overlay's own (0.05..1).</summary>
    public float FadeTo { get; set; } = 0.3f;
    public int FadeMs { get; set; } = 500;
    /// <summary>Also fade when no OVERLAY came for <see cref="QuietMs"/> (screen off, app closed, link stopped).</summary>
    public bool WhenSilent { get; set; } = true;

    public void Normalize()
    {
        FlatWithinDeg = Math.Clamp(float.IsFinite(FlatWithinDeg) ? FlatWithinDeg : 10f, 1f, 90f);
        StillBelowDps = Math.Clamp(float.IsFinite(StillBelowDps) ? StillBelowDps : 0.5f, 0.05f, 10f);
        QuietMs = Math.Clamp(QuietMs, 500, 60_000);
        FadeTo = Math.Clamp(float.IsFinite(FadeTo) ? FadeTo : 0.3f, 0.05f, 1f);
        FadeMs = Math.Clamp(FadeMs, 0, 5000);
    }
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
