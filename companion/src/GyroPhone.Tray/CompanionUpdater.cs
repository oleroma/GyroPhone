using System.ComponentModel;
using System.Diagnostics;
using GyroPhone.Host.Update;

namespace GyroPhone.Tray;

/// <summary>
/// Restarts into a newer build that update.cmd put at this exe's path. update.cmd renames the running
/// exe to "&lt;exe&gt;.old" (Windows allows renaming a running image) and copies the new one in its place;
/// this notices the file's version changed and, once no phone is connected, starts the new exe and
/// exits. The path never changes, so the firewall rule and the Start with Windows entry stay valid,
/// and settings and pairing live in %APPDATA%\GyroPhone. Local builds (no CI version) never restart.
/// Runs on the UI thread.
/// </summary>
internal sealed class CompanionUpdater : IDisposable
{
    private const int TickMs = 5_000;

    private readonly string _exePath, _oldPath;
    private readonly string _version;
    private readonly Action<string> _log;
    private readonly Func<bool> _isIdle;
    private readonly Action _exitForUpdate;
    private readonly System.Windows.Forms.Timer _timer;
    private bool _restarting, _disposed;

    /// <summary>The build waiting at this exe's path for the phone to disconnect, or null.</summary>
    public string? Pending { get; private set; }

    public event Action? Changed;

    public CompanionUpdater(string exePath, string version, Action<string> log, Func<bool> isIdle, Action exitForUpdate)
    {
        _exePath = exePath;
        _oldPath = exePath + ".old";
        _version = version;
        _log = log;
        _isIdle = isIdle;
        _exitForUpdate = exitForUpdate;
        _timer = new System.Windows.Forms.Timer { Interval = TickMs };
        _timer.Tick += (_, _) => Tick();
    }

    public void Start()
    {
        if (UpdateInfo.BuildNumber(_version) == null)
        {
            _log($"self-restart off: {_version} is not a CI build");
            return;
        }
        _timer.Start();
        Tick();
    }

    /// <summary>Restarts now if a new build is waiting and no phone is connected.</summary>
    public void TryRestart()
    {
        var pending = Pending;
        if (pending == null || _restarting || _disposed || !_isIdle()) return;
        _restarting = true;
        _timer.Stop();
        _log($"new build {pending} at {_exePath}, restarting");
        try
        {
            using var p = Process.Start(new ProcessStartInfo(_exePath, $"{Program.RestartedArg} {Program.UpdatedArg}")
            {
                UseShellExecute = false, // keeps this instance's elevation
                WorkingDirectory = Path.GetDirectoryName(_exePath) ?? "",
            });
        }
        catch (Win32Exception e)
        {
            _log($"restart into {pending} failed: {e.NativeErrorCode}");
            Pending = null;
            Changed?.Invoke();
            return; // keep running this build; the new one starts next time
        }
        _exitForUpdate();
    }

    private void Tick()
    {
        if (File.Exists(_oldPath)) TryDelete(_oldPath); // fails while an instance still runs from it
        string? onDisk = DiskVersion();
        string? pending = UpdateInfo.IsReplaced(_version, onDisk) ? onDisk : null;
        if (pending != Pending)
        {
            Pending = pending;
            if (pending != null) _log($"new build {pending} installed, restarting when no phone is connected");
            Changed?.Invoke();
        }
        TryRestart();
    }

    private string? DiskVersion()
    {
        try
        {
            return File.Exists(_exePath) ? FileVersionInfo.GetVersionInfo(_exePath).ProductVersion : null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null; // being copied
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // still running; retried on a later tick
        }
    }

    public void Dispose()
    {
        _disposed = true;
        _timer.Dispose();
    }
}
