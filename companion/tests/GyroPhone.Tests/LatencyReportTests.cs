using GyroPhone.Latency;
using Xunit;

namespace GyroPhone.Tests;

public class LatencyReportTests
{
    private static LatencyRun Run(int stretches, string? tag = null, string video = "IMG_1438.MOV") =>
        new(new DateTime(2026, 11, 7, 14, 36, 0), 13.81, 1.78, 16, 239.8, 0.97, "Wi‑Fi 5 GHz", "OnePlus 15", 165, "0.1.518",
            video, [0, 0.5, 1, 0.5, 0], [0, 0.4, 0.9, 0.6, 0], 0.5, 11.1, 8, 2, stretches, tag);

    [Fact]
    public void ShowsEveryRunWithItsNumbers()
    {
        string html = LatencyReport.Html([Run(5, "claude"), Run(3)]);
        Assert.StartsWith("<!doctype html>", html);
        Assert.Contains("13.8 ms", html);
        Assert.Contains("±1.8 ms", html);
        Assert.Contains("claude", html);
        Assert.Contains("2 freezes, 8 of 13 stretches left out", html);
        Assert.Equal(4, html.Split("<polyline").Length - 1);
        Assert.DoesNotContain("unreliable</span>", html);
    }

    [Fact]
    public void ARunWithNoStretchCheckedIsMarkedUnreliable()
    {
        Assert.Contains("unreliable</span>", LatencyReport.Html([Run(0)]));
    }

    [Fact]
    public void TextIsEscaped()
    {
        string html = LatencyReport.Html([Run(5, video: "<b>x</b>.mov")]);
        Assert.DoesNotContain("<b>x</b>", html);
        Assert.Contains("&lt;b&gt;x&lt;/b&gt;.mov", html);
    }

    [Fact]
    public void NoRunsSaysSo() => Assert.Contains("No results yet.", LatencyReport.Html([]));
}
