using System.Globalization;
using System.Net;
using System.Text;

namespace GyroPhone.Host.Perf;

/// <summary>
/// The link tests as one plain web page, <c>results.html</c> in the records folder:
/// the table of every kept record, newest first, the best value of each column marked, then each record's
/// summary, bursts of trouble and second-by-second table. Rewritten whenever a record is saved; it opens in
/// any browser and can be shared as one file.
/// </summary>
public static class PerfReport
{
    public const string FileName = "results.html";

    /// <summary>Writes the page for <paramref name="runs"/> (newest first) into <paramref name="folder"/>; returns its path.</summary>
    public static string Write(string folder, IReadOnlyList<PerfRun> runs)
    {
        Directory.CreateDirectory(folder);
        string path = Path.Combine(folder, FileName);
        File.WriteAllText(path, Html(runs), Encoding.UTF8);
        return path;
    }

    public static string Html(IReadOnlyList<PerfRun> runs)
    {
        var ci = CultureInfo.InvariantCulture;
        var sb = new StringBuilder();
        sb.Append("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>GyroPhone link tests</title>
            <style>
            :root { color-scheme: light dark; --bg: #f6f7f9; --fg: #1d2330; --dim: #667085; --line: #d9dde5; --card: #fff; --best: #1a73c9; --warn: #b26b00; }
            @media (prefers-color-scheme: dark) { :root { --bg: #15181e; --fg: #e8ebf1; --dim: #8d96a8; --line: #2c323d; --card: #1c2028; --best: #83cfff; --warn: #e5a50a; } }
            body { margin: 0; padding: 24px 16px; background: var(--bg); color: var(--fg); font: 15px/1.45 system-ui, "Segoe UI", sans-serif; }
            main { max-width: 1200px; margin: 0 auto; }
            h1 { font-size: 22px; margin: 0 0 4px; }
            p.sub { color: var(--dim); margin: 0 0 20px; }
            .wrap { overflow-x: auto; background: var(--card); border: 1px solid var(--line); border-radius: 8px; }
            table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; }
            th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--line); white-space: nowrap; }
            th { color: var(--dim); font-weight: 600; font-size: 13px; }
            tr:last-child td { border-bottom: 0; }
            td.best { color: var(--best); font-weight: 700; }
            td.warn { color: var(--warn); }
            a { color: inherit; }
            h2 { font-size: 16px; margin: 28px 0 8px; }
            .run { background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; }
            .run .meta { color: var(--dim); font-size: 13px; }
            .run .meta.warn { color: var(--warn); }
            .run pre { margin: 8px 0 0; overflow-x: auto; font: 12px/1.4 ui-monospace, Consolas, monospace; }
            summary { cursor: pointer; color: var(--dim); font-size: 13px; margin-top: 8px; }
            </style></head><body><main>
            <h1>GyroPhone link tests</h1>
            <p class="sub">Each record of the Performance page, newest first. The best value in each column is in colour. Lost: phone packets lost. Late: held up more than 15 ms, with the worst delay. Pauses: the phone sent nothing for a while during input. Let go: held keys let go because the phone went silent. Bursts: stretches of link trouble. Floor: half the best round trip, the fastest the link went. Over the floor: how much slower than the fastest packets every packet of the run came, middle · slowest 1% (needs no clock guess, the number to compare). Latency: phone to PC one way, typical · slowest 1% (depends on a clock guess). Hand-offs: per 10,000 packets that took over 2 ms from arrival to Windows, and the slowest. Held up: late packets that waited on the way · inside the PC · on the phone (needs Diagnostics' stall watch), and "PC busy": ones that looked held up on the way in a second the whole PC's CPU was at 95% or more, so most likely waited in the PC. ⚠: the floor moved on the same setup. Frames only with the FPS overlay on. Busy: seconds other programs used 20% of the PC or more. Playing: the same numbers over only the seconds the game was in front and the input moved (late · over the floor slowest 1% · how long), the number to compare between game sessions; the game is a guess unless the FPS overlay named it.</p>
            """);
        if (runs.Count == 0)
        {
            sb.Append("<p>No tests yet.</p></main></body></html>\n");
            return sb.ToString();
        }

        var withFrames = runs.Where(r => r.Frames && r.GameSeconds > 0).ToList();
        double bestFps = withFrames.Count > 0 ? withFrames.Max(r => r.AvgFps) : 0, bestLow = withFrames.Count > 0 ? withFrames.Max(r => r.Low1Fps) : 0;
        double bestJumps = withFrames.Count > 0 ? withFrames.Min(r => r.JumpsPerMin) : 0;
        int bestLost = runs.Min(r => r.Lost), bestLate = runs.Min(r => r.Late), bestGaps = runs.Min(r => r.Gaps), bestBursts = runs.Min(r => r.Bursts);
        var over = runs.Where(r => r.OverP99Ms >= 0).ToList();
        double bestOver = over.Count > 0 ? over.Min(r => r.OverP99Ms) : 0;
        var hand = runs.Where(r => r.HandOffMaxMs >= 0 && r.Packets > 0).ToList();
        double bestHand = hand.Count > 0 ? hand.Min(SlowPer10k) : 0;
        var lat = runs.Where(r => r.LatP50Ms >= 0).ToList();
        double bestLat = lat.Count > 0 ? lat.Min(Lat) : 0;
        bool many = runs.Count > 1;
        string Td(string text, bool best = false, string? cls = null) =>
            $"<td{(best && many ? " class=\"best\"" : cls != null ? $" class=\"{cls}\"" : "")}>{E(text)}</td>";

        sb.Append("<div class=\"wrap\"><table><thead><tr><th>Name</th><th>When</th><th>Path</th><th>Playing</th><th>Lost</th><th>Late</th><th>Pauses</th><th>Let go</th><th>Bursts</th><th>Floor</th><th>Over the floor</th><th>Hand-offs</th><th>Held up</th><th>Latency</th><th>Jitter</th><th>Avg fps</th><th>1% low</th><th>Jumps/min</th><th>Worst frame</th><th>CPU</th><th>Driver</th></tr></thead><tbody>\n");
        for (int i = 0; i < runs.Count; i++)
        {
            var r = runs[i];
            bool fr = r.Frames && r.GameSeconds > 0;
            sb.Append(ci, $"<tr><td><a href=\"#run{i}\">{E(r.Name)}</a></td>")
              .Append(Td(r.Start.ToString("d MMM HH:mm", ci)))
              .Append(Td(PathOf(r)))
              .Append(Td(r.Playing is { } pl ? string.Format(ci, "late {0} · {1} · {2:0} s", pl.Late, pl.OverP99Ms >= 0 ? pl.OverP99Ms.ToString("0.00", ci) + " ms" : "–", pl.Seconds) : "–"))
              .Append(Td(r.Lost.ToString(ci), r.Lost <= bestLost))
              .Append(Td(r.Late > 0 ? string.Format(ci, "{0} (≤{1:0} ms)", r.Late, r.MaxLateMs) : "0", r.Late <= bestLate))
              .Append(Td(r.Gaps.ToString(ci), r.Gaps <= bestGaps))
              .Append(Td(r.Releases.ToString(ci)))
              .Append(Td(r.Bursts.ToString(ci), r.Bursts <= bestBursts))
              .Append(Td(r.FloorMs >= 0 ? string.Format(ci, "{0:0.00} ms{1}", r.FloorMs, r.FloorWarning.Length > 0 ? " ⚠" : "") : "–",
                  cls: r.FloorWarning.Length > 0 ? "warn" : null))
              .Append(Td(r.OverP99Ms >= 0 ? string.Format(ci, "{0:0.00} · {1:0.00} ms", r.OverP50Ms, r.OverP99Ms) : "–", r.OverP99Ms >= 0 && r.OverP99Ms <= bestOver))
              .Append(Td(r.HandOffMaxMs >= 0 && r.Packets > 0 ? string.Format(ci, "{0:0.0} · ≤{1:0.0} ms", SlowPer10k(r), r.HandOffMaxMs) : "–",
                  r.HandOffMaxMs >= 0 && r.Packets > 0 && SlowPer10k(r) <= bestHand))
              .Append(Td(r.Late > 0 && r.HeldAir + r.HeldPc + r.HeldPhone + r.HeldPcBusy > 0
                  ? string.Format(ci, "{0} · {1} · {2}", r.HeldAir, r.HeldPc, r.HeldPhone) + (r.HeldPcBusy > 0 ? string.Format(ci, " · PC busy {0}", r.HeldPcBusy) : "") : "–"))
              .Append(Td(r.LatP50Ms >= 0 ? string.Format(ci, "{0:0.0} · {1:0.0} ms", r.LatP50Ms, r.LatP99Ms) : "–", r.LatP50Ms >= 0 && Lat(r) <= bestLat))
              .Append(Td(r.LatP50Ms >= 0 ? string.Format(ci, "{0:0.0} ms", r.JitterMs) : "–"))
              .Append(Td(fr ? r.AvgFps.ToString("0", ci) : "–", fr && r.AvgFps >= bestFps))
              .Append(Td(fr ? r.Low1Fps.ToString("0", ci) : "–", fr && r.Low1Fps >= bestLow))
              .Append(Td(fr ? r.JumpsPerMin.ToString("0.0", ci) : "–", fr && r.JumpsPerMin <= bestJumps))
              .Append(Td(fr ? r.WorstMs.ToString("0", ci) + " ms" : "–"))
              .Append(Td(r.BusySeconds > 0 ? string.Format(ci, "{0:0}% ⚠ busy {1} s", r.CpuPct, r.BusySeconds) : string.Format(ci, "{0:0}%", r.CpuPct),
                  cls: r.BusySeconds > 0 ? "warn" : null))
              .Append(Td(string.Format(ci, "{0:0.0}% · {1:0}%", r.DriverPct, r.BusiestCoreDriverPct)))
              .Append("</tr>\n");
        }
        sb.Append("</tbody></table></div>\n<h2>Each test</h2>\n");
        for (int i = 0; i < runs.Count; i++)
        {
            var r = runs[i];
            sb.Append(ci, $"<div class=\"run\" id=\"run{i}\"><div><b>{E(r.Name)}</b></div>")
              .Append(ci, $"<div class=\"meta\">{E(r.Start.ToString("yyyy-MM-dd HH:mm:ss", ci))} · {r.Seconds:0} s · {E(PathOf(r))}</div>\n");
            if (r.FloorWarning.Length > 0) sb.Append("<div class=\"meta warn\">⚠ ").Append(E(r.FloorWarning)).Append("</div>\n");
            if (r.Setup.Length > 0) sb.Append("<div class=\"meta\">Setup: ").Append(E(r.Setup)).Append("</div>\n");
            if (r.PlayGame.Length > 0)
                sb.Append("<div class=\"meta\">Game: ").Append(E(r.PlayGame)).Append(r.PlayGameGuessed ? " (a guess)" : "")
                  .Append(ci, $" · playing {r.Playing?.Seconds ?? 0} s · in front, still {r.GameStill?.Seconds ?? 0} s · else {r.Other?.Seconds ?? 0} s</div>\n");
            string? text = TextOf(r);
            if (text != null)
                sb.Append("<details><summary>Second by second</summary><pre>").Append(E(text.TrimEnd())).Append("</pre></details>\n");
            sb.Append("</div>\n");
        }
        sb.Append("</main></body></html>\n");
        return sb.ToString();
    }

    private static double SlowPer10k(PerfRun r) => r.Packets > 0 ? r.SlowHandOffs * 10_000.0 / r.Packets : 0;

    private static double Lat(PerfRun r) => r.LatP99Ms >= 0 ? r.LatP99Ms : r.LatP50Ms;

    private static string PathOf(PerfRun r) => r.Path.Length > 0 ? r.Path : r.Link + " (typed)";

    /// <summary>The record's second-by-second text: kept in the record, or in the text file older versions wrote.</summary>
    private static string? TextOf(PerfRun r)
    {
        if (r.Text != null) return r.Text;
        try
        {
            if (r.File is { } f && f.EndsWith(".txt", StringComparison.OrdinalIgnoreCase) && File.Exists(f)) return File.ReadAllText(f);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
        return null;
    }

    private static string E(string s) => WebUtility.HtmlEncode(s);
}
