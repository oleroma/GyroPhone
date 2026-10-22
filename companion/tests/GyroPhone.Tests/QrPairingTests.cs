using System.Text.Json;
using GyroPhone.Host;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;

namespace GyroPhone.Tests;

/// <summary>QR code pairing: the shared vectors (qr-v1.json) and the engine side.</summary>
public class QrPairingTests
{
    private static readonly JsonElement K = Vectors.Key;
    private static readonly JsonElement Q = Vectors.Qr;
    private static readonly uint ReqId = (uint)Q.Long("reqId");

    [Fact]
    public void PayloadAndPacketsMatchVectors()
    {
        var token = Q.Bytes("token");
        var addresses = Q.GetProperty("addresses").EnumerateArray().Select(a => a.GetString()!).ToList();
        Assert.Equal(Q.Str("payload"), QrPairing.Format(new QrInvite(K.Bytes("pkH"), token, Q.Str("hostName"), addresses)));
        Assert.Equal(Q.Str("tokenId"), Hex.S(QrPairing.TokenId(token)));

        var b = new byte[Wire.MaxDatagram];
        int n = WireCodec.EncodeKeyEnroll(ReqId, token, K.Bytes("pkP"), Q.Str("phoneName"), b);
        Assert.Equal(Q.Str("enroll"), Hex.S(b.AsSpan(0, n)));
        Assert.True(WireCodec.TryDecodeKeyEnroll(b.AsSpan(0, n), out var e));
        Assert.Equal(Q.Str("phoneName"), e.Name);
        Assert.Equal(K.Str("pkP"), Hex.S(e.PkP));
        Assert.True(QrPairing.CheckEnroll(b.AsSpan(0, n), token));
        Assert.False(QrPairing.CheckEnroll(b.AsSpan(0, n), new byte[16]));

        var tokenId = QrPairing.TokenId(token);
        n = WireCodec.EncodeKeyEnrollAck(ReqId, tokenId, EnrollStatus.Ok, token, K.Bytes("pkP"), b);
        Assert.Equal(Q.Str("ackOk"), Hex.S(b.AsSpan(0, n)));
        n = WireCodec.EncodeKeyEnrollAck(ReqId, tokenId, EnrollStatus.Used, token, K.Bytes("pkP"), b);
        Assert.Equal(Q.Str("ackUsed"), Hex.S(b.AsSpan(0, n)));
        n = WireCodec.EncodeKeyEnrollAck(ReqId, tokenId, EnrollStatus.Expired, default, default, b);
        Assert.Equal(Q.Str("ackExpired"), Hex.S(b.AsSpan(0, n)));
    }

    [Fact]
    public void ScannedPhoneIsAuthorizedAndConnects()
    {
        using var hostKey = P256.FromPrivateScalar(K.Bytes("hostPrivD"));
        var h = new Harness(pairDefault: false, hostKey: hostKey);
        var phone = FakePhone.Paired(K.Bytes("keyPhoneId"), "OnePlus 15", K.Bytes("pairKey"));
        var saved = new List<PubKeyFile>();
        string Save(PubKeyFile k)
        {
            saved.Add(k);
            return "/keys/" + KeyPairing.FileName(k.Name);
        }

        // No QR code on screen: EXPIRED, unauthenticated.
        var stray = Enroll(new byte[16], K.Bytes("pkP"));
        Assert.Equal(EnrollStatus.Expired, AckStatus(h.Send(phone, stray)));

        var token = h.Engine.OpenQrPairing(Save)!;
        Assert.Equal(Wire.QrPairingS, h.Engine.Snapshot().QrPairingS);

        // A wrong mac (someone who guessed the token id) gets nothing.
        var forged = Enroll(token, K.Bytes("pkP"));
        forged[125] ^= 1;
        Assert.Null(h.Send(phone, forged));
        Assert.Empty(saved);

        var ack = h.Send(phone, Enroll(token, K.Bytes("pkP")))!;
        Assert.Equal(EnrollStatus.Ok, AckStatus(ack));
        Assert.Equal(Hex.S(QrPairing.AckMac(token, ack, K.Bytes("pkP"))), Hex.S(ack.AsSpan(25, 16)));
        var key = Assert.Single(saved);
        Assert.Equal("OnePlus 15", key.Name);
        Assert.Equal("OnePlus 15", h.Engine.Snapshot().QrEnrolledPhone);

        // A retransmission is acked again without a second file.
        Assert.Equal(EnrollStatus.Ok, AckStatus(h.Send(phone, Enroll(token, K.Bytes("pkP")))));
        Assert.Single(saved);

        // Another phone with the same QR code: USED.
        using var other = P256.Generate();
        Assert.Equal(EnrollStatus.Used, AckStatus(h.Send(phone, Enroll(token, other.PublicKey))));

        // The phone is authorized at once (before the folder watcher sees the file) and connects by key.
        h.Connect(phone);
        var s = h.Engine.Snapshot();
        Assert.True(s.Connected);
        Assert.True(Assert.Single(s.PairedPhones).ByKey);

        // The folder watcher's next list (with the file) keeps the session.
        h.Engine.SetAuthorizedPhones([new AuthorizedPhone("/keys/OnePlus 15.pub", key)]);
        Assert.True(h.Engine.Snapshot().Connected);
    }

    [Fact]
    public void QrCodeExpiresAndCloses()
    {
        using var hostKey = P256.Generate();
        var h = new Harness(pairDefault: false, hostKey: hostKey);
        var phone = h.NewPhone();
        var token = h.Engine.OpenQrPairing(_ => "x.pub")!;
        h.Clock.AdvanceMs(Wire.QrPairingS * 1000L);
        Assert.Equal(0, h.Engine.Snapshot().QrPairingS);
        Assert.Equal(EnrollStatus.Expired, AckStatus(h.Send(phone, Enroll(token, K.Bytes("pkP")))));

        token = h.Engine.OpenQrPairing(_ => null)!; // saving fails: no answer, nothing authorized
        Assert.Null(h.Send(phone, Enroll(token, K.Bytes("pkP"))));
        Assert.Empty(h.Engine.Snapshot().PairedPhones);
        h.Engine.CloseQrPairing();
        Assert.Equal(EnrollStatus.Expired, AckStatus(h.Send(phone, Enroll(token, K.Bytes("pkP")))));

        var noKey = new Harness(pairDefault: false);
        Assert.Null(noKey.Engine.OpenQrPairing(_ => "x.pub"));
        Assert.Equal(EnrollStatus.NoKey, AckStatus(noKey.Send(phone, Enroll(new byte[16], K.Bytes("pkP")))));
    }

    [Fact]
    public void AddAuthorizedReusesAndNumbersFiles()
    {
        var dir = Path.Combine(Path.GetTempPath(), "gp-qr-" + Guid.NewGuid().ToString("N"));
        try
        {
            var files = new KeyFiles(dir);
            var a = files.AddAuthorized(new PubKeyFile(K.Bytes("pkP"), "OnePlus 15"));
            Assert.Equal("OnePlus 15.pub", Path.GetFileName(a));
            Assert.Equal(a, files.AddAuthorized(new PubKeyFile(K.Bytes("pkP"), "renamed")));
            var b = files.AddAuthorized(new PubKeyFile(K.Bytes("pkH"), "OnePlus 15"));
            Assert.Equal("OnePlus 15 2.pub", Path.GetFileName(b));
            Assert.Equal(2, files.ReadAuthorized().Count);
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch (IOException) { }
        }
    }

    [Fact]
    public void QrCodeHasFinderPatterns()
    {
        var m = QrCode.Modules(Q.Str("payload"));
        int n = m.Count;
        Assert.All(m, r => Assert.Equal(n, r.Length));
        Assert.True(n >= 21 + 8);
        // Quiet zone light, then the dark centres of the three 7x7 finders (none bottom right).
        Assert.False(m[0][0]);
        Assert.True(m[7][7] && m[7][n - 8] && m[n - 8][7]);
        Assert.True(m[4][4] && m[4][n - 5] && m[n - 5][4]);
        Assert.False(m[5][5] || m[5][n - 6] || m[n - 6][5]); // the light ring inside each finder
    }

    private static byte[] Enroll(byte[] token, byte[] pkP)
    {
        var b = new byte[Wire.KeyEnrollSize];
        WireCodec.EncodeKeyEnroll(ReqId, token, pkP, "OnePlus 15", b);
        return b;
    }

    private static byte AckStatus(byte[]? ack)
    {
        Assert.NotNull(ack);
        Assert.Equal(PacketType.KeyEnrollAck, ack![3]);
        Assert.Equal(Wire.KeyEnrollAckSize, ack.Length);
        return ack[24];
    }
}
