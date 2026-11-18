using GyroPhone.Host;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>The overlay side runs on its own thread (gp-overlay): gp-rx only copies the overlay bytes in.</summary>
public class OverlayWorkerTests
{
    private static bool WaitFor(Func<bool> done, int ms = 3000)
    {
        var until = Environment.TickCount64 + ms;
        while (Environment.TickCount64 < until)
        {
            if (done()) return true;
            Thread.Sleep(5);
        }
        return done();
    }

    [Fact]
    public void OnItsOwnThreadTheOverlaysStillTurn()
    {
        var h = new Harness();
        var shape = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Shown = true };
        h.Settings.Overlays.Add(shape);
        h.Overlays.Start();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Overlays.SetOverlay(true);
        h.Send(phone, phone.Sealed(PacketType.OverlayLayout, OverlayTests.Piece(1, 0, 1, Hex.B(OverlayTests.LayoutBlob))));
        h.Send(phone, phone.Sealed(PacketType.Overlay, Hex.B(OverlayTests.Frame)));
        var frame = new OverlayFrame();
        OverlayLayout? layout = null;
        Assert.True(WaitFor(() => h.Overlays.OverlaySnapshot(frame, out layout) >= 0 && layout != null));
        Assert.True(frame.IsPressed(0));
        Assert.Equal("Main", layout!.Workspace);
        var id = shape.Id;
        Assert.True(WaitFor(() => h.Overlays.OverlayPoseOf(id) != null));

        // Off: everything goes.
        h.Overlays.SetOverlay(false);
        Assert.True(WaitFor(() => h.Overlays.OverlaySnapshot(frame, out _) == -1));
    }

    [Fact]
    public void StartingAgainMarksSilenceOnTheFadesClock()
    {
        var h = new Harness();
        // Far from Environment.TickCount64: a stamp from another clock would fade at once.
        h.Clock.NowUs = Environment.TickCount64 * 1000 + 10_000_000_000;
        var shape = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Shown = true };
        h.Settings.Overlays.Add(shape);
        h.Settings.OverlayRestFade.On = true;
        h.Settings.OverlayRestFade.WhenSilent = true;
        h.Overlays.StartAgain();
        h.Overlays.Pump();
        Assert.False(h.Overlays.OverlayResting(shape.Id));
        h.Clock.AdvanceMs(h.Settings.OverlayRestFade.QuietMs);
        Assert.True(h.Overlays.OverlayResting(shape.Id));
    }

    [Fact]
    public void WindowsReadACopyThatAChangeSwaps()
    {
        var h = new Harness();
        var id = h.Overlays.AddOverlay(GyroPhone.Host.Settings.OverlayKind.Shape);
        var before = h.Overlays.WindowSettings(id)!;
        Assert.NotSame(h.Settings.Overlays.Find(o => o.Id == id), before);
        h.Overlays.UpdateOverlaySettings(id, o => o.Opacity = 0.5f);
        Assert.NotEqual(0.5f, before.Opacity);
        Assert.Equal(0.5f, h.Overlays.WindowSettings(id)!.Opacity);
        h.Overlays.RemoveOverlay(id);
        Assert.Null(h.Overlays.WindowSettings(id));
    }

    [Fact]
    public void AFullInboxDropsTheNewestAndALostMarkStartsAgain()
    {
        var inbox = new OverlayInbox();
        var bytes = new byte[Wire.OverlayPlain];
        for (int i = 0; i < OverlayInbox.Capacity; i++) Assert.True(inbox.Put(OverlayInbox.Kind.Overlay, bytes, i, i));
        Assert.False(inbox.Put(OverlayInbox.Kind.Overlay, bytes, 0, 0));
        inbox.Mark(OverlayInbox.Kind.Clear, 0, flag: true);
        Assert.Equal(2, inbox.Dropped);
        Assert.True(inbox.ClearLost());
        Assert.False(inbox.ClearLost());

        // Read in order.
        for (int i = 0; i < OverlayInbox.Capacity; i++)
        {
            Assert.True(inbox.TryPeek(out var s));
            Assert.Equal(i, s.RxUs);
            Assert.Equal(Wire.OverlayPlain, s.Data.Length);
            inbox.Release();
        }
        Assert.False(inbox.TryPeek(out _));
    }

    [Fact]
    public void DiagnosticsLogsEachMemoryCleanup()
    {
        var h = new Harness();
        h.Engine.SetDiagnostics(true);
        bool Logged() => h.Engine.Debug.Latest(10_000).Any(e => e.Text.StartsWith("memory cleanup ", StringComparison.Ordinal));
        Assert.True(WaitFor(() =>
        {
            GC.Collect();
            return Logged();
        }, 10_000));
        var line = h.Engine.Debug.Latest(10_000).First(e => e.Text.StartsWith("memory cleanup ", StringComparison.Ordinal)).Text;
        Assert.Matches(@"^memory cleanup \d+\.\d\d ms: gen \d, [a-z ]+, why: .+, heap \d+ MB, started by .+$", line);
        h.Engine.SetDiagnostics(false);
    }
}
