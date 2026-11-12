using System.Runtime;
using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Windows;

namespace GyroPhone.Tray;

internal static class Program
{
    public const string AppName = "GyroPhone Companion";
    private const string MutexName = @"Local\GyroPhoneCompanion";

    /// <summary>True while a companion (not the watcher) runs in this session.</summary>
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
            return true; // an elevated instance owns it
        }
    }

    /// <summary>Passed by "Restart as administrator": wait for the old instance to exit instead of bowing out.</summary>
    public const string RestartedArg = "--restarted";

    /// <summary>Passed when restarting into a newer build, which then says it was updated.</summary>
    public const string UpdatedArg = "--updated";

    /// <summary>Passed by the watcher when a known phone appeared.</summary>
    public const string WokenArg = "--woken";

    /// <summary>
    /// How long a locked settings.json or host.key is waited for before giving up. Longer when the watcher or
    /// an update started the companion: then it starts right after login or a copy, when scanners and
    /// backup tools hold files longest, and nobody is at the PC to start it again.
    /// </summary>
    public static int LockedFileWaitMs { get; private set; } = SettingsStore.DefaultLockedWaitMs;

    /// <summary>How long a restarted copy waits for the old one to close (the same as an update's wait).</summary>
    private static readonly TimeSpan RestartWait = TimeSpan.FromSeconds(30);

    public static bool JustUpdated { get; private set; }

    /// <summary>Started again on the old build because an update couldn't replace the exe.</summary>
    public static bool UpdateFailed { get; private set; }

    /// <summary>update.cmd: make host.key and host.pub if missing, then exit. No window, no tray, no server.</summary>
    public const string InitKeysArg = "--init-keys";

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains(InitKeysArg)) return InitKeys();
        if (args.Contains(UpdateFinisher.Arg)) return UpdateFinisher.Run(args);
        if (args.Contains(Watcher.Arg)) return Watcher.Run();

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);

        Mutex mutex;
        bool owned;
        try
        {
            mutex = new Mutex(initiallyOwned: true, MutexName, out owned);
        }
        catch (UnauthorizedAccessException)
        {
            // An elevated instance owns the mutex and its default DACL denies us: it is running.
            ShowAlreadyRunning();
            return 0;
        }
        using var _ = mutex;
        if (!owned && args.Contains(RestartedArg))
        {
            try
            {
                owned = mutex.WaitOne(RestartWait);
            }
            catch (AbandonedMutexException)
            {
                owned = true; // the previous instance exited without releasing it
            }
        }
        if (!owned)
        {
            if (args.Contains(RestartedArg))
                MessageBox.Show("The previous GyroPhone Companion is still closing. Start it again in a moment.",
                    AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            else ShowAlreadyRunning();
            return 0;
        }

        JustUpdated = args.Contains(UpdatedArg);
        UpdateFailed = args.Contains(UpdateFinisher.FailedArg);
        if (JustUpdated || UpdateFailed || args.Contains(WokenArg)) LockedFileWaitMs = SettingsStore.LongLockedWaitMs;
        GCSettings.LatencyMode = GCLatencyMode.SustainedLowLatency;
        try
        {
            TrayApp created;
            try
            {
                created = new TrayApp();
            }
            catch (IOException e)
            {
                // settings.json stayed locked: starting on defaults would overwrite the pairings.
                MessageBox.Show($"GyroPhone Companion could not start: {e.Message}. Try again in a moment.",
                    AppName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return 1;
            }
            using var app = created;
            Application.Run(app);
        }
        finally
        {
            mutex.ReleaseMutex();
        }
        return 0;
    }

    private static int InitKeys()
    {
        try
        {
            var settingsPath = SettingsStore.DefaultPath();
            var name = File.Exists(settingsPath) ? new SettingsStore(settingsPath).Load(Environment.MachineName).HostName : Environment.MachineName;
            using var key = new KeyFiles(KeyFiles.DefaultDirectory()).LoadOrCreateHostKey(new DpapiKeyProtector(), name);
            return 0;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException)
        {
            return 1;
        }
    }

    private static void ShowAlreadyRunning() =>
        MessageBox.Show("GyroPhone Companion is already running. Look for its icon in the notification area.",
            AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
}
