using System.Text.Json;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

/// <summary>Every value in wire-v1.json must be reproduced byte for byte (SPEC 1.10, 5).</summary>
public class GoldenVectorTests
{
    private static readonly JsonElement P = Vectors.Pairing;
    private static readonly JsonElement S = Vectors.Session;

    [Fact]
    public void PairingMathMatches()
    {
        using var phone = P256.FromPrivateScalar(P.Bytes("phonePrivD"));
        using var host = P256.FromPrivateScalar(P.Bytes("hostPrivD"));
        Assert.Equal(P.Str("pkP"), Hex.S(phone.PublicKey));
        Assert.Equal(P.Str("pkH"), Hex.S(host.PublicKey));
        var z = phone.Ecdh(host.PublicKey);
        Assert.Equal(P.Str("ecdhZ"), Hex.S(z));
        Assert.Equal(P.Str("ecdhZ"), Hex.S(host.Ecdh(phone.PublicKey)));

        Assert.Equal(P.Str("commit"), Hex.S(PairingMath.Commit(phone.PublicKey, P.Bytes("nP"))));
        var th = PairingMath.Th(P.Bytes("phoneId"), P.Bytes("hostId"), phone.PublicKey, host.PublicKey,
            P.Bytes("nP"), P.Bytes("nH"), (uint)P.Long("attemptId"));
        Assert.Equal(P.Str("th"), Hex.S(th));
        Assert.Equal(P.Str("sas"), PairingMath.SasDigits(PairingMath.Sas(th)));
        Assert.Equal("400 588", PairingMath.SasDisplay(PairingMath.Sas(th)));
        var pairKey = PairingMath.PairKey(z, th);
        Assert.Equal(P.Str("pairKey"), Hex.S(pairKey));
        Assert.Equal(P.Str("confirmP"), Hex.S(PairingMath.ConfirmP(pairKey, th)));
        Assert.Equal(P.Str("confirmH"), Hex.S(PairingMath.ConfirmH(pairKey, th)));
    }

    [Fact]
    public void SessionKeysMatch()
    {
        var (kPH, kHP) = SessionKeys.Derive(S.Bytes("pairKey"), S.Bytes("nP"), S.Bytes("nH"), (uint)S.Long("sessionId"));
        Assert.Equal(S.Str("kPH"), Hex.S(kPH));
        Assert.Equal(S.Str("kHP"), Hex.S(kHP));
        Assert.Equal(S.Str("okm"), Hex.S(kPH) + Hex.S(kHP));
    }

    public static IEnumerable<object[]> PacketNames() =>
        Vectors.Root.GetProperty("packets").EnumerateArray().Select(p => new object[] { p.Str("name") });

    [Theory]
    [MemberData(nameof(PacketNames))]
    public void PacketEncodesAndDecodes(string name)
    {
        var v = Vectors.Packet(name);
        var f = v.GetProperty("fields");
        var hex = v.Str("hex");
        var raw = Hex.B(hex);
        Assert.Equal((int)v.Long("len"), raw.Length);
        Assert.Equal(PacketType.Size(raw[3]), raw.Length);
        var buf = new byte[Wire.MaxDatagram];
        var pairKey = S.Bytes("pairKey");

        switch (name)
        {
            case "DISCOVER":
            {
                var p = new Discover((uint)f.Long("reqId"), f.Bytes("phoneId"), (ushort)f.Long("flags"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodeDiscover(p, buf))));
                Assert.True(WireCodec.TryDecodeDiscover(raw, out var d));
                Assert.Equal(p.ReqId, d.ReqId);
                Assert.Equal(p.PhoneId, d.PhoneId);
                Assert.Equal(p.Flags, d.Flags);
                break;
            }
            case "ANNOUNCE":
            {
                var p = new Announce((uint)f.Long("reqId"), f.Bytes("hostId"), (ushort)f.Long("flags"), f.Str("name"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodeAnnounce(p, buf))));
                Assert.True(WireCodec.TryDecodeAnnounce(raw, out var d));
                Assert.Equal((p.ReqId, p.Flags, p.Name), (d.ReqId, d.Flags, d.Name));
                Assert.Equal(p.HostId, d.HostId);
                break;
            }
            case "PAIR_START":
            {
                var p = new PairStart(f.Bytes("phoneId"), f.Bytes("pkP"), f.Bytes("commit"), f.Str("name"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodePairStart(p, buf))));
                Assert.True(WireCodec.TryDecodePairStart(raw, out var d));
                Assert.Equal(p.PhoneId, d.PhoneId);
                Assert.Equal(p.PkP, d.PkP);
                Assert.Equal(p.Commit, d.Commit);
                Assert.Equal(p.Name, d.Name);
                break;
            }
            case "PAIR_HOST":
            {
                var p = new PairHost((uint)f.Long("attemptId"), f.Bytes("hostId"), f.Bytes("pkH"), f.Bytes("nH"), f.Str("name"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodePairHost(p, buf))));
                Assert.True(WireCodec.TryDecodePairHost(raw, out var d));
                Assert.Equal(p.AttemptId, d.AttemptId);
                Assert.Equal(p.HostId, d.HostId);
                Assert.Equal(p.PkH, d.PkH);
                Assert.Equal(p.NH, d.NH);
                Assert.Equal(p.Name, d.Name);
                break;
            }
            case "PAIR_REVEAL":
            {
                var p = new PairReveal((uint)f.Long("attemptId"), f.Bytes("phoneId"), f.Bytes("nP"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodePairReveal(p, buf))));
                Assert.True(WireCodec.TryDecodePairReveal(raw, out var d));
                Assert.Equal(p.AttemptId, d.AttemptId);
                Assert.Equal(p.PhoneId, d.PhoneId);
                Assert.Equal(p.NP, d.NP);
                break;
            }
            case "PAIR_ACK":
            {
                var p = new PairAck((uint)f.Long("attemptId"), (byte)f.Long("status"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodePairAck(p, buf))));
                Assert.True(WireCodec.TryDecodePairAck(raw, out var d));
                Assert.Equal(p, d);
                break;
            }
            case "PAIR_CONFIRM":
            {
                var p = new PairConfirm((uint)f.Long("attemptId"), f.Bytes("phoneId"), f.Bytes("confirmP"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodePairConfirm(p, buf))));
                Assert.True(WireCodec.TryDecodePairConfirm(raw, out var d));
                Assert.Equal(p.AttemptId, d.AttemptId);
                Assert.Equal(p.PhoneId, d.PhoneId);
                Assert.Equal(p.ConfirmP, d.ConfirmP);
                break;
            }
            case "PAIR_DONE":
            {
                var p = new PairDone((uint)f.Long("attemptId"), (byte)f.Long("status"), f.Bytes("confirmH"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodePairDone(p, buf))));
                Assert.True(WireCodec.TryDecodePairDone(raw, out var d));
                Assert.Equal((p.AttemptId, p.Status), (d.AttemptId, d.Status));
                Assert.Equal(p.ConfirmH, d.ConfirmH);
                break;
            }
            case "HELLO":
            {
                var p = new Hello(f.Bytes("phoneId"), f.Bytes("nP"), (uint)f.Long("caps"), (ushort)f.Long("maxRateHz"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodeHello(p, pairKey, buf))));
                Assert.True(WireCodec.TryDecodeHello(raw, out var d));
                Assert.True(WireCodec.VerifyMac(pairKey, raw));
                Assert.Equal(p.PhoneId, d.PhoneId);
                Assert.Equal(p.NP, d.NP);
                Assert.Equal((p.Caps, p.MaxRateHz), (d.Caps, d.MaxRateHz));
                break;
            }
            case "WELCOME":
            {
                var p = new Welcome((uint)f.Long("sessionId"), f.Bytes("hostId"), f.Bytes("nP"), f.Bytes("nH"),
                    (byte)f.Long("outputMode"), (byte)f.Long("hostFlags"), (ushort)f.Long("keepaliveMs"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodeWelcome(p, pairKey, buf))));
                Assert.True(WireCodec.TryDecodeWelcome(raw, out var d));
                Assert.True(WireCodec.VerifyMac(pairKey, raw));
                Assert.Equal((p.SessionId, p.OutputMode, p.HostFlags, p.KeepaliveMs), (d.SessionId, d.OutputMode, d.HostFlags, d.KeepaliveMs));
                Assert.Equal(p.HostId, d.HostId);
                Assert.Equal(p.NP, d.NP);
                Assert.Equal(p.NH, d.NH);
                break;
            }
            case "ERROR":
            {
                var p = new ErrorPacket((ushort)f.Long("code"), (byte)f.Long("maxVersion"));
                Assert.Equal(hex, Hex.S(buf.AsSpan(0, WireCodec.EncodeError(p, buf))));
                Assert.True(WireCodec.TryDecodeError(raw, out var d));
                Assert.Equal(p, d);
                break;
            }
            default:
                SealedPacket(v, f, raw);
                break;
        }
    }

    private static void SealedPacket(JsonElement v, JsonElement f, byte[] raw)
    {
        string name = v.Str("name");
        bool p2h = v.Str("dir") == "P2H";
        uint dir = p2h ? Wire.DirPhoneToHost : Wire.DirHostToPhone;
        var key = S.Bytes(v.Str("key"));
        uint sid = (uint)v.Long("sessionId");
        uint seq = (uint)v.Long("seq");
        Assert.Equal(p2h ? "kPH" : "kHP", v.Str("key"));
        Assert.Equal(p2h, PacketType.IsPhoneToHostSealed(raw[3]));

        var nonce = new byte[12];
        Le.PutU32(nonce, 0, dir);
        Le.PutU32(nonce, 4, sid);
        Le.PutU32(nonce, 8, seq);
        Assert.Equal(v.Str("nonce"), Hex.S(nonce));

        // Plaintext from fields.
        var plain = new byte[PacketType.PlainSize(raw[3])];
        if (name.StartsWith("STATE"))
        {
            var fr = FrameFromFields(f);
            fr.Encode(plain);
        }
        else
        {
            switch (name)
            {
                case "PING": WireCodec.EncodePing(new Ping((ulong)f.Long("t1")), plain); break;
                case "LINK_INFO":
                    WireCodec.EncodeLinkInfo(new LinkInfo(f.Long("clockOffsetUs"), (uint)f.Long("rttMinUs"), (ushort)f.Long("sendRateHz"),
                        (sbyte)f.Long("rssiDbm"), (byte)f.Long("band"), (ushort)f.Long("linkMbps"), (ushort)f.Long("freqMhz"), (byte)f.Long("flags")), plain);
                    break;
                case "BYE":
                case "HOST_BYE":
                    WireCodec.EncodeReason((byte)f.Long("reason"), plain);
                    break;
                case "PONG":
                    WireCodec.EncodePong(new Pong((ulong)f.Long("t1"), (ulong)f.Long("t2"), (ulong)f.Long("t3")), plain);
                    break;
                case "HOST_STATUS":
                    WireCodec.EncodeHostStatus(new HostStatus((uint)f.Long("lastSeq"), (ushort)f.Long("rxPerSec"), (ushort)f.Long("lossPermille"),
                        (uint)f.Long("latP50Us"), (uint)f.Long("latP99Us"), (uint)f.Long("jitterUs"), (byte)f.Long("outputMode"), (byte)f.Long("flags")), plain);
                    break;
                case "RUMBLE":
                    WireCodec.EncodeRumble(new Rumble((byte)f.Long("large"), (byte)f.Long("small")), plain);
                    break;
                default:
                    Assert.Fail("no encoder for " + name);
                    break;
            }
        }
        Assert.Equal(v.Str("plaintext"), Hex.S(plain));

        using var sealer = new SessionCipher(key, dir);
        var packet = new byte[raw.Length];
        Assert.Equal(raw.Length, sealer.Seal(raw[3], sid, seq, plain, packet));
        Assert.Equal(v.Str("hex"), Hex.S(packet));

        using var opener = new SessionCipher(key, dir);
        var opened = new byte[plain.Length];
        Assert.True(opener.Open(raw, opened));
        Assert.Equal(plain, opened);

        // And back to fields.
        switch (name)
        {
            case var _ when name.StartsWith("STATE"):
                var fr = new StateFrame();
                fr.Decode(opened);
                var expect = FrameFromFields(f);
                var again = new byte[Wire.StatePlain];
                fr.Encode(again);
                Assert.Equal(plain, again);
                Assert.Equal((byte)f.Long("modifiers"), fr.Modifiers);
                Assert.Equal(expect.MouseTotalX, fr.MouseTotalX);
                break;
            case "PING": Assert.Equal((ulong)f.Long("t1"), WireCodec.DecodePing(opened).T1); break;
            case "LINK_INFO":
                var li = WireCodec.DecodeLinkInfo(opened);
                Assert.Equal((f.Long("clockOffsetUs"), (long)li.RssiDbm, (long)li.FreqMhz), (li.ClockOffsetUs, f.Long("rssiDbm"), f.Long("freqMhz")));
                break;
            case "PONG": Assert.Equal((ulong)f.Long("t3"), WireCodec.DecodePong(opened).T3); break;
            case "HOST_STATUS": Assert.Equal((uint)f.Long("jitterUs"), WireCodec.DecodeHostStatus(opened).JitterUs); break;
            case "RUMBLE": Assert.Equal(new Rumble(255, 64), WireCodec.DecodeRumble(opened)); break;
            default: Assert.Equal((byte)f.Long("reason"), opened[0]); break;
        }
    }

    internal static StateFrame FrameFromFields(JsonElement f)
    {
        var fr = new StateFrame
        {
            PhoneTimeUs = (uint)f.Long("phoneTimeUs"),
            Flags = (ushort)f.Long("flags"),
            Buttons = (ushort)f.Long("buttons"),
            Hat = (byte)f.Long("hat"),
            MouseButtons = (byte)f.Long("mouseButtons"),
            Lx = (short)f.Long("lx"),
            Ly = (short)f.Long("ly"),
            Rx = (short)f.Long("rx"),
            Ry = (short)f.Long("ry"),
            Lt = (ushort)f.Long("lt"),
            Rt = (ushort)f.Long("rt"),
            MouseTotalX = (int)f.Long("mouseTotalX"),
            MouseTotalY = (int)f.Long("mouseTotalY"),
            WheelTotal = (int)f.Long("wheelTotal"),
        };
        foreach (var k in f.GetProperty("keys").EnumerateArray()) fr.SetKey(k.GetInt32(), true);
        fr.Modifiers |= (byte)f.Long("modifiers");
        return fr;
    }

    [Fact]
    public void InvalidPacketsAreRejectedByCodecAndCipher()
    {
        using var opener = new SessionCipher(S.Bytes("kPH"), Wire.DirPhoneToHost);
        var plain = new byte[Wire.MaxDatagram];
        foreach (var inv in Vectors.Root.GetProperty("invalid").EnumerateArray())
        {
            var raw = inv.Bytes("hex");
            string name = inv.Str("name");
            bool frameOk = raw.Length >= Wire.Header && raw[0] == Wire.Magic0 && raw[1] == Wire.Magic1 &&
                           raw[2] == Wire.Version && PacketType.Size(raw[3]) == raw.Length;
            bool accepted = frameOk && (raw[3] == PacketType.Hello
                ? WireCodec.VerifyMac(S.Bytes("pairKey"), raw)
                : opener.Open(raw, plain));
            Assert.False(accepted, name);
        }
    }

    /// <summary>The engine itself, seeded so its sid and nH equal the vectors, must reproduce WELCOME and reject every invalid packet.</summary>
    [Fact]
    public void EngineReproducesWelcomeAndRejectsInvalid()
    {
        var sidBytes = new byte[4];
        Le.PutU32(sidBytes, 0, (uint)S.Long("sessionId"));
        byte[] Rand(int n) => n == 4 ? (byte[])sidBytes.Clone() : S.Bytes("nH");
        using var h = new Harness(random: Rand, pairDefault: false);
        h.AddPaired(P.Bytes("phoneId"), "OnePlus 15", S.Bytes("pairKey"));
        var engine = new Host.HostEngine(h.Settings, h.Pad, h.KeyMouse, h.Prompt, new PlainProtector(), h.Clock, h.Events, Rand);
        engine.AttachSender(h.Sender);
        var from = new System.Net.IPEndPoint(System.Net.IPAddress.Loopback, 5000).Serialize();

        engine.OnDatagram(Vectors.Packet("HELLO").Bytes("hex"), from, h.Clock.NowUs);
        Assert.Equal(Vectors.Packet("WELCOME").Str("hex"), Hex.S(h.Sender.Last));

        foreach (var inv in Vectors.Root.GetProperty("invalid").EnumerateArray())
        {
            h.Sender.Clear();
            engine.OnDatagram(inv.Bytes("hex"), from, h.Clock.NowUs);
            engine.Flush(h.Clock.NowUs);
            Assert.False(engine.Snapshot().Connected, inv.Str("name"));
            Assert.Equal(0, engine.Snapshot().Counters.States);
            // Only bad_version may get a (rate-limited, advisory) ERROR; nothing else is answered.
            if (inv.Str("name") == "bad_version")
                Assert.Equal("4750017f000000000000000001000100", Hex.S(h.Sender.Last));
            else
                Assert.Empty(h.Sender.Sent);
        }

        engine.OnDatagram(Vectors.Packet("STATE_busy").Bytes("hex"), from, h.Clock.NowUs);
        engine.Flush(h.Clock.NowUs);
        Assert.True(engine.Snapshot().Connected);
        Assert.Equal(1, engine.Snapshot().Counters.States);
    }

    [Fact]
    public void KeyboardUsagesAllMap()
    {
        var usages = Vectors.Root.GetProperty("keyboardUsages").EnumerateArray().Select(e => e.GetInt32()).ToList();
        Assert.Equal(116, usages.Count);
        foreach (var u in usages)
        {
            var sc = HidScanCodes.For(u);
            Assert.True(sc.IsMapped, $"usage 0x{u:X2} has no scan code");
            if (u == 0x48)
                Assert.Equal((ushort)0, sc.Scan);
            else
                Assert.Equal((ushort)0, sc.Vk);
        }
        var pause = HidScanCodes.For(0x48);
        Assert.Equal(InjectEvent.VkPause, pause.Vk);
        var e = InjectEvent.Key(0x48, pause, up: false);
        Assert.Equal(0u, e.Flags & InjectEvent.KeyScancode);
    }
}
