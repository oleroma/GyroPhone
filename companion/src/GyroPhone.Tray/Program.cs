using System.Runtime;

namespace GyroPhone.Tray;

internal static class Program
{
    public const string AppName = "GyroPhone Companion";
    private const string MutexName = @"Local\GyroPhoneCompanion";

    /// <summary>Passed by "Restart as administrator": wait for the old instance to exit instead of bowing out.</summary>
    public const string RestartedArg = "--restarted";

    /// <summary>Passed when restarting into a newer build, which then says it was updated.</summary>
    public const string UpdatedArg = "--updated";

    public static bool JustUpdated { get; private set; }

    [STAThread]
    private static int Main(string[] args)
    {
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
                owned = mutex.WaitOne(TimeSpan.FromSeconds(10));
            }
            catch (AbandonedMutexException)
            {
                owned = true; // the previous instance exited without releasing it
            }
        }
        if (!owned)
        {
            ShowAlreadyRunning();
            return 0;
        }

        JustUpdated = args.Contains(UpdatedArg);
        GCSettings.LatencyMode = GCLatencyMode.SustainedLowLatency;
        try
        {
            using var app = new TrayApp();
            Application.Run(app);
        }
        finally
        {
            mutex.ReleaseMutex();
        }
        return 0;
    }

    private static void ShowAlreadyRunning() =>
        MessageBox.Show("GyroPhone Companion is already running. Look for its icon in the notification area.",
            AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
}
