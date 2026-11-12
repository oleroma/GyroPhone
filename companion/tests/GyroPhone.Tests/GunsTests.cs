using GyroPhone.Host;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tests;

/// <summary>The pointer's guns, as the phone's GunsTest.</summary>
public class GunsTests
{
    private static readonly float[] Identity = [1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f];

    [Fact]
    public void EachGunsMuzzleIsAheadOfItsModel()
    {
        foreach (var g in Guns.All)
        {
            float front = float.MaxValue;
            for (int o = 0; o < g.Boxes.Length; o += 6) front = Math.Min(front, g.Boxes[o + 2] - g.Boxes[o + 5]);
            Assert.True(g.MuzzleZ <= front + 1e-4f, g.Name);
            Assert.True(g.MuzzleZ >= -1.05f, g.Name);
        }
    }

    [Fact]
    public void RecoilRisesToFullAndSettles()
    {
        foreach (var g in Guns.All)
        {
            Assert.Equal(1f, g.Recoil.At(g.Recoil.RiseMs, 0f), 0.001f);
            Assert.Equal(0f, g.Recoil.At(g.Recoil.LengthMs, 0f));
        }
        Assert.True(Guns.Sniper.Recoil.Back > Guns.SMG.Recoil.Back);
        Assert.True(Guns.Sniper.Recoil.LengthMs > Guns.SMG.Recoil.LengthMs);
    }

    [Fact]
    public void FlashLengthFollowsCalibre()
    {
        Assert.True(Guns.Pistol.Flash < Guns.SMG.Flash);
        Assert.True(Guns.SMG.Flash < Guns.AR.Flash);
        Assert.True(Guns.AR.Flash < Guns.Sniper.Flash);
        Assert.Equal(Guns.All.Max(g => g.Bore), Guns.Shotgun.Bore);
    }

    [Fact]
    public void EachGunKeepsItsOwnFireRate()
    {
        var o = new OverlaySettings();
        Assert.Equal(600, o.RpmOf(Guns.AR));
        Assert.Equal(800, o.RpmOf(Guns.SMG));
        o.GunRpm["smg"] = 900;
        Assert.Equal(900, o.RpmOf(Guns.SMG));
        Assert.Equal(600, o.RpmOf(Guns.AR));
    }

    [Fact]
    public void RoundScopeGrowsInWhileAimingButNotOnThePistol()
    {
        int bare = LaserTrace.RifleFaces(Identity, true, null, gun: Guns.AR).Count;
        int scoped = LaserTrace.RifleFaces(Identity, true, null, aim: 1f, gun: Guns.AR).Count;
        Assert.True(scoped > bare + 6);
        Assert.Equal(bare, LaserTrace.RifleFaces(Identity, true, null, aim: 1f, gun: Guns.AR, scope: false).Count);
        Assert.Equal(LaserTrace.RifleFaces(Identity, true, null, gun: Guns.Pistol).Count,
            LaserTrace.RifleFaces(Identity, true, null, aim: 1f, gun: Guns.Pistol).Count);
    }

    [Fact]
    public void LoweredPitchesTheMuzzleDown()
    {
        var up = LaserTrace.MuzzleAt(0f, 0f, Guns.AR);
        var down = LaserTrace.MuzzleAt(0f, 0f, Guns.AR, 1f);
        Assert.True(down.Y < up.Y - 0.2f);
    }
}
