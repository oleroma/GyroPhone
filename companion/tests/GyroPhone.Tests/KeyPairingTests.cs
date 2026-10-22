using System.Text.Json;
using GyroPhone.Host;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;

namespace GyroPhone.Tests;

/// <summary>Key file pairing: the shared vectors (key-v1.json), key files, and the engine side.</summary>
public class KeyPairingTests
{
    private static readonly JsonElement K = Vectors.Key;

    [Fact]
    public void KeyMathMatchesVectors()
    {
        using var phone = P256.FromPrivateScalar(K.Bytes("phonePrivD"));
        using var host = P256.FromPrivateScalar(K.Bytes("hostPrivD"));
        Assert.Equal(K.Str("pkP"), Hex.S(phone.PublicKey));
        Assert.Equal(K.Str("pkH"), Hex.S(host.PublicKey));
        var ss = host.Ecdh(phone.PublicKey);
        Assert.Equal(K.Str("ss"), Hex.S(ss));
        Assert.Equal(K.Str("salt"), Hex.S(KeyPairing.Salt(phone.PublicKey, host.PublicKey)));
        Assert.Equal(K.Str("pairKey"), Hex.S(KeyPairing.PairKey(ss, phone.PublicKey, host.PublicKey)));
        Assert.Equal(K.Str("keyPhoneId"), Hex.S(KeyPairing.KeyPhoneId(phone.PublicKey, host.PublicKey)));
        Assert.Equal(K.Str("phoneKeyId"), Hex.S(KeyPairing.KeyId(phone.PublicKey)));
        Assert.Equal(K.Str("hostKeyId"), Hex.S(KeyPairing.KeyId(host.PublicKey)));
        Assert.Equal(K.Str("phoneFingerprint"), KeyPairing.Fingerprint(phone.PublicKey));
        Assert.Equal(K.Str("hostFingerprint"), KeyPairing.Fingerprint(host.PublicKey));

        var (id, pairKey) = KeyPairing.HostSecrets(host, phone.PublicKey);
        Assert.Equal(K.Str("keyPhoneId"), Hex.S(id));
        Assert.Equal(K.Str("pairKey"), Hex.S(pairKey));
    }

    [Fact]
    public void PrivateScalarRoundTrips()
    {
        using var host = P256.FromPrivateScalar(K.Bytes("hostPrivD"));
        Assert.Equal(K.Str("hostPrivD"), Hex.S(host.ExportPrivateScalar()));
        using var fresh = P256.Generate();
        using var again = P256.FromPrivateScalar(fresh.ExportPrivateScalar());
        Assert.Equal(fresh.PublicKey, again.PublicKey);
    }

    [Fact]
    public void KeyFilesRoundTrip()
    {
        Assert.Equal(K.Str("hostFile") + "\n", new PubKeyFile(K.Bytes("pkH"), "OLEX").Format());
        var p = PubKeyFile.Parse("\r\n" + K.Str("phoneFile") + "\r\n");
        Assert.NotNull(p);
        Assert.Equal(K.Str("pkP"), Hex.S(p!.PublicKey));
        Assert.Equal("OnePlus 15", p.Name);
        Assert.Null(PubKeyFile.Parse(""));
        Assert.Null(PubKeyFile.Parse("ssh-ed25519 AAAA me"));
        Assert.Null(PubKeyFile.Parse("gyrophone-p256 not-base64!! x"));
        var off = K.Bytes("pkP");
        off[64] ^= 1;
        Assert.Null(PubKeyFile.Parse(new PubKeyFile(off, "x").Format()));
        Assert.Equal("a_b_c.pub", KeyPairing.FileName("a/b\\c"));
    }

    [Fact]
    public void DiscoverCarriesKeyIds()
    {
        var d = K.GetProperty("discover");
        var p = new Discover((uint)d.Long("reqId"), d.Bytes("phoneId"), 0, d.Bytes("hostKeyId"), d.Bytes("phoneKeyId"));
        var b = new byte[Wire.MaxDatagram];
        Assert.Equal(d.Str("hex"), Hex.S(b.AsSpan(0, WireCodec.EncodeDiscover(p, b))));
        Assert.True(WireCodec.TryDecodeDiscover(Hex.B(d.Str("hex")), out var back));
        Assert.Equal(d.Str("hostKeyId"), Hex.S(back.HostKeyId));
        Assert.Equal(d.Str("phoneKeyId"), Hex.S(back.PhoneKeyId));
    }

    [Fact]
    public void AuthorizedPhoneConnectsWithItsKey()
    {
        using var hostKey = P256.FromPrivateScalar(K.Bytes("hostPrivD"));
        var h = new Harness(pairDefault: false, hostKey: hostKey);
        var phoneKey = new PubKeyFile(K.Bytes("pkP"), "OnePlus 15");
        var phone = FakePhone.Paired(K.Bytes("keyPhoneId"), "OnePlus 15", K.Bytes("pairKey"));

        // Unknown until the file appears.
        Assert.Equal(PacketType.Error, h.Send(phone, phone.Hello())![3]);
        Assert.Equal(0, AnnounceFlagsFor(h, phone) & AnnounceFlags.KnowsYourKey);

        h.Engine.SetAuthorizedPhones([new AuthorizedPhone("/keys/OnePlus 15.pub", phoneKey)]);
        Assert.Equal(AnnounceFlags.KeyMatch | AnnounceFlags.KnowsYourKey,
            AnnounceFlagsFor(h, phone) & (AnnounceFlags.KeyMatch | AnnounceFlags.KnowsYourKey));
        h.Connect(phone);
        var s = h.Engine.Snapshot();
        Assert.True(s.Connected);
        Assert.Equal("OnePlus 15", s.PhoneName);
        var listed = Assert.Single(s.PairedPhones);
        Assert.True(listed.ByKey);
        Assert.Equal("/keys/OnePlus 15.pub", listed.KeyFile);
        Assert.Equal(K.Str("hostFingerprint"), s.HostKeyFingerprint);
        Assert.Empty(h.Settings.PairedPhones); // key pairings are never stored in settings

        // The same list again (a rename, a touch) keeps the session.
        h.Engine.SetAuthorizedPhones([new AuthorizedPhone("/keys/renamed.pub", phoneKey)]);
        Assert.True(h.Engine.Snapshot().Connected);

        // Deleting the file revokes the phone at once.
        h.Sender.Clear();
        h.Engine.SetAuthorizedPhones([]);
        Assert.False(h.Engine.Snapshot().Connected);
        Assert.Contains(h.Sender.Sent, x => phone.TryOpen(x.Data, out _) == PacketType.HostBye);
        Assert.Equal(PacketType.Error, h.Send(phone, phone.Hello())![3]);
    }

    [Fact]
    public void OneSidedKeysAreReported()
    {
        using var hostKey = P256.FromPrivateScalar(K.Bytes("hostPrivD"));
        var h = new Harness(pairDefault: false, hostKey: hostKey);
        var phone = FakePhone.Paired(K.Bytes("keyPhoneId"), "OnePlus 15", K.Bytes("pairKey"));
        // The phone trusts this PC, the PC does not know the phone.
        Assert.Equal(AnnounceFlags.KeyMatch, AnnounceFlagsFor(h, phone) & (AnnounceFlags.KeyMatch | AnnounceFlags.KnowsYourKey));
        // The PC knows the phone, the phone trusts some other PC.
        h.Engine.SetAuthorizedPhones([new AuthorizedPhone("p.pub", new PubKeyFile(K.Bytes("pkP"), "p"))]);
        Assert.Equal(AnnounceFlags.KnowsYourKey,
            AnnounceFlagsFor(h, phone, hostKeyId: new byte[8]) & (AnnounceFlags.KeyMatch | AnnounceFlags.KnowsYourKey));
    }

    [Fact]
    public void KeyPhoneSkipsTheFirstRunPairingWindow()
    {
        using var hostKey = P256.Generate();
        var h = new Harness(pairDefault: false, hostKey: hostKey);
        h.Engine.SetAuthorizedPhones([new AuthorizedPhone("p.pub", new PubKeyFile(K.Bytes("pkP"), "p"))]);
        h.Engine.Start(h.Clock.NowUs);
        Assert.Equal(0, h.Engine.Snapshot().PairingWindowS);
    }

    [Fact]
    public void HostKeyIsCreatedOnceAndAuthorizedFilesAreRead()
    {
        var dir = Path.Combine(Path.GetTempPath(), "gp-keys-" + Guid.NewGuid().ToString("N"));
        try
        {
            var files = new KeyFiles(dir);
            using var first = files.LoadOrCreateHostKey(new PlainProtector(), "OLEX");
            using var second = files.LoadOrCreateHostKey(new PlainProtector(), "OLEX");
            Assert.Equal(first.PublicKey, second.PublicKey);
            var pub = PubKeyFile.Parse(File.ReadAllText(files.HostPubPath));
            Assert.Equal(first.PublicKey, pub!.PublicKey);
            Assert.Equal("OLEX", pub.Name);

            File.WriteAllText(Path.Combine(files.AuthorizedDirectory, "OnePlus 15.pub"), K.Str("phoneFile") + "\r\n");
            File.WriteAllText(Path.Combine(files.AuthorizedDirectory, "junk.pub"), "hello");
            File.WriteAllText(Path.Combine(files.AuthorizedDirectory, "notes.txt"), K.Str("phoneFile"));
            var list = files.ReadAuthorized();
            var only = Assert.Single(list);
            Assert.Equal("OnePlus 15", only.Key.Name);

            var seen = new List<int>();
            using var watcher = new AuthorizedPhonesWatcher(files, l => { lock (seen) seen.Add(l.Count); });
            lock (seen) Assert.Equal([1], seen);
            File.Delete(Path.Combine(files.AuthorizedDirectory, "OnePlus 15.pub"));
            var until = DateTime.UtcNow.AddSeconds(5);
            while (DateTime.UtcNow < until)
            {
                lock (seen) if (seen[^1] == 0) break;
                Thread.Sleep(50);
            }
            lock (seen) Assert.Equal(0, seen[^1]);
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch (IOException) { }
        }
    }

    private static ushort AnnounceFlagsFor(Harness h, FakePhone phone, byte[]? hostKeyId = null)
    {
        var b = new byte[Wire.DiscoverSize];
        WireCodec.EncodeDiscover(new Discover(9, Hex.Range(0x70, 16), 0, hostKeyId ?? K.Bytes("hostKeyId"), K.Bytes("phoneKeyId")), b);
        Assert.True(WireCodec.TryDecodeAnnounce(h.Send(phone, b)!, out var a));
        return a.Flags;
    }
}

public class PhoneWatchTests
{
    private static readonly JsonElement K = Vectors.Key;

    private static byte[] Discover(byte[] phoneId, byte[]? phoneKeyId = null)
    {
        var b = new byte[Wire.DiscoverSize];
        WireCodec.EncodeDiscover(new Discover(1, phoneId, 0, null, phoneKeyId), b);
        return b;
    }

    [Fact]
    public void OnlyKnownPhonesCount()
    {
        var w = new PhoneWatch([Harness.Phone1Id], [K.Bytes("pkP")]);
        Assert.False(w.Empty);
        Assert.True(w.IsKnownPhone(Discover(Harness.Phone1Id)));
        Assert.True(w.IsKnownPhone(Discover(Hex.Range(0x70, 16), K.Bytes("phoneKeyId"))));
        Assert.False(w.IsKnownPhone(Discover(Hex.Range(0x70, 16))));
        Assert.False(w.IsKnownPhone(Discover(Hex.Range(0x70, 16), new byte[8])));
        var other = Discover(Harness.Phone1Id);
        other[3] = PacketType.Hello; // not a DISCOVER
        Assert.False(w.IsKnownPhone(other));
        Assert.True(new PhoneWatch([], []).Empty);
    }
}
