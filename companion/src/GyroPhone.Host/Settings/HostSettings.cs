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
