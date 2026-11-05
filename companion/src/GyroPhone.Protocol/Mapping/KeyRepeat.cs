namespace GyroPhone.Protocol.Mapping;

/// <summary>
/// Repeats held volume keys (<see cref="HidScanCodes.Repeats"/>) the way a keyboard does: Windows
/// doesn't repeat injected keys, so a held Volume Up would only step once. After
/// <see cref="DelayUs"/> held, a key down goes again every <see cref="IntervalUs"/> (or at each
/// call, when calls come further apart) until the key is let go.
/// </summary>
public sealed class KeyRepeat
{
    public const long DelayUs = 400_000;
    public const long IntervalUs = 50_000;

    private static readonly int[] Usages = { 0x80, 0x81 };
    private readonly long[] _nextUs = { -1, -1 };

    /// <summary>
    /// Writes a key down for each repeating usage held in <paramref name="injected"/> whose next
    /// repeat is due; a newly held one starts its delay. Returns the event count.
    /// </summary>
    public int Due(ReadOnlySpan<byte> injected, long nowUs, Span<InjectEvent> output)
    {
        int n = 0;
        for (int i = 0; i < Usages.Length; i++)
        {
            int u = Usages[i];
            bool held = (injected[u >> 3] & (1 << (u & 7))) != 0;
            if (!held) { _nextUs[i] = -1; continue; }
            if (_nextUs[i] < 0) { _nextUs[i] = nowUs + DelayUs; continue; }
            if (nowUs < _nextUs[i] || n >= output.Length) continue;
            output[n++] = InjectEvent.Key((byte)u, HidScanCodes.For(u), up: false);
            _nextUs[i] = Math.Max(_nextUs[i] + IntervalUs, nowUs);
        }
        return n;
    }

    /// <summary>Forgets every held key (after a release-all or a session end).</summary>
    public void Reset() => Array.Fill(_nextUs, -1);
}
