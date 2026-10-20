using System.Security.Cryptography;
using GyroPhone.Host.Abstractions;

namespace GyroPhone.Windows;

/// <summary>Wraps pairing keys with DPAPI for the current user, entropy "GyroPhone.v1".</summary>
public sealed class DpapiKeyProtector : IKeyProtector
{
    private static readonly byte[] Entropy = "GyroPhone.v1"u8.ToArray();

    public byte[] Protect(byte[] plain) => ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);

    public byte[] Unprotect(byte[] blob) => ProtectedData.Unprotect(blob, Entropy, DataProtectionScope.CurrentUser);
}
