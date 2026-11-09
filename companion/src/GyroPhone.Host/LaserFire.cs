using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// A laser pointer overlay's fire output (OverlaySettings.LaserFire): while it is held, Draw paints in the Fire
/// colours. One number: <see cref="Mouse"/> + a mouse button's bit, <see cref="Key"/> + a keyboard usage,
/// <see cref="Pad"/> + a controller button's bit, <see cref="Lt"/> or <see cref="Rt"/>; 0 none. Same numbers as the
/// phone's LaserFire.
/// </summary>
public static class LaserFire
{
    public const int Mouse = 0x100, Key = 0x200, Pad = 0x300, Lt = 0x400, Rt = 0x401;
    public const int Default = Mouse; // left

    /// <summary>A trigger past this much (of 1023) counts as held.</summary>
    private const int TriggerHeld = 1023 * 3 / 10;

    /// <summary>The choices the settings offer, by group.</summary>
    public static readonly (string Label, int Code)[] MouseChoices =
        [("Left", Mouse), ("Right", Mouse + 1), ("Middle", Mouse + 2), ("Back", Mouse + 3), ("Forward", Mouse + 4)];

    public static readonly (string Label, int Code)[] PadChoices =
        [("A", Pad), ("B", Pad + 1), ("X", Pad + 2), ("Y", Pad + 3), ("LB", Pad + 4), ("RB", Pad + 5), ("LT", Lt), ("RT", Rt), ("LS", Pad + 8), ("RS", Pad + 9)];

    public static readonly (string Label, int Code)[] KeyChoices = MakeKeys();

    private static (string, int)[] MakeKeys()
    {
        var k = new List<(string, int)> { ("Space", Key + 0x2C) };
        for (int c = 0; c < 26; c++) k.Add((((char)('A' + c)).ToString(), Key + 0x04 + c));
        for (int d = 1; d <= 9; d++) k.Add((d.ToString(), Key + 0x1D + d));
        k.Add(("0", Key + 0x27));
        k.Add(("Enter", Key + 0x28));
        k.Add(("Tab", Key + 0x2B));
        k.Add(("Shift", Key + 0xE1));
        k.Add(("Ctrl", Key + 0xE0));
        k.Add(("Alt", Key + 0xE2));
        return [.. k];
    }

    /// <summary>
    /// Fire output <paramref name="code"/> is held in the phone's output <paramref name="s"/>, or on the PC's
    /// controller (<paramref name="pcHeld"/> by place, <paramref name="pcL2"/>/<paramref name="pcR2"/> pulled 0..1) when one is read.
    /// </summary>
    public static bool Held(int code, StateFrame s, uint pcHeld = 0, float pcL2 = 0f, float pcR2 = 0f)
    {
        int v = code & 0xFF;
        switch (code & 0xF00)
        {
            case Mouse: return (s.MouseButtons & (1 << v)) != 0;
            case Key: return s.IsKeyDown(v);
            case Pad: return (s.Buttons & (1 << v)) != 0 || (PadPlace(v) is { } p && (pcHeld & p) != 0);
            case Lt & 0xF00:
                return code == Lt ? s.Lt > TriggerHeld || pcL2 > 0.3f || (pcHeld & OverlayFrame.PadL2) != 0
                    : s.Rt > TriggerHeld || pcR2 > 0.3f || (pcHeld & OverlayFrame.PadR2) != 0;
            default: return false;
        }
    }

    /// <summary>A controller button's bit (the phone's GamepadButton) as a PC controller place bit; null when it has none.</summary>
    private static uint? PadPlace(int bit) => bit switch
    {
        0 => OverlayFrame.PadSouth, 1 => OverlayFrame.PadEast, 2 => OverlayFrame.PadWest, 3 => OverlayFrame.PadNorth,
        4 => OverlayFrame.PadL1, 5 => OverlayFrame.PadR1, 6 => OverlayFrame.PadMinus, 7 => OverlayFrame.PadPlus,
        8 => OverlayFrame.PadL3, 9 => OverlayFrame.PadR3, 10 => OverlayFrame.PadHome, 15 => OverlayFrame.PadCapture,
        _ => null,
    };
}
