namespace GyroPhone.Windows;

/// <summary>
/// A process has one raw mouse input target. "Press a key" listening (<see cref="RawInputListener"/>) takes it while it
/// runs; Diagnostics' delivery probe (<see cref="DeliveryProbe"/>) registers or removes it only while the listener
/// doesn't hold it, and registers again when the listener lets go (<see cref="DeliveryProbe.RawMouseReleased"/>).
/// </summary>
internal static class RawMouseOwner
{
    /// <summary>Held around every raw mouse register or remove in the process, and around <see cref="Listener"/>.</summary>
    public static readonly object Gate = new();

    /// <summary>The "Press a key" listener holds the raw mouse input (under <see cref="Gate"/>).</summary>
    public static bool Listener;
}
