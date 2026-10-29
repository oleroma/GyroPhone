using GyroPhone.Host.Update;

namespace GyroPhone.Tray;

/// <summary>
/// Shuts down, updates and starts again when a newer build waits next to this exe. The install hook never
/// touches a running exe: it puts the new build at "&lt;exe&gt;.new". This notices it within a few seconds,
/// stops the watcher, starts the .new file as an <see cref="UpdateFinisher"/> and exits cleanly (release-all,
/// bye). The finisher copies itself over this exe once it has exited and starts it again, even with a phone
/// connected (Olex: always restart on update); the phone reconnects by itself. The exe is single-file and
/// reads parts of itself by path while it runs, which is why it must never be renamed or replaced under a
/// running instance. The path never changes, so the firewall rule and the Start with Windows entry stay
/// valid, and settings and pairing live in %APPDATA%\GyroPhone. Local builds (no CI version) never update.
/// Runs on the UI thread.
/// </summary>
internal sealed class CompanionUpdater : IDisposable
{
    private const int TickMs = 5_000;

    private readonly string _exePath, _newPath, _oldPath;
    private readonly string _version;
    private readonly Action<string> _log;
    private readonly Action _exitForUpdate;
    private readonly System.Windows.Forms.Timer _timer;
    private bool _restarting, _disposed;
    private string? _lastNew = "";
    /// <summary>The .new build (<see cref="UpdateFinisher.StampOf"/>) a failed update came back from: not tried again until reinstalled.</summary>
    private readonly string? _failedStamp;

    /// <summary>The newer build next to this exe that this instance is about to restart into, or null.</summary>
    public string? Pending { get; private set; }

    public event Action? Changed;

    public CompanionUpdater(string exePath, string version, Action<string> log, Action exitForUpdate, bool updateFailed)
    {
        _exePath = exePath;
        _newPath = exePath + UpdateFinisher.NewSuffix;
        _oldPath = exePath + ".old";
        _version = version;
        _log = log;
        _exitForUpdate = exitForUpdate;
        _timer = new System.Windows.Forms.Timer { Interval = TickMs };
        _timer.Tick += (_, _) => Tick();
        if (updateFailed) _failedStamp = UpdateFinisher.StampOf(_newPath);
    }

    public void Start()
    {
        if (UpdateInfo.BuildNumber(_version) == null)
        {
            _log($"self-restart off: {_version} is not a CI build");
            return;
        }
        _log($"self-restart on: {_version} watches {_newPath}");
        if (_failedStamp != null) _log($"update to {_failedStamp} failed; not retrying it until it is installed again");
        _timer.Start();
        Tick();
    }

    /// <summary>Restarts now if a new build is waiting.</summary>
    public void TryRestart()
    {
        var pending = Pending;
        if (pending == null || _restarting || _disposed) return;
        _restarting = true;
        _timer.Stop();
        bool watcher = Watcher.IsRunning();
        if (watcher) Watcher.SignalStop();
        _log($"new build {pending} at {_newPath}, restarting{(watcher ? " with the watcher" : "")}");
        if (!UpdateFinisher.Launch(_newPath, tray: true, watch: watcher, _log))
        {
            Pending = null;
            Changed?.Invoke();
            return; // keep running this build; the update is tried again when .new changes
        }
        _exitForUpdate();
    }

    private void Tick()
    {
        if (File.Exists(_oldPath)) TryDelete(_oldPath); // left by builds that swapped the running exe
        string? onDisk = NewVersion();
        if (onDisk != _lastNew)
        {
            _lastNew = onDisk;
            if (onDisk != null) _log($"self-restart: {_newPath} is {onDisk}");
        }
        if (onDisk != null && string.Equals(onDisk, _version, StringComparison.OrdinalIgnoreCase))
        {
            TryDelete(_newPath); // the finisher this build came from; it may still be exiting
            onDisk = null;
        }
        string? pending = UpdateInfo.IsReplaced(_version, onDisk)
            && (_failedStamp == null || UpdateFinisher.StampOf(_newPath) != _failedStamp) ? onDisk : null;
        if (pending != Pending)
        {
            Pending = pending;
            if (pending != null) _log($"new build {pending} installed, restarting");
            Changed?.Invoke();
        }
        TryRestart();
    }

    /// <summary>The version of the waiting .new build, or null when there is none (or it can't be read yet).</summary>
    private string? NewVersion() => UpdateFinisher.VersionOf(_newPath);

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
