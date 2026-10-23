using System.Security.Cryptography;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol.Crypto;

namespace GyroPhone.Host;

/// <summary>A phone key from authorized_phones: the file it came from and what it holds.</summary>
public sealed record AuthorizedPhone(string File, PubKeyFile Key);

/// <summary>
/// Where key file pairing lives on the PC: %APPDATA%\GyroPhone\host.key (the private scalar, wrapped
/// by the IKeyProtector), host.pub next to it, and authorized_phones\*.pub.
/// </summary>
public sealed class KeyFiles
{
    public KeyFiles(string directory) => Directory = directory;

    public static string DefaultDirectory() => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GyroPhone");

    public string Directory { get; }
    public string HostKeyPath => Path.Combine(Directory, "host.key");
    public string HostPubPath => Path.Combine(Directory, "host.pub");
    public string AuthorizedDirectory => Path.Combine(Directory, "authorized_phones");

    /// <summary>
    /// The host's static key: loaded from host.key, or created on first start. host.pub is rewritten
    /// when it is missing or differs (for example after a rename of the PC).
    /// </summary>
    public P256 LoadOrCreateHostKey(IKeyProtector protector, string hostName, Action<string>? log = null)
    {
        System.IO.Directory.CreateDirectory(Directory);
        System.IO.Directory.CreateDirectory(AuthorizedDirectory);
        P256? key = null;
        if (File.Exists(HostKeyPath))
        {
            try
            {
                var d = protector.Unprotect(File.ReadAllBytes(HostKeyPath));
                key = P256.FromPrivateScalar(d);
                CryptographicOperations.ZeroMemory(d);
            }
            catch (Exception e) when (e is CryptographicException or ArgumentException or IOException)
            {
                // Never overwrite a key we cannot read (another Windows user's DPAPI blob, say).
                var bad = $"{HostKeyPath}.bad-{DateTime.UtcNow:yyyyMMddHHmmss}";
                try { File.Move(HostKeyPath, bad, overwrite: true); } catch (IOException) { }
                log?.Invoke($"host key unreadable ({e.GetType().Name}); moved aside, making a new one");
            }
        }
        if (key == null)
        {
            key = P256.Generate();
            var d = key.ExportPrivateScalar();
            WriteAtomic(HostKeyPath, protector.Protect(d));
            CryptographicOperations.ZeroMemory(d);
            log?.Invoke($"created host key {KeyPairing.Fingerprint(key.PublicKey)}");
        }
        var line = new PubKeyFile(key.PublicKey, hostName).Format();
        try
        {
            if (!File.Exists(HostPubPath) || File.ReadAllText(HostPubPath) != line)
                WriteAtomic(HostPubPath, System.Text.Encoding.UTF8.GetBytes(line));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log?.Invoke($"could not write host.pub: {e.GetType().Name}");
        }
        return key;
    }

    /// <summary>Every valid .pub in authorized_phones, in file name order. Unreadable files are skipped.</summary>
    public List<AuthorizedPhone> ReadAuthorized(Action<string>? log = null) => ReadAuthorized(log, out _);

    /// <summary>Same, and <paramref name="summary"/> says what the scan saw (for the tray's Status window).</summary>
    public List<AuthorizedPhone> ReadAuthorized(Action<string>? log, out string summary)
    {
        var list = new List<AuthorizedPhone>();
        if (!System.IO.Directory.Exists(AuthorizedDirectory))
        {
            summary = "folder missing";
            return list;
        }
        int files = 0;
        var problems = new List<string>();
        foreach (var f in System.IO.Directory.EnumerateFiles(AuthorizedDirectory, "*.pub").Order(StringComparer.OrdinalIgnoreCase))
        {
            files++;
            string text;
            try
            {
                text = File.ReadAllText(f);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                problems.Add($"{Path.GetFileName(f)}: {e.GetType().Name}");
                continue; // still being written: the watcher fires again when it is closed
            }
            if (PubKeyFile.Parse(text) is { } k) list.Add(new AuthorizedPhone(f, k));
            else
            {
                problems.Add($"{Path.GetFileName(f)}: not a key file");
                log?.Invoke($"ignoring {Path.GetFileName(f)}: not a GyroPhone key file");
            }
        }
        summary = $"{list.Count} of {files} .pub file(s) valid" + (problems.Count > 0 ? $" ({string.Join("; ", problems)})" : "");
        return list;
    }

    /// <summary>
    /// Writes <paramref name="key"/> to authorized_phones (QR code pairing) and returns its path. A file
    /// already holding the same key is reused; a name taken by another key gets " 2", " 3", ...
    /// </summary>
    public string AddAuthorized(PubKeyFile key)
    {
        System.IO.Directory.CreateDirectory(AuthorizedDirectory);
        foreach (var a in ReadAuthorized())
            if (a.Key.PublicKey.AsSpan().SequenceEqual(key.PublicKey)) return a.File;
        var baseName = key.Name.Length > 0 ? key.Name : "Phone";
        var path = Path.Combine(AuthorizedDirectory, KeyPairing.FileName(baseName));
        for (int n = 2; File.Exists(path); n++) path = Path.Combine(AuthorizedDirectory, KeyPairing.FileName($"{baseName} {n}"));
        WriteAtomic(path, System.Text.Encoding.UTF8.GetBytes(key.Format()));
        return path;
    }

    private static void WriteAtomic(string path, byte[] bytes)
    {
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, bytes);
        File.Move(tmp, path, overwrite: true);
    }
}

/// <summary>
/// Watches authorized_phones and hands the current list to <paramref name="onChange"/> (on a thread
/// pool thread) shortly after any change, plus once at start. Adding a file authorizes that phone
/// at once; deleting it revokes it.
/// </summary>
public sealed class AuthorizedPhonesWatcher : IDisposable
{
    private const int DebounceMs = 300;

    private readonly KeyFiles _files;
    private readonly Action<IReadOnlyList<AuthorizedPhone>> _onChange;
    private readonly Action<string>? _log;
    private readonly FileSystemWatcher? _watcher;
    private readonly Timer _debounce;
    private readonly object _lock = new();
    private bool _disposed;

    public AuthorizedPhonesWatcher(KeyFiles files, Action<IReadOnlyList<AuthorizedPhone>> onChange, Action<string>? log = null)
    {
        _files = files;
        _onChange = onChange;
        _log = log;
        _debounce = new Timer(_ => Reload(), null, Timeout.Infinite, Timeout.Infinite);
        try
        {
            System.IO.Directory.CreateDirectory(files.AuthorizedDirectory);
            _watcher = new FileSystemWatcher(files.AuthorizedDirectory)
            {
                NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.Size,
                IncludeSubdirectories = false,
            };
            _watcher.Created += (_, _) => Kick();
            _watcher.Changed += (_, _) => Kick();
            _watcher.Deleted += (_, _) => Kick();
            _watcher.Renamed += (_, _) => Kick();
            _watcher.Error += (_, e) =>
            {
                _log?.Invoke($"authorized_phones watcher error: {e.GetException().GetType().Name}");
                Kick();
            };
            _watcher.EnableRaisingEvents = true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException)
        {
            _log?.Invoke($"cannot watch authorized_phones: {e.GetType().Name}");
        }
        Reload();
    }

    /// <summary>What the last read of the folder saw, with its local time, or why it failed.</summary>
    public string LastScan { get; private set; } = "not read yet";

    /// <summary>Reads the folder again now (also used by "Forget" after deleting a file).</summary>
    public void Reload()
    {
        lock (_lock)
        {
            if (_disposed) return;
            try
            {
                var list = _files.ReadAuthorized(_log, out var summary);
                _onChange(list);
                LastScan = $"{summary}, {DateTime.Now:HH:mm:ss}";
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Security.SecurityException
                                           or CryptographicException or ArgumentException)
            {
                // A scan that fails keeps the phones from the last good one, and says why.
                LastScan = $"failed {DateTime.Now:HH:mm:ss}: {e.GetType().Name}: {e.Message}";
                _log?.Invoke($"reading authorized_phones failed: {e.GetType().Name}: {e.Message}");
            }
        }
    }

    private void Kick()
    {
        lock (_lock)
        {
            if (!_disposed) _debounce.Change(DebounceMs, Timeout.Infinite);
        }
    }

    public void Dispose()
    {
        lock (_lock)
        {
            if (_disposed) return;
            _disposed = true;
        }
        _watcher?.Dispose();
        _debounce.Dispose();
    }
}
