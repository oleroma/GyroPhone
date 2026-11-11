using System.Drawing;
using GyroPhone.Host.Perf;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The FPS overlay (docs/spec/pc-overlays.md): the game's frames per second big, then 1% low, jumps and the
/// worst frame of the last minute, and the last 10 seconds of frame times as a strip with jumps as dots. Its
/// edge lights in the jump colour for a second after a jump. While a link test records, its time and what is
/// left show top left ("● REC 1:12 · 1:48 left").
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
                FrameWatchState.NeedsPermission => "Needs a Windows permission: open Performance",
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
        float textH = strip ? inner.Height * 0.55f : inner.Height;
        float bigPx = Math.Clamp(textH * 0.55f, 10f, 120f);
        float subPx = Math.Clamp(textH * 0.18f, 8f, 28f);
        var bigBox = new RectangleF(inner.X, inner.Y, inner.Width, textH * 0.68f);
        var subBox = new RectangleF(inner.X, inner.Y + textH * 0.68f, inner.Width, textH * 0.32f);
        string title = scene.FpsGame is { Length: > 0 } game && watching && f is { Pid: > 0 } ? game : "";
        if (title.Length > 0)
            g.DrawText(title, subPx, false, Color.FromArgb(text.A * 3 / 4, text), new RectangleF(inner.X, inner.Y, inner.Width, subPx * 1.4f), TextAlign.Far, TextAlign.Near);
        if (scene.FpsTest is { } test)
            g.DrawText(TestText(test), subPx, true, jump, new RectangleF(inner.X, inner.Y, inner.Width, subPx * 1.4f), TextAlign.Near, TextAlign.Near);
        g.DrawText(big, bigPx, true, text, bigBox, TextAlign.Near, TextAlign.Far);
        g.DrawText(sub, subPx, false, text, subBox, TextAlign.Near, TextAlign.Center);

        if (strip && f is { Recent.Length: > 1 } && watching) Strip(g, new RectangleF(inner.X, inner.Y + textH + pad / 2, inner.Width, inner.Height - textH - pad / 2), f.Recent, line, jump, text);

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
