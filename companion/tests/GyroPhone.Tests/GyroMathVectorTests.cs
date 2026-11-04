using System.Text.Json;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>
/// GyroMath.cs must give exactly what the phone's GyroMath.kt gives: every case of the shared
/// gyro-math-v1.json (written by the Kotlin GyroMathVectorsTest) replayed step by step, mouse and
/// wheel counts equal and the game pad axes equal to the bit. Also decodes its STATE_GYRO tail.
/// </summary>
public class GyroMathVectorTests
{
    private static readonly string FilePath = Path.Combine(AppContext.BaseDirectory, "vectors", "gyro-math-v1.json");

    private static JsonElement Root()
    {
        Assert.True(File.Exists(FilePath),
            "gyro-math-v1.json is missing: run the Kotlin GyroMathVectorsTest with -DregenVectors=true to write it");
        return JsonDocument.Parse(File.ReadAllText(FilePath)).RootElement;
    }

    /// <summary>A float as the Kotlin side wrote it: its exact bits ("nameBits") when there, else the number.</summary>
    private static float F(JsonElement e, string name)
    {
        if (e.TryGetProperty(name + "Bits", out var bits)) return BitConverter.Int32BitsToSingle(bits.GetInt32());
        return e.GetProperty(name).GetSingle();
    }

    private static float[] Floats(JsonElement e, string name)
    {
        if (e.TryGetProperty(name + "Bits", out var bits))
            return bits.EnumerateArray().Select(v => BitConverter.Int32BitsToSingle(v.GetInt32())).ToArray();
        return e.GetProperty(name).EnumerateArray().Select(v => v.GetSingle()).ToArray();
    }

    private static bool[] Bools(JsonElement e, string name) => e.GetProperty(name).EnumerateArray().Select(v => v.GetBoolean()).ToArray();

    private static GyroMath.Config Config(JsonElement c)
    {
        var cfg = new GyroMath.Config();
        Floats(c, "sensitivity").CopyTo(cfg.Sensitivity, 0);
        Bools(c, "inertia").CopyTo(cfg.Inertia, 0);
        Floats(c, "glideMs").CopyTo(cfg.GlideMs, 0);
        Bools(c, "linear").CopyTo(cfg.Linear, 0);
        foreach (var r in c.GetProperty("routes").EnumerateArray())
            cfg.Routes.Add(new GyroMath.Route(r.GetProperty("axis").GetInt32(), r.GetProperty("target").GetInt32(), F(r, "scale")));
        if (c.TryGetProperty("curvesBits", out var curves))
        {
            var axisSpeed = Bools(c, "axisSpeed");
            int a = 0;
            foreach (var curve in curves.EnumerateArray())
            {
                var flat = curve.EnumerateArray().Select(v => BitConverter.Int32BitsToSingle(v.GetInt32())).ToArray();
                cfg.SetCurve(a, flat, axisSpeed[a]);
                a++;
            }
        }
        return cfg;
    }

    [Fact]
    public void EveryCaseMatchesTheKotlinMath()
    {
        var cases = Root().GetProperty("cases");
        Assert.True(cases.GetArrayLength() > 0);
        foreach (var k in cases.EnumerateArray())
        {
            string name = k.GetProperty("name").GetString()!;
            var cfg = Config(k.GetProperty("config"));
            float tightening = F(k, "tightening");
            float smoothing = F(k, "smoothing");
            var math = new GyroMath();
            int i = 0;
            foreach (var s in k.GetProperty("steps").EnumerateArray())
            {
                var o = math.Step(F(s, "yaw"), F(s, "pitch"), F(s, "roll"), F(s, "dt"), cfg, s.GetProperty("axes").GetInt32(), tightening, smoothing);
                string at = $"{name} step {i}";
                Assert.True(s.GetProperty("dx").GetInt32() == o.Dx, $"{at}: dx {o.Dx}, Kotlin {s.GetProperty("dx").GetInt32()}");
                Assert.True(s.GetProperty("dy").GetInt32() == o.Dy, $"{at}: dy {o.Dy}, Kotlin {s.GetProperty("dy").GetInt32()}");
                Assert.True(s.GetProperty("wheel").GetInt32() == o.Wheel, $"{at}: wheel {o.Wheel}");
                Assert.True(s.GetProperty("hwheel").GetInt32() == o.HWheel, $"{at}: hwheel {o.HWheel}");
                var pad = Floats(s, "pad");
                Assert.Equal(GyroMath.PadAxes, pad.Length);
                for (int a = 0; a < GyroMath.PadAxes; a++)
                {
                    Assert.True(BitConverter.SingleToInt32Bits(pad[a]) == BitConverter.SingleToInt32Bits(o.Pad[a]),
                        $"{at}: pad[{a}] {o.Pad[a]:R}, Kotlin {pad[a]:R}");
                }
                Assert.True(s.GetProperty("coasting").GetBoolean() == math.Coasting, $"{at}: coasting {math.Coasting}");
                i++;
            }
        }
    }

    [Fact]
    public void TailVectorDecodesAndEncodesTheSame()
    {
        var root = Root();
        if (!root.TryGetProperty("tail", out var tail))
        {
            Assert.Fail("gyro-math-v1.json has no \"tail\" vector");
            return;
        }
        var raw = Hex.B(tail.GetProperty("hex").GetString()!);
        // The tail alone, or a whole STATE_GYRO plaintext.
        var bytes = raw.Length == Wire.StateGyroPlain ? raw.AsSpan(Wire.StateHiresPlain).ToArray() : raw;
        Assert.Equal(PcGyroTail.Size, bytes.Length);
        var t = new PcGyroTail();
        t.Decode(bytes);
        var again = new byte[PcGyroTail.Size];
        t.Encode(again);
        Assert.Equal(Hex.S(bytes), Hex.S(again));
        if (raw.Length == Wire.StateGyroPlain)
        {
            var f = new StateFrame();
            f.Decode(raw);
            Assert.True(f.HasPcGyro);
        }

        // Whatever named fields the vector also lists must match the decoded tail.
        foreach (var p in tail.EnumerateObject())
        {
            switch (p.Name)
            {
                case "flags": Assert.Equal(p.Value.GetInt32(), t.Flags); break;
                case "n" or "count": Assert.Equal(p.Value.GetInt32(), t.Count); break;
                case "seq" or "newestSeq": Assert.Equal(p.Value.GetInt32(), t.NewestSeq); break;
                case "timeUs" or "newestTimeUs" or "time": Assert.Equal(p.Value.GetInt64(), t.NewestTimeUs); break;
                case "totals":
                    Assert.Equal(p.Value.EnumerateArray().Select(v => v.GetInt64()), t.Totals);
                    break;
                case "tightening": Assert.Equal(F(tail, "tightening"), t.Tightening); break;
                case "smoothing": Assert.Equal(F(tail, "smoothing"), t.Smoothing); break;
                case "samples":
                    int i = 0;
                    foreach (var s in p.Value.EnumerateArray())
                    {
                        if (s.TryGetProperty("dtUs", out var dt)) Assert.Equal(dt.GetInt32(), t.DtUs[i]);
                        if (s.TryGetProperty("yaw", out var y)) Assert.Equal(y.GetInt32(), t.Rate[i * 3]);
                        if (s.TryGetProperty("pitch", out var pi)) Assert.Equal(pi.GetInt32(), t.Rate[i * 3 + 1]);
                        if (s.TryGetProperty("roll", out var r)) Assert.Equal(r.GetInt32(), t.Rate[i * 3 + 2]);
                        if (s.TryGetProperty("axes", out var ax)) Assert.Equal(ax.GetInt32(), t.Axes[i]);
                        i++;
                    }
                    Assert.Equal(t.Count, i);
                    break;
                case "config":
                    // As on the wire: glide bits (inertia axis i = bit i, linear = bit 3 + i), routes, and the global filters.
                    var c = p.Value;
                    Assert.Equal(Floats(c, "sensitivity"), t.Config.Sensitivity);
                    Assert.Equal(Floats(c, "glideMs"), t.Config.GlideMs);
                    if (c.TryGetProperty("glideBits", out var gb))
                    {
                        for (int a = 0; a < 3; a++)
                        {
                            Assert.Equal((gb.GetInt32() & (1 << a)) != 0, t.Config.Inertia[a]);
                            Assert.Equal((gb.GetInt32() & (1 << (3 + a))) != 0, t.Config.Linear[a]);
                        }
                    }
                    if (c.TryGetProperty("routeCount", out var rc)) Assert.Equal(rc.GetInt32(), t.Config.Routes.Count);
                    if (c.TryGetProperty("tightening", out _)) Assert.Equal(F(c, "tightening"), t.Tightening);
                    if (c.TryGetProperty("smoothing", out _)) Assert.Equal(F(c, "smoothing"), t.Smoothing);
                    var routes = c.GetProperty("routes").EnumerateArray()
                        .Select(r => new GyroMath.Route(r.GetProperty("axis").GetInt32(), r.GetProperty("target").GetInt32(), F(r, "scale")));
                    Assert.Equal(routes, t.Config.Routes);
                    if (c.TryGetProperty("curves", out var curves))
                    {
                        int a = 0;
                        foreach (var curve in curves.EnumerateArray())
                        {
                            var q = curve.GetProperty("q").EnumerateArray().Select(v => v.GetInt32()).ToArray();
                            Assert.Equal(q.Length / 2, t.Config.CurvePoints[a]);
                            Assert.Equal(curve.GetProperty("axisSpeed").GetBoolean(), t.Config.AxisSpeed[a]);
                            for (int j = 0; j < q.Length / 2; j++)
                            {
                                Assert.Equal(q[2 * j] / 10f, t.Config.Curves[a][2 * j]);
                                Assert.Equal(q[2 * j + 1] / 1000f, t.Config.Curves[a][2 * j + 1]);
                            }
                            a++;
                        }
                    }
                    break;
            }
        }
    }
}
