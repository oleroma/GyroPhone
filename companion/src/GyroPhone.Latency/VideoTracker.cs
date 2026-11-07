using OpenCvSharp;
using OpenCvSharp.Aruco;

namespace GyroPhone.Latency;

/// <summary>A timed video: the fit, the video's real frame rate and a second of both motions for the result card.</summary>
public sealed record VideoAnalysis(LatencyFit Fit, double Fps, int Frames, double TrackedShare, double[] PhoneCurve, double[] BarCurve);

/// <summary>Why a video can't be timed, in words for the person.</summary>
public sealed class VideoProblem(string message) : Exception(message);

/// <summary>
/// Reads a slow-motion video of the latency test frame by frame. In each frame it finds the phone's
/// four markers (ArUco 4×4, ids 0 to 3) and works out how far the phone is turned about its
/// vertical axis: the perspective that maps the markers' corners in the first frame where all four
/// show onto this frame, as a turn, with the camera's focal length guessed from the frame size
/// (a wrong guess changes the size of the turn, not its timing). It finds the test window's green
/// bar and takes its left-right position just above the phone's top markers, so both are read from
/// nearly the same lines of the picture (a phone camera captures them one after another).
/// </summary>
public static class VideoTracker
{
    /// <summary>Frames are tracked at most this wide (taller ones scaled to match): enough for the markers, and quick.</summary>
    private const int TrackWidth = 960;
    /// <summary>Below this the file is a slowed-down copy, or not slow motion: timing would be too coarse.</summary>
    public const double MinFps = 100;
    /// <summary>The bar's green, in OpenCV HSV (hue 0..180).</summary>
    private static readonly Scalar GreenLo = new(40, 110, 110), GreenHi = new(85, 255, 255);

    public static VideoAnalysis Analyze(string path, IProgress<double>? progress = null, CancellationToken cancel = default)
    {
        using var cap = new VideoCapture(path);
        if (!cap.IsOpened()) throw new VideoProblem("This video can't be opened. Try the original MP4 or MOV file.");
        int total = Math.Max(1, (int)cap.Get(VideoCaptureProperties.FrameCount));
        double propFps = cap.Get(VideoCaptureProperties.Fps);
        using var dict = CvAruco.GetPredefinedDictionary(PredefinedDictionaryType.Dict4X4_50);
        using var detector = new ArucoDetector(dict);

        var times = new List<double>();
        var phone = new List<double>();
        var bar = new List<double>();
        Point2f[]?[]? reference = null;
        using var frame = new Mat();
        using var small = new Mat();
        using var gray = new Mat();
        using var hsv = new Mat();
        using var green = new Mat();
        int tracked = 0;
        while (cap.Read(frame) && !frame.Empty())
        {
            cancel.ThrowIfCancellationRequested();
            times.Add(cap.Get(VideoCaptureProperties.PosMsec) / 1000.0);
            double scale = Math.Min(1.0, TrackWidth / (double)Math.Min(frame.Width, frame.Height));
            if (scale < 1) Cv2.Resize(frame, small, new Size(), scale, scale, InterpolationFlags.Area);
            else frame.CopyTo(small);
            Cv2.CvtColor(small, gray, ColorConversionCodes.BGR2GRAY);
            detector.DetectMarkers(gray, out var corners, out var ids, out _);
            var found = new Point2f[]?[4];
            for (int i = 0; i < ids.Length; i++)
                if (ids[i] is >= 0 and < 4) found[ids[i]] = corners[i];
            int count = found.Count(c => c != null);
            if (reference == null && count == 4) reference = found;

            double turn = double.NaN;
            float top = float.NaN;
            if (count >= 2)
            {
                top = found.Where(c => c != null).SelectMany(c => c!).Min(p => p.Y);
                if (reference != null) turn = Turn(reference, found, small.Width, small.Height);
            }
            if (!double.IsNaN(turn)) tracked++;
            phone.Add(turn);
            bar.Add(BarX(small, hsv, green, top));
            if (times.Count % 20 == 0) progress?.Report(Math.Min(1, times.Count / (double)total));
        }
        progress?.Report(1);
        if (times.Count < 30) throw new VideoProblem("This video is too short to time.");

        double fps = RealFps(times, propFps);
        if (fps < MinFps)
            throw new VideoProblem($"This video plays at {fps:0} frames a second. Film in slow motion (240 fps) and copy the original file, not a shared copy.");
        if (reference == null)
            throw new VideoProblem("The phone's four markers never showed together. Keep the phone's screen toward the camera, markers in the picture.");
        var ph = phone.ToArray();
        var br = bar.ToArray();
        var fit = LatencyMath.Fit(ph, br, fps)
            ?? throw new VideoProblem("The phone's twist and the green bar don't match well enough. Twist quickly back and forth, keep the bar and the markers in the picture, and check that the gyro moves the mouse.");
        var (pc, bc) = Curves(ph, br, fps, fit);
        return new VideoAnalysis(fit, fps, times.Count, tracked / (double)times.Count, pc, bc);
    }

    /// <summary>
    /// How far the phone is turned about its vertical axis (radians, from the reference frame): the
    /// perspective from the reference corners to these, undone by a guessed camera, as a turn.
    /// </summary>
    private static double Turn(Point2f[]?[] reference, Point2f[]?[] found, int width, int height)
    {
        var src = new List<Point2d>();
        var dst = new List<Point2d>();
        for (int i = 0; i < 4; i++)
        {
            if (reference[i] == null || found[i] == null) continue;
            for (int j = 0; j < 4; j++)
            {
                src.Add(new Point2d(reference[i]![j].X, reference[i]![j].Y));
                dst.Add(new Point2d(found[i]![j].X, found[i]![j].Y));
            }
        }
        if (src.Count < 8) return double.NaN;
        using var h = Cv2.FindHomography(src, dst);
        if (h.Empty()) return double.NaN;
        double f = Math.Max(width, height) * 0.9, cx = width / 2.0;
        // The first column of K^-1 H K, with K the guessed camera [[f, 0, cx], [0, f, cy], [0, 0, 1]]:
        // K^-1 H (f, 0, 0). Its x and z say how far the screen's left-right axis turned toward the camera.
        double x = f * h.At<double>(0, 0), z = f * h.At<double>(2, 0);
        double rx = (x - cx * z) / f, rz = z;
        return Math.Atan2(rz, rx);
    }

    /// <summary>
    /// The green bar's left-right centre: in the band of rows just above the phone's top markers
    /// when the phone was found, else in the whole frame; NaN when too little green shows.
    /// </summary>
    private static double BarX(Mat small, Mat hsv, Mat green, float phoneTop)
    {
        Cv2.CvtColor(small, hsv, ColorConversionCodes.BGR2HSV);
        Cv2.InRange(hsv, GreenLo, GreenHi, green);
        int h = small.Height;
        Rect band = new(0, 0, small.Width, h);
        if (!float.IsNaN(phoneTop))
        {
            int bottom = (int)phoneTop - h / 50;
            int height = h / 8;
            if (bottom - 4 > 0) band = new Rect(0, Math.Max(0, bottom - height), small.Width, bottom - Math.Max(0, bottom - height));
        }
        using var roi = new Mat(green, band);
        var m = Cv2.Moments(roi, binaryImage: true);
        if (m.M00 < 20) return double.NaN;
        return m.M10 / m.M00;
    }

    /// <summary>The frame rate the frames were filmed at, from their times (the file's own figure when those are missing).</summary>
    private static double RealFps(List<double> times, double propFps)
    {
        double span = times[^1] - times[0];
        if (span > 0 && times.Count > 2)
        {
            double fromTimes = (times.Count - 1) / span;
            if (fromTimes > 1) return fromTimes;
        }
        return propFps;
    }

    /// <summary>One second of both motions (drift-free, same size, the bar turned to the phone's direction), from the middle of the video, for the card.</summary>
    private static (double[] Phone, double[] Bar) Curves(double[] phone, double[] bar, double fps, LatencyFit fit)
    {
        var a = LatencyMath.Normalize(LatencyMath.HighPass(LatencyMath.Fill(phone)!, (int)(fps * LatencyMath.DriftS) | 1));
        var b = LatencyMath.Normalize(LatencyMath.HighPass(LatencyMath.Fill(bar)!, (int)(fps * LatencyMath.DriftS) | 1));
        int maxLag = (int)(LatencyMath.MaxLagS * fps);
        var (_, c) = LatencyMath.BestLag(a, b, maxLag, 0);
        double sign = Math.Sign(c);
        int n = Math.Min(a.Length, (int)fps);
        int start = (a.Length - n) / 2;
        return (a.AsSpan(start, n).ToArray(), b.AsSpan(start, n).ToArray().Select(v => v * sign).ToArray());
    }
}
