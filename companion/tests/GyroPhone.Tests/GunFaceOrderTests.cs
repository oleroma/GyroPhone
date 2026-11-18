using GyroPhone.Host;

namespace GyroPhone.Tests;

/// <summary>
/// The gun's faces, drawn one over another in LaserTrace.RifleFaces' order, show what a true depth test (each pixel the
/// nearest face) shows. Drawn by their middles' depth alone, a face behind a long one could cover it, and the long face
/// looked missing. As the phone's GunFaceOrderTest.
/// </summary>
public class GunFaceOrderTests
{
    private const int N = 160;

    private static float[] RandomTurn(Random r)
    {
        float a = r.NextSingle() * 2 - 1, b = r.NextSingle() * 2 - 1, c = r.NextSingle() * 2 - 1, d = r.NextSingle() * 2 - 1;
        float l = MathF.Sqrt(a * a + b * b + c * c + d * d);
        a /= l; b /= l; c /= l; d /= l;
        return
        [
            a * a + b * b - c * c - d * d, 2 * (b * c - a * d), 2 * (b * d + a * c),
            2 * (b * c + a * d), a * a - b * b + c * c - d * d, 2 * (c * d - a * b),
            2 * (b * d - a * c), 2 * (c * d + a * b), a * a - b * b - c * c + d * d,
        ];
    }

    private static bool Inside((float X, float Y)[] p, float x, float y)
    {
        bool pos = false, neg = false;
        for (int i = 0; i < p.Length; i++)
        {
            var a = p[i]; var b = p[(i + 1) % p.Length];
            float cr = (b.X - a.X) * (y - a.Y) - (b.Y - a.Y) * (x - a.X);
            if (cr > 0) pos = true; else if (cr < 0) neg = true;
        }
        return !(pos && neg);
    }

    private static float Depth(System.Numerics.Vector4 p, float x, float y, bool persp)
    {
        if (!persp) return (p.W - p.X * x - p.Y * y) / p.Z;
        float dd = LaserTrace.CameraDistance, a = (p.X * x + p.Y * y) / dd;
        return (p.W - a * dd) / (p.Z - a);
    }

    /// <summary>Share of the gun's pixels (away from edges) the drawing order gets wrong.</summary>
    private static float Wrong(List<LaserTrace.Face> faces, bool persp, List<LaserTrace.Face> ordered)
    {
        var drawn = new int[N * N];
        var nearest = new int[N * N];
        var z = new float[N * N];
        Array.Fill(drawn, -1); Array.Fill(nearest, -1); Array.Fill(z, -float.MaxValue);
        var index = ordered.Select(f => faces.FindIndex(g => ReferenceEquals(g.Corners, f.Corners))).ToArray();
        for (int py = 0; py < N; py++)
            for (int px = 0; px < N; px++)
            {
                float x = (px + 0.5f) / N * 2.4f - 1.2f, y = (py + 0.5f) / N * 2.4f - 1.2f;
                int at = py * N + px;
                for (int k = 0; k < ordered.Count; k++) if (Inside(ordered[k].Corners, x, y)) drawn[at] = index[k];
                for (int i = 0; i < faces.Count; i++)
                    if (Inside(faces[i].Corners, x, y))
                    {
                        float d = Depth(faces[i].Plane, x, y, persp);
                        if (d > z[at]) { z[at] = d; nearest[at] = i; }
                    }
            }
        int covered = 0, bad = 0;
        for (int py = 1; py < N - 1; py++)
            for (int px = 1; px < N - 1; px++)
            {
                int at = py * N + px;
                if (nearest[at] < 0) continue;
                covered++;
                // Edge pixels are a coin toss either way.
                if (nearest[at - 1] != nearest[at] || nearest[at + 1] != nearest[at] || nearest[at - N] != nearest[at] || nearest[at + N] != nearest[at]) continue;
                if (drawn[at] != nearest[at]) bad++;
            }
        return (float)bad / Math.Max(covered, 1);
    }

    [Fact]
    public void NearerFacesAlwaysCoverFartherOnes()
    {
        var r = new Random(7);
        float worstNew = 0f, sumNew = 0f, sumOld = 0f;
        int count = 0;
        foreach (var gun in Guns.All)
            foreach (bool persp in new[] { false, true })
                for (int t = 0; t < 12; t++)
                {
                    var faces = LaserTrace.RifleFaces(RandomTurn(r), false, null, persp, gun: gun);
                    float w = Wrong(faces, persp, faces);
                    worstNew = Math.Max(worstNew, w);
                    sumNew += w;
                    sumOld += Wrong(faces, persp, [.. faces.OrderBy(f => f.Depth)]);
                    count++;
                }
        // Only where boxes cut into each other (a barrel into its receiver) can any order be off, and only a sliver.
        Assert.True(worstNew < 0.005f, $"worst {worstNew}");
        Assert.True(sumNew < sumOld / 3f, $"new {sumNew / count} old {sumOld / count}");
    }

    [Fact]
    public void FacesAwayFromTheCameraAreLeftOut()
    {
        var r = new Random(3);
        for (int t = 0; t < 50; t++)
            foreach (var f in LaserTrace.RifleFaces(RandomTurn(r), false, null, true))
                // Toward the camera at (0, 0, D): normal · (camera − point) > 0.
                Assert.True(f.Plane.Z * LaserTrace.CameraDistance - f.Plane.W > 0f);
    }
}
