namespace GyroPhone.Host.Abstractions;

/// <summary>
/// A controller plugged into this PC, read for "Use PC controller for gyro ratchet" (XInput on Windows).
/// Reads run on the implementation's own thread; <see cref="Read"/> only copies the latest state.
/// </summary>
public interface IPcPad
{
    /// <summary>Starts reading (idempotent), skipping the XInput slot <paramref name="ownSlot"/> returns (our own virtual pad, -1 = none).</summary>
    void Start(Func<int> ownSlot);

    /// <summary>Stops reading (idempotent).</summary>
    void Stop();

    /// <summary>
    /// The latest state: buttons held by place (<see cref="GyroPhone.Protocol.OverlayFrame"/> Pad bit order)
    /// and each place's press count into <paramref name="presses"/>. False when no controller is read.
    /// </summary>
    bool Read(uint[] presses, out uint held);

    /// <summary>
    /// The latest sticks and triggers into <paramref name="axes"/>: left x, y, right x, y (-1..1, y down),
    /// left and right trigger (0..1). False when no controller is read.
    /// </summary>
    bool ReadAxes(Span<float> axes);

    /// <summary>
    /// The buttons held by place at host time <paramref name="us"/> (the engine's clock, the one PONG
    /// uses), from a history of the last changes: what the PC gyro ratchets each sample by. 0 when no
    /// controller was read then.
    /// </summary>
    uint HeldAt(long us);

    /// <summary>What is read, for the main window: "Controller 2", or null when none is.</summary>
    string? Name { get; }
}

/// <summary>No PC controller (Linux, tests).</summary>
public sealed class NullPcPad : IPcPad
{
    public static readonly NullPcPad Instance = new();

    public void Start(Func<int> ownSlot) { }

    public void Stop() { }

    public bool Read(uint[] presses, out uint held)
    {
        held = 0;
        return false;
    }

    public bool ReadAxes(Span<float> axes)
    {
        axes.Clear();
        return false;
    }

    public uint HeldAt(long us) => 0;

    public string? Name => null;
}
