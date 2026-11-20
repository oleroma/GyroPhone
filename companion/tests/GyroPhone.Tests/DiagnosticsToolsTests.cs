using GyroPhone.Host.Perf;

namespace GyroPhone.Tests;

/// <summary>Over the floor, the setup snapshot and the floor book, and the run numbers built from them.</summary>
public class DiagnosticsToolsTests
{
    private static int[] Over(params (int Us, int Count)[] packets)
    {
        var h = new int[OverFloor.Buckets];
        foreach (var (us, n) in packets) h[OverFloor.Bucket(us)] += n;
        return h;
    }

    [Fact]
    public void OverTheFloorPoolsEveryPacket()
    {
        Assert.Equal(0, OverFloor.Bucket(0));
        Assert.Equal(1, OverFloor.Bucket(100));
        Assert.Equal(OverFloor.Buckets - 1, OverFloor.Bucket(1_000_000));
        var all = new long[OverFloor.Buckets];
        // A still second (50 packets, all slow) and a moving one (1000 fast): pooled, the fast ones decide the middle.
        OverFloor.Add(all, Over((5_000, 50)));
        OverFloor.Add(all, Over((50, 990), (1_200, 10)));
        Assert.InRange(OverFloor.Pct(all, 0.5), 0, 0.1);
        Assert.InRange(OverFloor.Pct(all, 0.99), 4.9, 7.5);
        Assert.Equal(60, OverFloor.AtLeast(all, 1_000));
        Assert.Equal(-1, OverFloor.Pct(new long[OverFloor.Buckets], 0.5));
        Assert.Equal("–", OverFloor.Text(new long[OverFloor.Buckets]));
    }

    [Fact]
    public void RunNumbersComeFromTheSecondsPacketWeighted()
    {
        var link = new List<LinkSecond>
        {
            new(1_000_000, 1000, 0, 0, 0, 0, 0) { FloorMs = 0.5f, Moving = 900, Over = Over((50, 1000)), HandOffMaxMs = 0.4f, SlowHandOffs = 0, StallMaxMs = 0.3f },
            new(2_000_000, 50, 0, 1, 20, 0, 0) { FloorMs = 0.75f, Moving = 0, Over = Over((50, 40), (20_000, 10)), HandOffMaxMs = 3f, SlowHandOffs = 2, LockWaitMs = 1f, StallMaxMs = 4f, Stalls = 3, Air = 1, Late3 = 4 },
            new(3_000_000, 1000, 0, 0, 0, 0, 0) { FloorMs = 0.5f, Moving = 1000, Over = Over((50, 990), (2_500, 10)) },
        };
        var run = new PerfRun("t", "", DateTime.Now, 3, "", 0, 0, 0, 0, 0, 0, 1, 20, 0, 0, 0, 0, 0);
        run = PerfRecording.WithLinkNumbers(run, link, []);
        Assert.Equal(0.5, run.FloorMs, 3);
        Assert.Equal(2050, run.Packets);
        Assert.InRange(run.OverP50Ms, 0, 0.1);
        Assert.InRange(run.MovingOverP99Ms, 0, 3);
        Assert.True(run.StillOverP99Ms >= 15, $"still {run.StillOverP99Ms}");
        Assert.Equal((3.0, 2, 1.0), (run.HandOffMaxMs, run.SlowHandOffs, run.LockWaitMaxMs));
        Assert.Equal((4.0, 3, 1, 4), (run.StallMaxMs, run.Stalls, run.HeldAir, run.Late3));
    }

    [Fact]
    public void SetupChangesLeaveOutWhatMovesAllTheTime()
    {
        var before = new List<SetupItem> { new("pc wifi", "5 GHz ch 48"), new("phone battery", "80%"), new("pc suspects", "none") };
        var after = new List<SetupItem> { new("pc wifi", "5 GHz ch 100"), new("phone battery", "79%"), new("pc suspects", "OBS"), new("phone charging", "yes") };
        Assert.Equal(["pc wifi 5 GHz ch 48 → 5 GHz ch 100", "pc suspects none → OBS", "phone charging yes (new)"], SetupSnapshot.Changes(before, after));
        Assert.Equal("pc wifi 5 GHz ch 48 · phone battery 80% · pc suspects none", SetupSnapshot.Line(before));
    }

    [Fact]
    public void ThePhonesSetupIsNamed()
    {
        var p = new PhoneSecond(0, 0, 0, 0, 0, 0, 0, 0, 0) { UsbVendor = 0x0bda, UsbProduct = 0x8156, SetupFlags = 1 | 4, Battery = 77, TempDeciC = 315, Thermal = 1, WifiStandard = 0 };
        var line = SetupSnapshot.Line(SetupSnapshot.Phone(p));
        Assert.Contains("phone usb adapter Realtek RTL8156 (2.5 Gb) 0bda:8156", line);
        Assert.Contains("phone charging yes", line);
        Assert.Contains("phone thermal light", line);
        Assert.Contains("phone temp 31.5 °C", line);
    }

    [Fact]
    public void AFloorShiftOnTheSameSetupIsFlagged()
    {
        string dir = Path.Combine(Path.GetTempPath(), "gp-floors-" + Guid.NewGuid().ToString("N"));
        try
        {
            var book = new FloorBook(dir);
            var setup = new List<SetupItem> { new("path", "Wired (USB)"), new("phone usb adapter", "x") };
            string key = SetupSnapshot.LinkKey(setup);
            Assert.Null(book.CheckAndKeep(key, 0.5, DateTime.Now, "first"));
            Assert.Null(book.CheckAndKeep(key, 0.55, DateTime.Now, "same"));
            Assert.Contains("0.55 ms on the same setup", new FloorBook(dir).CheckAndKeep(key, 0.8, DateTime.Now, "shifted"));
            // Another setup has its own floor.
            Assert.Null(book.CheckAndKeep(SetupSnapshot.LinkKey([new("path", "Router")]), 2.0, DateTime.Now, "router"));
        }
        finally
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
        }
    }
}
