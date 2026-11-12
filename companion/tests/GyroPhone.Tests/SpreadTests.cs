using GyroPhone.Host;

namespace GyroPhone.Tests;

/// <summary>Simulate spread, Bloom, Draw each shot and guns scaled to fit, as the phone's SpreadTest.</summary>
public class SpreadTests
{
    private static readonly float[] Identity = [1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f];

    [Fact]
    public void ShotsLandWithinTheSpread()
    {
        var k = new CrosshairKick.State();
        var beam = LaserTrace.Beam(Identity);
        float spread = 2f * MathF.PI / 180f;
        for (int i = 0; i < 50; i++)
        {
            k.Step(i * 100, true, CrosshairKick.Once, 100, pellets: 8);
            k.Step(i * 100 + 50, false, CrosshairKick.Once, 100, pellets: 8);
            Assert.Equal(8, k.Pellets);
            for (int p = 0; p < k.Pellets; p++)
            {
                var hit = LaserTrace.ShotPoint(Identity, beam, k.Offsets[p * 2] * spread, k.Offsets[p * 2 + 1] * spread);
                float off = MathF.Acos(Math.Clamp(hit.X * beam.X + hit.Y * beam.Y + hit.Z * beam.Z, -1f, 1f));
                Assert.True(off <= spread * 1.001f);
            }
        }
        Assert.Equal(50, k.Shots);
    }

    [Fact]
    public void BloomGrowsInARowAndSettlesAfterLettingGo()
    {
        var k = new CrosshairKick.State();
        for (long t = 0; t <= 1500; t += 10) k.Step(t, true, CrosshairKick.Auto, 100);
        Assert.Equal(2f, k.Bloom, 0.001f);
        k.Step(1600, false, CrosshairKick.Auto, 100);
        k.Step(2200, true, CrosshairKick.Auto, 100);
        Assert.Equal(1f, k.Bloom, 0.001f);
    }

    [Fact]
    public void TurnTowardMovesTheBeamOntoTheShot()
    {
        var beam = LaserTrace.Beam(Identity);
        var to = LaserTrace.ShotPoint(Identity, beam, 0.05f, -0.03f);
        var m = LaserTrace.TurnToward(Identity, beam, to, 1f);
        var b = LaserTrace.Beam(m);
        Assert.Equal(to.X, b.X, 0.0005f);
        Assert.Equal(to.Y, b.Y, 0.0005f);
        Assert.Equal(to.Z, b.Z, 0.0005f);
    }

    [Fact]
    public void HitsStayUntilCleared()
    {
        var t = new LaserTrace();
        t.AddHit(0f, 0f, -2f, 0.01f);
        t.AddHit(0.1f, 0f, -1f, 0.01f);
        Assert.Equal(2, t.HitCount);
        Assert.Equal(-1f, t.Hits[2], 0.0001f);
        t.Clear();
        Assert.Equal(0, t.HitCount);
    }

    [Fact]
    public void GunsAreScaledToTheRiflesLength()
    {
        Assert.Equal(1f, Guns.AR.Fit, 0.0001f);
        Assert.True(Guns.Pistol.Fit > 1.3f);
        foreach (var g in Guns.All)
        {
            var muzzle = LaserTrace.RifleAt(0f, 0f, g.MuzzleZ, 0f, 0f, false, g);
            Assert.True(muzzle.Z > -1.1f && muzzle.Z < -0.3f, g.Name);
        }
    }

    [Fact]
    public void HipFiredGunFitsItsFrame()
    {
        var m = LaserTrace.Aim(Identity);
        foreach (var g in Guns.All)
        {
            float k = LaserTrace.GunFrameScale(m, true, true, g, 1f, 1f);
            Assert.True(k > 0.3f && k <= 1f, g.Name);
            float e = 0f;
            foreach (var f in LaserTrace.RifleFaces(m, true, null, true, 0f, 1f, g, 0f, false, 1f))
                foreach (var p in f.Corners) e = Math.Max(e, MathF.Sqrt(p.X * p.X + p.Y * p.Y));
            Assert.True(e * k <= LaserTrace.GunFrame + 1e-4f, g.Name);
        }
        Assert.Equal(1f, LaserTrace.GunFrameScale(m, true, false, Guns.Pistol, 0f, 0f));
    }
}
