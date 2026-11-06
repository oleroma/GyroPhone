using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// "Measure buttons": how long a press or release takes from the phone seeing its input (a touch,
/// a controller key) to this PC handing it to the game. The engine notes when each STATE with a
/// new press or release is injected (<see cref="OnInjected"/>); the phone's PRESS_TIME names that
/// STATE and when its input happened (<see cref="OnPressTime"/>). Either can come first. The clock
/// offset is the phone's, from LINK_INFO, as for the link latency. Keeps the last
/// <see cref="Window"/> presses; under the engine lock.
/// </summary>
public sealed class PressLatency
{
    public const int Window = 64;
    private const int Ring = 32;
    /// <summary>A PRESS_TIME or an injection unmatched for this long is dropped.</summary>
    private const long MatchUs = 1_000_000;

    private readonly uint[] _injState = new uint[Ring];
    private readonly long[] _injAt = new long[Ring];
    private int _injNext, _injCount;

    private readonly PressTime[] _waiting = new PressTime[8];
    private readonly long[] _waitingSince = new long[8];
    private int _waitingCount;

    private readonly long[] _total = new long[Window];
    private readonly long[] _phone = new long[Window];
    private int _next;

    /// <summary>Presses measured in the window (at most <see cref="Window"/>).</summary>
    public int Count { get; private set; }

    /// <summary>The newest press, total and phone part, in µs; -1 before the first.</summary>
    public long LastTotalUs { get; private set; } = -1;
    public long LastPhoneUs { get; private set; } = -1;

    public void Reset()
    {
        _injNext = _injCount = 0;
        _waitingCount = 0;
        _next = 0;
        Count = 0;
        LastTotalUs = LastPhoneUs = -1;
    }

    /// <summary>
    /// A STATE (phone time <paramref name="stateTimeUs"/>) went to the game at <paramref name="injectUs"/>.
    /// A burst injects only its newest STATE, so it also delivers every older one.
    /// </summary>
    public void OnInjected(uint stateTimeUs, long injectUs, long clockOffsetUs, bool haveOffset)
    {
        _injState[_injNext] = stateTimeUs;
        _injAt[_injNext] = injectUs;
        _injNext = (_injNext + 1) % Ring;
        if (_injCount < Ring) _injCount++;
        for (int i = _waitingCount - 1; i >= 0; i--)
        {
            if (!Covers(stateTimeUs, _waiting[i].StateTimeUs)) continue;
            var p = _waiting[i];
            RemoveWaiting(i);
            if (haveOffset) Add(p, injectUs, clockOffsetUs);
        }
    }

    /// <summary>The phone's PRESS_TIME, received at <paramref name="nowUs"/>.</summary>
    public void OnPressTime(in PressTime p, long nowUs, long clockOffsetUs, bool haveOffset)
    {
        // The first injection that carried its STATE: that one, or the oldest newer one.
        int best = -1;
        uint bestDiff = uint.MaxValue;
        for (int k = 0; k < _injCount; k++)
        {
            int i = (_injNext - 1 - k + Ring) % Ring;
            if (!Covers(_injState[i], p.StateTimeUs)) continue;
            uint d = _injState[i] - p.StateTimeUs;
            if (d < bestDiff) (best, bestDiff) = (i, d);
        }
        if (best >= 0)
        {
            if (nowUs - _injAt[best] <= MatchUs && haveOffset) Add(p, _injAt[best], clockOffsetUs);
            return;
        }
        // Its STATE isn't injected yet (it waits for the next flush): keep it until it is.
        for (int i = _waitingCount - 1; i >= 0; i--)
            if (nowUs - _waitingSince[i] > MatchUs) RemoveWaiting(i);
        if (_waitingCount == _waiting.Length) RemoveWaiting(0);
        _waiting[_waitingCount] = p;
        _waitingSince[_waitingCount] = nowUs;
        _waitingCount++;
    }

    /// <summary>An injection of the STATE at phone time <paramref name="injected"/> delivers the one at <paramref name="state"/>: the same or up to a second newer.</summary>
    private static bool Covers(uint injected, uint state) => unchecked(injected - state) <= MatchUs;

    private void RemoveWaiting(int i)
    {
        for (int j = i; j < _waitingCount - 1; j++)
        {
            _waiting[j] = _waiting[j + 1];
            _waitingSince[j] = _waitingSince[j + 1];
        }
        _waitingCount--;
    }

    private void Add(in PressTime p, long injectUs, long clockOffsetUs)
    {
        long total = LinkStats.LatencyUs(p.EventTimeUs, injectUs, clockOffsetUs);
        long phone = unchecked((int)(p.StateTimeUs - p.EventTimeUs));
        // A clock step or a stale input time: not a press anyone made.
        if (total < 0 || total > MatchUs || phone < 0 || phone > total + 2_000) return;
        _total[_next] = total;
        _phone[_next] = phone;
        _next = (_next + 1) % Window;
        if (Count < Window) Count++;
        LastTotalUs = total;
        LastPhoneUs = phone;
    }

    /// <summary>The middle press of the window: total and phone part in µs, or null before any.</summary>
    public (long TotalUs, long PhoneUs)? Median()
    {
        if (Count == 0) return null;
        return (Middle(_total), Middle(_phone));
    }

    private long Middle(long[] values)
    {
        var a = values.AsSpan(0, Count).ToArray();
        Array.Sort(a);
        return a[a.Length / 2];
    }
}
