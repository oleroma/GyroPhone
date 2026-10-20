using GyroPhone.Host.Update;

namespace GyroPhone.Tests;

public sealed class UpdateInfoTests
{
    [Theory]
    [InlineData("0.1.30-2b6f965", 30)]
    [InlineData("0.1.7-2b6f9651ed485782b8d555df841f9cca0d6a0629", 7)]
    [InlineData("1.0.0", null)]
    [InlineData("1.0.0+2b6f965", null)]
    [InlineData(null, null)]
    public void BuildNumberFromProductVersion(string? version, int? expected) =>
        Assert.Equal(expected, UpdateInfo.BuildNumber(version));

    [Theory]
    [InlineData("0.1.30-2b6f965", "0.1.31-abc1234", true)]
    [InlineData("0.1.31-abc1234", "0.1.30-2b6f965", true)] // whatever update.cmd installed wins, even an older branch build
    [InlineData("0.1.30-2b6f965", "0.1.30-2b6f965", false)]
    [InlineData("1.0.0", "0.1.31-abc1234", false)] // local build: never restarts
    [InlineData("0.1.30-2b6f965", "1.0.0", false)]
    [InlineData("0.1.30-2b6f965", null, false)] // exe missing mid-copy
    public void RestartsOnlyForAnotherCiBuild(string? running, string? onDisk, bool expected) =>
        Assert.Equal(expected, UpdateInfo.IsReplaced(running, onDisk));
}
