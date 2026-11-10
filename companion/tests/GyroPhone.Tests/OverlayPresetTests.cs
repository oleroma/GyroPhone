using GyroPhone.Host.Settings;

namespace GyroPhone.Tests;

public sealed class OverlayPresetTests
{
    [Fact]
    public void CopyKeepsEverySettingUnderANewId()
    {
        var o = new OverlaySettings { Kind = OverlayKind.Laser, Label = "Rifle", Width = 333, MotionScale = 2f };
        o.Colors["beam"] = 0x112233;
        var c = o.Copy();
        Assert.NotEqual(o.Id, c.Id);
        Assert.Equal(OverlayKind.Laser, c.Kind);
        Assert.Equal("Rifle", c.Label);
        Assert.Equal(333, c.Width);
        Assert.Equal(2f, c.MotionScale);
        Assert.Equal(0x112233, c.Colors["beam"]);
        // A deep copy: changing it leaves the original alone.
        c.Colors["beam"] = 1;
        Assert.Equal(0x112233, o.Colors["beam"]);
    }

    [Fact]
    public void PresetsSurviveTheSettingsFile()
    {
        var s = new HostSettings { HostId = new byte[16], HostName = "PC" };
        s.OverlayPresets.Add(new OverlaySettings { Kind = OverlayKind.Disk, Label = "Bubble" });
        s.Normalize();
        var back = System.Text.Json.JsonSerializer.Deserialize(SettingsStore.Serialize(s), HostSettingsJson.Default.HostSettings)!;
        back.Normalize();
        Assert.Single(back.OverlayPresets);
        Assert.Equal("Bubble", back.OverlayPresets[0].Label);
        Assert.Equal(OverlayKind.Disk, back.OverlayPresets[0].Kind);
    }
}
