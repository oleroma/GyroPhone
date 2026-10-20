using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public sealed class SettingsAndStatsTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "gp-settings-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void SettingsRoundTripAndAtomicSave()
    {
        var store = new SettingsStore(Path.Combine(_dir, "settings.json"));
        var s = store.Load("PC");
        Assert.Equal(16, s.HostId.Length);
        s.OutputMode = OutputMode.DualShock4;
        s.PairedPhones.Add(new PairedPhone { PhoneId = Hex.Range(0, 16), Name = "OnePlus 15", ProtectedKey = [1, 2, 3] });
        store.Save(s);
        store.Save(s);
        Assert.True(File.Exists(store.Path + ".bak"));
        Assert.Contains("\"DualShock4\"", File.ReadAllText(store.Path));
        var back = new SettingsStore(store.Path).Load("other");
        Assert.Equal(s.HostId, back.HostId);
        Assert.Equal(OutputMode.DualShock4, back.OutputMode);
        Assert.Equal("PC", back.HostName);
        Assert.Equal([1, 2, 3], Assert.Single(back.PairedPhones).ProtectedKey);
    }

    [Fact]
    public void CorruptSettingsMovedAsideAndDefaultsUsed()
    {
        Directory.CreateDirectory(_dir);
        var path = Path.Combine(_dir, "settings.json");
        File.WriteAllText(path, "{ broken");
        var s = new SettingsStore(path).Load("PC");
        Assert.Empty(s.PairedPhones);
        Assert.Single(Directory.GetFiles(_dir, "settings.json.bad-*"));
        Assert.True(File.Exists(path));
    }

    [Fact]
    public void EnginePersistsPairingViaWatchdog()
    {
        var store = new SettingsStore(Path.Combine(_dir, "settings.json"));
        var h = new Harness();
        using var wd = new Watchdog(h.Engine, h.Clock, store: store);
        Assert.True(h.Engine.Forget(Harness.Phone1Id));
        wd.Tick();
        Assert.Empty(store.Load("x").PairedPhones);
    }

    [Fact]
    public void LatencyUnwrapsPhoneTime()
    {
        // phone clock = host - 5000 us; packet took 1500 us; phone 32-bit time wrapped.
        long offset = 5000;
        long host = 0x1_0000_0100L + offset + 1500;
        uint phone32 = 0x100;
        Assert.Equal(1500, LinkStats.LatencyUs(phone32, host, offset));
        Assert.Equal(1500, LinkStats.LatencyUs(0xFFFFFF00, 0xFFFFFF00L + offset + 1500, offset));
    }

    [Fact]
    public void StatsWindow()
    {
        var s = new LinkStats();
        s.OnLinkInfo(new LinkInfo(0, 0, 1000, 0, 5, 0, 0, 0));
        uint seq = 0;
        for (int i = 0; i < 100; i++)
        {
            seq += i % 10 == 9 ? 2u : 1u; // one gap per ten
            s.OnSealed(seq);
            s.OnState(seq * 1000, seq * 1000 + 2000);
        }
        s.Roll();
        Assert.Equal(100, s.RxPerSec);
        Assert.InRange(s.LossPermille, 80, 100);
        Assert.Equal(2025u, s.LatP50Us);
        Assert.Equal(2025u, s.LatP99Us);
        s.Roll();
        Assert.Equal(HostStatus.Unknown, s.LatP50Us);
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
