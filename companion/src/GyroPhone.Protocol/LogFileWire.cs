using System.Security.Cryptography;
using System.Text;

namespace GyroPhone.Protocol;

/// <summary>
/// The phone's Diagnostics save, fetched over its own TCP connection, apart from the input packets
/// (docs/spec/protocol.md, "Diagnostics file"; the phone's side is core LogFileWire.kt). The PC connects to the
/// phone's <see cref="Port"/>, sends a <see cref="Request"/>-byte request with a mac under the session's log key and
/// reads a <see cref="Head"/>-byte head; with <see cref="Ok"/> sealed frames follow: <c>u32le len || AES-128-GCM(logKey,
/// nonce = reqId(8) || u32le(index))</c>. Frame 0 is the meta (u32 raw size, u32 zipped size, str8 name), then the
/// zipped data, then an empty frame that ends it.
/// </summary>
public static class LogFileWire
{
    public const int Port = Wire.Port;
    public const byte Version = 1;
    public const byte KindNewest = 1;
    public const int Request = 40;
    public const int Head = 8;
    public const int Mac = 16;
    public const int FrameMax = 16 * 1024;

    public const byte Ok = 0, NotYet = 1, Refused = 2, Off = 3;

    private static ReadOnlySpan<byte> Magic => "GPLF"u8;
    private static readonly byte[] InfoLabel = "GP-log-file-v1"u8.ToArray();

    /// <summary>HKDF(kPH, salt = none, info = "GP-log-file-v1" || u32le(sid), 16): never used on the input link.</summary>
    public static byte[] LogKey(ReadOnlySpan<byte> kPH, uint sessionId)
    {
        Span<byte> info = stackalloc byte[InfoLabel.Length + 4];
        InfoLabel.CopyTo(info);
        Le.PutU32(info, InfoLabel.Length, sessionId);
        var key = new byte[16];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, kPH, key, [], info);
        return key;
    }

    public static byte[] EncodeRequest(ReadOnlySpan<byte> logKey, byte kind, ushort maxAgeS, uint sessionId, ReadOnlySpan<byte> reqId)
    {
        var b = new byte[Request];
        Magic.CopyTo(b);
        b[4] = Version;
        b[5] = kind;
        Le.PutU16(b, 6, maxAgeS);
        Le.PutU32(b, 8, sessionId);
        reqId[..8].CopyTo(b.AsSpan(12));
        HMACSHA256.HashData(logKey, b.AsSpan(0, 24))[..Mac].CopyTo(b.AsSpan(24));
        return b;
    }

    /// <summary>The status in a reply's head, or -1 when it isn't one.</summary>
    public static int HeadStatus(ReadOnlySpan<byte> head) =>
        head.Length < Head || !head[..4].SequenceEqual(Magic) || head[4] != Version ? -1 : head[5];

    /// <summary>Opens frame <paramref name="index"/>'s sealed bytes (ciphertext || tag, without the length); null on a bad tag.</summary>
    public static byte[]? OpenFrame(ReadOnlySpan<byte> logKey, ReadOnlySpan<byte> reqId, uint index, ReadOnlySpan<byte> sealedBytes)
    {
        if (sealedBytes.Length < Wire.GcmTag) return null;
        Span<byte> nonce = stackalloc byte[12];
        reqId[..8].CopyTo(nonce);
        Le.PutU32(nonce, 8, index);
        var plain = new byte[sealedBytes.Length - Wire.GcmTag];
        using var aes = new AesGcm(logKey, Wire.GcmTag);
        try
        {
            aes.Decrypt(nonce, sealedBytes[..plain.Length], sealedBytes[plain.Length..], plain);
            return plain;
        }
        catch (AuthenticationTagMismatchException)
        {
            return null;
        }
    }

    /// <summary>Seals one frame (the phone's side; here for the tests): u32le len || ciphertext || tag.</summary>
    public static byte[] SealFrame(ReadOnlySpan<byte> logKey, ReadOnlySpan<byte> reqId, uint index, ReadOnlySpan<byte> plain)
    {
        Span<byte> nonce = stackalloc byte[12];
        reqId[..8].CopyTo(nonce);
        Le.PutU32(nonce, 8, index);
        var b = new byte[4 + plain.Length + Wire.GcmTag];
        Le.PutU32(b, 0, (uint)(plain.Length + Wire.GcmTag));
        using var aes = new AesGcm(logKey, Wire.GcmTag);
        aes.Encrypt(nonce, plain, b.AsSpan(4, plain.Length), b.AsSpan(4 + plain.Length, Wire.GcmTag));
        return b;
    }

    public readonly record struct Meta(uint RawSize, uint ZipSize, string Name);

    public static Meta? DecodeMeta(ReadOnlySpan<byte> b)
    {
        if (b.Length < 9 || b.Length < 9 + b[8]) return null;
        return new Meta(Le.U32(b, 0), Le.U32(b, 4), Encoding.UTF8.GetString(b.Slice(9, b[8])));
    }
}
