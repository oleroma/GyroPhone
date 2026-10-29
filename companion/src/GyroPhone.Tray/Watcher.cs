using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using GyroPhone.Host;
using GyroPhone.Host.Update;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Windows;

namespace GyroPhone.Tray;

/// <summary>
/// GyroPhoneCompanion.exe --watch: no window, no tray. While the companion is off it holds UDP 47800,
/// and the first DISCOVER from a known phone makes it free the port and start the installed companion
/// (%LOCALAPPDATA%\Programs\GyroPhone, the copy with the firewall rule). While the companion runs the
/// port is taken, so it only retries the bind. A companion started any other way (by hand, Start with
/// Windows, its own update restart) signals <see cref="YieldEventName"/> first and the watcher lets go
/// of the port. Started at login by the GyroPhoneWatcher Run entry.
///
/// DISCOVER is not authenticated: anyone on the LAN who copies a known phone id or key id can make the
/// watcher start the companion (at most once per <see cref="RelaunchGapMs"/>). That only starts the
/// companion; connecting still needs the pairing key.
/// </summary>
internal static class Watcher
{
    public const string Arg = "--watch";
    private const string MutexName = @"Local\GyroPhoneWatcher";
    /// <summary>Signalled by the tray when the user turns the watcher off.</summary>
    private const string StopEventName = @"Local\GyroPhoneWatcherStop";
    /// <summary>Signalled by a starting companion that wants UDP 47800.</summary>
    private const string YieldEventName = @"Local\GyroPhoneWatcherYield";
    /// <summary>How long the watcher stays off the port after yielding, so the companion can bind.</summary>
    private const int YieldMs = 10_000;
    private const int BindRetryMs = 3000;
    private const int ReceiveTimeoutMs = 1000;
    private const long ReloadMs = 10_000;
    private const long RelaunchGapMs = 30_000;

    /// <summary>The installed companion, which the watcher starts and the Run entry points at.</summary>
    public static string InstalledExe => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "GyroPhone", "GyroPhoneCompanion.exe");

    public static int Run()
    {
        using var mutex = new Mutex(initiallyOwned: true, MutexName, out bool owned);
        if (!owned) return 0; // one watcher per user
        using var stop = new EventWaitHandle(false, EventResetMode.AutoReset, StopEventName);
        using var yieldPort = new EventWaitHandle(false, EventResetMode.AutoReset, YieldEventName);
        using var log = new FileLog();
        log.Write($"watcher {Application.ProductVersion} started");
        var update = new UpdateCheck(Environment.ProcessPath ?? Application.ExecutablePath, log);
        bool stopped = false;
        // The stop event resets when a wait sees it, so remember it once seen.
        bool Stopped(int waitMs) => stopped || (stopped = stop.WaitOne(waitMs));
        var keyFiles = new KeyFiles(KeyFiles.DefaultDirectory());
        var settingsPath = SettingsStore.DefaultPath();
        long lastLaunch = -RelaunchGapMs;
        var clock = Stopwatch.StartNew();

        while (!Stopped(0))
        {
            if (update.HandOff()) break;
            using var s = TryBind();
            if (s == null)
            {
                if (Stopped(BindRetryMs)) break; // the companion (or something else) has the port
                continue;
            }
            var known = PhoneWatch.Load(settingsPath, keyFiles);
            long loadedAt = clock.ElapsedMilliseconds;
            var buf = new byte[Wire.MaxDatagram];
            EndPoint from = new IPEndPoint(IPAddress.Any, 0);
            bool yielded = false;
            while (!Stopped(0))
            {
                if (update.HandOff())
                {
                    stopped = true;
                    break;
                }
                if (yieldPort.WaitOne(0))
                {
                    s.Close();
                    yielded = true;
                    log.Write("companion starting: port released");
                    break;
                }
                int n;
                try
                {
                    n = s.ReceiveFrom(buf, ref from);
                }
                catch (SocketException)
                {
                    n = 0; // timeout or ICMP noise
                }
                if (clock.ElapsedMilliseconds - loadedAt >= ReloadMs)
                {
                    known = PhoneWatch.Load(settingsPath, keyFiles);
                    loadedAt = clock.ElapsedMilliseconds;
                }
                if (n == 0 || !known.IsKnownPhone(buf.AsSpan(0, n))) continue;
                if (clock.ElapsedMilliseconds - lastLaunch < RelaunchGapMs) continue;
                lastLaunch = clock.ElapsedMilliseconds;
                s.Close(); // the companion binds next; the phone repeats DISCOVER every second
                if (Program.IsRunning()) log.Write("known phone seen: the companion is already starting");
                else Launch(log);
                break;
            }
            if (Stopped(yielded ? YieldMs : BindRetryMs)) break;
        }
        log.Write("watcher stopped");
        return 0;
    }

    /// <summary>True while a watcher runs in this session.</summary>
    public static bool IsRunning()
    {
        try
        {
            if (!Mutex.TryOpenExisting(MutexName, out var m)) return false;
            m.Dispose();
            return true;
        }
        catch (UnauthorizedAccessException)
        {
            return true;
        }
    }

    /// <summary>Asks a running watcher to exit (the tray's switch was turned off, or the companion is updating).</summary>
    public static void SignalStop()
    {
        if (EventWaitHandle.TryOpenExisting(StopEventName, out var e))
        {
            using (e) e.Set();
        }
    }

    /// <summary>
    /// Asks a running watcher to let go of UDP 47800. True when a watcher was asked; it releases the
    /// port within about a second (its receive timeout).
    /// </summary>
    public static bool AskToYieldPort()
    {
        if (!EventWaitHandle.TryOpenExisting(YieldEventName, out var e)) return false;
        using (e) e.Set();
        return true;
    }

    private static Socket? TryBind()
    {
        var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        try
        {
            s.ExclusiveAddressUse = true;
            s.ReceiveTimeout = ReceiveTimeoutMs;
            s.Bind(new IPEndPoint(IPAddress.Any, Wire.Port));
            return s;
        }
        catch (SocketException)
        {
            s.Dispose();
            return null;
        }
    }

    private static void Launch(FileLog log)
    {
        var exe = File.Exists(InstalledExe) ? InstalledExe : Environment.ProcessPath ?? Application.ExecutablePath;
        try
        {
            using var p = Process.Start(new ProcessStartInfo(exe)
            {
                UseShellExecute = true,
                WorkingDirectory = Path.GetDirectoryName(exe) ?? "",
            });
            log.Write($"known phone seen: started {exe}");
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
        {
            log.Write($"could not start the companion: {e.GetType().Name}");
        }
    }

    /// <summary>
    /// While the companion is off, the watcher does the companion's update itself: when a newer build waits
    /// at "&lt;exe&gt;.new", it starts that build as an <see cref="UpdateFinisher"/> and exits, and the finisher
    /// starts the watcher again from the updated exe. While the companion runs, the companion does it.
    /// </summary>
    private sealed class UpdateCheck(string exe, FileLog log)
    {
        private readonly string _newPath = exe + UpdateFinisher.NewSuffix;
        private string? _failed;

        public bool HandOff()
        {
            if (UpdateInfo.BuildNumber(Application.ProductVersion) == null || !File.Exists(_newPath) || Program.IsRunning()) return false;
            string? version = UpdateFinisher.VersionOf(_newPath);
            if (string.Equals(version, Application.ProductVersion, StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    File.Delete(_newPath); // the finisher this build came from; it may still be exiting
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                }
                return false;
            }
            if (!UpdateInfo.IsReplaced(Application.ProductVersion, version)) return false;
            string? stamp = UpdateFinisher.StampOf(_newPath);
            if (stamp == null || stamp == _failed) return false;
            log.Write($"watcher: new build {version} at {_newPath}, restarting");
            if (UpdateFinisher.Launch(_newPath, tray: false, watch: true, log.Write)) return true;
            _failed = stamp;
            return false;
        }
    }
}
