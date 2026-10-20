using GyroPhone.Host;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public sealed class HeldKeysMarkerTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "gp-held-" + Guid.NewGuid().ToString("N"));
    private string PathName => Path.Combine(_dir, "held.json");

    [Fact]
    public void WrittenWhileHeldDeletedWhenReleased()
    {
        var h = new Harness();
        var marker = new HeldKeysMarker(PathName);
        var phone = h.NewPhone();
        h.Connect(phone);
        var buf = new byte[32];

        int v = h.Engine.HeldSnapshot(buf, out byte mb);
        marker.Update(v, buf, mb);
        Assert.False(File.Exists(PathName));

        h.Send(phone, phone.State(f => { f.SetKey(0x1A, true); f.MouseButtons = 1; }));
        v = h.Engine.HeldSnapshot(buf, out mb);
        marker.Update(v, buf, mb);
        Assert.True(File.Exists(PathName));

        // Mouse movement alone does not change the held set.
        h.Send(phone, phone.State(f => f.MouseTotalX += 10));
        Assert.Equal(v, h.Engine.HeldSnapshot(buf, out _));

        h.Send(phone, phone.State(f => { f.SetKey(0x1A, false); f.MouseButtons = 0; }));
        v = h.Engine.HeldSnapshot(buf, out mb);
        marker.Update(v, buf, mb);
        Assert.False(File.Exists(PathName));
    }

    [Fact]
    public void RecoverReleasesAndDeletes()
    {
        var marker = new HeldKeysMarker(PathName);
        var keys = new byte[32];
        keys[0x1A >> 3] |= 1 << (0x1A & 7);
        keys[StateFrame.ModifierByte] = 0x01;
        marker.Update(1, keys, 0b10001);
        Assert.True(File.Exists(PathName));

        var sink = new FakeKeyMouse();
        int n = HeldKeysMarker.Recover(PathName, sink);
        Assert.Equal(4, n);
        var e = sink.All.ToArray();
        Assert.Equal(0x1A, e[0].Usage);
        Assert.True(e[0].IsKeyUp);
        Assert.Equal(0xE0, e[1].Usage);
        Assert.True(e[1].IsKeyUp);
        Assert.Equal(InjectEvent.MouseLeftUp, e[2].Flags);
        Assert.Equal((InjectEvent.MouseXUp, InjectEvent.XButton2), (e[3].Flags, e[3].MouseData));
        Assert.False(File.Exists(PathName));
        Assert.Equal(0, HeldKeysMarker.Recover(PathName, sink));
    }

    [Fact]
    public void CorruptMarkerIsDeleted()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(PathName, "{not json");
        Assert.Equal(0, HeldKeysMarker.Recover(PathName, new FakeKeyMouse()));
        Assert.False(File.Exists(PathName));
    }

    [Fact]
    public void WatchdogWritesMarkerOffRxThread()
    {
        var h = new Harness();
        var marker = new HeldKeysMarker(PathName);
        using var wd = new Watchdog(h.Engine, h.Clock, marker);
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.State(f => f.SetKey(0x2C, true)));
        Assert.False(File.Exists(PathName)); // rx never writes it
        wd.Tick();
        Assert.True(File.Exists(PathName));
        h.Clock.AdvanceMs(300);
        wd.Tick(); // silence release
        wd.Tick();
        Assert.False(File.Exists(PathName));
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
