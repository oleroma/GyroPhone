using System.Security.Cryptography;

namespace GyroPhone.Protocol.Crypto;

/// <summary>Per-session AES-128 keys (SPEC 1.6).</summary>
public static class SessionKeys
{
    private static readonly byte[] InfoLabel = "GP-session-v1"u8.ToArray();

    /// <summary>okm = HKDF(pairKey, salt = nP || nH, info = "GP-session-v1" || u32le(sid), 32); kPH = okm[0..16], kHP = okm[16..32].</summary>
    public static (byte[] KPH, byte[] KHP) Derive(ReadOnlySpan<byte> pairKey, ReadOnlySpan<byte> nP, ReadOnlySpan<byte> nH, uint sessionId)
    {
        Span<byte> salt = stackalloc byte[nP.Length + nH.Length];
        nP.CopyTo(salt);
        nH.CopyTo(salt[nP.Length..]);
        Span<byte> info = stackalloc byte[InfoLabel.Length + 4];
        InfoLabel.CopyTo(info);
        Le.PutU32(info, InfoLabel.Length, sessionId);
        Span<byte> okm = stackalloc byte[32];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, pairKey, okm, salt, info);
        var keys = (okm[..16].ToArray(), okm[16..].ToArray());
        CryptographicOperations.ZeroMemory(okm);
        return keys;
    }
}
