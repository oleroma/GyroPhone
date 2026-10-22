using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Windows;

namespace GyroPhone.Tray;

/// <summary>
/// GyroPhoneCompanion.exe --watch: no window, no tray. While the companion is off it holds UDP 47800,
/// and the first DISCOVER from a known phone makes it free the port and start the installed companion
/// (%LOCALAPPDATA%\Programs\GyroPhone, the copy with the firewall rule). While the companion runs the
/// port is taken, so it only retries the bind. Started at login by the GyroPhoneWatcher Run entry.
/// </summary>
internal static class Watcher
{
    public const string Arg = "--watch";
    private const string MutexName = @"Local\GyroPhoneWatcher";
    /// <summary>Signalled by the tray when the user turns the watcher off.</summary>
    private const string StopEventName = @"Local\GyroPhoneWatcherStop";
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
        using var log = new FileLog();
        log.Write($"watcher {Application.ProductVersion} started");
        var keyFiles = new KeyFiles(KeyFiles.DefaultDirectory());
        var settingsPath = SettingsStore.DefaultPath();
        long lastLaunch = -RelaunchGapMs;
        var clock = Stopwatch.StartNew();

        while (!stop.WaitOne(0))
        {
            using var s = TryBind();
            if (s == null)
            {
                if (stop.WaitOne(BindRetryMs)) break; // the companion (or something else) has the port
                continue;
            }
            var known = PhoneWatch.Load(settingsPath, keyFiles);
            long loadedAt = clock.ElapsedMilliseconds;
            var buf = new byte[Wire.MaxDatagram];
            EndPoint from = new IPEndPoint(IPAddress.Any, 0);
            while (!stop.WaitOne(0))
            {
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
                Launch(log);
                break;
            }
            if (stop.WaitOne(BindRetryMs)) break;
        }
        log.Write("watcher stopped");
        return 0;
    }

    /// <summary>Asks a running watcher to exit (the tray's switch was turned off).</summary>
    public static void SignalStop()
    {
        if (EventWaitHandle.TryOpenExisting(StopEventName, out var e))
        {
            using (e) e.Set();
        }
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
}
