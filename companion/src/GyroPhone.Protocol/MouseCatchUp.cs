namespace GyroPhone.Protocol;

/// <summary>
/// Keeps a lost or late stretch of mouse motion from landing as one jump. Each accepted STATE's
/// mouse delta comes in through <see cref="Add"/> with the phone time it covers; <see cref="Take"/>
/// (once per injection) hands out what to move now:
/// <list type="bullet">
/// <item>The newest motion (the last STATE's, or, after lost packets, its share of the newest
/// <see cref="FreshUs"/> of phone time) always goes out at once, so aiming never waits.</item>
/// <item>Older motion that arrives in the same injection (a late burst, or the part of a gap that
/// lost packets would have carried) goes out at once too while it covers at most
/// <see cref="MinBacklogUs"/>, which is ordinary Wi-Fi jitter. A longer backlog is played out over
/// half the time it covers, at most <see cref="MaxGlideUs"/>, on top of the newest motion.</item>
/// </list>
/// Nothing is lost: every count goes out, a glide just spreads some over a few milliseconds.
/// Not thread safe.
/// </summary>
public sealed class MouseCatchUp
{
    /// <summary>A backlog covering at most this much phone time goes out at once.</summary>
    public const long MinBacklogUs = 4_000;
    /// <summary>The longest a backlog is spread over.</summary>
    public const long MaxGlideUs = 16_000;
    private const int MinFreshUs = 500, MaxFreshUs = 8_000, StartFreshUs = 1_000;
    /// <summary>Spans longer than this are idle pauses, not the phone's send spacing.</summary>
    private const int MaxSpacingUs = 25_000;

    // This injection's motion: the newest STATE's own (fresh) and the older ones' (old).
    private int _freshX, _freshY, _oldX, _oldY;
    private long _freshSpan, _oldSpan;
    private bool _hasFresh;

    // The glide: counts still to go out, from _glideFromUs until _glideEndUs.
    private double _glideX, _glideY;
    private long _glideFromUs, _glideEndUs;
    private double _fracX, _fracY;

    /// <summary>The phone's usual spacing between moving STATEs, µs (learned).</summary>
    public int FreshUs { get; private set; } = StartFreshUs;

    /// <summary>Some backlog is still to go out: <see cref="Take"/> should run again by <see cref="GlideEndUs"/>.</summary>
    public bool Gliding { get; private set; }

    public long GlideEndUs => _glideEndUs;

    /// <summary>The last <see cref="Take"/> started or lengthened a glide: the backlog's phone time, counts and glide length (for the input log).</summary>
    public bool BacklogTaken { get; private set; }
    public long BacklogSpanUs { get; private set; }
    public int BacklogPx { get; private set; }
    public long BacklogGlideUs { get; private set; }

    /// <summary>Nothing pending (a new session, a release, a pause): the backlog is dropped.</summary>
    public void Clear()
    {
        _freshX = _freshY = _oldX = _oldY = 0;
        _freshSpan = _oldSpan = 0;
        _hasFresh = false;
        _glideX = _glideY = 0;
        _fracX = _fracY = 0;
        Gliding = false;
    }

    /// <summary>
    /// One STATE's mouse delta. <paramref name="spanUs"/>: phone time since the STATE before it
    /// (0 when unknown); <paramref name="lost"/>: packets were missing between the two, so the delta
    /// also carries their motion.
    /// </summary>
    public void Add(int dx, int dy, long spanUs, bool lost)
    {
        if (spanUs < 0) spanUs = 0;
        if (_hasFresh)
        {
            // A newer STATE in the same injection: the one before it is now backlog.
            _oldX += _freshX;
            _oldY += _freshY;
            _oldSpan += _freshSpan;
        }
        _hasFresh = true;
        bool moved = dx != 0 || dy != 0;
        if (!lost && moved && spanUs > 0 && spanUs <= MaxSpacingUs)
        {
            // Learn the spacing slowly, so one odd span does not move it.
            FreshUs = (int)Math.Clamp(FreshUs + (spanUs - FreshUs) / 8, MinFreshUs, MaxFreshUs);
        }
        if (lost && spanUs > FreshUs)
        {
            // The lost packets' motion came before this STATE's own: only the newest share is fresh.
            double share = (double)FreshUs / spanUs;
            int fx = (int)Math.Round(dx * share), fy = (int)Math.Round(dy * share);
            _freshX = fx;
            _freshY = fy;
            _oldX += dx - fx;
            _oldY += dy - fy;
            _oldSpan += spanUs - FreshUs;
            _freshSpan = FreshUs;
        }
        else
        {
            // Nothing lost: the phone sent as soon as it moved, so all of it is fresh (after an
            // idle pause the span is long but the motion is recent).
            _freshX = dx;
            _freshY = dy;
            _freshSpan = Math.Min(spanUs, FreshUs);
        }
    }

    /// <summary>What to move now, at host time <paramref name="nowUs"/>; call once per injection.</summary>
    public void Take(long nowUs, out int dx, out int dy)
    {
        double x = _freshX, y = _freshY;
        BacklogTaken = false;
        if (_oldSpan > MinBacklogUs)
        {
            long end = nowUs + Math.Min(_oldSpan / 2, MaxGlideUs);
            BacklogTaken = true;
            BacklogSpanUs = _oldSpan;
            BacklogPx = Math.Abs(_oldX) + Math.Abs(_oldY);
            BacklogGlideUs = end - nowUs;
            if (!Gliding)
            {
                _glideFromUs = nowUs;
                _glideEndUs = end;
            }
            else if (end > _glideEndUs) _glideEndUs = end;
            _glideX += _oldX;
            _glideY += _oldY;
            Gliding = true;
        }
        else
        {
            x += _oldX;
            y += _oldY;
        }
        _freshX = _freshY = _oldX = _oldY = 0;
        _freshSpan = _oldSpan = 0;
        _hasFresh = false;

        if (Gliding)
        {
            double part = nowUs >= _glideEndUs ? 1.0 : (double)(nowUs - _glideFromUs) / (_glideEndUs - _glideFromUs);
            if (part > 0)
            {
                double gx = _glideX * part, gy = _glideY * part;
                _glideX -= gx;
                _glideY -= gy;
                x += gx;
                y += gy;
                _glideFromUs = nowUs;
            }
            if (nowUs >= _glideEndUs)
            {
                Gliding = false;
                _glideX = _glideY = 0;
            }
        }
        // Whole counts go out; the glide's fractions wait for the next injection.
        x += _fracX;
        y += _fracY;
        dx = (int)Math.Truncate(x);
        dy = (int)Math.Truncate(y);
        _fracX = x - dx;
        _fracY = y - dy;
        if (!Gliding)
        {
            // Fractions only come from a glide; once it ends, the last of them go out with it.
            dx += (int)Math.Round(_fracX);
            dy += (int)Math.Round(_fracY);
            _fracX = _fracY = 0;
        }
    }
}
