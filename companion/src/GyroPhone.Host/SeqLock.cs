namespace GyroPhone.Host;

/// <summary>
/// A sequence lock: one writer at a time (the caller makes sure of that, e.g. under its own lock)
/// changes some fields between <see cref="BeginWrite"/> and <see cref="EndWrite"/>, and any number of
/// readers copy them without a lock and without ever making the writer wait. A reader takes
/// <see cref="ReadBegin"/>, copies, and starts again while <see cref="ReadRetry"/> says a write came in
/// between, so it never keeps a half-written copy. Readers must only copy (no exceptions on a torn
/// value, such as an index out of range); what they work out from the copy comes after.
/// </summary>
public sealed class SeqLock
{
    private int _seq;

    public void BeginWrite()
    {
        Volatile.Write(ref _seq, _seq + 1); // odd: a write is under way
        Interlocked.MemoryBarrier();
    }

    public void EndWrite() => Volatile.Write(ref _seq, _seq + 1);

    /// <summary>The sequence to hand to <see cref="ReadRetry"/>; waits out a write under way (the writer never waits).</summary>
    public int ReadBegin()
    {
        int s = Volatile.Read(ref _seq);
        if ((s & 1) == 0) return s;
        var spin = new SpinWait();
        while (((s = Volatile.Read(ref _seq)) & 1) != 0) spin.SpinOnce();
        return s;
    }

    /// <summary>True when a write came in since <paramref name="begin"/>: copy again.</summary>
    public bool ReadRetry(int begin)
    {
        Interlocked.MemoryBarrier();
        return Volatile.Read(ref _seq) != begin;
    }
}
