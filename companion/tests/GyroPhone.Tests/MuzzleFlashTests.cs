using GyroPhone.Host;

namespace GyroPhone.Tests;

/// <summary>The muzzle flash and the rifle's kick, as the phone's MuzzleFlashTest.</summary>
public class MuzzleFlashTests
{
    private const int Hot = unchecked((int)0xFFFFE04A), End = unchecked((int)0xFFFF7A1A), Base = unchecked((int)0xFF2080FF);

    [Fact]
    public void BurstsWhiteThenYellowThenOrangeThenBack()
    {
        Assert.Equal(unchecked((int)0xFFFFFFFF), MuzzleFlash.Color(0f, Hot, End, Base));
        Assert.Equal(Hot, MuzzleFlash.Color(MuzzleFlash.HotMs, Hot, End, Base));
        Assert.Equal(End, MuzzleFlash.Color(MuzzleFlash.EndMs, Hot, End, Base));
        Assert.Equal(Base, MuzzleFlash.Color(MuzzleFlash.LengthMs, Hot, End, Base));
        Assert.Equal(Base, MuzzleFlash.Color(-1f, Hot, End, Base));
        Assert.Equal(1f, MuzzleFlash.Glow(0f));
        Assert.InRange(MuzzleFlash.Glow(100f), 0.01f, 0.5f);
        Assert.Equal(0f, MuzzleFlash.Glow(MuzzleFlash.LengthMs));
    }

    [Fact]
    public void KickOffStillCountsShotsForTheFlash()
    {
        var s = new CrosshairKick.State();
        Assert.True(s.Step(10, true, CrosshairKick.Off, 100, flash: true));
        Assert.Equal(10L, s.ShotAt);
        s.Step(30, true, CrosshairKick.Off, 100, flash: true);
        Assert.Equal(0f, s.Value);
    }

    [Fact]
    public void AutoRifleFlashesEachShot()
    {
        var s = new CrosshairKick.State();
        s.Step(0, true, CrosshairKick.Auto, 100, flash: true);
        s.Step(104, true, CrosshairKick.Auto, 100, flash: true);
        Assert.Equal(100L, s.ShotAt);
    }

    [Fact]
    public void RifleKicksBackWithItsMuzzleUp()
    {
        var rest = LaserTrace.MuzzleAt(0f);
        var kicked = LaserTrace.MuzzleAt(0f, 1f);
        Assert.True(kicked.Y > rest.Y + 0.1f);
        Assert.True(kicked.Z > rest.Z);
        Assert.Equal(LaserTrace.Muzzle.Z, rest.Z, 0.000001f);
    }
}
