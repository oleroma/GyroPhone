using System.Diagnostics;
using System.Windows;
using GyroPhone.Latency;

namespace GyroPhone.LatencyTest;

/// <summary>
/// The video latency test (README.md). With no video: opens the test window, which also times a video
/// dropped on it. With a video (dropped on latency-test.bat): times it here in the console.
/// Either way the result is saved in Documents\GyroPhone\Latency and results.html opens.
/// Options: --tag &lt;name&gt; marks the runs (Claude's own runs use "claude").
/// </summary>
internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        string? tag = null, video = null;
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--tag" && i + 1 < args.Length) tag = args[++i];
            else video ??= args[i];
        }
        if (video != null)
        {
            Console.WriteLine($"Timing {video}");
            var (ok, message) = Time(video, tag, p => Console.Write($"\rReading the video… {p * 100:0}%   "));
            Console.WriteLine();
            Console.WriteLine(message);
            return ok ? 0 : 1;
        }
        // The window needs no console (latency-test.bat starts it in a console of its own).
        FreeConsole();
        var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
        return app.Run(new TestWindow(tag));
    }

    /// <summary>
    /// Times <paramref name="video"/>, saves the result, rewrites results.html and opens it.
    /// Returns whether a result was saved, and a line for the person.
    /// </summary>
    public static (bool Ok, string Message) Time(string video, string? tag, Action<double> progress)
    {
        try
        {
            string copy = LatencyResults.KeepLastVideo(video);
            var a = VideoTracker.Analyze(copy, new SyncProgress(progress));
            var run = LatencyRun.From(a, video, "—", "—", TestWindow.MonitorHz(), CompanionVersion(), tag);
            LatencyResults.Save(run);
            OpenReport();
            string timed = a.TimedToS - a.TimedFromS < a.LengthS - 1
                ? $" Timed {Clock(a.TimedFromS)}–{Clock(a.TimedToS)} of {Clock(a.LengthS)}, where you twist."
                : "";
            string extra = string.Concat(new[] { run.FreezesText(), run.LeftOut() }.Where(t => t.Length > 0).Select(t => $" {char.ToUpper(t[0])}{t[1..]}."));
            return (true, (run.Unreliable
                    ? $"{run.LatencyMs:0.0} ms, unreliable: too few swings could be timed. Twist quickly and keep the bar in the picture."
                    : $"{run.LatencyMs:0.0} ms (±{run.SpreadMs:0.0}) from {run.Twists} twists.") + timed + extra);
        }
        catch (VideoProblem e)
        {
            return (false, e.Message);
        }
        catch (Exception e)
        {
            return (false, $"This video couldn't be read: {e.Message}");
        }
    }

    /// <summary>Writes results.html afresh and opens it in the default browser.</summary>
    public static void OpenReport()
    {
        try
        {
            Process.Start(new ProcessStartInfo(LatencyReport.Write()) { UseShellExecute = true });
        }
        catch (Exception e) when (e is IOException or System.ComponentModel.Win32Exception)
        {
            Console.WriteLine($"Couldn't open results.html: {e.Message}");
        }
    }

    /// <summary>The installed companion's version (what the result was measured with), or "—".</summary>
    private static string CompanionVersion()
    {
        string exe = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs", "GyroPhone", "GyroPhoneCompanion.exe");
        try
        {
            return File.Exists(exe) && FileVersionInfo.GetVersionInfo(exe).ProductVersion is { Length: > 0 } v ? v : "—";
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return "—";
        }
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    private static extern bool FreeConsole();

    private static string Clock(double s) => TimeSpan.FromSeconds(Math.Max(0, s)).ToString(@"m\:ss");

    /// <summary>Reports on the analyser's thread (Progress&lt;T&gt; would need a sync context in the console).</summary>
    private sealed class SyncProgress(Action<double> report) : IProgress<double>
    {
        public void Report(double value) => report(value);
    }
}
