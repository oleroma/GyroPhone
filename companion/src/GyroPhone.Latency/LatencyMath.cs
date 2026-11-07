namespace GyroPhone.Latency;

/// <summary>What the analyser found: how far the bar trails the phone, and how sure it is.</summary>
/// <param name="LatencyMs">The lag that lines the two motions up best over the whole video.</param>
/// <param name="SpreadMs">Half the spread (middle half) of the lag measured over shorter stretches.</param>
/// <param name="Twists">Back-and-forth twists seen.</param>
/// <param name="Correlation">How well the two motions match once lined up (0 to 1).</param>
public sealed record LatencyFit(double LatencyMs, double SpreadMs, int Twists, double Correlation, int Stretches);

/// <summary>
/// The timing maths, without video: both motions are sampled once per video frame. Each is
/// filled where tracking missed a frame, freed of slow drift (a moving average of
/// <see cref="DriftS"/> taken off) and scaled to the same size; then the lag from 0 to
/// <see cref="MaxLagS"/> with the best match is found to a fraction of a frame. The two may move
/// in opposite directions (the bar's direction follows the mouse mapping): the sign is taken from
/// the whole video, then used for every stretch.
/// </summary>
public static class LatencyMath
{
    public const double MaxLagS = 0.25;
    public const double DriftS = 0.6;
    public const double StretchS = 1.5;
    /// <summary>Below this match the video can't be timed (no twisting, or tracking lost).</summary>
    public const double MinCorrelation = 0.5;

    /// <summary>Times the bar against the phone; null when they don't match well enough.</summary>
    public static LatencyFit? Fit(double[] phone, double[] bar, double fps)
    {
        if (phone.Length != bar.Length || fps <= 0) throw new ArgumentException("one sample of each per frame");
        int maxLag = (int)(MaxLagS * fps);
        if (phone.Length < maxLag * 3) return null;
        var a = Fill(phone);
        var b = Fill(bar);
        if (a == null || b == null) return null;
        int w = (int)(fps * DriftS) | 1;
        a = Normalize(HighPass(a, w));
        b = Normalize(HighPass(b, w));
        var (lag, c) = BestLag(a, b, maxLag, 0);
        if (Math.Abs(c) < MinCorrelation) return null;
        int sign = Math.Sign(c);

        int seg = (int)(fps * StretchS);
        var lags = new List<double>();
        for (int s = 0; s + seg <= a.Length; s += seg / 2)
        {
            var (l, cc) = BestLag(a.AsSpan(s, seg), b.AsSpan(s, seg), maxLag, sign);
            if (cc * sign >= MinCorrelation) lags.Add(l);
        }
        double spread = 0;
        if (lags.Count >= 2)
        {
            lags.Sort();
            spread = (Quantile(lags, 0.75) - Quantile(lags, 0.25)) / 2 / fps * 1000;
        }
        return new LatencyFit(lag / fps * 1000, spread, CountTwists(a), Math.Abs(c), lags.Count);
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
    public static (double Lag, double Match) BestLag(ReadOnlySpan<double> a, ReadOnlySpan<double> b, int maxLag, int sign)
    {
        var c = new double[maxLag];
        for (int l = 0; l < maxLag; l++)
        {
            double s = 0;
            int n = a.Length - l;
            for (int i = 0; i < n; i++) s += a[i] * b[i + l];
            c[l] = s / n;
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
