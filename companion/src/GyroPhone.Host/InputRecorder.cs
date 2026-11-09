using System.Globalization;
using System.Text;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// Always-on record of how the phone's input reached Windows, for finding stutter in real play
/// (docs/spec/pc-companion.md, "Input log"). Called by the engine under its lock; <c>write</c> only
/// enqueues a line. Per second it writes one summary line while a session is up, and at once one line
/// per event: lost packets, packets held up on the way, the phone going quiet, a catch-up glide, a big
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
    /// <summary>At most this many event lines a second; the rest are only counted in the second's line.</summary>
    public const int MaxEventsPerSecond = 10;
    private const long BaseWindowUs = 5_000_000;

    private readonly Action<string> _write;
    private readonly StringBuilder _sb = new(160);

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
    private int _sStates, _sMoving, _sLost, _sLate, _sInjects, _sMaxStep, _sSlow, _sGlides, _sJumps, _sEvents, _sSkipped;
    private long _sPx, _sMaxLateUs, _sMaxInjectUs;
    // Whole session.
    private long _tStates, _tLost, _tLate, _tHoldups, _tGaps, _tGlides, _tJumps, _tSlow, _tReleases, _tRefused;
    private readonly long[] _lateHist = new long[64]; // 1 ms buckets of extra delay, last = 63+ ms

    public InputRecorder(Action<string> write) => _write = write;

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
        _tStates = _tLost = _tLate = _tHoldups = _tGaps = _tGlides = _tJumps = _tSlow = _tReleases = _tRefused = 0;
        Array.Clear(_lateHist);
        Line(nowUs, "session start", $"phone '{phone}'");
    }

    public void SessionEnded(string why, long nowUs)
    {
        if (!_active) return;
        EndHoldup();
        _active = false;
        Line(nowUs, "session end", $"{why} after {(nowUs - _sessionStartUs) / 1e6:0} s: states {_tStates} lost {_tLost} late {_tLate} " +
            $"holdups {_tHoldups} phone-gaps {_tGaps} glides {_tGlides} jumps {_tJumps} slow {_tSlow} releases {_tReleases} refused {_tRefused}; " +
            $"extra delay ms p50 {Pct(0.5)} p99 {Pct(0.99)} p99.9 {Pct(0.999)}");
    }

    /// <summary>Lost packets: a gap in the shared seq.</summary>
    public void Lost(long count, uint seq, long nowUs)
    {
        if (!_active || count <= 0) return;
        _sLost += (int)Math.Min(count, 1_000_000);
        _tLost += count;
        if (count >= 3) Event(nowUs, "lost", $"{count} packets before seq {seq}");
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
                Event(recvUs, "phone gap", $"phone sent nothing for {span / 1000.0:0.0} ms" + (_prevActive ? " while input was moving or held" : ""));
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
        _lateHist[Math.Clamp(extra / 1000, 0, _lateHist.Length - 1)]++;
        if (extra > _sMaxLateUs) _sMaxLateUs = extra;
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
        // Packets that left the phone on time but arrived late together: held up on the way (Wi-Fi or router).
        Event(_holdStartUs, "late", $"{_holdCount} packets up to {_holdMaxUs / 1000.0:0.0} ms late over {(_holdLastUs - _holdStartUs) / 1000.0:0.0} ms" +
            (_holdPhoneSpanMaxUs > PhoneGapUs ? " (the phone also paused)" : " (sent on time: held up on the way)"));
    }

    /// <summary>One hand-off to Windows: the mouse move in it and how long after the newest packet's arrival.</summary>
    public void Inject(int dx, int dy, long recvUs, long nowUs, bool refused)
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
            Event(nowUs, "big move", $"{dx},{dy} px in one step");
        }
        if (recvUs >= 0)
        {
            long took = nowUs - recvUs;
            if (took > _sMaxInjectUs) _sMaxInjectUs = took;
            if (took > SlowInjectUs)
            {
                _sSlow++;
                _tSlow++;
                Event(nowUs, "slow hand-off", $"{took / 1000.0:0.0} ms from receive to SendInput");
            }
        }
        if (refused)
        {
            _tRefused++;
            Event(nowUs, "refused", "Windows took fewer events than given (an admin window or the lock screen in front)");
        }
    }

    /// <summary>A late or lost stretch of motion is gliding out instead of landing at once.</summary>
    public void Glide(long backlogUs, int px, long glideUs, long nowUs)
    {
        if (!_active) return;
        _sGlides++;
        _tGlides++;
        Event(nowUs, "glide", $"{px} px from {backlogUs / 1000.0:0.0} ms of late or lost motion spread over {glideUs / 1000.0:0.0} ms");
    }

    /// <summary>Everything let go after the phone was silent (Wire.ReleaseTimeoutMs).</summary>
    public void ReleasedForSilence(int held, long nowUs)
    {
        if (!_active) return;
        _tReleases++;
        Event(nowUs, "release", $"phone silent {Wire.ReleaseTimeoutMs} ms: let go of {held} held keys or buttons");
    }

    /// <summary>The once-a-second line, with the link numbers the engine rolled just before.</summary>
    public void Second(LinkStats stats, long nowUs)
    {
        if (!_active) return;
        _sb.Clear();
        _sb.Append(CultureInfo.InvariantCulture, $"rx {stats.RxPerSec} moving {_sMoving} lost {_sLost} late {_sLate} maxlate {_sMaxLateUs / 1000.0:0.0}");
        if (stats.LatP50Us != HostStatus.Unknown) _sb.Append(CultureInfo.InvariantCulture, $" lat {stats.LatP50Us / 1000.0:0.0}/{stats.LatP99Us / 1000.0:0.0}");
        _sb.Append(CultureInfo.InvariantCulture, $" jitter {stats.JitterUs / 1000.0:0.0} inj {_sInjects} px {_sPx} maxstep {_sMaxStep} handoff {_sMaxInjectUs / 1000.0:0.0}");
        if (_sGlides > 0) _sb.Append(" glides ").Append(_sGlides);
        if (_sSlow > 0) _sb.Append(" slow ").Append(_sSlow);
        if (_sJumps > 0) _sb.Append(" bigmoves ").Append(_sJumps);
        if (_sSkipped > 0) _sb.Append(" (").Append(_sSkipped).Append(" more events not written)");
        Line(nowUs, "sec", _sb.ToString());
        ClearSecond();
    }

    private void ClearSecond()
    {
        _sStates = _sMoving = _sLost = _sLate = _sInjects = _sMaxStep = _sSlow = _sGlides = _sJumps = _sEvents = _sSkipped = 0;
        _sPx = _sMaxLateUs = _sMaxInjectUs = 0;
    }

    private string Pct(double p)
    {
        long n = 0;
        foreach (var c in _lateHist) n += c;
        if (n == 0) return "-";
        long rank = (long)Math.Ceiling(p * n), acc = 0;
        for (int i = 0; i < _lateHist.Length; i++)
        {
            acc += _lateHist[i];
            if (acc >= rank) return i == _lateHist.Length - 1 ? $"{i}+" : $"<{i + 1}";
        }
        return "-";
    }

    /// <summary>An event line, unless this second already had <see cref="MaxEventsPerSecond"/> (a flick at high sensitivity can make big moves every millisecond).</summary>
    private void Event(long atUs, string what, string detail)
    {
        if (_sEvents >= MaxEventsPerSecond)
        {
            _sSkipped++;
            return;
        }
        _sEvents++;
        Line(atUs, what, detail);
    }

    private void Line(long atUs, string what, string detail) =>
        _write(string.Create(CultureInfo.InvariantCulture, $"{(atUs - _sessionStartUs) / 1e6,10:0.000} {what,-13} {detail}"));
}
