using System.Net;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public class AmplificationTests
{
    /// <summary>Every unauthenticated reply is no larger than the request that caused it.</summary>
    [Fact]
    public void UnauthenticatedRepliesNeverExceedRequest()
    {
        var h = new Harness(pairDefault: false);
        var pairs = new List<(int Req, int Rep, string What)>();
        byte[]? Send(FakePhone p, byte[] d, string what)
        {
            var r = h.Send(p, d);
            if (r != null) pairs.Add((d.Length, r.Length, what));
            return r;
        }

        var a = new FakePhone(Hex.Range(0x60, 16), "A", "192.168.1.60");
        var b = new FakePhone(Hex.Range(0x70, 16), "B", "192.168.1.70");
        Send(a, a.Discover(), "discover");
        Send(a, a.PairStart(), "start closed");
        h.Engine.OpenPairingWindow();
        Assert.True(a.OnPairHost(Send(a, a.PairStart(), "start ok")!));
        Send(b, b.PairStart(), "start busy");
        Send(a, a.PairConfirm(), "confirm before reveal");
        Send(a, a.PairReveal(), "reveal ok");
        Send(a, a.PairReveal(), "reveal dup");
        Send(a, a.PairConfirm(), "confirm pending");
        h.Engine.AnswerPair(a.AttemptId, false);
        Send(a, a.PairConfirm(), "confirm denied");
        Send(b, b.PairStart(), "start cooldown");
        h.Clock.AdvanceMs(Wire.PairCooldownMs + 1);
        Assert.True(b.OnPairHost(Send(b, b.PairStart(), "start ok 2")!));
        Send(b, b.PairReveal(Hex.Range(0, 16)), "reveal bad commit");
        Send(b, b.PairReveal(), "reveal after fail");

        Assert.True(pairs.Count >= 12);
        Assert.All(pairs, p => Assert.True(p.Rep <= p.Req, $"{p.What}: {p.Rep} > {p.Req}"));
    }

    [Fact]
    public void WrongSizesAndShortPacketsGetNothing()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        var d = phone.Discover();
        Assert.Null(h.Send(phone, d[..63]));
        Assert.Null(h.Send(phone, [.. d, 0]));
        // bad version but shorter than 16 bytes: no ERROR
        var shortBad = new byte[12];
        WireCodec.WriteHeader(shortBad, PacketType.Discover, 0, 0);
        shortBad[2] = 2;
        Assert.Null(h.Send(phone, shortBad));
        // unknown type
        var unknown = new byte[64];
        WireCodec.WriteHeader(unknown, 0x55, 0, 0);
        Assert.Null(h.Send(phone, unknown));
        // host-to-phone types are not accepted from the network
        Assert.Null(h.Send(phone, Vectors.Packet("ANNOUNCE").Bytes("hex")));
    }

    [Fact]
    public void ErrorRateLimits()
    {
        var h = new Harness();
        var bad = Vectors.Root.GetProperty("invalid").EnumerateArray().First(e => e.Str("name") == "bad_version").Bytes("hex");
        var one = new IPEndPoint(IPAddress.Parse("10.0.0.1"), 1).Serialize();
        for (int i = 0; i < 100; i++) h.Engine.OnDatagram(bad, one, h.Clock.NowUs);
        Assert.Equal(Wire.ErrorRatePerIp, h.Sender.Sent.Count);

        h.Clock.AdvanceMs(1000);
        h.Sender.Clear();
        for (int ip = 0; ip < 20; ip++)
        {
            var from = new IPEndPoint(IPAddress.Parse($"10.0.1.{ip}"), 1).Serialize();
            for (int i = 0; i < 10; i++) h.Engine.OnDatagram(bad, from, h.Clock.NowUs);
        }
        Assert.Equal(Wire.ErrorRateGlobal, h.Sender.Sent.Count);
        Assert.All(h.Sender.Sent, s => Assert.Equal(Wire.ErrorSize, s.Data.Length));
    }
}
