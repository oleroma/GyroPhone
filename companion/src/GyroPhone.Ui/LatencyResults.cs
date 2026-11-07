using System.Text.Json;
using GyroPhone.Latency;

namespace GyroPhone.Ui;

/// <summary>One timed video: the result and what it was measured with, as the card shows it.</summary>
public sealed record LatencyRun(
    DateTime When,
    double LatencyMs,
    double SpreadMs,
    int Twists,
    double Fps,
    double Match,
    string Link,
    string Phone,
    int MonitorHz,
    string App,
    string Video,
    double[] PhoneCurve,
    double[] BarCurve,
    double TimedFromS = 0,
    double TimedToS = 0)
{
    public static LatencyRun From(VideoAnalysis a, string video, string link, string phone, int monitorHz, string app) =>
        new(DateTime.Now, a.Fit.LatencyMs, a.Fit.SpreadMs, a.Fit.Twists, a.Fps, a.Fit.Correlation, link, phone, monitorHz, app,
            Path.GetFileName(video), a.PhoneCurve, a.BarCurve, a.TimedFromS, a.TimedToS);
}

/// <summary>
/// The latency test's results, newest first: one JSON file per timed video in
/// Documents\GyroPhone\Latency (the same file "Save data" writes).
/// </summary>
internal static class LatencyResults
{
    public static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static string Folder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "GyroPhone", "Latency");

    public static IReadOnlyList<(string File, LatencyRun Run)> Load()
    {
        var list = new List<(string, LatencyRun)>();
        if (!Directory.Exists(Folder)) return list;
        foreach (var f in Directory.GetFiles(Folder, "latency-*.json"))
        {
            try
            {
                if (JsonSerializer.Deserialize<LatencyRun>(File.ReadAllText(f)) is { } r) list.Add((f, r));
            }
            catch (Exception)
            {
                // Not one of ours, or damaged: left out.
            }
        }
        return list.OrderByDescending(x => x.Item2.When).ToList();
    }

    /// <summary>
    /// Copies the video being timed to <c>last-video</c> in <see cref="Folder"/> (replacing the one
    /// before), so the last video tried can be looked at again, whatever happens to the original.
    /// </summary>
    public static string KeepLastVideo(string video)
    {
        Directory.CreateDirectory(Folder);
        string copy = Path.Combine(Folder, "last-video" + Path.GetExtension(video).ToLowerInvariant());
        if (Path.GetFullPath(video).Equals(Path.GetFullPath(copy), StringComparison.OrdinalIgnoreCase)) return copy;
        foreach (var old in Directory.GetFiles(Folder, "last-video.*"))
            if (!old.Equals(copy, StringComparison.OrdinalIgnoreCase)) File.Delete(old);
        File.Copy(video, copy, overwrite: true);
        return copy;
    }

    public static string Save(LatencyRun run)
    {
        Directory.CreateDirectory(Folder);
        string file = Path.Combine(Folder, $"latency-{run.When:yyyyMMdd-HHmmss}.json");
        File.WriteAllText(file, JsonSerializer.Serialize(run, Json));
        return file;
    }
}
