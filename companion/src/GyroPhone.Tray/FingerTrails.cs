using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// Where each finger went over the last half second, for the phone overlay's trails. OVERLAY gives
/// the fingers' places but not which finger is which, so each new place joins the trail whose end
/// is nearest (a finger never jumps far in one frame); a finger that lifts leaves its trail to fade.
/// UI thread only.
/// </summary>
internal sealed class FingerTrails
{
    /// <summary>How long a point stays on a trail.</summary>
    public const long LifeMs = 500;
    private const int MaxPoints = 64;
    /// <summary>Further than this (fraction of the pad area) from every trail's end: a new finger.</summary>
    private const float JoinDistance = 0.15f;

    public sealed class Trail
    {
        public readonly List<(float U, float V, long T)> Points = [];
        public bool Live;
    }

    private readonly List<Trail> _trails = [];

    public IReadOnlyList<Trail> All => _trails;

    /// <summary>A new OVERLAY at <paramref name="now"/> (ms): its fingers extend their trails.</summary>
    public void Add(OverlayFrame f, long now)
    {
        foreach (var t in _trails) t.Live = false;
        int n = f.PadShown ? f.FingerCount : 0;
        Span<bool> used = stackalloc bool[_trails.Count];
        for (int i = 0; i < n; i++)
        {
            float u = f.Fingers[i * 2], v = f.Fingers[i * 2 + 1];
            int best = -1;
            float bestD = JoinDistance * JoinDistance;
            for (int k = 0; k < used.Length; k++)
            {
                var pts = _trails[k].Points;
                if (used[k] || pts.Count == 0) continue;
                var last = pts[^1];
                if (now - last.T > 100) continue; // lifted: a new touch starts its own trail
                float du = last.U - u, dv = last.V - v, d = du * du + dv * dv;
                if (d < bestD) { bestD = d; best = k; }
            }
            Trail t;
            if (best >= 0)
            {
                used[best] = true;
                t = _trails[best];
            }
            else
            {
                t = new Trail();
                _trails.Add(t);
            }
            t.Live = true;
            t.Points.Add((u, v, now));
            if (t.Points.Count > MaxPoints) t.Points.RemoveAt(0);
        }
        Expire(now);
    }

    /// <summary>Some trail is still on screen (draw again while it fades).</summary>
    public bool Fading(long now)
    {
        Expire(now);
        return _trails.Count > 0;
    }

    private void Expire(long now)
    {
        for (int k = _trails.Count - 1; k >= 0; k--)
        {
            var pts = _trails[k].Points;
            int old = 0;
            while (old < pts.Count && now - pts[old].T > LifeMs) old++;
            if (old > 0) pts.RemoveRange(0, old);
            if (pts.Count == 0) _trails.RemoveAt(k);
        }
    }
}
