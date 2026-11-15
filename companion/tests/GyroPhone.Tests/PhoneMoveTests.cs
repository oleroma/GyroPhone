using GyroPhone.Host;
using Xunit;

namespace GyroPhone.Tests;

public class PhoneMoveTests
{
    // 60 frames a second, gravity up plus `up` g upward.
    private static void Run(PhoneMove m, float seconds, float up)
    {
        for (int i = 0; i < (int)(seconds * 60); i++)
        {
            m.Turn(0f, 0f, 0f, 1f / 60f);
            m.Push(0f, 1f + up, 0f, 1f / 60f, null, 0.3f, 1f);
        }
    }

    [Fact]
    public void NudgeUpSlidesUpThenGlidesBack()
    {
        var m = new PhoneMove();
        Run(m, 0.5f, 0f);
        Assert.True(MathF.Abs(m.Y) < 0.001f);
        Run(m, 0.15f, 0.4f);
        Run(m, 0.15f, -0.4f);
        Assert.True(m.Y > 0.02f, $"moved up: {m.Y}");
        Run(m, 3f, 0f);
        Assert.True(MathF.Abs(m.Y) < 0.005f, $"back: {m.Y}");
    }

    [Fact]
    public void RatchetCentres()
    {
        var m = new PhoneMove();
        Run(m, 0.5f, 0f);
        Run(m, 0.2f, 0.4f);
        m.Center();
        Assert.Equal(0f, m.Y);
    }
}
