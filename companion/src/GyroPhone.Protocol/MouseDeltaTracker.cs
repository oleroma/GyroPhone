namespace GyroPhone.Protocol;

/// <summary>
/// Turns the cumulative wrapping mouse/wheel totals of accepted STATEs into deltas (SPEC 1.8).
/// Rebases (delta 0) on the first STATE, after more than RELEASE_TIMEOUT_MS of silence and when the
/// phone switches between STATE and STATE_HIRES; clamps each delta; always moves the baseline to
/// the new total.
/// </summary>
public sealed class MouseDeltaTracker
{
    private const long RebaseGapUs = Wire.ReleaseTimeoutMs * 1000L;

    private bool _hasBase;
    private bool _hires;
    private long _lastUs;
    private int _lastX, _lastY, _lastW, _lastH;

    /// <summary>Forget the baseline; the next STATE rebases.</summary>
    public void Reset() => _hasBase = false;

    /// <summary>STATE: the wheel total and delta are in whole notches.</summary>
    public void Apply(int totalX, int totalY, int totalW, long nowUs, out int dx, out int dy, out int dw) =>
        Apply(totalX, totalY, totalW, 0, false, nowUs, out dx, out dy, out dw, out _);

    /// <summary>
    /// STATE (<paramref name="hires"/> false: wheel in notches, no horizontal wheel) or STATE_HIRES
    /// (both wheels in 1/120 notch). Wheel deltas come out in the units of the packet.
    /// </summary>
    public void Apply(int totalX, int totalY, int totalW, int totalH, bool hires, long nowUs,
        out int dx, out int dy, out int dw, out int dh)
    {
        if (!_hasBase || nowUs - _lastUs > RebaseGapUs || hires != _hires)
        {
            dx = dy = dw = dh = 0;
            _hasBase = true;
        }
        else
        {
            int maxW = hires ? Wire.MaxWheelDeltaHires : Wire.MaxWheelDelta;
            dx = Math.Clamp(unchecked(totalX - _lastX), -Wire.MaxMouseDelta, Wire.MaxMouseDelta);
            dy = Math.Clamp(unchecked(totalY - _lastY), -Wire.MaxMouseDelta, Wire.MaxMouseDelta);
            dw = Math.Clamp(unchecked(totalW - _lastW), -maxW, maxW);
            dh = Math.Clamp(unchecked(totalH - _lastH), -maxW, maxW);
        }
        _hires = hires;
        _lastX = totalX;
        _lastY = totalY;
        _lastW = totalW;
        _lastH = totalH;
        _lastUs = nowUs;
    }
}
