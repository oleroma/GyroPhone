namespace GyroPhone.Host.Perf;

/// <summary>One second of the phone link, from the input log's second line (<see cref="InputRecorder.Second"/>).</summary>
/// <param name="AtUs">When the second ended, on the engine's clock (<see cref="Abstractions.IClock.NowUs"/>).</param>
/// <param name="Rx">Packets received.</param>
/// <param name="Lost">Packets lost.</param>
/// <param name="Late">Packets that came more than <see cref="InputRecorder.LateUs"/> later than the fastest ones.</param>
/// <param name="MaxLateMs">The latest packet's extra delay.</param>
/// <param name="Gaps">Times the phone sent nothing for longer than <see cref="InputRecorder.PhoneGapUs"/> while input was going on.</param>
/// <param name="Releases">Times held keys and buttons were let go because the phone went silent.</param>
public readonly record struct LinkSecond(long AtUs, int Rx, int Lost, int Late, float MaxLateMs, int Gaps, int Releases)
{
    /// <summary>Something went wrong on the link this second.</summary>
    public bool Trouble => Lost > 0 || Late > 0 || Gaps > 0 || Releases > 0;
}

/// <summary>
/// The last 20 minutes of <see cref="LinkSecond"/>s, for the Performance page's graph and its test records.
/// Written by the engine (under its lock), read by the UI and the monitor's timer.
/// </summary>
public sealed class LinkTimeline
{
    public const int Keep = 1200;
    private readonly LinkSecond[] _ring = new LinkSecond[Keep];
    private int _head, _count;
    private readonly object _lock = new();

    public void Add(LinkSecond s)
    {
        lock (_lock)
        {
            _ring[_head] = s;
            _head = (_head + 1) % Keep;
            if (_count < Keep) _count++;
        }
    }

    /// <summary>The seconds that ended after <paramref name="fromUs"/> and at or before <paramref name="toUs"/>, oldest first.</summary>
    public List<LinkSecond> Between(long fromUs, long toUs)
    {
        var list = new List<LinkSecond>();
        lock (_lock)
        {
            int start = (_head - _count + Keep) % Keep;
            for (int i = 0; i < _count; i++)
            {
                var s = _ring[(start + i) % Keep];
                if (s.AtUs > fromUs && s.AtUs <= toUs) list.Add(s);
            }
        }
        return list;
    }
}
