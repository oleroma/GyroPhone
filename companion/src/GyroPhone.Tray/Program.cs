using System.Runtime;

namespace GyroPhone.Tray;

internal static class Program
{
    public const string AppName = "GyroPhone Companion";
    private const string MutexName = @"Local\GyroPhoneCompanion";

    /// <summary>Passed by "Restart as administrator": wait for the old instance to exit instead of bowing out.</summary>
    public const string RestartedArg = "--restarted";

    [STAThread]
    private static int Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);

        using var mutex = new Mutex(initiallyOwned: true, MutexName, out bool owned);
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
            MessageBox.Show("GyroPhone Companion is already running. Look for its icon in the notification area.",
                AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 0;
        }

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
}
