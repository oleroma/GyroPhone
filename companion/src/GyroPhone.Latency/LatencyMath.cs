namespace GyroPhone.Latency;

/// <summary>What the analyser found: how far the bar trails the phone, and how sure it is.</summary>
/// <param name="LatencyMs">The middle of the lags of the half-twists timed one by one.</param>
/// <param name="SpreadMs">
/// The margin on that middle (95%): how far it could be off, from how much the half-twists differ
/// and how many there are. 0 when too few could be timed.
/// </param>
/// <param name="Twists">Back-and-forth twists seen.</param>
/// <param name="Correlation">How well the two motions match over the whole video once lined up (0 to 1).</param>
/// <param name="Stretches">Half-twists timed and used.</param>
/// <param name="Skipped">Half-twists left out: the phone or bar out of sight, a freeze, or a poor match.</param>
/// <param name="Freezes">Times the bar stood still while the phone moved (the motion stalled, then caught up).</param>
/// <param name="LongestFreezeMs">The longest of those freezes.</param>
public sealed record LatencyFit(double LatencyMs, double SpreadMs, int Twists, double Correlation, int Stretches,
    int Skipped = 0, int Freezes = 0, double LongestFreezeMs = 0);

/// <summary>
/// The timing maths, without video: both motions are sampled once per video frame. Each is
/// filled where tracking missed a frame, freed of slow drift (a moving average of
/// <see cref="DriftS"/> taken off) and scaled to the same size. The whole video's best lag (0 to
/// <see cref="MaxLagS"/>) checks that the two match at all and gives the direction (the bar's
/// direction follows the mouse mapping). Then each half-twist (one swing, from one crossing of the
/// middle to the next) is timed on its own, scored by its own match, and the result is the middle
/// of those lags with a margin from all of them, so no one piece decides it.
/// </summary>
public static class LatencyMath
{
    public const double MaxLagS = 0.25;
    public const double DriftS = 0.6;
    /// <summary>Below this match the video can't be timed (no twisting, or tracking lost).</summary>
    public const double MinCorrelation = 0.5;
    /// <summary>Fewer half-twists than this timed: the result rests on the whole video's lag alone, and is shown as unreliable.</summary>
    public const int MinHalfTwists = 3;

    /// <summary>Times the bar against the phone; null when they don't match well enough.</summary>
    public static LatencyFit? Fit(double[] phone, double[] bar, double fps)
    {
        if (phone.Length != bar.Length || fps <= 0) throw new ArgumentException("one sample of each per frame");
        int maxLag = (int)(MaxLagS * fps);
        if (phone.Length < maxLag * 3) return null;
        var a = Fill(phone);
        var b = Fill(bar);
        if (a == null || b == null) return null;
        int n = phone.Length;
        int w = (int)(fps * DriftS) | 1;
        a = Normalize(HighPass(a, w));
        b = Normalize(HighPass(b, w));
        var (lag, c) = BestLag(a, b, maxLag, 0);
        if (Math.Abs(c) < MinCorrelation) return null;
        int sign = Math.Sign(c);

        // Frames to leave out: where either was out of sight, or the bar froze while the phone moved.
        var bad = new bool[n];
        for (int i = 0; i < n; i++) bad[i] = double.IsNaN(phone[i]) || double.IsNaN(bar[i]);
        var (freezes, longest) = MarkFreezes(a, bar, fps, bad);

        // Each half-twist on its own: clean ones that match well are used, the others counted as left out.
        var lags = new List<double>();
        int skipped = 0;
        var cuts = Crossings(a);
        for (int k = 0; k + 1 < cuts.Count; k++)
        {
            int s = cuts[k], len = cuts[k + 1] - s;
            if (s + len + maxLag > n) break;
            var (l, r) = HalfTwistLag(a, b, s, len, maxLag, sign);
            int at = (int)Math.Round(l), badCount = 0;
            for (int i = s; i < s + len; i++) if (bad[i] || bad[i + at]) badCount++;
            if (badCount * 20 > len || r < HalfTwistMatch) skipped++;
            else lags.Add(l);
        }
        double latency, margin = 0;
        if (lags.Count >= MinHalfTwists)
        {
            lags.Sort();
            latency = Quantile(lags, 0.5);
            // The middle's margin: the half-twists' spread (from their middle half, so one odd one
            // doesn't sway it) over the root of their count, as for a median.
            double sd = (Quantile(lags, 0.75) - Quantile(lags, 0.25)) / 1.349;
            margin = 1.96 * 1.2533 * sd / Math.Sqrt(lags.Count);
        }
        else
        {
            // Too few to go by: the whole video's lag, over the clean frames only.
            latency = bad.Any(x => x) ? BestLag(a, b, maxLag, sign, bad).Lag : lag;
        }
        return new LatencyFit(latency / fps * 1000, margin / fps * 1000, CountTwists(a), Math.Abs(c), lags.Count, skipped,
            freezes, longest / fps * 1000);
    }

    /// <summary>A half-twist must match at least this well, on its own, to count.</summary>
    public const double HalfTwistMatch = 0.8;

    /// <summary>
    /// Where the drift-free motion crosses its middle, ignoring wobble under a fifth of its size:
    /// the frame where each swing to the other side begins.
    /// </summary>
    public static List<int> Crossings(double[] a)
    {
        var cuts = new List<int>();
        int side = 0, flip = 0;
        for (int i = 0; i < a.Length; i++)
        {
            if (i > 0 && Math.Sign(a[i]) != Math.Sign(a[i - 1])) flip = i;
            int s = a[i] > 0.2 ? 1 : a[i] < -0.2 ? -1 : 0;
            if (s == 0) continue;
            if (side != 0 && s != side) cuts.Add(flip);
            side = s;
        }
        return cuts;
    }

    /// <summary>
    /// The lag (frames, with a fraction) at which the bar best repeats the phone's half-twist
    /// <paramref name="start"/>..+<paramref name="len"/>, and the match there, scored on that piece
    /// alone (a correlation, -1 to 1, in the given direction).
    /// </summary>
    public static (double Lag, double Match) HalfTwistLag(double[] a, double[] b, int start, int len, int maxLag, int sign)
    {
        var c = new double[maxLag];
        double ma = 0;
        for (int i = start; i < start + len; i++) ma += a[i];
        ma /= len;
        double va = 0;
        for (int i = start; i < start + len; i++) va += (a[i] - ma) * (a[i] - ma);
        for (int l = 0; l < maxLag; l++)
        {
            double mb = 0;
            for (int i = start; i < start + len; i++) mb += b[i + l];
            mb /= len;
            double cov = 0, vb = 0;
            for (int i = start; i < start + len; i++)
            {
                double db = b[i + l] - mb;
                cov += (a[i] - ma) * db;
                vb += db * db;
            }
            c[l] = va > 0 && vb > 0 ? cov / Math.Sqrt(va * vb) * sign : 0;
        }
        int best = 0;
        for (int l = 1; l < maxLag; l++) if (c[l] > c[best]) best = l;
        double frac = 0;
        if (best > 0 && best < maxLag - 1)
        {
            double den = c[best - 1] - 2 * c[best] + c[best + 1];
            if (den < 0) frac = 0.5 * (c[best - 1] - c[best + 1]) / den;
        }
        return (best + frac, c[best]);
    }

    /// <summary>The bar standing still this long while the phone moves is a freeze.</summary>
    public const double FreezeS = 0.1;

    /// <summary>
    /// Finds the freezes: runs of at least <see cref="FreezeS"/> where the bar (raw, in pixels) moves
    /// under half a pixel a frame while the phone (<paramref name="phone"/>, drift-free and scaled)
    /// moves at least half its usual size. Marks them in <paramref name="bad"/>; returns how many,
    /// and the longest (frames).
    /// </summary>
    public static (int Count, int Longest) MarkFreezes(double[] phone, double[] bar, double fps, bool[] bad)
    {
        int min = Math.Max(2, (int)(FreezeS * fps));
        int count = 0, start = -1, longest = 0;
        for (int i = 1; i <= bar.Length; i++)
        {
            bool still = i < bar.Length && !double.IsNaN(bar[i]) && !double.IsNaN(bar[i - 1]) && Math.Abs(bar[i] - bar[i - 1]) < 0.5;
            if (still)
            {
                if (start < 0) start = i - 1;
                continue;
            }
            if (start >= 0 && i - start >= min)
            {
                double lo = double.PositiveInfinity, hi = double.NegativeInfinity;
                for (int k = start; k < i; k++) (lo, hi) = (Math.Min(lo, phone[k]), Math.Max(hi, phone[k]));
                if (hi - lo >= 0.5)
                {
                    count++;
                    longest = Math.Max(longest, i - start);
                    for (int k = start; k < i; k++) bad[k] = true;
                }
            }
            start = -1;
        }
        return (count, longest);
    }

    /// <summary>The analyser looks at the video in pieces this long to find where the phone is twisted.</summary>
    public const double PieceS = 0.5;

    /// <summary>
    /// The part of the video where the phone is being twisted: the longest run of
    /// <see cref="PieceS"/> pieces in which both the phone and the bar are mostly tracked and the
    /// phone turns at least a quarter as fast as in the brisk pieces (one quiet piece inside the run
    /// is allowed). The setup at the start and the end is left out, so the video needs no trimming.
    /// The whole video when no such run is found.
    /// </summary>
    public static (int Start, int Length) TwistingStretch(double[] phone, double[] bar, double fps)
    {
        if (phone.Length != bar.Length || fps <= 0) throw new ArgumentException("one sample of each per frame");
        int piece = Math.Max(2, (int)(fps * PieceS));
        int pieces = phone.Length / piece;
        if (pieces < 2) return (0, phone.Length);
        var speed = new double[pieces];
        for (int k = 0; k < pieces; k++)
        {
            int tracked = 0, steps = 0;
            double sum = 0;
            for (int i = k * piece; i < (k + 1) * piece; i++)
            {
                if (double.IsNaN(phone[i]) || double.IsNaN(bar[i])) continue;
                tracked++;
                if (i > 0 && !double.IsNaN(phone[i - 1]))
                {
                    double d = phone[i] - phone[i - 1];
                    sum += d * d;
                    steps++;
                }
            }
            speed[k] = tracked * 2 >= piece && steps > 0 ? Math.Sqrt(sum / steps) : double.NaN;
        }
        var known = speed.Where(v => !double.IsNaN(v)).OrderBy(v => v).ToList();
        if (known.Count == 0) return (0, phone.Length);
        double brisk = Quantile(known, 0.9), floor = brisk / 4;
        bool Active(int k) => !double.IsNaN(speed[k]) && speed[k] >= floor;

        int bestStart = -1, bestLen = 0;
        for (int k = 0; k < pieces; k++)
        {
            if (!Active(k)) continue;
            int end = k;
            while (end + 1 < pieces && (Active(end + 1) || (end + 2 < pieces && Active(end + 2)))) end += Active(end + 1) ? 1 : 2;
            if (end - k + 1 > bestLen) (bestStart, bestLen) = (k, end - k + 1);
            k = end;
        }
        if (bestStart < 0) return (0, phone.Length);
        int start = bestStart * piece;
        int stop = bestStart + bestLen == pieces ? phone.Length : (bestStart + bestLen) * piece;
        return (start, stop - start);
    }

    /// <summary>NaN (not tracked) frames filled in a straight line from their neighbours; null when under half were tracked.</summary>
    public static double[]? Fill(double[] v)
    {
        var known = new List<int>();
        for (int i = 0; i < v.Length; i++) if (!double.IsNaN(v[i])) known.Add(i);
        if (known.Count < v.Length / 2 || known.Count < 2) return null;
        var o = new double[v.Length];
        int first = known[0], last = known[^1], k = 0;
        for (int i = 0; i < v.Length; i++)
        {
            if (i <= first) o[i] = v[first];
            else if (i >= last) o[i] = v[last];
            else
            {
                while (known[k + 1] <= i) k++;
                int i0 = known[k], i1 = known[k + 1];
                o[i] = v[i0] + (v[i1] - v[i0]) * (i - i0) / (i1 - i0);
            }
        }
        return o;
    }

    /// <summary>Takes off a centred moving average of <paramref name="w"/> samples (edges use what they have).</summary>
    public static double[] HighPass(double[] v, int w)
    {
        var sum = new double[v.Length + 1];
        for (int i = 0; i < v.Length; i++) sum[i + 1] = sum[i] + v[i];
        var o = new double[v.Length];
        int h = w / 2;
        for (int i = 0; i < v.Length; i++)
        {
            int lo = Math.Max(0, i - h), hi = Math.Min(v.Length, i + h + 1);
            o[i] = v[i] - (sum[hi] - sum[lo]) / (hi - lo);
        }
        return o;
    }

    public static double[] Normalize(double[] v)
    {
        double mean = v.Average();
        double sd = Math.Sqrt(v.Sum(x => (x - mean) * (x - mean)) / v.Length);
        if (sd == 0) sd = 1;
        return v.Select(x => (x - mean) / sd).ToArray();
    }

    /// <summary>
    /// The lag in frames (with a fraction) at which <paramref name="b"/> best repeats <paramref name="a"/>,
    /// and the match there (signed). <paramref name="sign"/> 0 takes either direction.
    /// </summary>
    public static (double Lag, double Match) BestLag(ReadOnlySpan<double> a, ReadOnlySpan<double> b, int maxLag, int sign, bool[]? skip = null)
    {
        var c = new double[maxLag];
        for (int l = 0; l < maxLag; l++)
        {
            double s = 0;
            int n = a.Length - l, used = 0;
            for (int i = 0; i < n; i++)
            {
                if (skip != null && (skip[i] || skip[i + l])) continue;
                s += a[i] * b[i + l];
                used++;
            }
            c[l] = used > 0 ? s / used : 0;
        }
        int best = 0;
        double bestScore = double.NegativeInfinity;
        for (int l = 0; l < maxLag; l++)
        {
            double score = sign == 0 ? Math.Abs(c[l]) : c[l] * sign;
            if (score > bestScore) (best, bestScore) = (l, score);
        }
        double frac = 0;
        if (best > 0 && best < maxLag - 1)
        {
            double s = sign == 0 ? Math.Sign(c[best]) : sign;
            double y0 = c[best - 1] * s, y1 = c[best] * s, y2 = c[best + 1] * s;
            double den = y0 - 2 * y1 + y2;
            if (den < 0) frac = 0.5 * (y0 - y2) / den;
        }
        return (best + frac, c[best]);
    }

    /// <summary>Back-and-forth twists: zero crossings of the drift-free motion, two per twist, ignoring wobble under a fifth of its size.</summary>
    public static int CountTwists(double[] a)
    {
        int crossings = 0, side = 0;
        foreach (var x in a)
        {
            int s = x > 0.2 ? 1 : x < -0.2 ? -1 : 0;
            if (s == 0) continue;
            if (side != 0 && s != side) crossings++;
            side = s;
        }
        return crossings / 2;
    }

    private static double Quantile(List<double> sorted, double q)
    {
        double pos = q * (sorted.Count - 1);
        int i = (int)pos;
        return i + 1 < sorted.Count ? sorted[i] + (sorted[i + 1] - sorted[i]) * (pos - i) : sorted[i];
    }
}
