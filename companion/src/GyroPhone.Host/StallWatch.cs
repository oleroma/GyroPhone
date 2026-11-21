using System.Diagnostics;
using GyroPhone.Host.Abstractions;

namespace GyroPhone.Host;

/// <summary>
/// Diagnostics' PC stall watch (docs/spec/pc-companion.md, "Diagnostics"): a thread of its own asks to wake every
/// millisecond and notes how late Windows woke it. A late wake means the PC couldn't run a waiting thread on time
/// (a driver, another program, power saving), which is what a late phone packet looks like from inside the PC.
/// So a late packet with a calm watch was held up before it reached the PC (Wi-Fi, router, cable), and one with
/// a late watch waited inside the PC.
/// <para>
/// Runs only while Diagnostics is on. It runs a step below gp-rx (AboveNormal, while gp-rx has the games boost),
/// so it never competes with the input; it costs a thousand tiny wake-ups a second, which also keeps one core out
/// of deep sleep (part of why Diagnostics' own cost is checked with the A/B switch). Nothing here touches the
/// input path: the engine only reads its numbers once a second and when a hold-up ends.
/// </para>
/// </summary>
public sealed class StallWatch : IDisposable
{
    /// <summary>Asked wake interval.</summary>
    public const long PeriodUs = 1000;
    /// <summary>A wake this much late or more counts as a stall.</summary>
    public const long StallUs = 2000;
    /// <summary>Wakes later than this are kept (time, lateness) for <see cref="WorstBetween"/>.</summary>
    private const long KeepUs = 500;
    private const int Ring = 4096;

    private readonly Func<ITickSleep> _sleep;
    private readonly Func<long> _nowUs;
    private Thread? _thread;
    private volatile bool _stop;

    // Late wakes (one writer, the watch thread; readers check the sequence).
    private readonly long[] _at = new long[Ring];
    private readonly long[] _late = new long[Ring];
    private long _head;

    // This second (the watch thread adds; Interlocked hand-over).
    private long _sMaxUs, _sCount, _sWakes;

    /// <param name="sleep">The exact sleep (a high-resolution waitable timer on Windows).</param>
    /// <param name="nowUs">The engine's clock, so the times line up with packet times.</param>
    public StallWatch(Func<ITickSleep> sleep, Func<long> nowUs)
    {
        _sleep = sleep;
        _nowUs = nowUs;
    }

    public bool Running => _thread != null;

    public void Start()
    {
        if (_thread != null) return;
        _stop = false;
        _thread = new Thread(Loop) { Name = "gp-stallwatch", IsBackground = true, Priority = ThreadPriority.AboveNormal };
        _thread.Start();
    }

    public void Stop()
    {
        _stop = true;
        _thread?.Join(500);
        _thread = null;
    }

    public void Dispose() => Stop();

    private void Loop()
    {
        using var sleep = _sleep();
        long next = _nowUs() + PeriodUs;
        while (!_stop)
        {
            long wait = next - _nowUs();
            if (wait > 0) sleep.Sleep(wait);
            long now = _nowUs();
            long late = now - next;
            Add(now, late);
            next += PeriodUs;
            // Far behind (the PC slept, or a long stall): start again from now rather than rush to catch up.
            if (now - next > 50 * PeriodUs) next = now + PeriodUs;
        }
    }

    internal void Add(long now, long late)
    {
        Interlocked.Increment(ref _sWakes);
        if (late < KeepUs) return;
        long h = _head;
        int i = (int)(h & (Ring - 1));
        Volatile.Write(ref _at[i], now);
        Volatile.Write(ref _late[i], late);
        Volatile.Write(ref _head, h + 1);
        if (late >= StallUs) Interlocked.Increment(ref _sCount);
        long m;
        while (late > (m = Volatile.Read(ref _sMaxUs)) && Interlocked.CompareExchange(ref _sMaxUs, late, m) != m) { }
    }

    /// <summary>The second just ended: worst lateness (µs), stalls over <see cref="StallUs"/>, wakes. Clears them.</summary>
    public (long MaxUs, long Stalls, long Wakes) TakeSecond() =>
        (Interlocked.Exchange(ref _sMaxUs, 0), Interlocked.Exchange(ref _sCount, 0), Interlocked.Exchange(ref _sWakes, 0));

    /// <summary>
    /// The worst lateness of a wake that was due inside [<paramref name="fromUs"/>, <paramref name="toUs"/>] (a wake
    /// stamped at t that was L late was due at t − L, and the PC was busy from then to t), 0 when calm, −1 when the
    /// watch didn't run then.
    /// </summary>
    public long WorstBetween(long fromUs, long toUs)
    {
        if (_thread == null) return -1;
        long head = Volatile.Read(ref _head);
        long worst = 0;
        for (long k = head - 1; k >= 0 && k >= head - Ring; k--)
        {
            int i = (int)(k & (Ring - 1));
            long at = Volatile.Read(ref _at[i]);
            long late = Volatile.Read(ref _late[i]);
            if (at < fromUs) break;
            // Busy from (at - late) to at: overlaps the window?
            if (at - late <= toUs && late > worst) worst = late;
        }
        return worst;
    }
}
