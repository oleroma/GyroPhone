using System.Globalization;
using System.Net;
using System.Text;

namespace GyroPhone.Latency;

/// <summary>
/// The latency test's results as one plain web page, <c>results.html</c> in
/// <see cref="LatencyResults.Folder"/>: a table of every run, newest first, and each run's second
/// of both motions as a small chart. Rewritten whenever a result is saved, so it is always up to
/// date; it opens in any browser and can be shared as one file.
/// </summary>
public static class LatencyReport
{
    public const string FileName = "results.html";

    public static string PathOf => Path.Combine(LatencyResults.Folder, FileName);

    /// <summary>Writes the page from the saved results; returns its path.</summary>
    public static string Write()
    {
        var runs = LatencyResults.Load();
        Directory.CreateDirectory(LatencyResults.Folder);
        File.WriteAllText(PathOf, Html(runs.Select(r => r.Run).ToList()), Encoding.UTF8);
        return PathOf;
    }

    public static string Html(IReadOnlyList<LatencyRun> runs)
    {
        var ci = CultureInfo.InvariantCulture;
        var sb = new StringBuilder();
        sb.Append("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>GyroPhone latency results</title>
            <style>
            :root { color-scheme: light dark; --bg: #f6f7f9; --fg: #1d2330; --dim: #667085; --line: #d9dde5; --card: #fff; --phone: #d35bb0; --bar: #1fa463; }
            @media (prefers-color-scheme: dark) { :root { --bg: #15181e; --fg: #e8ebf1; --dim: #8d96a8; --line: #2c323d; --card: #1c2028; --phone: #f07cc8; --bar: #3ee08a; } }
            body { margin: 0; padding: 24px 16px; background: var(--bg); color: var(--fg); font: 15px/1.45 system-ui, "Segoe UI", sans-serif; }
            main { max-width: 1000px; margin: 0 auto; }
            h1 { font-size: 22px; margin: 0 0 4px; }
            p.sub { color: var(--dim); margin: 0 0 20px; }
            .wrap { overflow-x: auto; background: var(--card); border: 1px solid var(--line); border-radius: 8px; }
            table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; }
            th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--line); white-space: nowrap; }
            th { color: var(--dim); font-weight: 600; font-size: 13px; }
            tr:last-child td { border-bottom: 0; }
            td.big { font-weight: 700; font-size: 17px; }
            .tag { font-size: 12px; padding: 1px 6px; border-radius: 4px; border: 1px solid var(--dim); color: var(--dim); }
            .warn { font-size: 12px; font-weight: 600; padding: 1px 6px; border-radius: 4px; background: #e5a50a; color: #1d2330; }
            h2 { font-size: 16px; margin: 28px 0 8px; }
            .run { background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; }
            .run .meta { color: var(--dim); font-size: 13px; }
            svg { width: 100%; height: 120px; display: block; margin-top: 8px; }
            .key { font-size: 13px; color: var(--dim); }
            .key b { display: inline-block; width: 14px; height: 3px; vertical-align: middle; margin: 0 4px 0 10px; }
            </style></head><body><main>
            <h1>GyroPhone latency results</h1>
            <p class="sub">Time from moving the phone to the game seeing the mouse move, from slow-motion videos. Newest first.</p>
            """);
        if (runs.Count == 0)
        {
            sb.Append("<p>No results yet.</p></main></body></html>\n");
            return sb.ToString();
        }
        sb.Append("<div class=\"wrap\"><table><thead><tr><th>When</th><th>Latency</th><th>Spread</th><th>Twists</th><th>Match</th><th>Left out</th><th>Video</th><th>fps</th><th>Link</th><th>Phone</th><th>Monitor</th><th>App</th></tr></thead><tbody>\n");
        for (int i = 0; i < runs.Count; i++)
        {
            var r = runs[i];
            string left = r.LeftOut();
            sb.Append(ci, $"<tr><td><a href=\"#run{i}\">{E(r.When.ToString("yyyy-MM-dd HH:mm", ci))}</a>{Tag(r)}</td>")
              .Append(ci, $"<td class=\"big\">{r.LatencyMs:0.0} ms{Unsure(r)}</td><td>±{r.SpreadMs:0.0} ms</td><td>{r.Twists}</td><td>{r.Match:0.00}</td>")
              .Append(ci, $"<td>{E(left.Length > 0 ? left : "—")}</td><td>{E(r.Video)}</td><td>{r.Fps:0}</td><td>{E(r.Link)}</td><td>{E(r.Phone)}</td>")
              .Append(ci, $"<td>{(r.MonitorHz > 0 ? r.MonitorHz + " Hz" : "—")}</td><td>{E(r.App)}</td></tr>\n");
        }
        sb.Append("</tbody></table></div>\n<h2>One second of each run</h2>\n");
        sb.Append("<p class=\"key\"><b style=\"background:var(--phone)\"></b>Real phone (from the video)<b style=\"background:var(--bar)\"></b>What the game got</p>\n");
        for (int i = 0; i < runs.Count; i++)
        {
            var r = runs[i];
            sb.Append(ci, $"<div class=\"run\" id=\"run{i}\"><div><b>{r.LatencyMs:0.0} ms</b> ±{r.SpreadMs:0.0} ms{Tag(r)}{Unsure(r)}</div>")
              .Append(ci, $"<div class=\"meta\">{E(r.When.ToString("yyyy-MM-dd HH:mm", ci))} · {E(r.Video)} · {r.Twists} twists")
              .Append(r.TimedToS > r.TimedFromS ? string.Format(ci, " · timed {0:0.0}–{1:0.0} s", r.TimedFromS, r.TimedToS) : "")
              .Append("</div>\n")
              .Append(Chart(r))
              .Append("</div>\n");
        }
        sb.Append("</main></body></html>\n");
        return sb.ToString();
    }

    private static string Tag(LatencyRun r) => r.Tag is { } t ? $" <span class=\"tag\">{E(t)}</span>" : "";

    /// <summary>
    /// A run where no 1.5 s stretch could be checked (all left out) rests on the whole-video match
    /// alone, with no spread: marked so it isn't read as a clean number.
    /// </summary>
    public static bool Unreliable(LatencyRun r) => r.Stretches == 0;

    private static string Unsure(LatencyRun r) =>
        Unreliable(r) ? " <span class=\"warn\" title=\"No stretch could be checked; whole-video match only\">unreliable</span>" : "";

    private static string E(string s) => WebUtility.HtmlEncode(s);

    /// <summary>The run's second of both motions, as two lines over a 1000 × 120 box.</summary>
    private static string Chart(LatencyRun r)
    {
        if (r.PhoneCurve.Length < 2 || r.BarCurve.Length < 2) return "";
        double max = 0.1;
        foreach (var v in r.PhoneCurve.Concat(r.BarCurve)) max = Math.Max(max, Math.Abs(v));
        return "<svg viewBox=\"0 0 1000 120\" preserveAspectRatio=\"none\" role=\"img\" aria-label=\"One second of the phone's motion and the game's\">"
            + "<line x1=\"0\" y1=\"60\" x2=\"1000\" y2=\"60\" stroke=\"var(--line)\"/>"
            + Line(r.PhoneCurve, max, "var(--phone)") + Line(r.BarCurve, max, "var(--bar)") + "</svg>\n";
    }

    private static string Line(double[] v, double max, string color)
    {
        var ci = CultureInfo.InvariantCulture;
        var sb = new StringBuilder($"<polyline fill=\"none\" stroke=\"{color}\" stroke-width=\"2\" vector-effect=\"non-scaling-stroke\" points=\"");
        for (int i = 0; i < v.Length; i++)
            sb.Append(ci, $"{i * 1000.0 / (v.Length - 1):0.#},{60 - v[i] / max * 54:0.#} ");
        return sb.Append("\"/>").ToString();
    }
}
