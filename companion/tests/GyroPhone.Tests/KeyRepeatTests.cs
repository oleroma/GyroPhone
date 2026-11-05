using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class KeyRepeatTests
{
    private static byte[] Held(params int[] usages)
    {
        var keys = new byte[StateFrame.KeyBitmapSize];
        foreach (var u in usages) keys[u >> 3] |= (byte)(1 << (u & 7));
        return keys;
    }

    [Fact]
    public void HeldVolumeRepeatsAfterTheDelay()
    {
        var r = new KeyRepeat();
        var out_ = new InjectEvent[4];
        var held = Held(0x80);
        Assert.Equal(0, r.Due(held, 0, out_));
        Assert.Equal(0, r.Due(held, KeyRepeat.DelayUs - 1, out_));
        Assert.Equal(1, r.Due(held, KeyRepeat.DelayUs, out_));
        Assert.Equal(0x80, out_[0].Usage);
        Assert.False(out_[0].IsKeyUp);
        Assert.Equal(InjectEvent.VkVolumeUp, out_[0].Vk);
        Assert.Equal(0, r.Due(held, KeyRepeat.DelayUs + KeyRepeat.IntervalUs - 1, out_));
        Assert.Equal(1, r.Due(held, KeyRepeat.DelayUs + KeyRepeat.IntervalUs, out_));
    }

    [Fact]
    public void ReleaseRestartsTheDelayAndOtherKeysNeverRepeat()
    {
        var r = new KeyRepeat();
        var out_ = new InjectEvent[4];
        r.Due(Held(0x81), 0, out_);
        Assert.Equal(0, r.Due(Held(), KeyRepeat.DelayUs, out_));
        Assert.Equal(0, r.Due(Held(0x81), KeyRepeat.DelayUs + 1, out_));
        Assert.Equal(0, r.Due(Held(0x81), 2 * KeyRepeat.DelayUs, out_));
        Assert.Equal(1, r.Due(Held(0x81), 2 * KeyRepeat.DelayUs + 1, out_));
        // Mute and letters stay single presses.
        var other = new KeyRepeat();
        other.Due(Held(0x7F, 0x04), 0, out_);
        Assert.Equal(0, other.Due(Held(0x7F, 0x04), 10 * KeyRepeat.DelayUs, out_));
    }
}
