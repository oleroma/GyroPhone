using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host.Abstractions;

/// <summary>Virtual game pad (ViGEm on Windows). Called under the engine lock; must not block.</summary>
public interface IPadSink
{
    /// <summary>The bus driver is present and usable.</summary>
    bool DriverOk { get; }

    /// <summary>Currently plugged pad type, or <see cref="OutputMode.None"/>.</summary>
    OutputMode Plugged { get; }

    /// <summary>Plugs a pad of the given type (unplugging any other). False when it could not.</summary>
    bool Plug(OutputMode mode);

    void Unplug();

    void Submit(in X360Frame frame);

    void Submit(in Ds4Frame frame);

    /// <summary>Raised from the driver's thread when the game changes rumble (large, small motor).</summary>
    event Action<byte, byte>? Rumble;
}

/// <summary>Pad sink used when the ViGEmBus driver is missing (or --no-vigem): nothing is plugged, nothing is sent.</summary>
public sealed class NullPadSink : IPadSink
{
    public static readonly NullPadSink Instance = new();

    public bool DriverOk => false;

    public OutputMode Plugged => OutputMode.None;

    public bool Plug(OutputMode mode) => false;

    public void Unplug() { }

    public void Submit(in X360Frame frame) { }

    public void Submit(in Ds4Frame frame) { }

    public event Action<byte, byte>? Rumble
    {
        add { }
        remove { }
    }
}
