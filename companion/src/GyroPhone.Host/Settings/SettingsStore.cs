using System.Text;
using System.Text.Json;

namespace GyroPhone.Host.Settings;

/// <summary>
/// Loads and atomically saves <see cref="HostSettings"/>: write .tmp and flush it to the disk, then
/// File.Replace keeping .bak. A corrupt file is renamed to .bad-&lt;timestamp&gt; and the .bak copy is
/// used instead; defaults (a new HostId, no paired phones) only when neither can be read. A file that
/// another program keeps locked is never replaced: Load throws instead.
/// </summary>
public sealed class SettingsStore
{
    /// <summary>How long a locked file is waited for by default (a start by hand: the user can try again).</summary>
    public const int DefaultLockedWaitMs = 3000;
    /// <summary>The wait for a start by the watcher or an update, right after login or a copy.</summary>
    public const int LongLockedWaitMs = 15_000;

    private readonly object _writeLock = new();

    public SettingsStore(string path, int lockedWaitMs = DefaultLockedWaitMs)
    {
        Path = path;
        LockedWaitMs = lockedWaitMs;
    }

    public string Path { get; }

    /// <summary>How long a file another program holds is retried before Load gives up.</summary>
    public int LockedWaitMs { get; }

    public static string DefaultPath() => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GyroPhone", "settings.json");

    /// <summary>
    /// Loads settings, falling back to the .bak copy, then to (saved) defaults, when the file is missing or corrupt.
    /// Throws IOException when a file that is there stays locked, so its pairings are never overwritten.
    /// </summary>
    public HostSettings Load(string defaultHostName, Action<string>? log = null)
    {
        // Each start (an update restarts it too) keeps the file as the last run left it, before anything saves over it.
        TakeBackup(onlyIfChanged: true, log);
        if (File.Exists(Path))
        {
            var s = Read(Path, defaultHostName, log, LockedWaitMs, out bool corrupt);
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
            var b = Read(bak, defaultHostName, log, LockedWaitMs, out _);
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

    /// <summary>One settings file, validated; null if its content is bad. Throws IOException when it stays unreadable.</summary>
    private static HostSettings? Read(string path, string defaultHostName, Action<string>? log, int lockedWaitMs, out bool corrupt)
    {
        corrupt = false;
        var name = System.IO.Path.GetFileName(path);
        var waited = System.Diagnostics.Stopwatch.StartNew();
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                var s = JsonSerializer.Deserialize(File.ReadAllText(path), HostSettingsJson.Default.HostSettings);
                if (s == null || s.HostId == null || s.HostId.Length != Protocol.Wire.IdSize) throw new InvalidDataException("missing hostId");
                if (string.IsNullOrEmpty(s.HostName)) s.HostName = defaultHostName;
                s.Normalize();
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
                if (waited.ElapsedMilliseconds >= lockedWaitMs)
                {
                    // Not corrupt, only locked: falling back would save defaults or the older .bak over it.
                    log?.Invoke($"{name} unreadable ({e.GetType().Name})");
                    throw new IOException($"{name} is held by another program", e);
                }
                Thread.Sleep(LockedRetryDelayMs(attempt));
            }
        }
    }

    /// <summary>The pause before attempt <paramref name="attempt"/> + 1 at a locked file: 100 ms, growing to 1 s.</summary>
    public static int LockedRetryDelayMs(int attempt) => Math.Min(100 * attempt, 1000);

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
            if (DateTime.UtcNow - _lastBackupUtc >= BackupEvery) TakeBackup(onlyIfChanged: true, null);
        }
    }

    /// <summary>How many automatic backups are kept in <see cref="BackupDir"/>, newest first.</summary>
    public const int BackupsKept = 20;

    /// <summary>A save takes an automatic backup when the last one is this old.</summary>
    public static readonly TimeSpan BackupEvery = TimeSpan.FromMinutes(10);

    private DateTime _lastBackupUtc = DateTime.MinValue;

    /// <summary>The automatic backups' folder: "backups" beside the settings file.</summary>
    public string BackupDir => System.IO.Path.Combine(System.IO.Path.GetDirectoryName(Path) ?? ".", "backups");

    /// <summary>
    /// Copies the settings file to <see cref="BackupDir"/> as settings-&lt;time&gt;.json (a copy the
    /// same as the newest one is skipped when <paramref name="onlyIfChanged"/>), keeping the newest
    /// <see cref="BackupsKept"/>. A failure only logs: a backup never stops a start or a save.
    /// </summary>
    public void TakeBackup(bool onlyIfChanged, Action<string>? log)
    {
        lock (_writeLock)
        {
            try
            {
                if (!File.Exists(Path)) return;
                Directory.CreateDirectory(BackupDir);
                var now = File.ReadAllBytes(Path);
                var old = Directory.GetFiles(BackupDir, "settings-*.json").OrderByDescending(f => f, StringComparer.Ordinal).ToList();
                _lastBackupUtc = DateTime.UtcNow;
                if (onlyIfChanged && old.Count > 0 && File.ReadAllBytes(old[0]).AsSpan().SequenceEqual(now)) return;
                var stamp = $"settings-{DateTime.Now:yyyyMMdd-HHmmss-fff}";
                var name = System.IO.Path.Combine(BackupDir, stamp + ".json");
                for (int n = 2; File.Exists(name); n++) name = System.IO.Path.Combine(BackupDir, $"{stamp}-{n}.json");
                File.WriteAllBytes(name, now);
                old.Insert(0, name);
                foreach (var f in old.Skip(BackupsKept)) File.Delete(f);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                log?.Invoke($"settings backup failed: {e.GetType().Name}");
            }
        }
    }
}
