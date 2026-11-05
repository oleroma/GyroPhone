namespace GyroPhone.Protocol;

/// <summary>
/// The buttons a controller held, by time: each change as (host µs, place bits), so the PC gyro can
/// ask what was held when a sample was measured. Keeps the last <see cref="Capacity"/> changes,
/// well over a second of presses. Not thread safe: the owner locks.
/// </summary>
public sealed class PadHeldHistory
{
    public const int Capacity = 64;

    private readonly long[] _us = new long[Capacity];
    private readonly uint[] _held = new uint[Capacity];
    private int _count, _next;

    /// <summary>Notes that <paramref name="held"/> is held from <paramref name="us"/> on (nothing when it is no change).</summary>
    public void Add(long us, uint held)
    {
        if (_count > 0)
        {
            int last = (_next + Capacity - 1) % Capacity;
            if (_held[last] == held) return;
            if (us < _us[last]) us = _us[last]; // never back in time
        }
        _us[_next] = us;
        _held[_next] = held;
        _next = (_next + 1) % Capacity;
        if (_count < Capacity) _count++;
    }

    /// <summary>
    /// What was held at <paramref name="us"/>: the last change at or before it; before every change kept,
    /// the oldest kept; nothing when none was noted.
    /// </summary>
    public uint At(long us)
    {
        if (_count == 0) return 0;
        int idx = (_next + Capacity - 1) % Capacity;
        for (int k = 0; k < _count; k++)
        {
            if (_us[idx] <= us) return _held[idx];
            if (k < _count - 1) idx = (idx + Capacity - 1) % Capacity;
        }
        return _held[idx];
    }

    public void Clear()
    {
        _count = 0;
        _next = 0;
    }
}
