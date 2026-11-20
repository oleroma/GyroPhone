namespace GyroPhone.Host.Perf;

/// <summary>One second of the phone link, from the input log's second line (<see cref="InputRecorder.Second"/>).</summary>
/// <param name="AtUs">When the second ended, on the engine's clock (<see cref="Abstractions.IClock.NowUs"/>).</param>
/// <param name="Rx">Packets received.</param>
/// <param name="Lost">Packets lost.</param>
/// <param name="Late">Packets that came more than <see cref="InputRecorder.LateUs"/> later than the fastest ones.</param>
/// <param name="MaxLateMs">The latest packet's extra delay.</param>
/// <param name="Gaps">Times the phone sent nothing for longer than <see cref="InputRecorder.PhoneGapUs"/> while input was going on.</param>
/// <param name="Releases">Times held keys and buttons were let go because the phone went silent.</param>
/// <param name="LatP50Ms">The middle phone-to-PC latency, ms (−1 unknown).</param>
/// <param name="LatP99Ms">The slowest 1%'s latency, ms (−1 unknown).</param>
/// <param name="JitterMs">How much the latency varied, ms.</param>
public readonly record struct LinkSecond(long AtUs, int Rx, int Lost, int Late, float MaxLateMs, int Gaps, int Releases,
    float LatP50Ms = -1, float LatP99Ms = -1, float JitterMs = 0)
{
    /// <summary>Half the best round trip, ms: the fastest the link went (−1 unknown).</summary>
    public float FloorMs { get; init; } = -1;
    /// <summary>The phone's typical (middle) round trip, ms (−1 unknown).</summary>
    public float RttTypMs { get; init; } = -1;
    /// <summary>How much slower than the fastest packets this second's were: middle and slowest 1%, ms (−1 none). Needs no clock sync.</summary>
    public float OverP50Ms { get; init; } = -1;
    public float OverP99Ms { get; init; } = -1;
    /// <summary>Packets 3 to 15 ms slower than the fastest (below a hold-up, still felt in a flick).</summary>
    public int Late3 { get; init; }
    /// <summary>The slowest hand-off to Windows, ms; hand-offs over 2 ms; gp-rx's longest wait for the engine lock, ms.</summary>
    public float HandOffMaxMs { get; init; }
    public int SlowHandOffs { get; init; }
    public float LockWaitMs { get; init; }
    /// <summary>The PC stall watch: its latest wake, ms (−1 not running), and wakes 2 ms or more late.</summary>
    public float StallMaxMs { get; init; } = -1;
    public int Stalls { get; init; }
    /// <summary>Latencies that came out below zero (a wrong clock offset).</summary>
    public int NegLat { get; init; }
    /// <summary>Late packets by where they waited: on the way (Wi-Fi, router, cable), inside the PC, or the phone paused.</summary>
    public int Air { get; init; }
    public int Pc { get; init; }
    public int Phone { get; init; }
    /// <summary>Packets that moved the mouse or a stick.</summary>
    public int Moving { get; init; }
    /// <summary>This second's packets by how much later than the fastest they came (<see cref="OverFloor.EdgesUs"/>), or null (an older input program).</summary>
    public int[]? Over { get; init; }

    /// <summary>Something went wrong on the link this second.</summary>
    public bool Trouble => Lost > 0 || Late > 0 || Gaps > 0 || Releases > 0;
}

/// <summary>
/// The last 20 minutes of <see cref="LinkSecond"/>s, for the Performance page's graph and its test records.
/// Written by the engine (under its lock), read by the UI and the monitor's timer.
/// </summary>
public sealed class LinkTimeline
{
    public const int Keep = 1860; // Diagnostics keeps up to 30 minutes, plus a minute of slack
    private readonly LinkSecond[] _ring = new LinkSecond[Keep];
    private int _head, _count;
    private readonly object _lock = new();

    /// <summary>Also told of each second added (the input process hands them to the companion's Performance monitor).</summary>
    public Action<LinkSecond>? Added { get; set; }

    public void Add(LinkSecond s)
    {
        lock (_lock)
        {
            _ring[_head] = s;
            _head = (_head + 1) % Keep;
            if (_count < Keep) _count++;
        }
        Added?.Invoke(s);
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
