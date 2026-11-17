using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace GyroPhone.Host;

/// <summary>
/// GyroPhone's mouse moves as the input path handed them off (Stopwatch ticks at the hand-off's start, and the move's
/// size), for a raw input reader to time their delivery: Diagnostics' delivery probe (its own ring, in the input process)
/// and the Delivery test window (the ring in <see cref="SharedRegion"/>, read by the companion). One writer (the input
/// path, under the engine lock: no lock or allocation here), one reader. Full, a move is dropped and counted.
/// Stopwatch ticks are the same clock in every process, so the companion can time the input process's moves.
/// </summary>
public sealed unsafe class MoveRing : IDisposable
{
    public const int Capacity = 4096;

    private const int HeadOff = 0, TailOff = 64, OverflowOff = 128, SlotsOff = 192, SlotBytes = 16;

    /// <summary>The bytes it takes in a <see cref="SharedRegion"/>.</summary>
    public static int BytesNeeded => SlotsOff + Capacity * SlotBytes;

    private readonly byte* _p;
    private void* _own;

    public MoveRing(byte* p) => _p = p;

    /// <summary>A ring of its own, in this process only.</summary>
    public MoveRing()
    {
        _own = NativeMemory.AllocZeroed((nuint)BytesNeeded);
        _p = (byte*)_own;
    }

    private ref long HeadRef => ref Unsafe.AsRef<long>(_p + HeadOff);
    private ref long TailRef => ref Unsafe.AsRef<long>(_p + TailOff);
    private ref long OverflowRef => ref Unsafe.AsRef<long>(_p + OverflowOff);
    private byte* Slot(long i) => _p + SlotsOff + (int)(i & (Capacity - 1)) * SlotBytes;

    /// <summary>The next move's number (written by the input path).</summary>
    public long Head => Volatile.Read(ref HeadRef);

    /// <summary>The oldest move the reader still wants; moves before it may be written over.</summary>
    public long Tail
    {
        get => Volatile.Read(ref TailRef);
        set => Volatile.Write(ref TailRef, value);
    }

    public long Ticks(long i) => *(long*)Slot(i);
    public int Dx(long i) => *(int*)(Slot(i) + 8);
    public int Dy(long i) => *(int*)(Slot(i) + 12);

    /// <summary>Moves dropped because the ring was full since the last call (the reader fell behind, or there is none).</summary>
    public long TakeOverflow() => Interlocked.Exchange(ref OverflowRef, 0);

    /// <summary>Empties it. Only while nothing writes (before the input path is given it).</summary>
    public void Reset()
    {
        Volatile.Write(ref TailRef, 0);
        Volatile.Write(ref OverflowRef, 0);
        Volatile.Write(ref HeadRef, 0);
    }

    /// <summary>A move's hand-off started at Stopwatch <paramref name="ticks"/>. Input path: no lock, no allocation.</summary>
    public void Put(long ticks, int dx, int dy)
    {
        long h = HeadRef;
        if (h - Volatile.Read(ref TailRef) >= Capacity)
        {
            Interlocked.Increment(ref OverflowRef);
            return;
        }
        byte* s = Slot(h);
        *(long*)s = ticks;
        *(int*)(s + 8) = dx;
        *(int*)(s + 12) = dy;
        Volatile.Write(ref HeadRef, h + 1);
    }

    public void Dispose()
    {
        Free();
        GC.SuppressFinalize(this);
    }

    ~MoveRing() => Free();

    private void Free()
    {
        if (_own != null)
        {
            NativeMemory.Free(_own);
            _own = null;
        }
    }
}

/// <summary>One raw move matched to GyroPhone's moves (<see cref="DeliveryMatcher"/>), in Stopwatch ticks.</summary>
/// <param name="Ticks">From the start of the newest matched move's hand-off to the raw move being read.</param>
/// <param name="Merged">GyroPhone's moves that came inside this raw move besides the newest.</param>
/// <param name="Driver">The input driver's mouse (else SendInput).</param>
/// <param name="GapTicks">From the hand-off of the move before the newest one to the newest one's: how sparse the moves were.</param>
public readonly record struct Delivery(long Ticks, long Merged, bool Driver, long GapTicks);

/// <summary>
/// Pairs raw mouse input with GyroPhone's moves in a <see cref="MoveRing"/>. Windows 11 hands a background raw input
/// reader (the probe; the game in front still gets every report) a device's moves merged, at most about 125 times a
/// second, so one raw move is the sum of a run of stored moves in a row; SendInput moves and a reader in front get them
/// one by one. A raw move that matches no run yet waits a little (its hand-off may not be stored yet) instead of being
/// thrown away. The reader's thread only.
/// </summary>
public sealed class DeliveryMatcher
{
    private static readonly long StaleTicks = Stopwatch.Frequency / 20; // 50 ms
    private const int PendingSize = 32, MaxSkip = 32, MaxRun = 128;

    private readonly MoveRing _ring;
    private readonly Action<Delivery> _delivered;
    private readonly Action<long> _missed;
    private readonly Action _stray;

    private readonly long[] _pendTicks = new long[PendingSize];
    private readonly int[] _pendX = new int[PendingSize], _pendY = new int[PendingSize];
    private readonly bool[] _pendDriver = new bool[PendingSize];
    private int _pendingFirst, _pendingCount;
    private long _prevTicks;

    /// <param name="delivered">Each matched raw move.</param>
    /// <param name="missed">Stored moves that never showed up (lost, or the reader didn't get them).</param>
    /// <param name="stray">A raw move from GyroPhone that matched nothing.</param>
    public DeliveryMatcher(MoveRing ring, Action<Delivery> delivered, Action<long> missed, Action stray)
    {
        _ring = ring;
        _delivered = delivered;
        _missed = missed;
        _stray = stray;
    }

    /// <summary>A raw move from GyroPhone read at Stopwatch <paramref name="now"/>.</summary>
    public void Raw(long now, int x, int y, bool driver)
    {
        if (_pendingCount == PendingSize)
        {
            _stray();
            DropPendingFirst();
        }
        int slot = (_pendingFirst + _pendingCount++) % PendingSize;
        _pendTicks[slot] = now;
        _pendX[slot] = x;
        _pendY[slot] = y;
        _pendDriver[slot] = driver;
        Settle(now);
    }

    /// <summary>Forgets every stored and waiting move without counting them (the reader wasn't listening).</summary>
    public void Skip()
    {
        _pendingCount = 0;
        long head = _ring.Head;
        if (head > 0) _prevTicks = _ring.Ticks(head - 1);
        _ring.Tail = head;
    }

    /// <summary>Matches waiting raw moves in order, and gives up on stored moves and raw moves too old to still pair.</summary>
    public void Settle(long now)
    {
        long tail = _ring.Tail, head = _ring.Head;
        long safe = tail; // moves from here on can't be written over until the tail moves
        while (_pendingCount > 0)
        {
            int p = _pendingFirst;
            long at = _pendTicks[p];
            // Stored moves too old to come with this raw move never showed up.
            long dropped = 0;
            while (tail < head && at - _ring.Ticks(tail) > StaleTicks)
            {
                _prevTicks = _ring.Ticks(tail);
                tail++;
                dropped++;
            }
            if (dropped > 0) _missed(dropped);
            if (TryRun(tail, head, _pendX[p], _pendY[p], out long first, out long last))
            {
                // Moves before the run never showed up; the run's newest move is the one this raw move delivered last.
                if (first > tail) _missed(first - tail);
                long lastTicks = _ring.Ticks(last);
                long prev = last - 1 >= safe ? _ring.Ticks(last - 1) : _prevTicks;
                _delivered(new Delivery(at - lastTicks, last - first, _pendDriver[p], prev == 0 ? 0 : lastTicks - prev));
                _prevTicks = lastTicks;
                tail = last + 1;
                _ring.Tail = tail;
                DropPendingFirst();
                continue;
            }
            _ring.Tail = tail;
            if (now - at <= StaleTicks) break; // its hand-off may not be stored yet
            _stray();
            DropPendingFirst();
        }
        if (_pendingCount == 0)
        {
            // Nothing waiting: stored moves long past any raw move never showed up.
            long dropped = 0;
            while (tail < head && now - _ring.Ticks(tail) > 2 * StaleTicks)
            {
                _prevTicks = _ring.Ticks(tail);
                tail++;
                dropped++;
            }
            if (dropped > 0) _missed(dropped);
        }
        _ring.Tail = tail;
    }

    /// <summary>The first run of stored moves in a row (starting at most <see cref="MaxSkip"/> in) that adds up to x, y.</summary>
    private bool TryRun(long tail, long head, int x, int y, out long first, out long last)
    {
        long starts = Math.Min(head, tail + MaxSkip);
        for (long s = tail; s < starts; s++)
        {
            int sx = 0, sy = 0;
            long end = Math.Min(head, s + MaxRun);
            for (long k = s; k < end; k++)
            {
                sx += _ring.Dx(k);
                sy += _ring.Dy(k);
                if (sx == x && sy == y)
                {
                    first = s;
                    last = k;
                    return true;
                }
            }
        }
        first = last = -1;
        return false;
    }

    private void DropPendingFirst()
    {
        _pendingFirst = (_pendingFirst + 1) % PendingSize;
        _pendingCount--;
    }
}

/// <summary>Delivery numbers over a stretch (a second, or a whole Delivery test): times in ms.</summary>
public sealed record DeliveryNumbers(long N, double P50, double P99, double Max, long Slow, long Unmatched, long Merged, long Stray)
{
    /// <summary>Raw moves over 2 ms, per 1000 matched.</summary>
    public double SlowPer1000 => N == 0 ? 0 : Slow * 1000.0 / N;
}

/// <summary>Adds up <see cref="Delivery"/> results. One thread at a time (the caller's).</summary>
public sealed class DeliveryTally
{
    /// <summary>"Slow": a delivery over 2 ms.</summary>
    public static readonly long SlowTicks = Stopwatch.Frequency / 500;

    private readonly List<long> _lat;
    private long _max, _slow, _unmatched, _merged, _stray;

    public DeliveryTally(int capacity = 4096) => _lat = new List<long>(capacity);

    public bool Empty => _lat.Count == 0 && _unmatched == 0 && _stray == 0;

    public void Add(in Delivery d)
    {
        _lat.Add(d.Ticks);
        if (d.Ticks > _max) _max = d.Ticks;
        if (d.Ticks > SlowTicks) _slow++;
        _merged += d.Merged;
    }

    public void Missed(long n) => _unmatched += n;
    public void Stray() => _stray++;

    public static double Ms(long ticks) => ticks * 1000.0 / Stopwatch.Frequency;

    /// <summary>The numbers so far (sorts the times it holds).</summary>
    public DeliveryNumbers Numbers()
    {
        _lat.Sort();
        int n = _lat.Count;
        return new DeliveryNumbers(n,
            n > 0 ? Ms(_lat[n / 2]) : 0,
            n > 0 ? Ms(_lat[Math.Min(n - 1, n * 99 / 100)]) : 0,
            Ms(_max), _slow, _unmatched, _merged, _stray);
    }

    public void Clear()
    {
        _lat.Clear();
        _max = _slow = _unmatched = _merged = _stray = 0;
    }
}
