namespace GyroPhone.Host;

/// <summary>
/// The latest <see cref="Size"/> poses (quaternions w, x, y, z) with their times (ms), written by one thread (the
/// engine's, as OVERLAY_SAMPLES come) and read by another (an overlay window's): the writer fills a slot, then moves
/// <see cref="Count"/> on; the reader takes the slots after the count it last saw. No lock, nothing made per sample.
/// As the phone's PoseHistory.
/// </summary>
public sealed class PoseHistory
{
    /// <summary>About a quarter second of samples at the phone's ~950 Hz: plenty for the slowest frame.</summary>
    public const int Size = 256;

    private readonly float[] _q = new float[Size * 4];
    private readonly long[] _t = new long[Size];
    private long _count;

    /// <summary>Poses written so far (the newest is slot (Count − 1) % Size).</summary>
    public long Count => Volatile.Read(ref _count);

    public void Add(float w, float x, float y, float z, long ms)
    {
        long c = _count;
        int i = (int)(c % Size);
        _q[i * 4] = w; _q[i * 4 + 1] = x; _q[i * 4 + 2] = y; _q[i * 4 + 3] = z;
        _t[i] = ms;
        Volatile.Write(ref _count, c + 1);
    }

    /// <summary>
    /// Calls <paramref name="each"/> with every pose written after the first <paramref name="seen"/> (at most the newest
    /// Size − 8, so the writer never fills a slot being read) and its time, oldest first; returns the count to pass next time.
    /// </summary>
    public long Since<T>(long seen, ref T state, Each<T> each)
    {
        long c = Count;
        long from = Math.Max(Math.Max(seen, c - (Size - 8)), 0);
        for (; from < c; from++)
        {
            int i = (int)(from % Size);
            each(ref state, _q[i * 4], _q[i * 4 + 1], _q[i * 4 + 2], _q[i * 4 + 3], _t[i]);
        }
        return c;
    }

    public delegate void Each<T>(ref T state, float w, float x, float y, float z, long ms);
}
