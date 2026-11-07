using GyroPhone.Latency;
using OpenCvSharp;
using OpenCvSharp.Aruco;

namespace GyroPhone.Tests;

public class LatencyAnalysisTests
{
    /// <summary>A twisting motion: two waves, as a wrist makes.</summary>
    private static double Twist(double t) => 25 * Math.Sin(2 * Math.PI * 2.7 * t) + 8 * Math.Sin(2 * Math.PI * 4.1 * t + 1);

    [Theory]
    [InlineData(0.0)]
    [InlineData(12.5)]
    [InlineData(40.0)]
    public void FitFindsTheLagBetweenTwoMotions(double lagMs)
    {
        const double fps = 240;
        int n = (int)(fps * 6);
        var phone = new double[n];
        var bar = new double[n];
        var rng = new Random(3);
        for (int i = 0; i < n; i++)
        {
            double t = i / fps;
            phone[i] = Twist(t) + 0.5 * rng.NextDouble() + 3 * t; // drift and noise
            bar[i] = -4 * Twist(t - lagMs / 1000) + 400; // other direction, other size
        }
        var fit = LatencyMath.Fit(phone, bar, fps)!;
        Assert.InRange(fit.LatencyMs, lagMs - 0.5, lagMs + 0.5);
        Assert.InRange(fit.SpreadMs, 0, 1);
        Assert.InRange(fit.Twists, 14, 18);
    }

    [Fact]
    public void FitRefusesMotionsThatDontMatch()
    {
        const double fps = 240;
        var rng = new Random(5);
        var phone = Enumerable.Range(0, 1440).Select(i => Twist(i / fps)).ToArray();
        var bar = Enumerable.Range(0, 1440).Select(_ => rng.NextDouble()).ToArray();
        Assert.Null(LatencyMath.Fit(phone, bar, fps));
    }

    [Fact]
    public void TwistingStretchLeavesOutTheSetup()
    {
        const double fps = 240;
        // 4 s of walking up (phone seen, held still with a slow sway, then not seen at all for 2 s),
        // 10 s of twisting, 3 s of putting it down.
        int n = (int)(fps * 17);
        var phone = new double[n];
        var bar = new double[n];
        for (int i = 0; i < n; i++)
        {
            double t = i / fps;
            bool twisting = t >= 4 && t < 14;
            bool seen = !(t >= 1 && t < 3);
            phone[i] = !seen ? double.NaN : twisting ? Twist(t) : 2 * Math.Sin(t);
            bar[i] = !seen ? double.NaN : twisting ? -4 * Twist(t - 0.02) : 10 * Math.Sin(t);
        }
        var (start, length) = LatencyMath.TwistingStretch(phone, bar, fps);
        Assert.InRange(start / fps, 3.5, 4.5);
        Assert.InRange((start + length) / fps, 13.5, 14.5);
        var fit = LatencyMath.Fit(phone.AsSpan(start, length).ToArray(), bar.AsSpan(start, length).ToArray(), fps)!;
        Assert.InRange(fit.LatencyMs, 19.5, 20.5);
    }

    [Fact]
    public void TwistingStretchKeepsAWholeTwistingVideo()
    {
        const double fps = 240;
        var phone = Enumerable.Range(0, 1440).Select(i => Twist(i / fps)).ToArray();
        var bar = phone.Select(v => -v).ToArray();
        Assert.Equal((0, 1440), LatencyMath.TwistingStretch(phone, bar, fps));
    }

    [Fact]
    public void AFreezeIsLeftOut()
    {
        const double fps = 240;
        int n = (int)(fps * 10);
        var phone = new double[n];
        var bar = new double[n];
        for (int i = 0; i < n; i++)
        {
            double t = i / fps;
            phone[i] = Twist(t);
            bar[i] = 300 - 5 * Twist(t - 0.016);
        }
        // The bar stands still for 0.36 s at 5.67 s, then catches up over 70 ms.
        int f0 = (int)(5.67 * fps), f1 = (int)(6.03 * fps), f2 = (int)(6.10 * fps);
        for (int i = f0; i < f1; i++) bar[i] = bar[f0];
        for (int i = f1; i < f2; i++) bar[i] = bar[f0] + (bar[f2] - bar[f0]) * (i - f1) / (double)(f2 - f1);
        var fit = LatencyMath.Fit(phone, bar, fps)!;
        Assert.Equal(1, fit.Freezes);
        Assert.InRange(fit.Skipped, 1, 3);
        Assert.InRange(fit.LatencyMs, 15.5, 16.5);
    }

    [Fact]
    public void FillBridgesUntrackedFrames()
    {
        var v = LatencyMath.Fill([double.NaN, 1, double.NaN, double.NaN, 4, 5, double.NaN])!;
        Assert.Equal([1, 1, 2, 3, 4, 5, 5], v);
        Assert.Null(LatencyMath.Fill([double.NaN, double.NaN, double.NaN, 1]));
    }

    /// <summary>
    /// A made-up slow-motion video of the test: the phone's marker screen turning about its vertical
    /// axis in front of the camera, the test window's green bar above it following the turn
    /// <paramref name="lagMs"/> later.
    /// </summary>
    private static string SynthVideo(double lagMs, double fps, int frames, int markers = 0b1111, bool tilt = false)
    {
        const int w = 360, h = 640;
        string path = Path.Combine(Path.GetTempPath(), $"gp-latency-{Guid.NewGuid():N}.avi");
        using var dict = CvAruco.GetPredefinedDictionary(PredefinedDictionaryType.Dict4X4_50);
        // The phone's screen, portrait, as LatencyMarkers draws it: a 16-cell grid in the top half,
        // each marker a light grey quiet zone around its black 6 × 6 square.
        using var screen = new Mat(1300, 600, MatType.CV_8UC3, Scalar.Black);
        const int cell = 34, x0 = 28, y0 = 20;
        var light = new Scalar(168, 168, 168);
        for (int i = 0; i < 4; i++)
        {
            if ((markers & (1 << i)) == 0) continue;
            int mx = x0 + cell * 8 * (i % 2), my = y0 + cell * 8 * (i / 2);
            Cv2.Rectangle(screen, new Rect(mx, my, cell * 8, cell * 8), light, -1);
            using var m = new Mat();
            dict.GenerateImageMarker(i, 6, m, 1);
            for (int r = 0; r < 6; r++)
                for (int c = 0; c < 6; c++)
                    Cv2.Rectangle(screen, new Rect(mx + cell * (c + 1), my + cell * (r + 1), cell, cell),
                        m.At<byte>(r, c) > 127 ? light : Scalar.Black, -1);
        }
        using var whole = new Mat(1300, 600, MatType.CV_8UC1, Scalar.White);
        using var writer = new VideoWriter(path, FourCC.MJPG, fps, new Size(w, h));
        Assert.True(writer.IsOpened());
        using var img = new Mat();
        using var warped = new Mat();
        using var mask = new Mat();
        const double f = 600, z = 0.40, pw = 0.07, ph = 0.152;
        for (int k = 0; k < frames; k++)
        {
            double t = k / fps;
            img.Create(h, w, MatType.CV_8UC3);
            img.SetTo(new Scalar(20, 20, 20));
            int bx = (int)(w / 2 + Twist(t - lagMs / 1000) * 5);
            Cv2.Rectangle(img, new Rect(bx - 10, 0, 20, h), new Scalar(60, 255, 40), -1);
            double th = Twist(t) * Math.PI / 180;
            var corners = new[] { (0.0, 0.0), (600.0, 0.0), (600.0, 1300.0), (0.0, 1300.0) }.Select(p =>
            {
                double x = (p.Item1 / 600 - 0.5) * pw, y = (p.Item2 / 1300 - 0.5) * ph;
                if (tilt)
                {
                    // Turning within the picture, like a steering wheel.
                    double xt = x * Math.Cos(th) - y * Math.Sin(th), yt = x * Math.Sin(th) + y * Math.Cos(th);
                    return new Point2f((float)(w / 2 + f * xt / z), (float)(h * 0.62 + f * yt / z));
                }
                double xr = x * Math.Cos(th), zr = z + x * Math.Sin(th);
                return new Point2f((float)(w / 2 + f * xr / zr), (float)(h * 0.62 + f * y / zr));
            }).ToArray();
            using var hm = Cv2.GetPerspectiveTransform([new(0, 0), new(600, 0), new(600, 1300), new(0, 1300)], corners);
            Cv2.WarpPerspective(screen, warped, hm, new Size(w, h));
            Cv2.WarpPerspective(whole, mask, hm, new Size(w, h));
            warped.CopyTo(img, mask);
            writer.Write(img);
        }
        return path;
    }

    [Fact]
    public void TwoMarkersAreEnough()
    {
        string path = SynthVideo(lagMs: 30, fps: 240, frames: 240 * 4, markers: 0b0011);
        try
        {
            var a = VideoTracker.Analyze(path);
            Assert.InRange(a.Fit.LatencyMs, 27, 33);
            Assert.True(a.TrackedShare > 0.9);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void TwistSeenAsATiltIsTimed()
    {
        string path = SynthVideo(lagMs: 16, fps: 240, frames: 240 * 4, tilt: true);
        try
        {
            var a = VideoTracker.Analyze(path);
            Assert.Equal(PhoneMotion.Tilt, a.Motion);
            Assert.InRange(a.Fit.LatencyMs, 14.5, 17.5);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void VideoOfTheTestIsTimed()
    {
        string path = SynthVideo(lagMs: 20, fps: 240, frames: 240 * 4);
        try
        {
            var a = VideoTracker.Analyze(path);
            Assert.InRange(a.Fps, 239, 241);
            Assert.InRange(a.Fit.LatencyMs, 18.5, 21.5);
            Assert.True(a.TrackedShare > 0.9);
            Assert.Equal(240, a.PhoneCurve.Length);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void SlowedDownVideoIsRefused()
    {
        string path = SynthVideo(lagMs: 20, fps: 30, frames: 90);
        try
        {
            var e = Assert.Throws<VideoProblem>(() => VideoTracker.Analyze(path));
            Assert.Contains("30 frames a second", e.Message);
        }
        finally
        {
            File.Delete(path);
        }
    }
}
