using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;

namespace GyroPhone.Host;

/// <summary>
/// What the watcher (GyroPhoneCompanion.exe --watch) knows: the phones that may start the companion.
/// A DISCOVER counts when it comes from a code-paired phone id or a key session's phone id, or names
/// an authorized phone key.
/// </summary>
public sealed class PhoneWatch
{
    private readonly HashSet<string> _phoneIds;
    private readonly HashSet<string> _keyIds;

    /// <param name="hostPublicKey">This PC's key (host.pub): with it, the keyPhoneId each authorized key
    /// says HELLO and DISCOVER as from a key session counts too.</param>
    public PhoneWatch(IEnumerable<byte[]> pairedPhoneIds, IEnumerable<byte[]> authorizedKeys, byte[]? hostPublicKey = null)
    {
        var keys = authorizedKeys.ToList();
        _phoneIds = pairedPhoneIds.Select(Convert.ToHexString).ToHashSet();
        if (hostPublicKey != null)
            _phoneIds.UnionWith(keys.Select(k => Convert.ToHexString(KeyPairing.KeyPhoneId(k, hostPublicKey))));
        _keyIds = keys.Select(k => Convert.ToHexString(KeyPairing.KeyId(k))).ToHashSet();
    }

    public bool Empty => _phoneIds.Count == 0 && _keyIds.Count == 0;

    /// <summary>Reads the paired phones (settings.json, read only), authorized_phones and host.pub.</summary>
    public static PhoneWatch Load(string settingsPath, KeyFiles keyFiles)
    {
        var ids = new List<byte[]>();
        if (File.Exists(settingsPath))
        {
            try
            {
                var s = System.Text.Json.JsonSerializer.Deserialize(File.ReadAllText(settingsPath), HostSettingsJson.Default.HostSettings);
                if (s?.PairedPhones != null) ids.AddRange(s.PairedPhones.Select(p => p.PhoneId));
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Text.Json.JsonException or NotSupportedException)
            {
                // unreadable now: only key phones count until the next reload
            }
        }
        byte[]? hostPub = null;
        try
        {
            if (File.Exists(keyFiles.HostPubPath)) hostPub = PubKeyFile.Parse(File.ReadAllText(keyFiles.HostPubPath))?.PublicKey;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // unreadable now: key sessions count by their key id only until the next reload
        }
        return new PhoneWatch(ids, keyFiles.ReadAuthorized().Select(a => a.Key.PublicKey), hostPub);
    }

    /// <summary>True when <paramref name="d"/> is a valid DISCOVER from a phone this PC knows.</summary>
    public bool IsKnownPhone(ReadOnlySpan<byte> d)
    {
        if (!WireCodec.TryDecodeDiscover(d, out var disc)) return false;
        if (_phoneIds.Contains(Convert.ToHexString(disc.PhoneId))) return true;
        return disc.PhoneKeyId is { } k && k.AsSpan().ContainsAnyExcept((byte)0) && _keyIds.Contains(Convert.ToHexString(k));
    }
}
