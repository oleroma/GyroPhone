using System.Text.Json;

namespace GyroPhone.Host.Settings;

/// <summary>
/// Loads and atomically saves <see cref="HostSettings"/>: write .tmp, then File.Replace keeping .bak.
/// A corrupt file is renamed to .bad-&lt;timestamp&gt; and defaults are used.
/// </summary>
public sealed class SettingsStore
{
    private readonly object _writeLock = new();

    public SettingsStore(string path) => Path = path;

    public string Path { get; }

    public static string DefaultPath() => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GyroPhone", "settings.json");

    /// <summary>Loads settings, creating (and saving) defaults when the file is missing or corrupt.</summary>
    public HostSettings Load(string defaultHostName, Action<string>? log = null)
    {
        if (File.Exists(Path))
        {
            try
            {
                var s = JsonSerializer.Deserialize(File.ReadAllText(Path), HostSettingsJson.Default.HostSettings);
                if (s != null && s.HostId.Length == Protocol.Wire.IdSize)
                {
                    if (string.IsNullOrEmpty(s.HostName)) s.HostName = defaultHostName;
                    s.PairedPhones ??= [];
                    return s;
                }
                throw new InvalidDataException("missing hostId");
            }
            catch (Exception e) when (e is JsonException or InvalidDataException or NotSupportedException)
            {
                var bad = $"{Path}.bad-{DateTime.UtcNow:yyyyMMddHHmmss}";
                try { File.Move(Path, bad, overwrite: true); } catch (IOException) { }
                log?.Invoke($"settings corrupt ({e.GetType().Name}); moved aside, using defaults");
            }
        }
        var d = HostSettings.CreateDefault(defaultHostName);
        Save(d);
        return d;
    }

    public static string Serialize(HostSettings s) => JsonSerializer.Serialize(s, HostSettingsJson.Default.HostSettings);

    public void Save(HostSettings s) => WriteAtomic(Serialize(s));

    public void WriteAtomic(string json)
    {
        lock (_writeLock)
        {
            var dir = System.IO.Path.GetDirectoryName(Path);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            var tmp = Path + ".tmp";
            File.WriteAllText(tmp, json);
            if (File.Exists(Path)) File.Replace(tmp, Path, Path + ".bak");
            else File.Move(tmp, Path);
        }
    }
}
