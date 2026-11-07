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
            .hist { background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px 4px; margin-bottom: 20px; }
            .hist svg { height: auto; margin: 0; }
            .hist text { font-size: 11px; fill: var(--dim); font-family: system-ui, "Segoe UI", sans-serif; }
            .key i { display: inline-block; width: 9px; height: 9px; vertical-align: middle; margin: 0 4px 0 10px; border: 2px solid var(--bar); }
            .key b { display: inline-block; width: 14px; height: 3px; vertical-align: middle; margin: 0 4px 0 10px; }
            </style></head><body><main>
            <h1>GyroPhone latency results</h1>
            <p class="sub">Time from twisting the phone to the green bar moving on the screen, from slow-motion videos. Each swing of the wrist is timed on its own; the latency is their middle, the margin how far that middle could be off. Freezes (the bar stalling, then catching up) are left out of the latency and listed on their own. Newest first.</p>
            """);
        if (runs.Count == 0)
        {
            sb.Append("<p>No results yet.</p></main></body></html>\n");
            return sb.ToString();
        }
        sb.Append(History(runs));
        sb.Append("<div class=\"wrap\"><table><thead><tr><th>When</th><th>Latency</th><th>Margin</th><th>Twists</th><th>Match</th><th>Freezes</th><th>Left out</th><th>Video</th><th>fps</th><th>Link</th><th>Phone</th><th>Monitor</th><th>App</th></tr></thead><tbody>\n");
        for (int i = 0; i < runs.Count; i++)
        {
            var r = runs[i];
            string left = r.LeftOut();
            sb.Append(ci, $"<tr><td><a href=\"#run{i}\">{E(r.When.ToString("yyyy-MM-dd HH:mm", ci))}</a>{Tag(r)}</td>")
              .Append(ci, $"<td class=\"big\">{r.LatencyMs:0.0} ms{Unsure(r)}</td><td>{(r.Unreliable ? "—" : $"±{r.SpreadMs:0.0} ms")}</td><td>{r.Twists}</td><td>{r.Match:0.00}</td>")
              .Append(ci, $"<td>{E(r.FreezesText() is { Length: > 0 } fz ? fz : "—")}</td><td>{E(left.Length > 0 ? left : "—")}</td><td>{E(r.Video)}</td><td>{r.Fps:0}</td><td>{E(r.Link)}</td><td>{E(r.Phone)}</td>")
              .Append(ci, $"<td>{(r.MonitorHz > 0 ? r.MonitorHz + " Hz" : "—")}</td><td>{E(r.App)}</td></tr>\n");
        }
        sb.Append("</tbody></table></div>\n<h2>One second of each run</h2>\n");
        sb.Append("<p class=\"key\"><b style=\"background:var(--phone)\"></b>Real phone (from the video)<b style=\"background:var(--bar)\"></b>What the game got</p>\n");
        for (int i = 0; i < runs.Count; i++)
        {
            var r = runs[i];
            sb.Append(ci, $"<div class=\"run\" id=\"run{i}\"><div><b>{r.LatencyMs:0.0} ms</b>{(r.Unreliable ? "" : $" ±{r.SpreadMs:0.0} ms")}{Tag(r)}{Unsure(r)}</div>")
              .Append(ci, $"<div class=\"meta\">{E(r.When.ToString("yyyy-MM-dd HH:mm", ci))} · {E(r.Video)} · {r.Twists} twists")
              .Append(r.TimedToS > r.TimedFromS ? string.Format(ci, " · timed {0:0.0}–{1:0.0} s", r.TimedFromS, r.TimedToS) : "")
              .Append("</div>\n")
              .Append(Chart(r))
              .Append("</div>\n");
        }
        sb.Append("</main></body></html>\n");
        return sb.ToString();
    }

    /// <summary>
    /// Every run's latency over time, oldest on the left, one step per run (so a busy day doesn't
    /// crowd): a dot with a line for its margin, labelled with the app's version where it changes.
    /// Tagged runs (Claude's) are diamonds, unreliable ones hollow; hovering a point names it.
    /// </summary>
    public static string History(IReadOnlyList<LatencyRun> runs)
    {
        var ci = CultureInfo.InvariantCulture;
        var list = runs.OrderBy(r => r.When).ToList();
        if (list.Count == 0) return "";
        const double W = 1000, H = 220, left = 44, right = 12, top = 12, bottom = 44;
        double hi = list.Max(r => r.LatencyMs + (r.Unreliable ? 0 : r.SpreadMs));
        double lo = list.Min(r => r.LatencyMs - (r.Unreliable ? 0 : r.SpreadMs));
        double step = hi - lo > 40 ? 10 : hi - lo > 16 ? 5 : 2;
        double y0 = Math.Max(0, Math.Floor(lo / step) * step - step), y1 = Math.Ceiling(hi / step) * step + step;
        double X(int i) => list.Count == 1 ? (left + W - right) / 2 : left + 10 + i * (W - left - right - 20) / (list.Count - 1);
        double Y(double ms) => top + (y1 - ms) / (y1 - y0) * (H - top - bottom);
        var sb = new StringBuilder("<h2>History</h2><div class=\"hist\">");
        sb.Append(ci, $"<svg viewBox=\"0 0 {W} {H}\" role=\"img\" aria-label=\"Latency of every run over time\">");
        for (double v = y0; v <= y1 + 1e-9; v += step)
            sb.Append(ci, $"<line x1=\"{left}\" x2=\"{W - right}\" y1=\"{Y(v):0.#}\" y2=\"{Y(v):0.#}\" stroke=\"var(--line)\"/><text x=\"{left - 6}\" y=\"{Y(v) + 4:0.#}\" text-anchor=\"end\">{v:0} ms</text>");
        if (list.Count > 1)
        {
            sb.Append("<polyline fill=\"none\" stroke=\"var(--dim)\" stroke-opacity=\"0.4\" stroke-width=\"1.5\" vector-effect=\"non-scaling-stroke\" points=\"");
            for (int i = 0; i < list.Count; i++) sb.Append(ci, $"{X(i):0.#},{Y(list[i].LatencyMs):0.#} ");
            sb.Append("\"/>");
        }
        string? lastApp = null;
        for (int i = 0; i < list.Count; i++)
        {
            var r = list[i];
            double x = X(i), y = Y(r.LatencyMs);
            string tip = E($"{r.When.ToString("yyyy-MM-dd HH:mm", ci)} · {r.LatencyMs.ToString("0.0", ci)} ms"
                + (r.Unreliable ? " (unreliable)" : $" ± {r.SpreadMs.ToString("0.0", ci)}") + $" · {r.App} · {r.Video}" + (r.Tag is { } t ? $" · {t}" : ""));
            sb.Append("<g><title>").Append(tip).Append("</title>");
            if (!r.Unreliable && r.SpreadMs > 0)
                sb.Append(ci, $"<line x1=\"{x:0.#}\" x2=\"{x:0.#}\" y1=\"{Y(r.LatencyMs + r.SpreadMs):0.#}\" y2=\"{Y(r.LatencyMs - r.SpreadMs):0.#}\" stroke=\"var(--bar)\" stroke-width=\"2\"/>");
            string fill = r.Unreliable ? "var(--card)" : "var(--bar)";
            if (r.Tag != null)
                sb.Append(ci, $"<path d=\"M{x:0.#} {y - 6:0.#} L{x + 6:0.#} {y:0.#} L{x:0.#} {y + 6:0.#} L{x - 6:0.#} {y:0.#} Z\" fill=\"{fill}\" stroke=\"var(--bar)\" stroke-width=\"2\"/>");
            else
                sb.Append(ci, $"<circle cx=\"{x:0.#}\" cy=\"{y:0.#}\" r=\"5\" fill=\"{fill}\" stroke=\"var(--bar)\" stroke-width=\"2\"/>");
            sb.Append("</g>");
            if (r.App != lastApp)
            {
                string anchor = x < left + 60 ? "start" : x > W - right - 60 ? "end" : "middle";
                sb.Append(ci, $"<text x=\"{x:0.#}\" y=\"{H - bottom + 16}\" text-anchor=\"{anchor}\">{E(Short(r.App))}</text>");
                lastApp = r.App;
            }
        }
        sb.Append(ci, $"<text x=\"{left}\" y=\"{H - 6}\">{E(list[0].When.ToString("d MMM", ci))}</text><text x=\"{W - right}\" y=\"{H - 6}\" text-anchor=\"end\">{E(list[^1].When.ToString("d MMM", ci))}</text>");
        sb.Append("</svg>");
        sb.Append("<p class=\"key\"><i style=\"border-radius:50%;background:var(--bar)\"></i>Your run<i style=\"transform:rotate(45deg);background:var(--bar)\"></i>Claude's run<i style=\"border-radius:50%\"></i>Unreliable · line: margin · label: app version</p></div>\n");
        return sb.ToString();
    }

    /// <summary>The version alone ("0.1.521-e1a32bf" from "GyroPhone 0.1.521-e1a32bf"), for the chart's labels.</summary>
    private static string Short(string app)
    {
        var parts = app.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        return parts.Length > 0 ? parts[^1] : app;
    }

    private static string Tag(LatencyRun r) => r.Tag is { } t ? $" <span class=\"tag\">{E(t)}</span>" : "";

    /// <summary>A run with too few swings timed (<see cref="LatencyRun.Unreliable"/>) is marked so it isn't read as a clean number.</summary>
    private static string Unsure(LatencyRun r) =>
        r.Unreliable ? " <span class=\"warn\" title=\"Too few swings could be timed; whole-video match only\">unreliable</span>" : "";

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
