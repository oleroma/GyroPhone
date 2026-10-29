namespace GyroPhone.Host.Abstractions;

/// <summary>
/// The bulk endpoints of a phone in accessory mode (direct USB). Read blocks for the next transfer
/// and throws IOException once the phone is gone; Write gives up after a short timeout (the app on
/// the phone may not be reading). Cancel unblocks a pending Read and Write; Dispose
/// follows once no call is running. Implemented with WinUSB; faked in tests.
/// </summary>
public interface IUsbPipe : IDisposable
{
    int Read(Span<byte> buffer);

    /// <summary>False when the transfer failed or timed out.</summary>
    bool Write(ReadOnlySpan<byte> data);

    void Cancel();
}
