using OpenCvSharp;
using OpenCvSharp.Aruco;

namespace GyroPhone.Latency;

/// <summary>
/// A timed video: the fit, the video's real frame rate, the part of it that was timed (seconds from
/// its start, see <see cref="LatencyMath.TwistingStretch"/>) and a second of both motions for the result card.
/// </summary>
public sealed record VideoAnalysis(
    LatencyFit Fit, double Fps, int Frames, double TrackedShare, double[] PhoneCurve, double[] BarCurve,
    double TimedFromS, double TimedToS, double LengthS);

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
    private const int TrackWidth = 640;
    /// <summary>Frames looked at together, spread over the cores.</summary>
    private const int Batch = 64;
    /// <summary>Below this the file is a slowed-down copy, or not slow motion: timing would be too coarse.</summary>
    public const double MinFps = 100;
    /// <summary>The bar's green, in OpenCV HSV (hue 0..180).</summary>
    private static readonly Scalar GreenLo = new(40, 110, 110), GreenHi = new(85, 255, 255);

    public static VideoAnalysis Analyze(string path, IProgress<double>? progress = null, CancellationToken cancel = default)
    {
        using var cap = Open(path);
        int total = Math.Max(1, (int)cap.Get(VideoCaptureProperties.FrameCount));
        double propFps = cap.Get(VideoCaptureProperties.Fps);
        using var dict = CvAruco.GetPredefinedDictionary(PredefinedDictionaryType.Dict4X4_50);

        // Frames are decoded in order (one at a time, on the graphics card where it can), then
        // looked at in batches spread over the cores; each core keeps its own detector and buffers.
        using var workers = new ThreadLocal<Worker>(() => new Worker(dict), trackAllValues: true);
        var times = new List<double>();
        var phone = new List<double>();
        var bar = new List<double>();
        int anySeen = 0;
        var batch = new List<Mat>(Batch);
        var batchTurn = new double[Batch];
        var batchBar = new double[Batch];
        var batchSeen = new bool[Batch];
        try
        {
            using var frame = new Mat();
            bool more = true;
            while (more)
            {
                batch.Clear();
                while (batch.Count < Batch && (more = cap.Read(frame) && !frame.Empty()))
                {
                    cancel.ThrowIfCancellationRequested();
                    times.Add(cap.Get(VideoCaptureProperties.PosMsec) / 1000.0);
                    double scale = Math.Min(1.0, TrackWidth / (double)Math.Min(frame.Width, frame.Height));
                    var small = new Mat();
                    if (scale < 1) Cv2.Resize(frame, small, new Size(), scale, scale, InterpolationFlags.Area);
                    else frame.CopyTo(small);
                    batch.Add(small);
                }
                Parallel.For(0, batch.Count, new ParallelOptions { CancellationToken = cancel }, i =>
                    (batchTurn[i], batchBar[i], batchSeen[i]) = workers.Value!.Look(batch[i]));
                for (int i = 0; i < batch.Count; i++)
                {
                    phone.Add(batchTurn[i]);
                    bar.Add(batchBar[i]);
                    if (batchSeen[i]) anySeen++;
                    batch[i].Dispose();
                }
                progress?.Report(Math.Min(1, times.Count / (double)total));
            }
        }
        finally
        {
            foreach (var m in batch) m.Dispose();
            foreach (var w in workers.Values) w.Dispose();
        }
        progress?.Report(1);
        if (times.Count < 30) throw new VideoProblem("This video is too short to time.");

        double fps = RealFps(times, propFps);
        if (fps < MinFps)
            throw new VideoProblem($"This video plays at {fps:0} frames a second. Film in slow motion (240 fps) and copy the original file, not a shared copy.");
        if (anySeen == 0)
            throw new VideoProblem("The phone's markers never showed. Keep the phone's screen toward the camera, its top half in the picture, in good light.");
        var (from, timedCount) = LatencyMath.TwistingStretch(phone.ToArray(), bar.ToArray(), fps);
        var ph = phone.GetRange(from, timedCount).ToArray();
        var br = bar.GetRange(from, timedCount).ToArray();
        var fit = LatencyMath.Fit(ph, br, fps)
            ?? throw new VideoProblem("The phone's twist and the green bar don't match well enough. Twist quickly back and forth, keep the bar and the markers in the picture, and check that the gyro moves the mouse.");
        var (pc, bc) = Curves(ph, br, fps, fit);
        double t0 = times[0];
        int timedTracked = ph.Count(v => !double.IsNaN(v));
        return new VideoAnalysis(fit, fps, times.Count, timedTracked / (double)ph.Length, pc, bc,
            times[from] - t0, times[from + timedCount - 1] - t0, times[^1] - t0);
    }

    /// <summary>The video, decoded on the graphics card where Windows offers it (else on the CPU).</summary>
    private static VideoCapture Open(string path)
    {
        try
        {
            var hw = new VideoCapture(path, VideoCaptureAPIs.FFMPEG, [(int)VideoCaptureProperties.HwAcceleration, (int)VideoAccelerationType.Any]);
            if (hw.IsOpened()) return hw;
            hw.Dispose();
        }
        catch (OpenCVException)
        {
            // No such backend here: the plain one below.
        }
        var cap = new VideoCapture(path);
        if (!cap.IsOpened())
        {
            cap.Dispose();
            throw new VideoProblem("This video can't be opened. Try the original MP4 or MOV file.");
        }
        return cap;
    }

    /// <summary>One core's detector and buffers.</summary>
    private sealed class Worker(Dictionary dict) : IDisposable
    {
        private readonly ArucoDetector _detector = new(dict,
            new DetectorParameters { CornerRefinementMethod = CornerRefineMethod.Subpix }, new RefineParameters());
        private readonly Mat _gray = new(), _hsv = new(), _green = new();

        /// <summary>The phone's turn (NaN when no marker shows), the bar's position (NaN when not found), and whether a marker showed.</summary>
        public (double Turn, double Bar, bool Seen) Look(Mat small)
        {
            Cv2.CvtColor(small, _gray, ColorConversionCodes.BGR2GRAY);
            _detector.DetectMarkers(_gray, out var corners, out var ids, out _);
            var found = new Point2f[]?[4];
            for (int i = 0; i < ids.Length; i++)
                if (ids[i] is >= 0 and < 4) found[ids[i]] = corners[i];
            double turn = double.NaN;
            float top = float.NaN;
            bool seen = found.Any(c => c != null);
            if (seen) (turn, top) = Turn(found, small.Width, small.Height);
            return (turn, BarX(small, _hsv, _green, top), seen);
        }

        public void Dispose()
        {
            _detector.Dispose();
            _gray.Dispose();
            _hsv.Dispose();
            _green.Dispose();
        }
    }

    /// <summary>
    /// Where the phone's markers sit on its screen, in cells (see LatencyMarkers.kt on the phone): a
    /// 16 × 16 grid, each marker's black 6 × 6 square at (1, 1) of its 8 × 8 corner of the grid.
    /// </summary>
    private const int MarkerPitch = 8, MarkerSide = 6, GridCells = 16;

    /// <summary>
    /// How far the phone is turned about its vertical axis (radians, from facing the camera) and
    /// the image row of the top of its marker grid, from the markers seen (two or more of the four for the turn): the
    /// perspective from the grid's known layout to their corners, undone by a guessed camera, as a
    /// turn. It reads the screen's left-right axis, so it works however the video is turned.
    /// </summary>
    private static (double Turn, float Top) Turn(Point2f[]?[] found, int width, int height)
    {
        var src = new List<Point2d>();
        var dst = new List<Point2d>();
        for (int i = 0; i < 4; i++)
        {
            if (found[i] == null) continue;
            double x = MarkerPitch * (i % 2) + 1, y = MarkerPitch * (i / 2) + 1;
            // ArUco corners run top left, top right, bottom right, bottom left.
            Point2d[] grid = [new(x, y), new(x + MarkerSide, y), new(x + MarkerSide, y + MarkerSide), new(x, y + MarkerSide)];
            for (int j = 0; j < 4; j++)
            {
                src.Add(grid[j]);
                dst.Add(new Point2d(found[i]![j].X, found[i]![j].Y));
            }
        }
        using var h = Cv2.FindHomography(src, dst);
        if (h.Empty()) return (double.NaN, float.NaN);
        double f = Math.Max(width, height) * 0.9, cx = width / 2.0, cy = height / 2.0;
        // The first column of K^-1 H, with K the guessed camera [[f, 0, cx], [0, f, cy], [0, 0, 1]]:
        // the screen's left-right axis as the camera sees it. How far it points toward the camera is the turn.
        double h00 = h.At<double>(0, 0), h10 = h.At<double>(1, 0), h20 = h.At<double>(2, 0);
        double ax = (h00 - cx * h20) / f, ay = (h10 - cy * h20) / f;
        // One marker alone is too small a base to see the turn in (its perspective drowns in noise): two at least.
        double turn = src.Count >= 8 ? Math.Atan2(h20, Math.Sqrt(ax * ax + ay * ay)) : double.NaN;
        var top = Cv2.PerspectiveTransform([new Point2d(0, 0), new Point2d(GridCells, 0)], h);
        return (turn, (float)Math.Min(top[0].Y, top[1].Y));
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
