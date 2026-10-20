namespace GyroPhone.Host.Abstractions;

/// <summary>Protects pairing keys at rest (DPAPI CurrentUser on Windows, plain in tests).</summary>
public interface IKeyProtector
{
    byte[] Protect(byte[] plain);

    /// <summary>Throws when the blob cannot be unprotected (e.g. another user's profile).</summary>
    byte[] Unprotect(byte[] blob);
}
