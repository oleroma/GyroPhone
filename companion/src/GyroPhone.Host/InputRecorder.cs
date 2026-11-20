using System.Diagnostics;
using System.Globalization;
using System.Text;
using GyroPhone.Host.Perf;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// What held up one hand-off to Windows, for the input log's <c>slow hand-off</c> line: gp-rx's waits for the
/// engine lock (and who held it at the longest), the time inside the pad driver and SendInput, and the
/// garbage collector's pauses since the packet came.
/// </summary>
public struct HandOffCauses
{
    /// <summary>Stopwatch ticks gp-rx waited for the engine lock.</summary>
    public long LockWaitTicks;
    /// <summary>Who had the lock at the longest wait ("ui", "watchdog", ...); null without a wait.</summary>
    public string? LockHolder;
    private long _longestWaitTicks;
    /// <summary>Stopwatch ticks inside the pad driver (ViGEm or the DualSense driver) and inside SendInput (or the input driver).</summary>
    public long PadTicks, SendInputTicks;
    /// <summary>Where keys and mouse went: "SendInput" or "input driver" (<see cref="Abstractions.IKeyMouseSink.Route"/>).</summary>
    public string? Route;
    /// <summary>How long the garbage collector paused every thread (us).</summary>
    public long GcPauseUs;

    public static HandOffCauses Waited(long ticks, string? holder)
    {
        var c = new HandOffCauses();
        c.AddLockWait(ticks, holder);
        return c;
    }

    public void AddLockWait(long ticks, string? holder)
    {
        if (ticks <= 0) return;
        LockWaitTicks += ticks;
        if (ticks <= _longestWaitTicks) return;
        _longestWaitTicks = ticks;
        LockHolder = holder;
    }
}

/// <summary>
/// Always-on record of how the phone's input reached Windows, for finding stutter in real play
/// (docs/spec/pc-companion.md, "Input log"). Called by the engine under its lock; with
/// <see cref="Deferred"/> (the engine sets it) the lines only queue there, and the engine hands them to
/// <c>write</c> through <see cref="WritePending"/> once it let go of the lock. Per second it writes one summary line while a session is up, and at once one line
/// per event: lost packets, packets held up on the way, the phone going quiet, a catch-up, a big
/// single move, a slow hand-off to Windows, Windows refusing input, a release after silence. It never
/// writes which keys or buttons, only how many.
/// <para>
/// "Late" is measured against the session's fastest packets (the lowest receive time minus phone send
/// time over the last 5-10 s), so it needs no clock sync and is the delay a packet added on top of the
/// best the link did just before.
/// </para>
/// </summary>
public sealed class InputRecorder
{
    /// <summary>A packet this much later than the fastest ones starts a hold-up.</summary>
    public const long LateUs = 15_000;
    /// <summary>The phone sends at least every 20 ms (keepalive): a longer spacing with no packet lost is the phone not sending.</summary>
    public const long PhoneGapUs = 30_000;
    /// <summary>A single move at least this big is logged.</summary>
    public const int JumpPx = 64;
    /// <summary>Receive to SendInput slower than this is logged.</summary>
    public const long SlowInjectUs = 2_000;
    /// <summary>A packet this much later than the fastest ones (and up to <see cref="LateUs"/>) counts as "late3": felt in a flick, below a hold-up.</summary>
    public const long Late3Us = 3_000;
    /// <summary>Extra delay buckets: 0.1 ms steps, per second up to 30 ms and per session up to 100 ms (the last bucket holds the rest).</summary>
    public const int ExtraStepUs = 100;
    private const int SecondBuckets = 301, SessionBuckets = 1001;
    /// <summary>At most this many event lines a second; the rest are only counted in the second's line.</summary>
    public const int MaxEventsPerSecond = 10;
    private const long BaseWindowUs = 5_000_000;

    private readonly Action<string> _write;
    private readonly StringBuilder _sb = new(160);
    private int _writing;

    // Events wait here as numbers; their text is made in WritePending, on the thread that writes (never gp-rx while it
    // hands input to Windows). One writer (the engine, under its lock), one reader (WritePending, one at a time).
    private enum EvKind : byte { Text, Lost, PhoneGap, Late, BigMove, SlowHandOff, Refused, CatchUp, Release }
    private struct Ev
    {
        public long AtUs, A, B, C, D;
        public EvKind Kind;
        public string? Text, What;
        public HandOffCauses Causes;
    }
    private const int EvRing = 4096;
    private readonly Ev[] _ev = new Ev[EvRing];
    private long _evHead, _evTail;
    /// <summary>Events dropped because nobody wrote them out in time.</summary>
    public long EventsDropped { get; private set; }

    // Session.
    private bool _active;
    private long _sessionStartUs;

    // Baseline (relative receive - send, us) over the current and previous window.
    private bool _haveRef;
    private uint _refDiff;
    private long _winStartUs;
    private long _minCur = long.MaxValue, _minPrev = long.MaxValue;

    // The STATE before.
    private bool _havePrev;
    private uint _prevPhoneUs;
    private bool _prevActive;

    // Hold-up in progress.
    private bool _inHoldup;
    private long _holdStartUs, _holdLastUs, _holdMaxUs;
    private int _holdCount;
    private long _holdPhoneSpanMaxUs;

    // This second.
    private long _sMaxLockWaitUs;
    private string? _sLockHolder;
    private int _sStates, _sMoving, _sLost, _sLate, _sLate3, _sGaps, _sReleases, _sInjects, _sPadHandOffs, _sMaxStep, _sSlow, _sCatchUps, _sJumps, _sEvents, _sSkipped;
    private int _sAir, _sPc, _sPhone;
    private long _sPx, _sMaxLateUs, _sMaxInjectUs;
    // This second's extra delay over the floor (0.1 ms buckets), for "over p50/p99".
    private readonly int[] _sExtra = new int[SecondBuckets];
    private int _sExtraN;
    // The same, in the records' coarser buckets (OverFloor), handed on with the second.
    private readonly int[] _sOver = new int[Perf.OverFloor.Buckets];
    // Whole session.
    private long _tStates, _tLost, _tLate, _tLate3, _tHoldups, _tGaps, _tCatchUps, _tJumps, _tSlow, _tReleases, _tRefused, _tAir, _tPc, _tPhone;
    private readonly long[] _tExtra = new long[SessionBuckets];

    /// <summary>Diagnostics' per-packet record (null while it is off): one entry per STATE and its hand-off.</summary>
    public FlightRecorder? Flight { get; set; }

    /// <summary>The PC stall watch while Diagnostics runs (null otherwise): tells a hold-up that waited inside the PC from one held up on the way.</summary>
    public StallWatch? Stall { get; set; }

    public InputRecorder(Action<string> write) => _write = write;

    /// <summary>Gets each second's link numbers too, for the Performance page (null: none).</summary>
    public LinkTimeline? Timeline { get; set; }

    /// <summary>More for each second's line (the FPS overlay's frames), or null; called under the engine lock.</summary>
    public Func<string?>? SecondNote { get; set; }

    /// <summary>Lines go to input.log ("Record input log"); off, the seconds are still counted for the Performance page and records, but nothing is written.</summary>
    public bool WriteLines { get; set; } = true;

    /// <summary>Lines wait in a queue for <see cref="WritePending"/> instead of going to <c>write</c> at once.</summary>
    public bool Deferred { get; set; }

    /// <summary>Hands the queued lines to <c>write</c>, in order, making their text here. Any thread, outside the engine lock; one at a time (a second caller leaves them to the first).</summary>
    public void WritePending()
    {
        while (Volatile.Read(ref _evTail) != Volatile.Read(ref _evHead))
        {
            if (Interlocked.Exchange(ref _writing, 1) != 0) return;
            try
            {
                long tail = _evTail;
                while (tail != Volatile.Read(ref _evHead))
                {
                    ref Ev e = ref _ev[tail & (EvRing - 1)];
                    string line = Format(in e);
                    e.Text = null; e.What = null; e.Causes = default;
                    Volatile.Write(ref _evTail, ++tail);
                    _write(line);
                }
            }
            finally
            {
                Volatile.Write(ref _writing, 0);
            }
        }
    }

    /// <summary>Lines are waiting for <see cref="WritePending"/>.</summary>
    public bool HasPending => Volatile.Read(ref _evTail) != Volatile.Read(ref _evHead);

    public void SessionStarted(string phone, long nowUs)
    {
        if (_active) SessionEnded("replaced", nowUs);
        _active = true;
        _sessionStartUs = nowUs;
        _haveRef = false;
        _minCur = _minPrev = long.MaxValue;
        _winStartUs = nowUs;
        _havePrev = false;
        _inHoldup = false;
        ClearSecond();
        _tStates = _tLost = _tLate = _tLate3 = _tHoldups = _tGaps = _tCatchUps = _tJumps = _tSlow = _tReleases = _tRefused = _tAir = _tPc = _tPhone = 0;
        Array.Clear(_tExtra);
        Line(nowUs, "session start", $"phone '{phone}'");
    }

    public void SessionEnded(string why, long nowUs)
    {
        if (!_active) return;
        EndHoldup();
        _active = false;
        Line(nowUs, "session end", string.Create(CultureInfo.InvariantCulture,
            $"{why} after {(nowUs - _sessionStartUs) / 1e6:0} s: states {_tStates} lost {_tLost} late {_tLate} late3 {_tLate3} " +
            $"holdups {_tHoldups} (air {_tAir} pc {_tPc} phone {_tPhone}) phone-gaps {_tGaps} catch-ups {_tCatchUps} jumps {_tJumps} slow {_tSlow} releases {_tReleases} refused {_tRefused}; " +
            $"over the floor ms p50 {Pct(_tExtra, 0.5)} p99 {Pct(_tExtra, 0.99)} p99.9 {Pct(_tExtra, 0.999)}"));
    }

    /// <summary>A free-form event line (a Link test starting or ending).</summary>
    public void Note(string what, string detail, long nowUs)
    {
        if (_active) Line(nowUs, what, detail);
    }

    /// <summary>Lost packets: a gap in the shared seq.</summary>
    public void Lost(long count, uint seq, long nowUs)
    {
        if (!_active || count <= 0) return;
        _sLost += (int)Math.Min(count, 1_000_000);
        _tLost += count;
        if (count >= 3 && EventAllowed()) Event(nowUs, EvKind.Lost, count, seq);
    }

    /// <summary>
    /// One accepted STATE. <paramref name="active"/>: it moves the mouse or holds a key or button
    /// (so the phone's spacing matters); <paramref name="lost"/>: packets were lost just before it.
    /// </summary>
    public void State(uint phoneUs, long recvUs, bool moving, bool active, bool lost)
    {
        if (!_active) return;
        _sStates++;
        _tStates++;
        if (moving) _sMoving++;
        // Phone spacing: the phone didn't send (seq continuous) while something was going on.
        if (_havePrev)
        {
            long span = unchecked((int)(phoneUs - _prevPhoneUs));
            if (!lost && span > PhoneGapUs && (_prevActive || active))
            {
                _tGaps++;
                _sGaps++;
                if (EventAllowed()) Event(recvUs, EvKind.PhoneGap, span, _prevActive ? 1 : 0);
            }
            if (_inHoldup) _holdPhoneSpanMaxUs = Math.Max(_holdPhoneSpanMaxUs, span);
        }
        _havePrev = true;
        _prevPhoneUs = phoneUs;
        _prevActive = active;

        // Extra delay against the fastest recent packets.
        uint raw = unchecked((uint)recvUs - phoneUs);
        if (!_haveRef)
        {
            _haveRef = true;
            _refDiff = raw;
        }
        long rel = unchecked((int)(raw - _refDiff));
        if (recvUs - _winStartUs > BaseWindowUs)
        {
            _minPrev = _minCur;
            _minCur = long.MaxValue;
            _winStartUs = recvUs;
        }
        if (rel < _minCur) _minCur = rel;
        long extra = rel - Math.Min(_minCur, _minPrev);
        _sExtra[Math.Clamp(extra / ExtraStepUs, 0, SecondBuckets - 1)]++;
        _sExtraN++;
        _sOver[Perf.OverFloor.Bucket(extra)]++;
        _tExtra[Math.Clamp(extra / ExtraStepUs, 0, SessionBuckets - 1)]++;
        if (extra > _sMaxLateUs) _sMaxLateUs = extra;
        Flight?.State(phoneUs, recvUs, extra,
            (moving ? FlightRecorder.Moving : 0) | (active ? FlightRecorder.Active : 0) | (lost ? FlightRecorder.LostBefore : 0));
        if (extra > Late3Us && extra <= LateUs)
        {
            _sLate3++;
            _tLate3++;
        }
        if (extra > LateUs)
        {
            _sLate++;
            _tLate++;
            if (!_inHoldup)
            {
                _inHoldup = true;
                _holdStartUs = recvUs;
                _holdMaxUs = 0;
                _holdCount = 0;
                _holdPhoneSpanMaxUs = 0;
            }
            _holdLastUs = recvUs;
            _holdCount++;
            _holdMaxUs = Math.Max(_holdMaxUs, extra);
        }
        else if (_inHoldup)
        {
            EndHoldup();
        }
    }

    private void EndHoldup()
    {
        if (!_inHoldup) return;
        _inHoldup = false;
        _tHoldups++;
        // Where it waited: the phone paused; else the PC couldn't run a thread then (the stall watch); else on the
        // way (Wi-Fi, router, cable). The packets left the phone up to their extra delay before they came.
        int verdict;
        long stall = Stall?.WorstBetween(_holdStartUs - _holdMaxUs, _holdLastUs) ?? -1;
        if (_holdPhoneSpanMaxUs > PhoneGapUs) { verdict = 2; _sPhone += _holdCount; _tPhone++; }
        else if (stall >= StallWatch.StallUs) { verdict = 1; _sPc += _holdCount; _tPc++; }
        else { verdict = 0; _sAir += _holdCount; _tAir++; }
        if (EventAllowed()) Event(_holdStartUs, EvKind.Late, _holdCount, _holdMaxUs, _holdLastUs - _holdStartUs, ((stall < 0 ? 0 : stall) << 2) + verdict);
    }

    /// <summary>
    /// One hand-off to Windows: the mouse move in it and how long after the newest packet's arrival
    /// (<paramref name="recvUs"/> -1: no new packet), with what held it up.
    /// </summary>
    public void Inject(int dx, int dy, long recvUs, long nowUs, bool refused, in HandOffCauses causes = default)
    {
        if (!_active) return;
        _sInjects++;
        int step = Math.Max(Math.Abs(dx), Math.Abs(dy));
        _sPx += Math.Abs(dx) + Math.Abs(dy);
        if (step > _sMaxStep) _sMaxStep = step;
        if (step >= JumpPx)
        {
            _tJumps++;
            _sJumps++;
            if (EventAllowed()) Event(nowUs, EvKind.BigMove, dx, dy);
        }
        if (recvUs >= 0)
        {
            HandOffTook(nowUs - recvUs, nowUs, causes);
            Flight?.HandOff(nowUs - recvUs, Us(causes.LockWaitTicks), Us(causes.PadTicks + causes.SendInputTicks), refused ? FlightRecorder.Refused : 0);
        }
        if (refused)
        {
            _tRefused++;
            if (EventAllowed()) Event(nowUs, EvKind.Refused, 0, 0);
        }
    }

    /// <summary>
    /// A hand-off with only the virtual controller in it (no keys or mouse for SendInput): timed like any other, so slow
    /// hand-offs show in controller-only play too.
    /// </summary>
    public void PadHandOff(long recvUs, long nowUs, in HandOffCauses causes)
    {
        if (!_active) return;
        _sPadHandOffs++;
        HandOffTook(nowUs - recvUs, nowUs, causes);
        Flight?.HandOff(nowUs - recvUs, Us(causes.LockWaitTicks), Us(causes.PadTicks), FlightRecorder.PadOnly);
    }

    private void HandOffTook(long took, long nowUs, in HandOffCauses causes)
    {
        if (took > _sMaxInjectUs) _sMaxInjectUs = took;
        long waitUs = Us(causes.LockWaitTicks);
        if (waitUs > _sMaxLockWaitUs)
        {
            _sMaxLockWaitUs = waitUs;
            _sLockHolder = causes.LockHolder;
        }
        if (took > SlowInjectUs)
        {
            _sSlow++;
            _tSlow++;
            if (EventAllowed()) Event(nowUs, EvKind.SlowHandOff, took, 0, causes: causes);
        }
    }

    /// <summary>
    /// "3.1 ms from receive to SendInput: engine lock 2.6 ms (ui had it), pad driver 0.2 ms, SendInput 0.1 ms,
    /// ("input driver" in place of SendInput when keys and mouse go through it)
    /// garbage collection 0.0 ms, the rest 0.2 ms": each cause over 0.05 ms; the rest is gp-rx itself (waiting
    /// for a CPU, the burst's other packets).
    /// </summary>
    private string SlowText(long took, in HandOffCauses c)
    {
        _sb.Clear();
        string route = c.Route ?? "SendInput";
        _sb.Append(CultureInfo.InvariantCulture, $"{took / 1000.0:0.0} ms from receive to {route}:");
        long wait = Us(c.LockWaitTicks), pad = Us(c.PadTicks), send = Us(c.SendInputTicks), gc = Math.Max(0, c.GcPauseUs);
        int parts = 0;
        void Part(string what, long us, string? extra = null)
        {
            if (us < 50) return;
            _sb.Append(parts++ == 0 ? " " : ", ").Append(what).Append(' ')
                .Append((us / 1000.0).ToString("0.0", CultureInfo.InvariantCulture)).Append(" ms");
            if (extra != null) _sb.Append(" (").Append(extra).Append(" had it)");
        }
        Part("engine lock", wait, c.LockHolder);
        Part("pad driver", pad);
        Part(route, send);
        Part("garbage collection", gc);
        Part("the rest", took - wait - pad - send - gc);
        if (parts == 0) _sb.Append(" no cause measured");
        return _sb.ToString();
    }

    private static long Us(long stopwatchTicks) => stopwatchTicks * 1_000_000 / Stopwatch.Frequency;

    /// <summary>A late or lost stretch of motion is catching up instead of landing at once.</summary>
    public void CatchUp(long backlogUs, int px, long spreadUs, long nowUs)
    {
        if (!_active) return;
        _sCatchUps++;
        _tCatchUps++;
        if (EventAllowed()) Event(nowUs, EvKind.CatchUp, px, backlogUs, spreadUs);
    }

    /// <summary>Everything let go after the phone was silent (Wire.ReleaseTimeoutMs).</summary>
    public void ReleasedForSilence(int held, long nowUs)
    {
        if (!_active) return;
        _tReleases++;
        _sReleases++;
        if (EventAllowed()) Event(nowUs, EvKind.Release, held, 0);
    }

    /// <summary>The once-a-second line, with the link numbers the engine rolled just before.</summary>
    public void Second(LinkStats stats, long nowUs)
    {
        if (!_active) return;
        // Asked every second either way: it also rolls the delivery probe's second.
        string? note = SecondNote?.Invoke();
        var (stallMax, stalls, _) = Stall?.TakeSecond() ?? (-1, 0, 0);
        float overP50 = PctMs(_sExtra, _sExtraN, 0.5), overP99 = PctMs(_sExtra, _sExtraN, 0.99);
        if (WriteLines) SecondLine(stats, note, nowUs, overP50, overP99, stallMax, stalls);
        bool lat = stats.LatP50Us != HostStatus.Unknown;
        uint rttMin = stats.LastLinkInfo?.RttMinUs ?? 0;
        Timeline?.Add(new LinkSecond(nowUs, stats.RxPerSec, _sLost, _sLate, _sMaxLateUs / 1000f, _sGaps, _sReleases,
            lat ? stats.LatP50Us / 1000f : -1, lat && stats.LatP99Us != HostStatus.Unknown ? stats.LatP99Us / 1000f : -1, stats.JitterUs / 1000f)
        {
            FloorMs = rttMin > 0 ? rttMin / 2000f : -1,
            RttTypMs = stats.PhoneRttTypUs > 0 ? stats.PhoneRttTypUs / 1000f : -1,
            OverP50Ms = overP50,
            OverP99Ms = overP99,
            Late3 = _sLate3,
            HandOffMaxMs = _sMaxInjectUs / 1000f,
            SlowHandOffs = _sSlow,
            LockWaitMs = _sMaxLockWaitUs / 1000f,
            StallMaxMs = stallMax < 0 ? -1 : stallMax / 1000f,
            Stalls = (int)stalls,
            NegLat = stats.NegativePerSec,
            Air = _sAir,
            Pc = _sPc,
            Phone = _sPhone,
            Moving = _sMoving,
            Over = (int[])_sOver.Clone(),
        });
        ClearSecond();
    }

    /// <summary>The "sec" line; made only when it is written (on the watchdog's thread).</summary>
    private void SecondLine(LinkStats stats, string? note, long nowUs, float overP50, float overP99, long stallMax, long stalls)
    {
        var ci = CultureInfo.InvariantCulture;
        _sb.Clear();
        _sb.Append(ci, $"rx {stats.RxPerSec} moving {_sMoving} lost {_sLost} late {_sLate} maxlate {_sMaxLateUs / 1000.0:0.0}");
        if (stats.LatP50Us != HostStatus.Unknown) _sb.Append(ci, $" lat {stats.LatP50Us / 1000.0:0.0}/{stats.LatP99Us / 1000.0:0.0}");
        // The link split in two: the floor (half the best round trip, the fastest the link went) and how much slower
        // than the fastest packets this second's were (needs no clock sync).
        if (stats.LastLinkInfo is { RttMinUs: > 0 } li) _sb.Append(ci, $" floor {li.RttMinUs / 2000.0:0.00}");
        if (stats.PhoneRttTypUs > 0) _sb.Append(ci, $" rtt {(stats.LastLinkInfo?.RttMinUs ?? 0) / 1000.0:0.00}/{stats.PhoneRttTypUs / 1000.0:0.00}");
        if (_sExtraN > 0) _sb.Append(ci, $" over {overP50:0.0}/{overP99:0.0}");
        if (_sLate3 > 0) _sb.Append(" late3 ").Append(_sLate3);
        if (stats.NegativePerSec > 0) _sb.Append(" neglat ").Append(stats.NegativePerSec);
        _sb.Append(ci, $" jitter {stats.JitterUs / 1000.0:0.0} inj {_sInjects} px {_sPx} maxstep {_sMaxStep} handoff {_sMaxInjectUs / 1000.0:0.0}");
        if (_sPadHandOffs > 0) _sb.Append(" padonly ").Append(_sPadHandOffs);
        if (_sCatchUps > 0) _sb.Append(" catch-ups ").Append(_sCatchUps);
        if (_sSlow > 0) _sb.Append(" slow ").Append(_sSlow);
        if (_sJumps > 0) _sb.Append(" bigmoves ").Append(_sJumps);
        // gp-rx's longest wait for the engine lock this second, from 0.1 ms, and who had it.
        if (_sMaxLockWaitUs >= 100)
        {
            _sb.Append(ci, $" lockwait {_sMaxLockWaitUs / 1000.0:0.0}");
            if (_sLockHolder != null) _sb.Append(" (").Append(_sLockHolder).Append(')');
        }
        if (stallMax >= 0) _sb.Append(ci, $" stall {stallMax / 1000.0:0.0}/{stalls}");
        if (_sAir + _sPc + _sPhone > 0) _sb.Append(ci, $" heldup air {_sAir} pc {_sPc} phone {_sPhone}");
        if (note != null) _sb.Append(note);
        if (_sSkipped > 0) _sb.Append(" (").Append(_sSkipped).Append(" more events not written)");
        Line(nowUs, "sec", _sb.ToString());
    }

    private void ClearSecond()
    {
        _sStates = _sMoving = _sLost = _sLate = _sLate3 = _sGaps = _sReleases = _sInjects = _sPadHandOffs = _sMaxStep = _sSlow = _sCatchUps = _sJumps = _sEvents = _sSkipped = 0;
        _sAir = _sPc = _sPhone = 0;
        Array.Clear(_sExtra);
        Array.Clear(_sOver);
        _sExtraN = 0;
        _sPx = _sMaxLateUs = _sMaxInjectUs = _sMaxLockWaitUs = 0;
        _sLockHolder = null;
    }

    /// <summary>A percentile of a 0.1 ms histogram, as "&lt;0.3" (ms), "-" when empty.</summary>
    private static string Pct(long[] hist, double p)
    {
        long n = 0;
        foreach (var c in hist) n += c;
        if (n == 0) return "-";
        long rank = (long)Math.Ceiling(p * n), acc = 0;
        for (int i = 0; i < hist.Length; i++)
        {
            acc += hist[i];
            if (acc >= rank)
                return i == hist.Length - 1
                    ? string.Create(CultureInfo.InvariantCulture, $"{i * ExtraStepUs / 1000.0:0.0}+")
                    : string.Create(CultureInfo.InvariantCulture, $"<{(i + 1) * ExtraStepUs / 1000.0:0.0}");
        }
        return "-";
    }

    /// <summary>A percentile of this second's 0.1 ms histogram, ms (the bucket's middle), −1 when empty.</summary>
    private static float PctMs(int[] hist, int n, double p)
    {
        if (n == 0) return -1;
        long rank = (long)Math.Ceiling(p * n), acc = 0;
        for (int i = 0; i < hist.Length; i++)
        {
            acc += hist[i];
            if (acc >= rank) return (i * ExtraStepUs + ExtraStepUs / 2) / 1000f;
        }
        return (hist.Length * ExtraStepUs) / 1000f;
    }

    /// <summary>
    /// Room for one more event line this second (at most <see cref="MaxEventsPerSecond"/>: a flick at high sensitivity
    /// can make big moves every millisecond); the rest are only counted. Checked before the line's text is made; false while
    /// no lines are written.
    /// </summary>
    private bool EventAllowed()
    {
        // No lines written (a Link test watch or an FPS overlay without Diagnostics): no text made either.
        if (!WriteLines) return false;
        if (_sEvents >= MaxEventsPerSecond)
        {
            _sSkipped++;
            return false;
        }
        _sEvents++;
        return true;
    }

    private void Line(long atUs, string what, string detail)
    {
        if (!WriteLines) return;
        if (Deferred) Push(new Ev { AtUs = atUs, Kind = EvKind.Text, What = what, Text = detail });
        else _write(Stamp(atUs, what, detail));
    }

    /// <summary>An event as numbers: its text is made later (<see cref="Format"/>), off the input path.</summary>
    private void Event(long atUs, EvKind kind, long a, long b, long c = 0, long d = 0, in HandOffCauses causes = default)
    {
        var e = new Ev { AtUs = atUs, Kind = kind, A = a, B = b, C = c, D = d, Causes = causes };
        if (Deferred) Push(e);
        else _write(Format(in e));
    }

    private void Push(in Ev e)
    {
        long head = _evHead;
        if (head - Volatile.Read(ref _evTail) >= EvRing)
        {
            EventsDropped++;
            return;
        }
        _ev[head & (EvRing - 1)] = e;
        Volatile.Write(ref _evHead, head + 1);
    }

    private string Stamp(long atUs, string what, string detail) =>
        string.Create(CultureInfo.InvariantCulture, $"{(atUs - _sessionStartUs) / 1e6,10:0.000} {what,-13} {detail}");

    private string Format(in Ev e)
    {
        var ci = CultureInfo.InvariantCulture;
        switch (e.Kind)
        {
            case EvKind.Lost:
                return Stamp(e.AtUs, "lost", string.Create(ci, $"{e.A} packets before seq {e.B}"));
            case EvKind.PhoneGap:
                return Stamp(e.AtUs, "phone gap", string.Create(ci, $"phone sent nothing for {e.A / 1000.0:0.0} ms") + (e.B != 0 ? " while input was moving or held" : ""));
            case EvKind.Late:
            {
                int verdict = (int)(e.D & 3);
                long stall = e.D >> 2;
                string where = verdict switch
                {
                    2 => " (the phone also paused: phone)",
                    1 => string.Create(ci, $" (sent on time, the PC stalled {stall / 1000.0:0.0} ms then: PC)"),
                    _ => Stall == null && stall == 0 ? " (sent on time: held up on the way)" : " (sent on time, the PC ran on time: air)",
                };
                return Stamp(e.AtUs, "late", string.Create(ci, $"{e.A} packets up to {e.B / 1000.0:0.0} ms late over {e.C / 1000.0:0.0} ms") + where);
            }
            case EvKind.BigMove:
                return Stamp(e.AtUs, "big move", string.Create(ci, $"{e.A},{e.B} px in one step"));
            case EvKind.SlowHandOff:
                return Stamp(e.AtUs, "slow hand-off", SlowText(e.A, e.Causes));
            case EvKind.Refused:
                return Stamp(e.AtUs, "refused", "Windows took fewer events than given (an admin window or the lock screen in front)");
            case EvKind.CatchUp:
                return Stamp(e.AtUs, "catch-up", string.Create(ci, $"{e.A} px from {e.B / 1000.0:0.0} ms of late or lost motion spread over {e.C / 1000.0:0.0} ms"));
            case EvKind.Release:
                return Stamp(e.AtUs, "release", string.Create(ci, $"phone silent {Wire.ReleaseTimeoutMs} ms: let go of {e.A} held keys or buttons"));
            default:
                return Stamp(e.AtUs, e.What ?? "", e.Text ?? "");
        }
    }
}
