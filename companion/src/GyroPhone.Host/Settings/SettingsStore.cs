using System.Text;
using System.Text.Json;

namespace GyroPhone.Host.Settings;

/// <summary>
/// Loads and atomically saves <see cref="HostSettings"/>: write .tmp and flush it to the disk, then
/// File.Replace keeping .bak. A corrupt file is renamed to .bad-&lt;timestamp&gt; and the .bak copy is
/// used instead; defaults (a new HostId, no paired phones) only when neither can be read.
/// </summary>
public sealed class SettingsStore
{
    private const int ReadAttempts = 3;

    private readonly object _writeLock = new();

    public SettingsStore(string path) => Path = path;

    public string Path { get; }

    public static string DefaultPath() => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GyroPhone", "settings.json");

    /// <summary>Loads settings, falling back to the .bak copy, then to (saved) defaults, when the file is missing or corrupt.</summary>
    public HostSettings Load(string defaultHostName, Action<string>? log = null)
    {
        if (File.Exists(Path))
        {
            var s = Read(Path, defaultHostName, log, out bool corrupt);
            if (s != null) return s;
            if (corrupt)
            {
                var bad = $"{Path}.bad-{DateTime.UtcNow:yyyyMMddHHmmss}";
                try { File.Move(Path, bad, overwrite: true); } catch (IOException) { }
            }
        }
        var bak = Path + ".bak";
        if (File.Exists(bak))
        {
            var b = Read(bak, defaultHostName, log, out _);
            if (b != null)
            {
                log?.Invoke("settings restored from the .bak copy");
                TrySave(b, log);
                return b;
            }
        }
        if (File.Exists(Path) || File.Exists(bak)) log?.Invoke("settings unusable; using defaults");
        var d = HostSettings.CreateDefault(defaultHostName);
        TrySave(d, log);
        return d;
    }

    /// <summary>One settings file, validated; null if it cannot be used (<paramref name="corrupt"/>: its content is bad).</summary>
    private static HostSettings? Read(string path, string defaultHostName, Action<string>? log, out bool corrupt)
    {
        corrupt = false;
        var name = System.IO.Path.GetFileName(path);
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                var s = JsonSerializer.Deserialize(File.ReadAllText(path), HostSettingsJson.Default.HostSettings);
                if (s == null || s.HostId == null || s.HostId.Length != Protocol.Wire.IdSize) throw new InvalidDataException("missing hostId");
                if (string.IsNullOrEmpty(s.HostName)) s.HostName = defaultHostName;
                s.PairedPhones ??= [];
                return s;
            }
            catch (Exception e) when (e is JsonException or InvalidDataException or NotSupportedException)
            {
                corrupt = true;
                log?.Invoke($"{name} corrupt ({e.GetType().Name})");
                return null;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // A scanner or backup tool can hold the file for a moment, e.g. right after boot.
                if (attempt >= ReadAttempts)
                {
                    log?.Invoke($"{name} unreadable ({e.GetType().Name})");
                    return null;
                }
                Thread.Sleep(100 * attempt);
            }
        }
    }

    private void TrySave(HostSettings s, Action<string>? log)
    {
        try
        {
            Save(s);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log?.Invoke($"settings save failed: {e.GetType().Name}");
        }
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
            // On the disk before the rename: a power cut then leaves the old file or the new one, never zeros.
            using (var fs = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                fs.Write(Encoding.UTF8.GetBytes(json));
                fs.Flush(flushToDisk: true);
            }
            if (File.Exists(Path)) File.Replace(tmp, Path, Path + ".bak");
            else File.Move(tmp, Path);
        }
    }
}
