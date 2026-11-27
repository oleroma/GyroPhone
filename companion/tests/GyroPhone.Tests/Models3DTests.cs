using System.Numerics;
using GyroPhone.Host.Models3D;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public class Models3DTests
{
    [Theory]
    [InlineData(Model3D.OnePlus15, 4000)]
    [InlineData(Model3D.GpHandles, 10000)]
    [InlineData(Model3D.EightBitDo, 20000)]
    public void ShippedModelsLoadWithKnownMaterials(string file, int minTriangles)
    {
        var m = Model3D.Shipped(file);
        Assert.True(m.Indices.Length / 3 >= minTriangles);
        Assert.Equal(0, m.Indices.Length % 3);
        Assert.All(m.Indices, i => Assert.True(i < m.Vertices.Length));
        // Every part index and material slot in range; normals unit length.
        Assert.All(m.Vertices, v =>
        {
            Assert.InRange((int)v.P.W, 0, m.Parts.Length - 1);
            Assert.InRange((int)v.N.W, 0, Materials.Slots.Length - 1);
            Assert.InRange(new Vector3(v.N.X, v.N.Y, v.N.Z).Length(), 0.9f, 1.1f);
        });
    }

    [Fact]
    public void PhoneHasItsScreenFacingTheViewer()
    {
        var m = Model3D.Shipped(Model3D.OnePlus15);
        int screen = m.PartIndex("op15_screen");
        Assert.True(screen >= 0);
        var p = m.Parts[screen];
        // Landscape, about 15.7 × 7.3 cm, at the front.
        Assert.InRange(p.Max.X - p.Min.X, 15f, 16.5f);
        Assert.InRange(p.Max.Y - p.Min.Y, 7f, 7.6f);
        Assert.True(p.Min.Z >= m.Max.Z - 0.05f);
        Assert.Contains(m.Vertices, v => (int)v.N.W == Materials.Screen);
    }

    [Theory]
    [InlineData(Model3D.GpHandles)]
    [InlineData(Model3D.EightBitDo)]
    public void RigFindsEveryControl(string file)
    {
        var rig = new ControllerRig(Model3D.Shipped(file));
        uint all = 0;
        foreach (var g in rig.Groups) all |= g.Button;
        foreach (var b in new[] { OverlayFrame.PadSouth, OverlayFrame.PadEast, OverlayFrame.PadWest, OverlayFrame.PadNorth, OverlayFrame.PadL1,
                     OverlayFrame.PadR1, OverlayFrame.PadL2, OverlayFrame.PadR2, OverlayFrame.PadMinus, OverlayFrame.PadPlus, OverlayFrame.PadHome,
                     OverlayFrame.PadCapture, OverlayFrame.PadL3, OverlayFrame.PadR3 })
            Assert.True((all & b) != 0, $"button {b} has no part");
        // The D-pad: four buttons or one that rocks.
        Assert.True(rig.Groups.Any(g => g.Motion == ControllerRig.Motion.Rock) || (all & OverlayFrame.PadUp) != 0);
        // No part moves with two groups.
        var parts = rig.Groups.SelectMany(g => g.Parts).ToList();
        Assert.Equal(parts.Count, parts.Distinct().Count());
        // Face buttons by place: south is the lowest.
        var south = rig.Groups.Single(g => g.Button == OverlayFrame.PadSouth);
        var north = rig.Groups.Single(g => g.Button == OverlayFrame.PadNorth);
        Assert.True(south.Pivot.Y < north.Pivot.Y);
        // Their labels move with them.
        Assert.True(south.Parts.Length >= 2);
    }

    [Fact]
    public void PressedButtonSinksBouncesAndSettles()
    {
        var rig = new ControllerRig(Model3D.Shipped(Model3D.GpHandles));
        var st = new RigState(rig);
        var f = new OverlayFrame();
        double t = 0;
        Assert.False(st.Step(f, t)); // the first step jumps to the rest
        int g = rig.Groups.ToList().FindIndex(x => x.Button == OverlayFrame.PadSouth);
        f.PadButtons = OverlayFrame.PadSouth;
        float most = 0f;
        bool moving = true;
        int frames = 0;
        while (moving && frames < 200)
        {
            t += 1 / 60.0;
            moving = st.Step(f, t);
            most = MathF.Max(most, st.Press(g));
            frames++;
        }
        // Down within a few frames, past the bottom once (a small bounce), then still.
        Assert.False(moving);
        Assert.InRange(frames, 5, 40);
        Assert.InRange(most, 1.02f, 1.4f);
        Assert.Equal(1f, st.Press(g));
        Assert.True(st.Glow(g) > 0.99f);
        // The part sinks into the face.
        var moves = new Matrix4x4[rig.Model.Parts.Length];
        var glows = new float[moves.Length];
        rig.Pose(st, moves, glows);
        Assert.True(moves[rig.Groups[g].Parts[0]].M43 < -0.05f);
        // Let go: back up and the glow fades.
        f.PadButtons = 0;
        frames = 0;
        do
        {
            t += 1 / 60.0;
            frames++;
        } while (st.Step(f, t) && frames < 200);
        Assert.Equal(0f, st.Press(g));
        Assert.Equal(0f, st.Glow(g));
        Assert.InRange(frames, 5, 60);
    }

    [Fact]
    public void StickTiltsTowardItsDirection()
    {
        var rig = new ControllerRig(Model3D.Shipped(Model3D.EightBitDo));
        var st = new RigState(rig);
        var f = new OverlayFrame();
        f.PadSticks[0] = 1f; // left stick right
        st.Step(f, 0);
        var moves = new Matrix4x4[rig.Model.Parts.Length];
        rig.Pose(st, moves, new float[moves.Length]);
        var stick = rig.Groups.Single(g => g.Motion == ControllerRig.Motion.Stick && g.Axis == 0);
        int cap = rig.Model.PartIndex("8b_stick_l_cap");
        var top = rig.Model.Parts[cap].Centre;
        var moved = Vector3.Transform(top, moves[cap]);
        Assert.True(moved.X > top.X + 0.1f);
        // The pivot itself stays.
        Assert.True(Vector3.Distance(Vector3.Transform(stick.Pivot, moves[cap]), stick.Pivot) < 1e-3f);
    }
}
