namespace GyroPhone.Protocol;

/// <summary>
/// Turns the cumulative wrapping mouse/wheel totals of accepted STATEs into deltas (SPEC 1.8).
/// Rebases (delta 0) on the first STATE and after more than RELEASE_TIMEOUT_MS of silence; clamps
/// each delta; always moves the baseline to the new total.
/// </summary>
public sealed class MouseDeltaTracker
{
    private const long RebaseGapUs = Wire.ReleaseTimeoutMs * 1000L;

    private bool _hasBase;
    private long _lastUs;
    private int _lastX, _lastY, _lastW;

    /// <summary>Forget the baseline; the next STATE rebases.</summary>
    public void Reset() => _hasBase = false;

    public void Apply(int totalX, int totalY, int totalW, long nowUs, out int dx, out int dy, out int dw)
    {
        if (!_hasBase || nowUs - _lastUs > RebaseGapUs)
        {
            dx = dy = dw = 0;
            _hasBase = true;
        }
        else
        {
            dx = Math.Clamp(unchecked(totalX - _lastX), -Wire.MaxMouseDelta, Wire.MaxMouseDelta);
            dy = Math.Clamp(unchecked(totalY - _lastY), -Wire.MaxMouseDelta, Wire.MaxMouseDelta);
            dw = Math.Clamp(unchecked(totalW - _lastW), -Wire.MaxWheelDelta, Wire.MaxWheelDelta);
        }
        _lastX = totalX;
        _lastY = totalY;
        _lastW = totalW;
        _lastUs = nowUs;
    }
}
