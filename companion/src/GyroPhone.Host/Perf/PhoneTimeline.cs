namespace GyroPhone.Host.Perf;

/// <summary>One second of the phone's side of the link (PHONE_STATS), sent while a Performance record runs.</summary>
/// <param name="AtUs">When the second ended, on the engine's clock (from the phone's time, so a report that came late still lands on its second).</param>
/// <param name="States">STATE packets the phone sent.</param>
/// <param name="Packets">All packets the phone's send thread sent.</param>
/// <param name="Failed">Sends the phone's network refused.</param>
/// <param name="SlowSends">Sends that took 2 ms or more on the phone.</param>
/// <param name="SendMaxMs">The longest send, ms.</param>
/// <param name="WakeLateMs">How much later than planned the send thread woke at worst, ms (it wasn't run on time).</param>
/// <param name="GapMaxMs">The longest time between two STATEs the phone sent, ms (the keepalive is 20 ms).</param>
/// <param name="PhoneTx">Packets the whole phone sent (every app).</param>
public readonly record struct PhoneSecond(long AtUs, int States, int Packets, int Failed, int SlowSends,
    float SendMaxMs, float WakeLateMs, float GapMaxMs, long PhoneTx);

/// <summary>The last 20 minutes of <see cref="PhoneSecond"/>s, for test records. Written by the engine, read by the monitor.</summary>
public sealed class PhoneTimeline
{
    public const int Keep = 1200;
    private readonly PhoneSecond[] _ring = new PhoneSecond[Keep];
    private int _head, _count;
    private readonly object _lock = new();

    public void Add(PhoneSecond s)
    {
        lock (_lock)
        {
            _ring[_head] = s;
            _head = (_head + 1) % Keep;
            if (_count < Keep) _count++;
        }
    }

    /// <summary>The seconds that ended after <paramref name="fromUs"/> and at or before <paramref name="toUs"/>, oldest first.</summary>
    public List<PhoneSecond> Between(long fromUs, long toUs)
    {
        var list = new List<PhoneSecond>();
        lock (_lock)
        {
            int start = (_head - _count + Keep) % Keep;
            for (int i = 0; i < _count; i++)
            {
                var s = _ring[(start + i) % Keep];
                if (s.AtUs > fromUs && s.AtUs <= toUs) list.Add(s);
            }
        }
        list.Sort((a, b) => a.AtUs.CompareTo(b.AtUs));
        return list;
    }
}
