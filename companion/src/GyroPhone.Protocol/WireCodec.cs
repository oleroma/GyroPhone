using System.Security.Cryptography;
using System.Text;

namespace GyroPhone.Protocol;

/// <summary>
/// Encoders write into a caller buffer and return the length written. Decoders return false on any
/// rule violation (wrong size, magic, version, type or an overrunning nameLen).
/// </summary>
public static class WireCodec
{
    // ---- header ----

    public static void WriteHeader(Span<byte> b, byte type, uint sessionId, uint seq)
    {
        b[0] = Wire.Magic0;
        b[1] = Wire.Magic1;
        b[2] = Wire.Version;
        b[3] = type;
        Le.PutU32(b, 4, sessionId);
        Le.PutU32(b, 8, seq);
    }

    public static PacketHeader ReadHeader(ReadOnlySpan<byte> b) => new(b[3], Le.U32(b, 4), Le.U32(b, 8));

    /// <summary>Header checks for a packet of an expected type: magic, version, type and exact size.</summary>
    public static bool CheckFrame(ReadOnlySpan<byte> b, byte type) =>
        b.Length >= Wire.Header && b[0] == Wire.Magic0 && b[1] == Wire.Magic1 && b[2] == Wire.Version &&
        b[3] == type && b.Length == PacketType.Size(type);

    // ---- strings ----

    /// <summary>Writes u8 len + UTF-8 bytes zero-padded to max, truncating on a code point boundary.</summary>
    public static void WriteName(Span<byte> b, int off, string name, int max)
    {
        var field = b.Slice(off + 1, max);
        field.Clear();
        var bytes = Encoding.UTF8.GetBytes(name);
        int n = Math.Min(bytes.Length, max);
        // Back up over UTF-8 continuation bytes so a multi-byte code point is never split.
        if (n < bytes.Length)
            while (n > 0 && (bytes[n] & 0xC0) == 0x80) n--;
        bytes.AsSpan(0, n).CopyTo(field);
        b[off] = (byte)n;
    }

    public static bool TryReadName(ReadOnlySpan<byte> b, int off, int max, out string name)
    {
        int len = b[off];
        if (len > max) { name = ""; return false; }
        name = Encoding.UTF8.GetString(b.Slice(off + 1, len));
        return true;
    }

    private static void Put(Span<byte> b, int off, ReadOnlySpan<byte> v, int size)
    {
        if (v.Length != size) throw new ArgumentException($"expected {size} bytes, got {v.Length}");
        v.CopyTo(b[off..]);
    }

    private static byte[] Get(ReadOnlySpan<byte> b, int off, int size) => b.Slice(off, size).ToArray();

    private static Span<byte> Start(Span<byte> b, byte type)
    {
        int size = PacketType.Size(type);
        var p = b[..size];
        p.Clear();
        WriteHeader(p, type, 0, 0);
        return p;
    }

    // ---- discovery ----

    public static int EncodeDiscover(Discover p, Span<byte> b)
    {
        var o = Start(b, PacketType.Discover);
        Le.PutU32(o, 12, p.ReqId);
        Put(o, 16, p.PhoneId, Wire.IdSize);
        Le.PutU16(o, 32, p.Flags);
        if (p.HostKeyId != null) Put(o, 34, p.HostKeyId, Wire.KeyIdSize);
        if (p.PhoneKeyId != null) Put(o, 42, p.PhoneKeyId, Wire.KeyIdSize);
        return o.Length;
    }

    public static bool TryDecodeDiscover(ReadOnlySpan<byte> b, out Discover p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.Discover)) return false;
        p = new Discover(Le.U32(b, 12), Get(b, 16, Wire.IdSize), Le.U16(b, 32), Get(b, 34, Wire.KeyIdSize), Get(b, 42, Wire.KeyIdSize));
        return true;
    }

    public static int EncodeAnnounce(Announce p, Span<byte> b)
    {
        var o = Start(b, PacketType.Announce);
        Le.PutU32(o, 12, p.ReqId);
        Put(o, 16, p.HostId, Wire.IdSize);
        Le.PutU16(o, 32, p.Flags);
        WriteName(o, 34, p.Name, Wire.AnnounceNameMax);
        return o.Length;
    }

    public static bool TryDecodeAnnounce(ReadOnlySpan<byte> b, out Announce p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.Announce) || !TryReadName(b, 34, Wire.AnnounceNameMax, out var name)) return false;
        p = new Announce(Le.U32(b, 12), Get(b, 16, Wire.IdSize), Le.U16(b, 32), name);
        return true;
    }

    // ---- QR code pairing ----

    public static bool TryDecodeKeyEnroll(ReadOnlySpan<byte> b, out KeyEnroll p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.KeyEnroll) || !TryReadName(b, 89, Wire.PairNameMax, out var name)) return false;
        p = new KeyEnroll(Le.U32(b, 12), Get(b, 16, Wire.KeyIdSize), Get(b, 24, Wire.PublicKeySize), name, Get(b, 121, Crypto.QrPairing.MacSize));
        return true;
    }

    /// <summary>KEY_ENROLL with its mac under <paramref name="token"/> (the phone side; for tests).</summary>
    public static int EncodeKeyEnroll(uint reqId, ReadOnlySpan<byte> token, ReadOnlySpan<byte> pkP, string name, Span<byte> b)
    {
        var o = Start(b, PacketType.KeyEnroll);
        Le.PutU32(o, 12, reqId);
        Put(o, 16, Crypto.QrPairing.TokenId(token), Wire.KeyIdSize);
        Put(o, 24, pkP, Wire.PublicKeySize);
        WriteName(o, 89, name, Wire.PairNameMax);
        Crypto.QrPairing.EnrollMac(token, o[..121]).CopyTo(o[121..]);
        return o.Length;
    }

    /// <summary>KEY_ENROLL_ACK; with no token (EXPIRED, NO_KEY) the mac stays zero.</summary>
    public static int EncodeKeyEnrollAck(uint reqId, ReadOnlySpan<byte> tokenId, byte status, ReadOnlySpan<byte> token, ReadOnlySpan<byte> pkP, Span<byte> b)
    {
        var o = Start(b, PacketType.KeyEnrollAck);
        Le.PutU32(o, 12, reqId);
        Put(o, 16, tokenId, Wire.KeyIdSize);
        o[24] = status;
        if (!token.IsEmpty) Crypto.QrPairing.AckMac(token, o[..25], pkP).CopyTo(o[25..]);
        return o.Length;
    }

    // ---- pairing ----

    public static int EncodePairStart(PairStart p, Span<byte> b)
    {
        var o = Start(b, PacketType.PairStart);
        Put(o, 12, p.PhoneId, Wire.IdSize);
        Put(o, 28, p.PkP, Wire.PublicKeySize);
        Put(o, 93, p.Commit, Wire.HashSize);
        WriteName(o, 125, p.Name, Wire.PairNameMax);
        return o.Length;
    }

    public static bool TryDecodePairStart(ReadOnlySpan<byte> b, out PairStart p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.PairStart) || !TryReadName(b, 125, Wire.PairNameMax, out var name)) return false;
        p = new PairStart(Get(b, 12, Wire.IdSize), Get(b, 28, Wire.PublicKeySize), Get(b, 93, Wire.HashSize), name);
        return true;
    }

    public static int EncodePairHost(PairHost p, Span<byte> b)
    {
        var o = Start(b, PacketType.PairHost);
        Le.PutU32(o, 12, p.AttemptId);
        Put(o, 16, p.HostId, Wire.IdSize);
        Put(o, 32, p.PkH, Wire.PublicKeySize);
        Put(o, 97, p.NH, Wire.NonceSize);
        WriteName(o, 113, p.Name, Wire.PairNameMax);
        return o.Length;
    }

    public static bool TryDecodePairHost(ReadOnlySpan<byte> b, out PairHost p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.PairHost) || !TryReadName(b, 113, Wire.PairNameMax, out var name)) return false;
        p = new PairHost(Le.U32(b, 12), Get(b, 16, Wire.IdSize), Get(b, 32, Wire.PublicKeySize), Get(b, 97, Wire.NonceSize), name);
        return true;
    }

    public static int EncodePairReveal(PairReveal p, Span<byte> b)
    {
        var o = Start(b, PacketType.PairReveal);
        Le.PutU32(o, 12, p.AttemptId);
        Put(o, 16, p.PhoneId, Wire.IdSize);
        Put(o, 32, p.NP, Wire.NonceSize);
        return o.Length;
    }

    public static bool TryDecodePairReveal(ReadOnlySpan<byte> b, out PairReveal p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.PairReveal)) return false;
        p = new PairReveal(Le.U32(b, 12), Get(b, 16, Wire.IdSize), Get(b, 32, Wire.NonceSize));
        return true;
    }

    public static int EncodePairAck(PairAck p, Span<byte> b)
    {
        var o = Start(b, PacketType.PairAck);
        Le.PutU32(o, 12, p.AttemptId);
        o[16] = p.Status;
        return o.Length;
    }

    public static bool TryDecodePairAck(ReadOnlySpan<byte> b, out PairAck p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.PairAck)) return false;
        p = new PairAck(Le.U32(b, 12), b[16]);
        return true;
    }

    public static int EncodePairConfirm(PairConfirm p, Span<byte> b)
    {
        var o = Start(b, PacketType.PairConfirm);
        Le.PutU32(o, 12, p.AttemptId);
        Put(o, 16, p.PhoneId, Wire.IdSize);
        Put(o, 32, p.ConfirmP, Wire.HashSize);
        return o.Length;
    }

    public static bool TryDecodePairConfirm(ReadOnlySpan<byte> b, out PairConfirm p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.PairConfirm)) return false;
        p = new PairConfirm(Le.U32(b, 12), Get(b, 16, Wire.IdSize), Get(b, 32, Wire.HashSize));
        return true;
    }

    public static int EncodePairDone(PairDone p, Span<byte> b)
    {
        var o = Start(b, PacketType.PairDone);
        Le.PutU32(o, 12, p.AttemptId);
        o[16] = p.Status;
        Put(o, 17, p.ConfirmH, Wire.HashSize);
        return o.Length;
    }

    public static bool TryDecodePairDone(ReadOnlySpan<byte> b, out PairDone p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.PairDone)) return false;
        p = new PairDone(Le.U32(b, 12), b[16], Get(b, 17, Wire.HashSize));
        return true;
    }

    // ---- session handshake ----

    public static int EncodeHello(Hello p, ReadOnlySpan<byte> pairKey, Span<byte> b)
    {
        var o = Start(b, PacketType.Hello);
        Put(o, 12, p.PhoneId, Wire.IdSize);
        Put(o, 28, p.NP, Wire.NonceSize);
        Le.PutU32(o, 44, p.Caps);
        Le.PutU16(o, 48, p.MaxRateHz);
        WriteMac(pairKey, o, 52);
        return o.Length;
    }

    /// <summary>Decodes the HELLO fields. The mac is not checked here; see <see cref="VerifyMac"/>.</summary>
    public static bool TryDecodeHello(ReadOnlySpan<byte> b, out Hello p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.Hello)) return false;
        p = new Hello(Get(b, 12, Wire.IdSize), Get(b, 28, Wire.NonceSize), Le.U32(b, 44), Le.U16(b, 48));
        return true;
    }

    public static int EncodeWelcome(Welcome p, ReadOnlySpan<byte> pairKey, Span<byte> b)
    {
        var o = Start(b, PacketType.Welcome);
        Le.PutU32(o, 4, p.SessionId);
        Put(o, 12, p.HostId, Wire.IdSize);
        Put(o, 28, p.NP, Wire.NonceSize);
        Put(o, 44, p.NH, Wire.NonceSize);
        o[60] = p.OutputMode;
        o[61] = p.HostFlags;
        Le.PutU16(o, 62, p.KeepaliveMs);
        WriteMac(pairKey, o, 64);
        return o.Length;
    }

    public static bool TryDecodeWelcome(ReadOnlySpan<byte> b, out Welcome p)
    {
        p = null!;
        if (!CheckFrame(b, PacketType.Welcome)) return false;
        p = new Welcome(Le.U32(b, 4), Get(b, 12, Wire.IdSize), Get(b, 28, Wire.NonceSize), Get(b, 44, Wire.NonceSize),
            b[60], b[61], Le.U16(b, 62));
        return true;
    }

    /// <summary>mac16 = HMAC(pairKey, bytes[0..macOff))[0..16], written at macOff.</summary>
    public static void WriteMac(ReadOnlySpan<byte> pairKey, Span<byte> packet, int macOff)
    {
        Span<byte> full = stackalloc byte[32];
        HMACSHA256.HashData(pairKey, packet[..macOff], full);
        full[..Wire.HelloMac].CopyTo(packet[macOff..]);
    }

    /// <summary>Constant-time check of a HELLO (mac at 52) or WELCOME (mac at 64).</summary>
    public static bool VerifyMac(ReadOnlySpan<byte> pairKey, ReadOnlySpan<byte> packet)
    {
        int macOff = packet.Length - Wire.HelloMac;
        if (macOff < Wire.Header) return false;
        Span<byte> full = stackalloc byte[32];
        HMACSHA256.HashData(pairKey, packet[..macOff], full);
        return CryptographicOperations.FixedTimeEquals(full[..Wire.HelloMac], packet[macOff..]);
    }

    // ---- error ----

    public static int EncodeError(ErrorPacket p, Span<byte> b)
    {
        var o = Start(b, PacketType.Error);
        Le.PutU16(o, 12, p.Code);
        o[14] = p.MaxVersion;
        return o.Length;
    }

    /// <summary>ERROR is accepted with any version byte so a v1 phone can read a newer host's VERSION error.</summary>
    public static bool TryDecodeError(ReadOnlySpan<byte> b, out ErrorPacket p)
    {
        p = null!;
        if (b.Length != Wire.ErrorSize || b[0] != Wire.Magic0 || b[1] != Wire.Magic1 || b[3] != PacketType.Error) return false;
        p = new ErrorPacket(Le.U16(b, 12), b[14]);
        return true;
    }

    // ---- sealed plaintexts ----

    public static int EncodePing(in Ping p, Span<byte> b)
    {
        Le.PutU64(b, 0, p.T1);
        return Wire.PingPlain;
    }

    public static Ping DecodePing(ReadOnlySpan<byte> b) => new(Le.U64(b, 0));

    public static int EncodePong(in Pong p, Span<byte> b)
    {
        Le.PutU64(b, 0, p.T1);
        Le.PutU64(b, 8, p.T2);
        Le.PutU64(b, 16, p.T3);
        return Wire.PongPlain;
    }

    public static Pong DecodePong(ReadOnlySpan<byte> b) => new(Le.U64(b, 0), Le.U64(b, 8), Le.U64(b, 16));

    public static int EncodeLinkInfo(in LinkInfo p, Span<byte> b)
    {
        Le.PutI64(b, 0, p.ClockOffsetUs);
        Le.PutU32(b, 8, p.RttMinUs);
        Le.PutU16(b, 12, p.SendRateHz);
        b[14] = (byte)p.RssiDbm;
        b[15] = p.Band;
        Le.PutU16(b, 16, p.LinkMbps);
        Le.PutU16(b, 18, p.FreqMhz);
        b[20] = p.Flags;
        b[21] = 0;
        return Wire.LinkInfoPlain;
    }

    public static LinkInfo DecodeLinkInfo(ReadOnlySpan<byte> b) =>
        new(Le.I64(b, 0), Le.U32(b, 8), Le.U16(b, 12), (sbyte)b[14], b[15], Le.U16(b, 16), Le.U16(b, 18), b[20]);

    public static int EncodeHostStatus(in HostStatus p, Span<byte> b)
    {
        Le.PutU32(b, 0, p.LastSeq);
        Le.PutU16(b, 4, p.RxPerSec);
        Le.PutU16(b, 6, p.LossPermille);
        Le.PutU32(b, 8, p.LatP50Us);
        Le.PutU32(b, 12, p.LatP99Us);
        Le.PutU32(b, 16, p.JitterUs);
        b[20] = p.OutputMode;
        b[21] = p.Flags;
        return Wire.HostStatusPlain;
    }

    public static HostStatus DecodeHostStatus(ReadOnlySpan<byte> b) =>
        new(Le.U32(b, 0), Le.U16(b, 4), Le.U16(b, 6), Le.U32(b, 8), Le.U32(b, 12), Le.U32(b, 16), b[20], b[21]);

    /// <summary>BYE and HOST_BYE: reason then three zero bytes.</summary>
    public static int EncodeReason(byte reason, Span<byte> b)
    {
        b[..Wire.ByePlain].Clear();
        b[0] = reason;
        return Wire.ByePlain;
    }

    public static int EncodeRumble(in Rumble p, Span<byte> b)
    {
        b[0] = p.Large;
        b[1] = p.Small;
        b[2] = 0;
        b[3] = 0;
        return Wire.RumblePlain;
    }

    public static Rumble DecodeRumble(ReadOnlySpan<byte> b) => new(b[0], b[1]);
}
