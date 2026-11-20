using System.Globalization;

namespace GyroPhone.Host.Perf;

/// <summary>
/// "Over the floor": how much later a packet came than the fastest packets of the last 5-10 s (the input log's
/// extra delay; needs no clock sync). Each second of the link carries a small histogram of it
/// (<see cref="LinkSecond.Over"/>), so a record can pool every packet of the run (packet-weighted) instead of
/// taking the middle of per-second numbers, and split it into seconds with movement and still ones.
/// </summary>
public static class OverFloor
{
    /// <summary>Bucket upper edges, µs; the last bucket holds the rest. The Rust input program uses the same edges (recorder.rs OVER_EDGES_US).</summary>
    public static readonly int[] EdgesUs = [100, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 7500, 10000, 15000, 20000, 30000, 50000];
    public static int Buckets => EdgesUs.Length + 1;

    public static int Bucket(long us)
    {
        int i = 0;
        while (i < EdgesUs.Length && us >= EdgesUs[i]) i++;
        return i;
    }

    /// <summary>Adds <paramref name="add"/> into <paramref name="into"/> (missing or short arrays count as empty).</summary>
    public static void Add(long[] into, int[]? add)
    {
        if (add == null) return;
        for (int i = 0; i < Math.Min(into.Length, add.Length); i++) into[i] += add[i];
    }

    /// <summary>
    /// The <paramref name="p"/> quantile of the pooled histogram, ms, read straight through each bucket (so it is
    /// exact to the bucket's width); −1 when empty; the last bucket reads as its lower edge.
    /// </summary>
    public static double Pct(long[] hist, double p)
    {
        long n = 0;
        foreach (var c in hist) n += c;
        if (n == 0) return -1;
        double rank = p * n;
        long acc = 0;
        for (int i = 0; i < hist.Length; i++)
        {
            if (hist[i] == 0) continue;
            if (acc + hist[i] >= rank)
            {
                double lo = i == 0 ? 0 : EdgesUs[i - 1];
                if (i >= EdgesUs.Length) return lo / 1000;
                double hi = EdgesUs[i];
                double frac = Math.Clamp((rank - acc) / hist[i], 0, 1);
                return (lo + (hi - lo) * frac) / 1000;
            }
            acc += hist[i];
        }
        return EdgesUs[^1] / 1000.0;
    }

    /// <summary>Packets that came at least <paramref name="us"/> over the floor (rounded to the bucket edges).</summary>
    public static long AtLeast(long[] hist, int us)
    {
        long n = 0;
        for (int i = Bucket(us); i < hist.Length; i++) n += hist[i];
        return n;
    }

    /// <summary>"0.12 / 0.85 / 3.1 ms" (middle / slowest 1% / slowest 0.1%), or "–".</summary>
    public static string Text(long[] hist)
    {
        double p50 = Pct(hist, 0.5);
        if (p50 < 0) return "–";
        return string.Create(CultureInfo.InvariantCulture, $"{p50:0.00} / {Pct(hist, 0.99):0.00} / {Pct(hist, 0.999):0.0} ms");
    }
}
