using System.Security.Cryptography;

namespace GyroPhone.Protocol.Crypto;

/// <summary>What a QR code carries: the host key, a one-time token, the host name and where to reach it.</summary>
public sealed record QrInvite(byte[] HostKey, byte[] Token, string Name, IReadOnlyList<string> Addresses, int Port = Wire.Port);

/// <summary>
/// QR code pairing (must match QrPairing.kt). The host shows
/// <c>gyrophone://pair?v=1&amp;n=&lt;name&gt;&amp;k=&lt;pkH&gt;&amp;t=&lt;token&gt;&amp;a=&lt;ip,ip&gt;&amp;p=&lt;port&gt;</c>
/// (k and t base64url without padding, n percent-encoded UTF-8). The phone that scans it trusts pkH
/// and sends KEY_ENROLL with its own key, MACed with the token; the host adds that key to
/// authorized_phones and answers KEY_ENROLL_ACK, MACed with the token over the phone key.
/// <list type="bullet">
/// <item><c>tokenId = SHA-256("GP-qr-id-v1" || token)[0..8]</c></item>
/// <item><c>enrollMac = HMAC-SHA256(token, "GP-qr-enroll-v1" || KEY_ENROLL[0..121])[0..16]</c></item>
/// <item><c>ackMac = HMAC-SHA256(token, "GP-qr-ack-v1" || KEY_ENROLL_ACK[0..25] || pkP)[0..16]</c></item>
/// </list>
/// </summary>
public static class QrPairing
{
    public const string Prefix = "gyrophone://pair?";
    public const int MacSize = 16;

    private static readonly byte[] IdLabel = "GP-qr-id-v1"u8.ToArray();
    private static readonly byte[] EnrollLabel = "GP-qr-enroll-v1"u8.ToArray();
    private static readonly byte[] AckLabel = "GP-qr-ack-v1"u8.ToArray();

    public static byte[] TokenId(ReadOnlySpan<byte> token)
    {
        using var h = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        h.AppendData(IdLabel);
        h.AppendData(token);
        return h.GetHashAndReset()[..Wire.KeyIdSize];
    }

    /// <summary>The mac over a KEY_ENROLL's first 121 bytes.</summary>
    public static byte[] EnrollMac(ReadOnlySpan<byte> token, ReadOnlySpan<byte> head) => Mac(token, EnrollLabel, head[..121], default);

    /// <summary>The mac over a KEY_ENROLL_ACK's first 25 bytes, for the phone key <paramref name="pkP"/>.</summary>
    public static byte[] AckMac(ReadOnlySpan<byte> token, ReadOnlySpan<byte> head, ReadOnlySpan<byte> pkP) => Mac(token, AckLabel, head[..25], pkP);

    /// <summary>True when a KEY_ENROLL's mac is right for <paramref name="token"/>.</summary>
    public static bool CheckEnroll(ReadOnlySpan<byte> packet, ReadOnlySpan<byte> token) =>
        CryptographicOperations.FixedTimeEquals(EnrollMac(token, packet), packet.Slice(121, MacSize));

    public static string Format(QrInvite inv) => Prefix + "v=1"
        + "&n=" + Uri.EscapeDataString(inv.Name)
        + "&k=" + B64(inv.HostKey)
        + "&t=" + B64(inv.Token)
        + "&a=" + string.Join(",", inv.Addresses)
        + "&p=" + inv.Port;

    /// <summary>A fresh one-time token.</summary>
    public static byte[] NewToken() => RandomNumberGenerator.GetBytes(Wire.QrTokenSize);

    private static string B64(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] Mac(ReadOnlySpan<byte> key, byte[] label, ReadOnlySpan<byte> a, ReadOnlySpan<byte> b)
    {
        using var h = IncrementalHash.CreateHMAC(HashAlgorithmName.SHA256, key);
        h.AppendData(label);
        h.AppendData(a);
        h.AppendData(b);
        return h.GetHashAndReset()[..MacSize];
    }
}
