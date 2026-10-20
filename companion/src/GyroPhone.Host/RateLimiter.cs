using System.Net;

namespace GyroPhone.Host;

/// <summary>
/// Fixed one-second windows: at most perIp events per source IPv4 address and global in total
/// (ERROR replies, SPEC 1.3). Bounded memory: the per-IP table is cleared every window.
/// </summary>
public sealed class RateLimiter
{
    private const long WindowUs = 1_000_000;
    private const int MaxTrackedIps = 1024;

    private readonly int _perIp;
    private readonly int _global;
    private readonly Dictionary<uint, int> _counts = new();
    private long _windowStartUs = -WindowUs;
    private int _globalCount;

    public RateLimiter(int perIp, int global)
    {
        _perIp = perIp;
        _global = global;
    }

    public bool TryAcquire(SocketAddress from, long nowUs) => TryAcquire(Ipv4Key(from), nowUs);

    public bool TryAcquire(uint ipKey, long nowUs)
    {
        if (nowUs - _windowStartUs >= WindowUs)
        {
            _windowStartUs = nowUs;
            _globalCount = 0;
            _counts.Clear();
        }
        if (_globalCount >= _global) return false;
        _counts.TryGetValue(ipKey, out int c);
        if (c >= _perIp) return false;
        if (c == 0 && _counts.Count >= MaxTrackedIps) return false;
        _counts[ipKey] = c + 1;
        _globalCount++;
        return true;
    }

    /// <summary>IPv4 address bytes of a SocketAddress (offset 4..8 in the normalized layout).</summary>
    public static uint Ipv4Key(SocketAddress a)
    {
        var b = a.Buffer.Span;
        return b.Length >= 8 ? BitConverter.ToUInt32(b[4..8]) : 0;
    }
}
