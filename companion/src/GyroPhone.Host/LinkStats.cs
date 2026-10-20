using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// Per-session link statistics, updated on the rx thread and rolled once a second by the watchdog
/// (both under the engine lock). One-way latency goes into a 512-bucket, 50 us histogram once the
/// phone has reported a clock offset; jitter is the Welford standard deviation of STATE
/// inter-arrival times; loss comes from gaps in the shared P->H seq.
/// </summary>
public sealed class LinkStats
{
    public const int BucketUs = 50;
    public const int Buckets = 512;

    private readonly int[] _hist = new int[Buckets];
    private int _histCount;
    private bool _haveOffset;
    private long _clockOffsetUs;

    private bool _haveSeq;
    private uint _lastSeq;
    private int _rxSealed, _rxStates, _lost;

    private long _lastArrivalUs = -1;
    private long _n;
    private double _mean, _m2;

    public ushort RxPerSec { get; private set; }
    public ushort LossPermille { get; private set; }
    public uint LatP50Us { get; private set; } = HostStatus.Unknown;
    public uint LatP99Us { get; private set; } = HostStatus.Unknown;
    public uint JitterUs { get; private set; }
    public uint LastSeq => _lastSeq;
    public long TotalLost { get; private set; }
    public long TotalStates { get; private set; }
    public LinkInfo? LastLinkInfo { get; private set; }

    public void Reset()
    {
        Array.Clear(_hist);
        _histCount = 0;
        _haveOffset = false;
        _haveSeq = false;
        _lastSeq = 0;
        _rxSealed = _rxStates = _lost = 0;
        _lastArrivalUs = -1;
        _n = 0;
        _mean = _m2 = 0;
        RxPerSec = 0;
        LossPermille = 0;
        LatP50Us = LatP99Us = HostStatus.Unknown;
        JitterUs = 0;
        TotalLost = TotalStates = 0;
        LastLinkInfo = null;
    }

    /// <summary>Every accepted sealed P->H packet (seq is shared by all types).</summary>
    public void OnSealed(uint seq)
    {
        if (_haveSeq && seq > _lastSeq + 1)
        {
            long gap = seq - _lastSeq - 1;
            _lost += (int)Math.Min(gap, int.MaxValue / 2);
            TotalLost += gap;
        }
        _haveSeq = true;
        _lastSeq = seq;
        _rxSealed++;
    }

    /// <summary>Records one accepted STATE. Returns its one-way latency in us, or long.MinValue before the first LINK_INFO.</summary>
    public long OnState(uint phoneTimeUs, long hostRecvUs)
    {
        _rxStates++;
        TotalStates++;
        if (_lastArrivalUs >= 0)
        {
            double x = hostRecvUs - _lastArrivalUs;
            _n++;
            double d = x - _mean;
            _mean += d / _n;
            _m2 += d * (x - _mean);
        }
        _lastArrivalUs = hostRecvUs;
        if (!_haveOffset) return long.MinValue;
        long lat = LatencyUs(phoneTimeUs, hostRecvUs, _clockOffsetUs);
        long b = Math.Clamp(lat / BucketUs, 0, Buckets - 1);
        _hist[b]++;
        _histCount++;
        return lat;
    }

    public void OnLinkInfo(in LinkInfo info)
    {
        _clockOffsetUs = info.ClockOffsetUs;
        _haveOffset = true;
        LastLinkInfo = info;
    }

    /// <summary>
    /// latency = hostRecvUs - (unwrap(phoneTimeUs) + offset), where the 32-bit phone time is unwrapped
    /// to the value nearest hostRecvUs - offset.
    /// </summary>
    public static long LatencyUs(uint phoneTimeUs, long hostRecvUs, long clockOffsetUs)
    {
        long expectedPhone = hostRecvUs - clockOffsetUs;
        int diff = unchecked((int)(phoneTimeUs - (uint)expectedPhone));
        long phoneUs = expectedPhone + diff;
        return hostRecvUs - (phoneUs + clockOffsetUs);
    }

    /// <summary>Closes the current one-second window.</summary>
    public void Roll()
    {
        RxPerSec = (ushort)Math.Min(_rxStates, ushort.MaxValue);
        int total = _rxSealed + _lost;
        LossPermille = total == 0 ? (ushort)0 : (ushort)Math.Min(1000, _lost * 1000L / total);
        if (_histCount > 0)
        {
            LatP50Us = Percentile(0.50);
            LatP99Us = Percentile(0.99);
        }
        else
        {
            LatP50Us = LatP99Us = HostStatus.Unknown;
        }
        JitterUs = _n > 1 ? (uint)Math.Min(uint.MaxValue, Math.Sqrt(_m2 / (_n - 1))) : 0;
        Array.Clear(_hist);
        _histCount = 0;
        _rxSealed = _rxStates = _lost = 0;
        _n = 0;
        _mean = _m2 = 0;
    }

    private uint Percentile(double p)
    {
        long rank = (long)Math.Ceiling(p * _histCount);
        long acc = 0;
        for (int i = 0; i < Buckets; i++)
        {
            acc += _hist[i];
            if (acc >= rank) return (uint)(i * BucketUs + BucketUs / 2);
        }
        return (uint)(Buckets * BucketUs);
    }
}
