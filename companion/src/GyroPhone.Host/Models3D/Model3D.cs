using System.Buffers.Binary;
using System.Numerics;
using System.Text.Json;

namespace GyroPhone.Host.Models3D;

/// <summary>
/// A 3D model for the phone overlay's real look, read from a glTF binary (.glb) built in Blender (see
/// docs/spec/pc-overlays.md): every node is one part, its points already in model space, in centimetres, with x right,
/// y up and z toward the viewer (the phone held landscape, its camera end on the left, its screen facing you). Each
/// primitive's material name says how it looks (<see cref="Materials"/>). Only what the overlay needs is read:
/// points, normals and triangles.
/// </summary>
public sealed class Model3D
{
    /// <summary>One corner: its point and the part it belongs to (P.W), its normal and its material (N.W).</summary>
    public struct Vertex
    {
        public Vector4 P, N;
    }

    /// <summary>One part (a node of the model): its name and the box round its points.</summary>
    public sealed record Part(string Name, Vector3 Min, Vector3 Max)
    {
        public Vector3 Centre => (Min + Max) / 2f;
    }

    public required string Name { get; init; }
    public required Vertex[] Vertices { get; init; }
    /// <summary>Triangles, three corners each (into <see cref="Vertices"/>).</summary>
    public required uint[] Indices { get; init; }
    public required Part[] Parts { get; init; }
    /// <summary>The box round the whole model.</summary>
    public required Vector3 Min { get; init; }
    public required Vector3 Max { get; init; }

    /// <summary>The part named <paramref name="name"/>, or −1.</summary>
    public int PartIndex(string name) => Array.FindIndex(Parts, p => p.Name == name);

    /// <summary>The models that ship with the companion (embedded in this assembly), by file name.</summary>
    public const string OnePlus15 = "oneplus15.glb", GpHandles = "gp-handles.glb", EightBitDo = "8bitdo-ultimate-mobile.glb";

    private static readonly Dictionary<string, Model3D> Loaded = [];

    /// <summary>The shipped model <paramref name="file"/> (read once, then kept).</summary>
    public static Model3D Shipped(string file)
    {
        lock (Loaded)
        {
            if (Loaded.TryGetValue(file, out var m)) return m;
            var asm = typeof(Model3D).Assembly;
            using var s = asm.GetManifestResourceStream("GyroPhone.Host.Models3D." + file)
                ?? throw new FileNotFoundException("model not shipped: " + file);
            var bytes = new byte[s.Length];
            s.ReadExactly(bytes);
            m = Read(bytes, Path.GetFileNameWithoutExtension(file));
            Loaded[file] = m;
            return m;
        }
    }

    /// <summary>Reads a .glb (version 2, one buffer in its BIN chunk).</summary>
    public static Model3D Read(ReadOnlySpan<byte> glb, string name)
    {
        if (glb.Length < 20 || BinaryPrimitives.ReadUInt32LittleEndian(glb) != 0x46546C67) throw new InvalidDataException("not a glb");
        int jsonLen = (int)BinaryPrimitives.ReadUInt32LittleEndian(glb[12..]);
        if (BinaryPrimitives.ReadUInt32LittleEndian(glb[16..]) != 0x4E4F534A) throw new InvalidDataException("glb: no JSON chunk");
        var json = glb.Slice(20, jsonLen);
        int binAt = 20 + jsonLen;
        int binLen = (int)BinaryPrimitives.ReadUInt32LittleEndian(glb[binAt..]);
        var bin = glb.Slice(binAt + 8, binLen);
        using var doc = JsonDocument.Parse(json.ToArray());
        var root = doc.RootElement;
        var accessors = root.GetProperty("accessors");
        var views = root.GetProperty("bufferViews");
        var meshes = root.GetProperty("meshes");
        var materials = root.TryGetProperty("materials", out var mats) ? mats : default;

        var verts = new List<Vertex>();
        var indices = new List<uint>();
        var parts = new List<Part>();
        var min = new Vector3(float.MaxValue);
        var max = new Vector3(float.MinValue);
        foreach (var node in root.GetProperty("nodes").EnumerateArray())
        {
            if (!node.TryGetProperty("mesh", out var meshAt)) continue;
            int part = parts.Count;
            var pMin = new Vector3(float.MaxValue);
            var pMax = new Vector3(float.MinValue);
            foreach (var prim in meshes[meshAt.GetInt32()].GetProperty("primitives").EnumerateArray())
            {
                var attrs = prim.GetProperty("attributes");
                var pos = Floats(bin, accessors, views, attrs.GetProperty("POSITION").GetInt32(), 3);
                var nrm = Floats(bin, accessors, views, attrs.GetProperty("NORMAL").GetInt32(), 3);
                string matName = prim.TryGetProperty("material", out var mi) && materials.ValueKind == JsonValueKind.Array
                    ? materials[mi.GetInt32()].GetProperty("name").GetString() ?? "" : "";
                float slot = Materials.SlotOf(matName);
                uint first = (uint)verts.Count;
                int count = pos.Length / 3;
                for (int i = 0; i < count; i++)
                {
                    var p = new Vector3(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
                    var n = new Vector3(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2]);
                    verts.Add(new Vertex { P = new Vector4(p, part), N = new Vector4(n, slot) });
                    pMin = Vector3.Min(pMin, p);
                    pMax = Vector3.Max(pMax, p);
                }
                if (prim.TryGetProperty("indices", out var ia))
                    foreach (var i in ReadIndices(bin, accessors, views, ia.GetInt32())) indices.Add(first + i);
                else
                    for (uint i = 0; i < count; i++) indices.Add(first + i);
            }
            parts.Add(new Part(node.TryGetProperty("name", out var nm) ? nm.GetString() ?? "" : "", pMin, pMax));
            min = Vector3.Min(min, pMin);
            max = Vector3.Max(max, pMax);
        }
        if (verts.Count == 0) throw new InvalidDataException("glb: no meshes");
        return new Model3D { Name = name, Vertices = [.. verts], Indices = [.. indices], Parts = [.. parts], Min = min, Max = max };
    }

    private static (int Offset, int Count, int Stride) View(JsonElement accessors, JsonElement views, int accessor, int size)
    {
        var a = accessors[accessor];
        var v = views[a.GetProperty("bufferView").GetInt32()];
        int offset = (v.TryGetProperty("byteOffset", out var vo) ? vo.GetInt32() : 0) + (a.TryGetProperty("byteOffset", out var ao) ? ao.GetInt32() : 0);
        int stride = v.TryGetProperty("byteStride", out var bs) ? bs.GetInt32() : size;
        return (offset, a.GetProperty("count").GetInt32(), stride);
    }

    private static float[] Floats(ReadOnlySpan<byte> bin, JsonElement accessors, JsonElement views, int accessor, int n)
    {
        if (accessors[accessor].GetProperty("componentType").GetInt32() != 5126) throw new InvalidDataException("glb: points not float");
        var (offset, count, stride) = View(accessors, views, accessor, n * 4);
        var r = new float[count * n];
        for (int i = 0; i < count; i++)
            for (int k = 0; k < n; k++)
                r[i * n + k] = BinaryPrimitives.ReadSingleLittleEndian(bin[(offset + i * stride + k * 4)..]);
        return r;
    }

    private static uint[] ReadIndices(ReadOnlySpan<byte> bin, JsonElement accessors, JsonElement views, int accessor)
    {
        int type = accessors[accessor].GetProperty("componentType").GetInt32();
        int size = type switch { 5121 => 1, 5123 => 2, 5125 => 4, _ => throw new InvalidDataException("glb: index type " + type) };
        var (offset, count, stride) = View(accessors, views, accessor, size);
        var r = new uint[count];
        for (int i = 0; i < count; i++)
        {
            var at = bin[(offset + i * stride)..];
            r[i] = size switch { 1 => at[0], 2 => BinaryPrimitives.ReadUInt16LittleEndian(at), _ => BinaryPrimitives.ReadUInt32LittleEndian(at) };
        }
        return r;
    }
}

/// <summary>The materials the models name, by slot (the number the graphics card's material table is read by).</summary>
public static class Materials
{
    /// <summary>
    /// Phone: frame (brushed metal, the body colour), frontglass (black glass round the screen), backglass (frosted glass
    /// with a sparkle, the body colour), plateau (the camera plateau), lensglass, lensring, lens, flash, port, screen.
    /// Controllers: plastic (the handles colour), well (darker), stem (chrome), stickcap and stickknurl (rubber),
    /// stickmetal, button (gloss), backpad (rubber), and the face buttons' letters.
    /// </summary>
    public static readonly string[] Slots =
    [
        "plastic", "frame", "frontglass", "backglass", "plateau", "lensglass", "lensring", "lens", "flash", "port", "screen",
        "well", "stem", "stickcap", "stickknurl", "stickmetal", "button", "backpad",
        "letter_yellow", "letter_red", "letter_green", "letter_blue",
    ];

    /// <summary>The slot of material <paramref name="name"/> (Blender's ".001" endings ignored); an unknown one is plastic (0).</summary>
    public static int SlotOf(string name)
    {
        int dot = name.IndexOf('.');
        if (dot > 0) name = name[..dot];
        int i = Array.IndexOf(Slots, name);
        return i < 0 ? 0 : i;
    }

    public static readonly int Screen = SlotOf("screen");
}
