namespace GyroPhone.Tray;

/// <summary>
/// "Overlay timing log" (Performance › Debug): only while it is on do the overlays add up their draw times
/// (each window's, Direct2D's draw, end and present, and the 3D card's) for the log line. Off, nothing is summed.
/// </summary>
internal static class OverlayTiming
{
    public static volatile bool On;
}
