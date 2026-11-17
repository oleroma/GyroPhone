using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// The engine's published copy of the injected held set (the keys and mouse buttons it holds down now), for the
/// held-keys marker and a Diagnostics save's header. The engine writes it under its lock after each Flush; readers
/// copy it without that lock (<see cref="SeqLock"/>), so they never keep the input thread waiting.
/// </summary>
internal sealed class InjectedView
{
    private readonly SeqLock _seq = new();
    private readonly byte[] _keys = new byte[StateFrame.KeyBitmapSize];
    private byte _mouse;
    private int _version;

    public int Version => _version;

    public void Publish(byte[] keys, byte mouse, int version)
    {
        _seq.BeginWrite();
        keys.AsSpan().CopyTo(_keys);
        _mouse = mouse;
        _version = version;
        _seq.EndWrite();
    }

    /// <summary>The injected held set into <paramref name="keys"/>; returns its version.</summary>
    public int Read(Span<byte> keys, out byte mouseButtons)
    {
        while (true)
        {
            int s = _seq.ReadBegin();
            _keys.AsSpan().CopyTo(keys);
            mouseButtons = _mouse;
            int v = _version;
            if (!_seq.ReadRetry(s)) return v;
        }
    }
}
