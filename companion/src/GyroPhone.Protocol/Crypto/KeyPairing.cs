using System.Security.Cryptography;
using System.Text;

namespace GyroPhone.Protocol.Crypto;

/// <summary>
/// Key file pairing (must match KeyPairing.kt). Each side has a static P-256 key and a folder of the
/// other side's public keys; nothing secret is copied and no packet is added. Both sides derive
/// <c>pairKey = HKDF(ikm = ECDH(phone, host), salt = SHA-256("GP-key-v1" || pkP || pkH), info = "GP-key-pair-v1")</c>
/// and the phone says HELLO as <c>keyPhoneId = SHA-256("GP-key-phone-id-v1" || pkP || pkH)[0..16]</c>.
/// </summary>
public static class KeyPairing
{
    public const string Tag = "gyrophone-p256";

    private static readonly byte[] SaltLabel = "GP-key-v1"u8.ToArray();
    private static readonly byte[] PairKeyInfo = "GP-key-pair-v1"u8.ToArray();
    private static readonly byte[] PhoneIdLabel = "GP-key-phone-id-v1"u8.ToArray();

    /// <summary>First 8 bytes of SHA-256 of a public key: what DISCOVER carries.</summary>
    public static byte[] KeyId(ReadOnlySpan<byte> publicKey) => SHA256.HashData(publicKey)[..Wire.KeyIdSize];

    /// <summary>Short display form, e.g. "SHA256:3f9a…7c21".</summary>
    public static string Fingerprint(ReadOnlySpan<byte> publicKey)
    {
        var h = Convert.ToHexString(SHA256.HashData(publicKey)).ToLowerInvariant();
        return "SHA256:" + h[..4] + "…" + h[^4..];
    }

    public static byte[] Salt(ReadOnlySpan<byte> pkP, ReadOnlySpan<byte> pkH) => Hash(SaltLabel, pkP, pkH);

    public static byte[] PairKey(ReadOnlySpan<byte> ss, ReadOnlySpan<byte> pkP, ReadOnlySpan<byte> pkH)
    {
        var o = new byte[32];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, ss, o, Salt(pkP, pkH), PairKeyInfo);
        return o;
    }

    public static byte[] KeyPhoneId(ReadOnlySpan<byte> pkP, ReadOnlySpan<byte> pkH) => Hash(PhoneIdLabel, pkP, pkH)[..Wire.IdSize];

    /// <summary>Host side: (keyPhoneId, pairKey) for an authorized phone key.</summary>
    public static (byte[] PhoneId, byte[] PairKey) HostSecrets(P256 host, ReadOnlySpan<byte> phoneKey)
    {
        var ss = host.Ecdh(phoneKey);
        try
        {
            return (KeyPhoneId(phoneKey, host.PublicKey), PairKey(ss, phoneKey, host.PublicKey));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(ss);
        }
    }

    /// <summary>A file name for a key named <paramref name="name"/>: letters, digits, space, dot, dash and underscore.</summary>
    public static string FileName(string name)
    {
        var b = new StringBuilder(name.Length);
        foreach (var c in name) b.Append(char.IsLetterOrDigit(c) || c is ' ' or '.' or '_' or '-' ? c : '_');
        var clean = b.ToString().Trim().Trim('.');
        if (clean.Length == 0) clean = "key";
        return (clean.Length > 48 ? clean[..48] : clean) + ".pub";
    }

    private static byte[] Hash(ReadOnlySpan<byte> a, ReadOnlySpan<byte> b, ReadOnlySpan<byte> c)
    {
        using var h = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        h.AppendData(a);
        h.AppendData(b);
        h.AppendData(c);
        return h.GetHashAndReset();
    }
}

/// <summary>One .pub file: <c>gyrophone-p256 &lt;base64 SEC1 key&gt; &lt;name&gt;</c>.</summary>
public sealed record PubKeyFile(byte[] PublicKey, string Name)
{
    public string Fingerprint => KeyPairing.Fingerprint(PublicKey);

    /// <summary>The file's single line, with a trailing newline.</summary>
    public string Format() => $"{KeyPairing.Tag} {Convert.ToBase64String(PublicKey)} {Name}\n";

    /// <summary>Parses a key file (first non-blank line); null unless it holds a valid P-256 point.</summary>
    public static PubKeyFile? Parse(string text)
    {
        var line = text.Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.Length > 0);
        if (line == null) return null;
        var parts = line.Split((char[]?)null, 3, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2 || parts[0] != KeyPairing.Tag) return null;
        byte[] key;
        try
        {
            key = Convert.FromBase64String(parts[1]);
        }
        catch (FormatException)
        {
            return null;
        }
        if (!P256.IsValidPublic(key)) return null;
        return new PubKeyFile(key, parts.Length > 2 ? parts[2].Trim() : "");
    }
}
