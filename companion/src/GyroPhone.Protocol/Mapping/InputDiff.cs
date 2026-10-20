namespace GyroPhone.Protocol.Mapping;

/// <summary>
/// Diffs the injected keyboard/mouse state against a target STATE and writes SendInput events
/// (SPEC 3.3). Order: modifier downs, key downs, key ups, modifier ups, mouse buttons, move, wheel.
/// The injected state is updated in place for every event written, so anything that did not fit
/// in the output span is picked up by the next call.
/// </summary>
public static class InputDiff
{
    public const int MaxEvents = 96;
    private const int MouseButtonCount = 5;
    // Room kept for mouse buttons, move and wheel so keys can never starve them.
    private const int MouseReserve = MouseButtonCount + 2;
    private const int ModByte = StateFrame.ModifierByte;

    /// <summary>
    /// injected/injectedMouse: what the host has sent so far (updated). target/targetMouse: the
    /// new STATE. Usages 0..3 and usages with no scan code are never injected; each newly seen
    /// unknown usage increments <paramref name="unknown"/>. Returns the event count.
    /// </summary>
    public static int Diff(Span<byte> injected, ReadOnlySpan<byte> target, ref byte injectedMouse, byte targetMouse,
        int dx, int dy, int wheel, Span<InjectEvent> output, ref int unknown)
    {
        int n = 0;
        int keyLimit = Math.Min(output.Length, MaxEvents) - MouseReserve;

        // 1. modifier downs, 2. key downs
        n = Keys(injected, target, ModByte, ModByte + 1, down: true, output, n, keyLimit, ref unknown);
        for (int i = 0; i < StateFrame.KeyBitmapSize; i++)
            if (i != ModByte) n = Keys(injected, target, i, i + 1, down: true, output, n, keyLimit, ref unknown);
        // 3. key ups, 4. modifier ups
        for (int i = 0; i < StateFrame.KeyBitmapSize; i++)
            if (i != ModByte) n = Keys(injected, target, i, i + 1, down: false, output, n, keyLimit, ref unknown);
        n = Keys(injected, target, ModByte, ModByte + 1, down: false, output, n, keyLimit, ref unknown);

        // 5. mouse buttons
        int changed = (injectedMouse ^ targetMouse) & 0x1F;
        for (int bit = 0; bit < MouseButtonCount; bit++)
        {
            if ((changed & (1 << bit)) == 0) continue;
            bool up = (targetMouse & (1 << bit)) == 0;
            output[n++] = InjectEvent.MouseButton(bit, up);
        }
        injectedMouse = (byte)(targetMouse & 0x1F);

        // 6. move, 7. wheel
        if (dx != 0 || dy != 0) output[n++] = InjectEvent.Move(dx, dy);
        if (wheel != 0) output[n++] = InjectEvent.Wheel(wheel);
        return n;
    }

    /// <summary>Releases everything injected: key ups, then modifier ups, then mouse-button ups. Clears the state.</summary>
    public static int ReleaseAll(Span<byte> injected, ref byte injectedMouse, Span<InjectEvent> output)
    {
        Span<byte> none = stackalloc byte[StateFrame.KeyBitmapSize];
        int unknown = 0;
        int n = 0;
        // Release may need more than the normal key budget, so loop until nothing is held.
        int limit = output.Length - MouseButtonCount;
        for (int i = 0; i < StateFrame.KeyBitmapSize; i++)
            if (i != ModByte) n = Keys(injected, none, i, i + 1, down: false, output, n, limit, ref unknown);
        n = Keys(injected, none, ModByte, ModByte + 1, down: false, output, n, limit, ref unknown);
        for (int bit = 0; bit < MouseButtonCount; bit++)
            if ((injectedMouse & (1 << bit)) != 0) output[n++] = InjectEvent.MouseButton(bit, up: true);
        injectedMouse = 0;
        return n;
    }

    /// <summary>True when any key or mouse button is held in this state.</summary>
    public static bool AnyHeld(ReadOnlySpan<byte> injected, byte injectedMouse) =>
        injectedMouse != 0 || injected.IndexOfAnyExcept((byte)0) >= 0;

    private static int Keys(Span<byte> injected, ReadOnlySpan<byte> target, int fromByte, int toByte, bool down,
        Span<InjectEvent> output, int n, int limit, ref int unknown)
    {
        for (int i = fromByte; i < toByte; i++)
        {
            int want = target[i];
            if (i == 0) want &= 0xF0; // usages 0..3 are not keys
            int diff = (injected[i] ^ want) & (down ? want : ~want) & 0xFF;
            if (diff == 0) continue;
            for (int bit = 0; bit < 8; bit++)
            {
                if ((diff & (1 << bit)) == 0) continue;
                int usage = (i << 3) | bit;
                var sc = HidScanCodes.For(usage);
                if (sc.IsMapped)
                {
                    if (n >= limit) return n;
                    output[n++] = InjectEvent.Key((byte)usage, sc, up: !down);
                }
                else if (down)
                {
                    unknown++;
                }
                // Track unknown usages as injected too, so they are counted once, not every flush.
                if (down) injected[i] |= (byte)(1 << bit);
                else injected[i] &= (byte)~(1 << bit);
            }
        }
        return n;
    }
}
