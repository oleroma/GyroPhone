using System.Drawing;
using GyroPhone.Host.Perf;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The FPS overlay (docs/spec/pc-overlays.md): the game's frames per second big, then 1% low, jumps and the
/// worst frame of the last minute, and the last 10 seconds of frame times as a strip with jumps as dots. Its
/// edge lights in the jump colour for a second after a jump. While a link test records, its time and what is
/// left show top left ("● REC 1:12 · 1:48 left"), and under the frame times a graph of the link's last 60 s.
/// </summary>
internal static class FpsDrawing
{
    /// <summary>How often the overlay draws again without anything else changing, ms.</summary>
    public const int EveryMs = 250;

    public static void Draw(ICanvas g, RectangleF area, OverlayScene scene, OverlaySettings s)
    {
        var text = Color.FromArgb(s.Color("text"));
        var line = Color.FromArgb(s.Color("line"));
        var jump = Color.FromArgb(s.Color("jump"));
        var panel = Color.FromArgb(s.Color("panel"));
        if (panel.A > 0) g.FillRectangle(panel, area);
        float pad = Math.Max(4f, Math.Min(area.Width, area.Height) * 0.05f);
        var inner = RectangleF.Inflate(area, -pad, -pad);
        var f = scene.Fps;
        string big, sub;
        bool watching = scene.FpsState == FrameWatchState.On;
        if (!watching)
        {
            big = "–";
            sub = scene.FpsState switch
            {
                FrameWatchState.NeedsPermission => "Needs the FPS helper: open Performance",
                FrameWatchState.Failed => "Can't watch frames: see Performance",
                _ => "Starting…",
            };
        }
        else if (f == null || f.Pid == 0)
        {
            big = "–";
            sub = "No game";
        }
        else
        {
            big = $"{f.Fps:0} fps";
            sub = $"1% low {f.Low1Fps:0} · {f.Jumps} jumps/min · worst {f.WorstMs:0} ms" + (f.Focused ? "" : " · in the background");
        }

        bool strip = inner.Height >= 70;
        var test = scene.FpsTest;
        string title = scene.FpsGame is { Length: > 0 } game && watching && f is { Pid: > 0 } ? game : "";
        // A row of its own on top for the REC time and the game's name, so they never run into the number.
        float headPx = Math.Clamp(inner.Height * 0.08f, 8f, 18f);
        float headH = test != null || title.Length > 0 ? headPx * 1.5f : 0f;
        var head = new RectangleF(inner.X, inner.Y, inner.Width, headH);
        if (title.Length > 0) g.DrawText(title, headPx, false, Color.FromArgb(text.A * 3 / 4, text), head, TextAlign.Far, TextAlign.Center);
        if (test != null) g.DrawText(TestText(test), headPx, true, jump, head, TextAlign.Near, TextAlign.Center);
        var body = new RectangleF(inner.X, inner.Y + headH, inner.Width, inner.Height - headH);

        // While a test records, the link's last 60 s get a graph under the frame times.
        var link = test != null ? scene.FpsLink : null;
        float graphsH = strip ? body.Height * (link != null ? 0.55f : 0.45f) : 0f;
        float textH = body.Height - graphsH;
        float bigPx = Math.Clamp(textH * 0.55f, 10f, 120f);
        float subPx = Math.Clamp(textH * 0.18f, 8f, 28f);
        g.DrawText(big, bigPx, true, text, new RectangleF(body.X, body.Y, body.Width, textH * 0.68f), TextAlign.Near, TextAlign.Far);
        g.DrawText(sub, subPx, false, text, new RectangleF(body.X, body.Y + textH * 0.68f, body.Width, textH * 0.32f), TextAlign.Near, TextAlign.Center);

        if (strip)
        {
            var graphs = new RectangleF(body.X, body.Y + textH + pad / 2, body.Width, graphsH - pad / 2);
            var frames = link != null ? new RectangleF(graphs.X, graphs.Y, graphs.Width, graphs.Height / 2 - pad / 4) : graphs;
            if (f is { Recent.Length: > 1 } && watching) Strip(g, frames, f.Recent, line, jump, text);
            if (link != null) LinkStrip(g, new RectangleF(graphs.X, graphs.Y + graphs.Height / 2 + pad / 4, graphs.Width, graphs.Height / 2 - pad / 4), link, line, jump, text);
        }

        // A jump in the last second lights the edge.
        if (watching && f != null && JumpedLately(f.Recent))
            g.DrawRectangle(jump, Math.Max(2f, pad / 2), RectangleF.Inflate(area, -pad / 4, -pad / 4));
    }

    /// <summary>"● REC 1:12 · 1:48 left", or "● REC 1:12" for a record that runs until stopped.</summary>
    internal static string TestText(RecordingView r)
    {
        static string Clock(double s) => $"{(int)s / 60}:{(int)s % 60:00}";
        double elapsed = Math.Max(0, r.Elapsed);
        return r.Seconds > 0 ? $"● REC {Clock(elapsed)} · {Clock(Math.Ceiling(Math.Max(0, r.Seconds - elapsed)))} left" : $"● REC {Clock(elapsed)}";
    }

    /// <summary>The link's last 60 s, a bar per second as high as its latest packet's delay (0 to 100 ms); lost packets,
    /// phone pauses or let-go keys make it full height in the jump colour. The newest second's latency is written over it.</summary>
    private static void LinkStrip(ICanvas g, RectangleF r, IReadOnlyList<LinkSecond> seconds, Color line, Color jump, Color text)
    {
        if (r.Height < 8) return;
        g.DrawLine(Color.FromArgb(60, text), 1f, new PointF(r.X, r.Bottom - 1), new PointF(r.Right, r.Bottom - 1));
        float label = Math.Clamp(r.Height * 0.3f, 7f, 13f);
        var dim = Color.FromArgb(text.A * 2 / 3, text);
        if (seconds.Count == 0)
        {
            g.DrawText("link: waiting for the phone", label, false, dim, r, TextAlign.Near, TextAlign.Near);
            return;
        }
        const int Span = 60;
        float w = r.Width / Span;
        long newest = seconds[^1].AtUs;
        foreach (var sec in seconds)
        {
            int age = (int)((newest - sec.AtUs + 500_000) / 1_000_000);
            if (age >= Span) continue;
            float x = r.Right - (age + 1) * w;
            bool bad = sec.Lost > 0 || sec.Gaps > 0 || sec.Releases > 0;
            float k = bad ? 1f : Math.Clamp(sec.MaxLateMs / 100f, 0f, 1f);
            if (!bad && sec.Late == 0) k = 0.04f; // a clean second: a stub
            g.FillRectangle(bad ? jump : sec.Late > 0 ? line : Color.FromArgb(90, line), new RectangleF(x + w * 0.15f, r.Bottom - 1 - (r.Height - 2) * k, w * 0.7f, (r.Height - 2) * k));
        }
        var last = seconds[^1];
        string now = last.LatP50Ms >= 0
            ? string.Create(System.Globalization.CultureInfo.InvariantCulture, $"link {last.LatP50Ms:0.0} ms · p99 {last.LatP99Ms:0} · late {last.Late}")
            : $"link · late {last.Late}";
        g.DrawText(now, label, false, dim, r, TextAlign.Near, TextAlign.Near);
    }

    private static bool JumpedLately(Frame[] frames)
    {
        if (frames.Length == 0) return false;
        long from = frames[^1].AtUs - 1_000_000;
        for (int i = frames.Length - 1; i >= 0 && frames[i].AtUs > from; i--)
            if (frames[i].Jump) return true;
        return false;
    }

    /// <summary>The frames as a line: the longest frame in each pixel column, so every hitch shows at any width.</summary>
    private static void Strip(ICanvas g, RectangleF r, Frame[] frames, Color line, Color jump, Color text)
    {
        int cols = Math.Max(2, (int)r.Width);
        long to = frames[^1].AtUs, from = to - FrameStats.GraphUs;
        var worst = new float[cols];
        var jumped = new bool[cols];
        float max = 33f;
        foreach (var fr in frames)
        {
            if (fr.AtUs < from) continue;
            int c = (int)Math.Clamp((fr.AtUs - from) * (cols - 1) / FrameStats.GraphUs, 0, cols - 1);
            if (fr.Ms > worst[c]) worst[c] = fr.Ms;
            if (fr.Jump) jumped[c] = true;
            if (fr.Ms > max) max = fr.Ms;
        }
        max = Math.Min(200f, max * 1.15f);
        float Y(float ms) => r.Bottom - 1 - (r.Height - 2) * Math.Clamp(ms / max, 0f, 1f);
        // Guide at 60 fps.
        if (16.7f < max) g.DrawLine(Color.FromArgb(60, text), 1f, new PointF(r.X, Y(16.7f)), new PointF(r.Right, Y(16.7f)));
        var pts = new List<PointF>(cols);
        for (int c = 0; c < cols; c++)
            if (worst[c] > 0) pts.Add(new PointF(r.X + c * r.Width / (cols - 1), Y(worst[c])));
        if (pts.Count > 1) g.DrawLines(line, 1.5f, [.. pts]);
        for (int c = 0; c < cols; c++)
            if (jumped[c])
            {
                float x = r.X + c * r.Width / (cols - 1), y = Y(worst[c]);
                g.FillEllipse(jump, new RectangleF(x - 3, y - 3, 6, 6));
            }
    }
}
