using GyroPhone.Protocol;

namespace GyroPhone.Host.Abstractions;

/// <summary>
/// Reads this PC's keyboard and mouse while the phone's "Press a key" screen listens (Windows: raw input). Only
/// reads: nothing is ever held back from Windows or the game, and input other programs inject (ours too) is skipped.
/// </summary>
public interface IKeyMouseListener
{
    /// <summary>Starts reading (idempotent).</summary>
    void Start();

    /// <summary>Stops reading and forgets what was held (idempotent).</summary>
    void Stop();

    /// <summary>
    /// Writes the keys (<see cref="PcPress.Key"/>, by HID usage) and mouse buttons held now, those pressed and let go
    /// since the last call, and a <see cref="PcPress.Wheel"/> for each way the wheel turned since then, into
    /// <paramref name="into"/>; returns how many.
    /// </summary>
    int Read(Span<PcPress> into);
}

/// <summary>No keyboard or mouse listener (Linux, tests).</summary>
public sealed class NullKeyMouseListener : IKeyMouseListener
{
    public static readonly NullKeyMouseListener Instance = new();

    public void Start() { }

    public void Stop() { }

    public int Read(Span<PcPress> into) => 0;
}
