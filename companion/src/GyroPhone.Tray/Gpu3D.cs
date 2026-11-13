using System.Drawing;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Vortice.D3DCompiler;
using Vortice.Direct2D1;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.Mathematics;
using D2DAlphaMode = Vortice.DCommon.AlphaMode;
using D2DPixelFormat = Vortice.DCommon.PixelFormat;
using Color = System.Drawing.Color;
using Rect = Vortice.Mathematics.Rect;
using FeatureLevel = Vortice.Direct3D.FeatureLevel;
using BlendDescription = Vortice.Direct3D11.BlendDescription;
using Blend = Vortice.Direct3D11.Blend;
using BlendOperation = Vortice.Direct3D11.BlendOperation;
using FillMode = Vortice.Direct3D11.FillMode;

namespace GyroPhone.Tray;

/// <summary>
/// 3D overlay drawings on the graphics card. A drawing's lines and faces go up to the card once (a
/// <see cref="Geo"/>, kept per device by a name); each frame then sends only the turn (a few numbers), and the card
/// turns, projects and shades them. Lines are round-ended strips with their own smooth edges (as the Snake's in
/// <see cref="GpuPaint"/>); each line layer is drawn into a scratch picture with a "max" blend (so a polyline's joins
/// don't double up where its pieces overlap, as Direct2D draws one stroke), then laid over the window's picture at the
/// colour's opacity. Faces and disks are drawn straight onto the picture. The whole picture is then drawn by Direct2D
/// in one go, and not drawn again while nothing in it changes. False from <see cref="Draw{T}"/> when the card can't:
/// the caller draws on the CPU then.
/// </summary>
internal static class Gpu3D
{
    /// <summary>Which side of the view's middle plane (z = 0) a line layer keeps.</summary>
    public enum Side { Both, Front, Back }

    /// <summary>One line piece: ends A and B (x, y, z; A.W 1 when the piece is an edge between faces N1 and N2, N2.W 0 when it has one face).</summary>
    [StructLayout(LayoutKind.Sequential)]
    public struct Seg
    {
        public Vector4 A, B, N1, N2;
    }

    /// <summary>One triangle corner: its point and its face's normal.</summary>
    [StructLayout(LayoutKind.Sequential)]
    public struct Corner
    {
        public Vector4 P, N;
    }

    /// <summary>Line pieces and triangles on the card.</summary>
    public sealed class Geo : IDisposable
    {
        public ID3D11Buffer? Segs, Tris;
        public ID3D11ShaderResourceView? SegView, TriView;
        public int SegCount, TriCorners;

        public void Dispose()
        {
            SegView?.Dispose(); Segs?.Dispose(); TriView?.Dispose(); Tris?.Dispose();
            SegView = TriView = null; Segs = Tris = null;
        }
    }

    /// <summary>What a drawing builds its geometry from (only when it isn't on the card yet).</summary>
    public sealed class GeoBuilder
    {
        public readonly List<Seg> Segs = [];
        public readonly List<Corner> Corners = [];

        /// <summary>A piece whose front or back is told per pixel, by which side of the middle plane it is on.</summary>
        public void Line(float ax, float ay, float az, float bx, float by, float bz) =>
            Segs.Add(new Seg { A = new Vector4(ax, ay, az, 0f), B = new Vector4(bx, by, bz, 0f) });

        /// <summary>A piece that is in front when either of its faces faces the viewer (normal n2 optional).</summary>
        public void Edge(float ax, float ay, float az, float bx, float by, float bz, Vector3 n1, Vector3? n2) =>
            Segs.Add(new Seg
            {
                A = new Vector4(ax, ay, az, 1f), B = new Vector4(bx, by, bz, 0f),
                N1 = new Vector4(n1, 1f), N2 = n2 is { } n ? new Vector4(n, 1f) : Vector4.Zero,
            });

        /// <summary>A polyline through the points (x, y, z, x, y, z, ...), its front told per pixel.</summary>
        public void Polyline(ReadOnlySpan<float> p)
        {
            for (int i = 3; i + 2 < p.Length; i += 3) Line(p[i - 3], p[i - 2], p[i - 1], p[i], p[i + 1], p[i + 2]);
        }

        public void Triangle(Vector3 a, Vector3 b, Vector3 c, Vector3 n)
        {
            var nn = new Vector4(n, 0f);
            Corners.Add(new Corner { P = new Vector4(a, 1f), N = nn });
            Corners.Add(new Corner { P = new Vector4(b, 1f), N = nn });
            Corners.Add(new Corner { P = new Vector4(c, 1f), N = nn });
        }
    }

    /// <summary>One corner of a flat item: (x, y, kind 0 fill / 1 line, line half width), a line's ends, premultiplied colour.</summary>
    [StructLayout(LayoutKind.Sequential)]
    public struct FlatVertex
    {
        public Vector4 P, E, C;
    }

    /// <summary>
    /// Flat items in picture pixels, drawn in order (each over those before it, as Direct2D's calls): filled polygons
    /// and round-ended lines with smooth edges. For pictures worked out on the CPU each frame (cheap maths) whose
    /// drawing was the costly part.
    /// </summary>
    public sealed class FlatList
    {
        public readonly List<FlatVertex> Items = [];

        public void Clear() => Items.Clear();

        private static Vector4 Pre(Color c)
        {
            float a = c.A / 255f;
            return new Vector4(c.R / 255f * a, c.G / 255f * a, c.B / 255f * a, a);
        }

        /// <summary>A convex polygon filled (as a fan from its first corner).</summary>
        public void Fill(ReadOnlySpan<PointF> pts, Color color)
        {
            if (pts.Length < 3 || color.A == 0) return;
            var c = Pre(color);
            for (int i = 1; i + 1 < pts.Length; i++)
            {
                Items.Add(new FlatVertex { P = new Vector4(pts[0].X, pts[0].Y, 0f, 0f), C = c });
                Items.Add(new FlatVertex { P = new Vector4(pts[i].X, pts[i].Y, 0f, 0f), C = c });
                Items.Add(new FlatVertex { P = new Vector4(pts[i + 1].X, pts[i + 1].Y, 0f, 0f), C = c });
            }
        }

        /// <summary>A line from a to b, <paramref name="width"/> wide, round-ended.</summary>
        public void Line(PointF a, PointF b, Color color, float width)
        {
            if (color.A == 0) return;
            var c = Pre(color);
            float hw = width / 2f, e = hw + 1f;
            float dx = b.X - a.X, dy = b.Y - a.Y, l = MathF.Sqrt(dx * dx + dy * dy);
            float ux = l > 1e-4f ? dx / l : 1f, uy = l > 1e-4f ? dy / l : 0f;
            float nx = -uy * e, ny = ux * e;
            var p0 = new Vector2(a.X - ux * e + nx, a.Y - uy * e + ny);
            var p1 = new Vector2(a.X - ux * e - nx, a.Y - uy * e - ny);
            var p2 = new Vector2(b.X + ux * e + nx, b.Y + uy * e + ny);
            var p3 = new Vector2(b.X + ux * e - nx, b.Y + uy * e - ny);
            var ends = new Vector4(a.X, a.Y, b.X, b.Y);
            void V(Vector2 p) => Items.Add(new FlatVertex { P = new Vector4(p.X, p.Y, 1f, hw), E = ends, C = c });
            V(p0); V(p1); V(p2);
            V(p2); V(p1); V(p3);
        }

        /// <summary>A closed polygon's outline.</summary>
        public void Outline(ReadOnlySpan<PointF> pts, Color color, float width)
        {
            for (int i = 0; i < pts.Length; i++) Line(pts[i], pts[(i + 1) % pts.Length], color, width);
        }
    }

    /// <summary>The device-wide parts: shaders, states, the draw constants and the geometry (made once per device).</summary>
    internal sealed class Kit
    {
        public ID3D11VertexShader LineVs = null!, FaceVs = null!, DiskVs = null!, FullVs = null!, FlatVs = null!;
        public ID3D11PixelShader LinePs = null!, FacePs = null!, DiskPs = null!, CopyPs = null!, FlatPs = null!;
        public ID3D11BlendState Max = null!, Over = null!;
        public ID3D11DepthStencilState DepthWrite = null!, DepthTest = null!, DepthOff = null!;
        public ID3D11RasterizerState Raster = null!;
        public ID3D11Buffer Cb = null!;
        public readonly Dictionary<string, Geo> Geos = [];

        public void Dispose()
        {
            foreach (var g in Geos.Values) g.Dispose();
            Geos.Clear();
            LineVs?.Dispose(); FaceVs?.Dispose(); DiskVs?.Dispose(); FullVs?.Dispose(); FlatVs?.Dispose();
            LinePs?.Dispose(); FacePs?.Dispose(); DiskPs?.Dispose(); CopyPs?.Dispose(); FlatPs?.Dispose();
            Max?.Dispose(); Over?.Dispose(); DepthWrite?.Dispose(); DepthTest?.Dispose(); DepthOff?.Dispose();
            Raster?.Dispose(); Cb?.Dispose();
        }
    }

    /// <summary>A picture on the card that Direct2D can draw and shaders can read.</summary>
    internal sealed class Target : IDisposable
    {
        public readonly ID3D11Texture2D Texture;
        public readonly ID3D11RenderTargetView View;
        public readonly ID3D11ShaderResourceView Read;
        public readonly ID2D1Bitmap1 Bitmap;

        public Target(ID3D11Device d3d, ID2D1DeviceContext dc, int w, int h)
        {
            Texture = d3d.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, (uint)w, (uint)h, 1, 1,
                BindFlags.RenderTarget | BindFlags.ShaderResource));
            View = d3d.CreateRenderTargetView(Texture);
            Read = d3d.CreateShaderResourceView(Texture);
            using var surface = Texture.QueryInterface<IDXGISurface>();
            Bitmap = dc.CreateBitmapFromDxgiSurface(surface,
                new BitmapProperties1(new D2DPixelFormat(Format.B8G8R8A8_UNorm, D2DAlphaMode.Premultiplied), 96, 96, BitmapOptions.None));
        }

        public void Dispose()
        {
            Bitmap.Dispose();
            Read.Dispose();
            View.Dispose();
            Texture.Dispose();
        }
    }

    /// <summary>One window's pictures: one per slot (what is drawn where, such as a shape or a grid's near half), and the scratch picture, depth buffer and flat items they share.</summary>
    internal sealed class Surface : IDisposable
    {
        public GpuDevice? Gpu;
        public int W, H;
        public Target? Scratch;
        public ID3D11Texture2D? DepthTexture;
        public ID3D11DepthStencilView? Depth;
        public readonly Dictionary<string, Slot> Slots = [];
        public ID3D11Buffer? Flats;
        public ID3D11ShaderResourceView? FlatView;
        public int FlatRoom;

        public void Dispose()
        {
            foreach (var slot in Slots.Values) slot.Dispose();
            Slots.Clear();
            Scratch?.Dispose(); Depth?.Dispose(); DepthTexture?.Dispose();
            FlatView?.Dispose(); Flats?.Dispose();
            Scratch = null; Depth = null; DepthTexture = null; FlatView = null; Flats = null;
            FlatRoom = 0;
            W = H = 0;
        }
    }

    /// <summary>One slot's picture and what it shows.</summary>
    internal sealed class Slot : IDisposable
    {
        public Target? Picture;
        public Key Shown;
        public bool HasShown;
        // Drawn in this window's last frame (a slot not drawn for a while lets its picture go).
        public long UsedAt;

        public void Dispose()
        {
            Picture?.Dispose();
            Picture = null;
            HasShown = false;
        }
    }

    /// <summary>Everything a picture shows: drawn again only when it changes.</summary>
    public readonly record struct Key(int Look, Matrix4x4 Turn, RectangleF Area, int Extra);

    private static readonly object KitGate = new();
    private static readonly ConditionalWeakTable<GpuDevice, Kit> Kits = new();
    private static readonly ConditionalWeakTable<GpuDevice, object> Failed = new();
    private static readonly ConditionalWeakTable<D2DCanvas, Surface> Surfaces = new();

    /// <summary>Where the 3D overlays are drawn, for the overlay timing log: "3D on GPU", or on the CPU and why; empty before any.</summary>
    public static string Status { get; private set; } = "";

    /// <summary>A 3x3 row-major turn as the key's matrix.</summary>
    public static Matrix4x4 TurnOf(float[] m) => new(m[0], m[1], m[2], 0f, m[3], m[4], m[5], 0f, m[6], m[7], m[8], 0f, 0f, 0f, 0f, 1f);

    /// <summary>
    /// Draws a picture of <paramref name="area"/> through the card: <paramref name="build"/> draws it with the
    /// <see cref="Frame"/> (only when <paramref name="key"/> changed since the last one), then Direct2D shows it.
    /// False when the card can't (draw on the CPU then).
    /// </summary>
    public static bool Draw<T>(ICanvas canvas, RectangleF area, string slotName, Key key, T state, Action<Frame, T> build)
    {
        if (canvas is not D2DCanvas g || g.Gpu is not { } gpu) return false;
        if (area.Width < 2f || area.Height < 2f) return true;
        var kit = KitFor(gpu);
        if (kit == null) return false;
        var surface = Surfaces.GetValue(g, _ => new Surface());
        g.OwnScene(surface);
        // Every slot of a window shares its size: the whole canvas.
        int w = (int)MathF.Ceiling(g.Width), h = (int)MathF.Ceiling(g.Height);
        if (w < 2 || h < 2) return true;
        long frame = g.Frame;
        try
        {
            if (surface.Gpu != gpu || surface.W != w || surface.H != h)
            {
                gpu.Enter();
                try
                {
                    surface.Dispose();
                    surface.Gpu = gpu;
                    var d3d = gpu.D3D;
                    surface.Scratch = new Target(d3d, g.Dc, w, h);
                    surface.DepthTexture = d3d.CreateTexture2D(new Texture2DDescription(Format.D32_Float, (uint)w, (uint)h, 1, 1, BindFlags.DepthStencil));
                    surface.Depth = d3d.CreateDepthStencilView(surface.DepthTexture);
                    surface.W = w;
                    surface.H = h;
                }
                finally
                {
                    gpu.Leave();
                }
            }
            if (!surface.Slots.TryGetValue(slotName, out var slot))
            {
                // Slots not drawn for a second (a look switched off) let their pictures go first.
                foreach (var (name, old) in surface.Slots.ToArray())
                    if (frame - old.UsedAt > 120)
                    {
                        old.Dispose();
                        surface.Slots.Remove(name);
                    }
                surface.Slots[slotName] = slot = new Slot();
            }
            slot.UsedAt = frame;
            if (!slot.HasShown || !slot.Shown.Equals(key))
            {
                gpu.Enter();
                try
                {
                    slot.Picture ??= new Target(gpu.D3D, g.Dc, w, h);
                    var f = new Frame(kit, gpu, surface, slot.Picture, 0f, 0f);
                    f.Start();
                    build(f, state);
                    f.Finish();
                }
                finally
                {
                    gpu.Leave();
                }
                slot.Shown = key;
                slot.HasShown = true;
            }
            g.Dc.DrawBitmap(slot.Picture!.Bitmap, new Rect(0, 0, w, h), 1f, BitmapInterpolationMode.NearestNeighbor, null);
        }
        catch (Exception ex)
        {
            // Anything the card refused: this device draws 3D on the CPU from now on.
            lock (KitGate) Failed.AddOrUpdate(gpu, new object());
            Status = "3D on CPU (" + ex.GetType().Name + ": " + ex.Message + ")";
            surface.Dispose();
            return false;
        }
        return true;
    }

    /// <summary>A drawing in progress on one window's picture (positions in the area's pixels, from its top left).</summary>
    public sealed class Frame
    {
        private readonly Kit _kit;
        private readonly GpuDevice _gpu;
        private readonly Surface _s;
        private readonly Target _picture;
        private readonly float _dx, _dy;
        private DrawData _d;

        internal Frame(Kit kit, GpuDevice gpu, Surface s, Target picture, float dx, float dy)
        {
            _kit = kit; _gpu = gpu; _s = s; _picture = picture; _dx = dx; _dy = dy;
        }

        /// <summary>
        /// Perspective for the line layers that follow: a point at depth z is scaled by
        /// <c>distance / (distance − min(z, distance − 0.5))</c> (the pointer's <see cref="GyroPhone.Host.LaserTrace.Perspective"/>); 0 for none.
        /// </summary>
        public float Perspective { get => _d.Persp; set => _d.Persp = value; }

        private ID3D11DeviceContext Ctx => _gpu.D3D.ImmediateContext;

        internal void Start()
        {
            var ctx = Ctx;
            ctx.ClearRenderTargetView(_picture.View, new Color4(0f, 0f, 0f, 0f));
            ctx.ClearDepthStencilView(_s.Depth!, DepthStencilClearFlags.Depth, 1f, 0);
            ctx.RSSetState(_kit.Raster);
            ctx.RSSetViewport(0f, 0f, _s.W, _s.H);
            ctx.IASetInputLayout(null);
            ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
            _d.Target = new Vector2(_s.W, _s.H);
        }

        internal void Finish() => Unbind(Ctx);

        /// <summary>The geometry named <paramref name="name"/> (made by <paramref name="make"/> the first time on this device).</summary>
        public Geo Geometry(string name, Action<GeoBuilder> make)
        {
            if (_kit.Geos.TryGetValue(name, out var geo)) return geo;
            var b = new GeoBuilder();
            make(b);
            geo = new Geo();
            try
            {
                var d3d = _gpu.D3D;
                if (b.Segs.Count > 0)
                {
                    geo.Segs = Upload<Seg>(d3d, CollectionsMarshal.AsSpan(b.Segs));
                    geo.SegView = d3d.CreateShaderResourceView(geo.Segs, new ShaderResourceViewDescription(geo.Segs, Format.Unknown, 0, (uint)b.Segs.Count));
                    geo.SegCount = b.Segs.Count;
                }
                if (b.Corners.Count > 0)
                {
                    geo.Tris = Upload<Corner>(d3d, CollectionsMarshal.AsSpan(b.Corners));
                    geo.TriView = d3d.CreateShaderResourceView(geo.Tris, new ShaderResourceViewDescription(geo.Tris, Format.Unknown, 0, (uint)b.Corners.Count));
                    geo.TriCorners = b.Corners.Count;
                }
            }
            catch
            {
                geo.Dispose();
                throw;
            }
            _kit.Geos[name] = geo;
            return geo;
        }

        private static unsafe ID3D11Buffer Upload<TItem>(ID3D11Device d3d, ReadOnlySpan<TItem> items) where TItem : unmanaged
        {
            int stride = sizeof(TItem);
            fixed (TItem* p = items)
            {
                return d3d.CreateBuffer(new BufferDescription((uint)(items.Length * stride), BindFlags.ShaderResource, ResourceUsage.Immutable,
                    CpuAccessFlags.None, ResourceOptionFlags.BufferStructured, (uint)stride), new SubresourceData(p));
            }
        }

        private void Set(float[]? m, PointF c, float r)
        {
            if (m == null)
            {
                _d.M0 = new Vector4(1f, 0f, 0f, 0f); _d.M1 = new Vector4(0f, 1f, 0f, 0f); _d.M2 = new Vector4(0f, 0f, 1f, 0f);
            }
            else
            {
                _d.M0 = new Vector4(m[0], m[1], m[2], 0f); _d.M1 = new Vector4(m[3], m[4], m[5], 0f); _d.M2 = new Vector4(m[6], m[7], m[8], 0f);
            }
            _d.Centre = new Vector2(c.X + _dx, c.Y + _dy);
            _d.Radius = r;
        }

        private void Push() => Ctx.UpdateSubresource(in _d, _kit.Cb);

        /// <summary>
        /// One line layer: <paramref name="geo"/>'s pieces turned by row-major <paramref name="m"/> (null: as they are),
        /// at centre <paramref name="c"/> with radius <paramref name="r"/> in pixels (y up), <paramref name="width"/>
        /// wide, on <paramref name="side"/> of the middle plane, laid over the picture at the colour's opacity. With
        /// <paramref name="depthTest"/>, hidden by faces drawn before it.
        /// </summary>
        public void Lines(Geo geo, float[]? m, PointF c, float r, Color color, float width, Side side, bool twoSided = false, bool depthTest = false)
        {
            if (geo.SegCount == 0 || color.A == 0) return;
            var ctx = Ctx;
            Set(m, c, r);
            _d.Col = Straight(color);
            _d.HalfW = width / 2f;
            _d.Side = side switch { Side.Front => 1f, Side.Back => -1f, _ => 0f };
            _d.TwoSided = twoSided ? 1f : 0f;
            Push();
            ctx.PSSetShaderResource(2, null!);
            ctx.ClearRenderTargetView(_s.Scratch!.View, new Color4(0f, 0f, 0f, 0f));
            ctx.OMSetRenderTargets(_s.Scratch.View, depthTest ? _s.Depth : null);
            ctx.OMSetDepthStencilState(depthTest ? _kit.DepthTest : _kit.DepthOff, 0);
            ctx.OMSetBlendState(_kit.Max);
            ctx.VSSetShader(_kit.LineVs);
            ctx.VSSetConstantBuffer(0, _kit.Cb);
            ctx.VSSetShaderResource(0, geo.SegView!);
            ctx.PSSetShader(_kit.LinePs);
            ctx.PSSetConstantBuffer(0, _kit.Cb);
            ctx.DrawInstanced(6, (uint)geo.SegCount, 0, 0);
            // The layer over the picture at the colour's opacity.
            ctx.OMSetRenderTargets(_picture.View, null);
            ctx.OMSetDepthStencilState(_kit.DepthOff, 0);
            ctx.OMSetBlendState(_kit.Over);
            ctx.VSSetShader(_kit.FullVs);
            ctx.PSSetShader(_kit.CopyPs);
            ctx.PSSetShaderResource(2, _s.Scratch.Read);
            ctx.Draw(3, 0);
            ctx.PSSetShaderResource(2, null!);
        }

        /// <summary>
        /// <paramref name="geo"/>'s triangles turned by <paramref name="m"/>, each shaded by how much it faces the
        /// viewer (those facing away left out unless <paramref name="twoSided"/>), into the depth buffer too.
        /// </summary>
        public void Faces(Geo geo, float[] m, PointF c, float r, Color body, bool twoSided)
        {
            if (geo.TriCorners == 0 || body.A == 0) return;
            var ctx = Ctx;
            Set(m, c, r);
            _d.Col = Straight(body);
            _d.TwoSided = twoSided ? 1f : 0f;
            Push();
            ctx.OMSetRenderTargets(_picture.View, _s.Depth);
            ctx.OMSetDepthStencilState(_kit.DepthWrite, 0);
            ctx.OMSetBlendState(_kit.Over);
            ctx.VSSetShader(_kit.FaceVs);
            ctx.VSSetConstantBuffer(0, _kit.Cb);
            ctx.VSSetShaderResource(1, geo.TriView!);
            ctx.PSSetShader(_kit.FacePs);
            ctx.Draw((uint)geo.TriCorners, 0);
        }

        /// <summary>A disk at <paramref name="c"/>, radius <paramref name="r"/>, lit from <paramref name="litAt"/> (<paramref name="lit"/> there, <paramref name="color"/> at the rim), as Direct2D's radial gradient.</summary>
        public void Ball(PointF c, float r, PointF litAt, Color lit, Color color)
        {
            if (r <= 0f) return;
            var ctx = Ctx;
            Set(null, c, r);
            _d.Col = Straight(color);
            _d.Col2 = Straight(lit);
            _d.Lit = new Vector2(litAt.X + _dx, litAt.Y + _dy);
            Push();
            ctx.OMSetRenderTargets(_picture.View, null);
            ctx.OMSetDepthStencilState(_kit.DepthOff, 0);
            ctx.OMSetBlendState(_kit.Over);
            ctx.VSSetShader(_kit.DiskVs);
            ctx.VSSetConstantBuffer(0, _kit.Cb);
            ctx.PSSetShader(_kit.DiskPs);
            ctx.PSSetConstantBuffer(0, _kit.Cb);
            ctx.Draw(6, 0);
        }

        /// <summary>A plain disk.</summary>
        public void Disk(PointF c, float r, Color color) => Ball(c, r, c, color, color);

        /// <summary><paramref name="list"/>'s items over the picture, in order.</summary>
        public unsafe void Flat(FlatList list)
        {
            int n = list.Items.Count;
            if (n == 0) return;
            var d3d = _gpu.D3D;
            var ctx = Ctx;
            if (_s.FlatRoom < n)
            {
                _s.FlatView?.Dispose(); _s.Flats?.Dispose();
                int room = Math.Max(1024, _s.FlatRoom);
                while (room < n) room *= 2;
                int stride = sizeof(FlatVertex);
                _s.Flats = d3d.CreateBuffer(new BufferDescription((uint)(room * stride), BindFlags.ShaderResource, ResourceUsage.Dynamic,
                    CpuAccessFlags.Write, ResourceOptionFlags.BufferStructured, (uint)stride));
                _s.FlatView = d3d.CreateShaderResourceView(_s.Flats, new ShaderResourceViewDescription(_s.Flats, Format.Unknown, 0, (uint)room));
                _s.FlatRoom = room;
            }
            var mapped = ctx.Map(_s.Flats!, 0, MapMode.WriteDiscard);
            try
            {
                CollectionsMarshal.AsSpan(list.Items).CopyTo(new Span<FlatVertex>((void*)mapped.DataPointer, n));
            }
            finally
            {
                ctx.Unmap(_s.Flats!, 0);
            }
            Set(null, new PointF(0f, 0f), 1f);
            Push();
            ctx.OMSetRenderTargets(_picture.View, null);
            ctx.OMSetDepthStencilState(_kit.DepthOff, 0);
            ctx.OMSetBlendState(_kit.Over);
            ctx.VSSetShader(_kit.FlatVs);
            ctx.VSSetConstantBuffer(0, _kit.Cb);
            ctx.VSSetShaderResource(3, _s.FlatView!);
            ctx.PSSetShader(_kit.FlatPs);
            ctx.Draw((uint)n, 0);
        }
    }

    private static Vector4 Straight(Color c) => new(c.R / 255f, c.G / 255f, c.B / 255f, c.A / 255f);

    private static Kit? KitFor(GpuDevice gpu)
    {
        if (Kits.TryGetValue(gpu, out var kit)) return kit;
        lock (KitGate)
        {
            if (Kits.TryGetValue(gpu, out kit)) return kit;
            if (Failed.TryGetValue(gpu, out _)) return null;
            try
            {
                if (gpu.D3D.FeatureLevel < FeatureLevel.Level_11_0) throw new NotSupportedException("feature level");
                gpu.Enter();
                try
                {
                    kit = MakeKit(gpu.D3D);
                }
                finally
                {
                    gpu.Leave();
                }
                Kits.Add(gpu, kit);
                Status = "3D on GPU";
                return kit;
            }
            catch (Exception ex)
            {
                kit?.Dispose();
                Failed.AddOrUpdate(gpu, new object());
                Status = "3D on CPU (" + ex.Message + ")";
                return null;
            }
        }
    }

    private static byte[] CompileShader(string entry, string profile)
    {
        var result = Compiler.Compile(Shaders, entry, "Gpu3D", profile, out Blob blob, out Blob error);
        if (result.Failure || blob == null)
        {
            string msg = error?.AsString() ?? result.ToString();
            error?.Dispose();
            blob?.Dispose();
            throw new InvalidOperationException("shader " + entry + ": " + msg);
        }
        error?.Dispose();
        var bytes = blob.AsBytes();
        blob.Dispose();
        return bytes;
    }

    private static Kit MakeKit(ID3D11Device d3d)
    {
        var kit = new Kit();
        try
        {
            kit.LineVs = d3d.CreateVertexShader(CompileShader("LineVs", "vs_5_0"));
            kit.LinePs = d3d.CreatePixelShader(CompileShader("LinePs", "ps_5_0"));
            kit.FaceVs = d3d.CreateVertexShader(CompileShader("FaceVs", "vs_5_0"));
            kit.FacePs = d3d.CreatePixelShader(CompileShader("FacePs", "ps_5_0"));
            kit.DiskVs = d3d.CreateVertexShader(CompileShader("DiskVs", "vs_5_0"));
            kit.DiskPs = d3d.CreatePixelShader(CompileShader("DiskPs", "ps_5_0"));
            kit.FullVs = d3d.CreateVertexShader(CompileShader("FullVs", "vs_5_0"));
            kit.CopyPs = d3d.CreatePixelShader(CompileShader("CopyPs", "ps_5_0"));
            kit.FlatVs = d3d.CreateVertexShader(CompileShader("FlatVs", "vs_5_0"));
            kit.FlatPs = d3d.CreatePixelShader(CompileShader("FlatPs", "ps_5_0"));
            kit.Max = MakeBlend(d3d, Blend.One, Blend.One, BlendOperation.Max);
            // Premultiplied "over".
            kit.Over = MakeBlend(d3d, Blend.One, Blend.InverseSourceAlpha, BlendOperation.Add);
            kit.DepthWrite = d3d.CreateDepthStencilState(new DepthStencilDescription(true, DepthWriteMask.All, ComparisonFunction.LessEqual));
            kit.DepthTest = d3d.CreateDepthStencilState(new DepthStencilDescription(true, DepthWriteMask.Zero, ComparisonFunction.LessEqual));
            kit.DepthOff = d3d.CreateDepthStencilState(new DepthStencilDescription(false, DepthWriteMask.Zero, ComparisonFunction.Always));
            kit.Raster = d3d.CreateRasterizerState(new RasterizerDescription(CullMode.None, FillMode.Solid));
            kit.Cb = d3d.CreateBuffer(new BufferDescription((uint)Marshal.SizeOf<DrawData>(), BindFlags.ConstantBuffer));
            return kit;
        }
        catch
        {
            kit.Dispose();
            throw;
        }
    }

    private static ID3D11BlendState MakeBlend(ID3D11Device d3d, Blend src, Blend dst, BlendOperation op)
    {
        var blend = new BlendDescription { AlphaToCoverageEnable = false, IndependentBlendEnable = false };
        blend.RenderTarget[0] = new RenderTargetBlendDescription
        {
            BlendEnable = true,
            SourceBlend = src, DestinationBlend = dst, BlendOperation = op,
            SourceBlendAlpha = src, DestinationBlendAlpha = dst, BlendOperationAlpha = op,
            RenderTargetWriteMask = ColorWriteEnable.All,
        };
        return d3d.CreateBlendState(blend);
    }

    /// <summary>Leaves nothing of ours bound (Direct2D reads these pictures next).</summary>
    private static void Unbind(ID3D11DeviceContext ctx)
    {
        ctx.UnsetRenderTargets();
        ctx.OMSetBlendState(null);
        ctx.OMSetDepthStencilState(null, 0);
        ctx.PSSetShaderResources(0u, 4u, NoViews);
        ctx.VSSetShaderResources(0u, 4u, NoViews);
    }

    private static readonly ID3D11ShaderResourceView[] NoViews = new ID3D11ShaderResourceView[4];

    [StructLayout(LayoutKind.Sequential)]
    private struct DrawData
    {
        public Vector4 M0, M1, M2;
        public Vector4 Col, Col2;
        public Vector2 Centre;
        public float Radius, HalfW;
        public Vector2 Target;
        public float Side, TwoSided;
        public Vector2 Lit;
        public float Persp, Pad1;
    }

    /// <summary>The shaders (HLSL, shader model 5). Positions are in the picture's pixels (y down); the view has z toward the viewer.</summary>
    private const string Shaders = """
        cbuffer Draw : register(b0)
        {
            float4 M0; float4 M1; float4 M2;
            float4 Col; float4 Col2;
            float2 Centre; float Radius; float HalfW;
            float2 TargetSize; float Side; float TwoSided;
            float2 LitAt; float Persp; float Pad;
        };

        struct Seg { float4 A; float4 B; float4 N1; float4 N2; };
        StructuredBuffer<Seg> Segs : register(t0);
        struct Corner { float4 P; float4 N; };
        StructuredBuffer<Corner> Corners : register(t1);
        Texture2D<float4> Layer : register(t2);

        float3 Turn(float3 p) { return float3(dot(M0.xyz, p), dot(M1.xyz, p), dot(M2.xyz, p)); }
        float2 OnScreen(float3 v)
        {
            float k = Persp > 0.0 ? Persp / (Persp - min(v.z, Persp - 0.5)) : 1.0;
            return Centre + float2(v.x, -v.y) * (Radius * k);
        }
        float4 ClipAt(float2 s, float depth) { return float4(s.x / TargetSize.x * 2.0 - 1.0, 1.0 - s.y / TargetSize.y * 2.0, depth, 1.0); }
        // Nearer is smaller; the shapes lie within the unit ball.
        float DepthOf(float z) { return saturate(0.5 - z * 0.45); }
        static const float4 Nowhere = float4(2.0, 2.0, 2.0, 1.0);

        struct LineOut
        {
            float4 Pos : SV_Position;
            nointerpolation float4 Ends : TEXCOORD0;
            nointerpolation float3 Zs : TEXCOORD1;
        };

        // Each piece as a box round it (its width and a pixel more each way), two triangles.
        LineOut LineVs(uint v : SV_VertexID, uint id : SV_InstanceID)
        {
            Seg sg = Segs[id];
            float3 a = Turn(sg.A.xyz);
            float3 b = Turn(sg.B.xyz);
            float2 pa = OnScreen(a);
            float2 pb = OnScreen(b);
            bool keep = true;
            bool edge = sg.A.w > 0.5;
            if (edge)
            {
                bool front = TwoSided > 0.5 || dot(M2.xyz, sg.N1.xyz) > 0.0 || (sg.N2.w > 0.5 && dot(M2.xyz, sg.N2.xyz) > 0.0);
                keep = Side == 0.0 || (Side > 0.0) == front;
            }
            else if (Side > 0.0) keep = max(a.z, b.z) >= 0.0;
            else if (Side < 0.0) keep = min(a.z, b.z) < 0.0;
            float2 d = pb - pa;
            float l = length(d);
            float2 u = l > 1e-4 ? d / l : float2(1.0, 0.0);
            float2 n = float2(-u.y, u.x);
            float e = HalfW + 1.0;
            uint k = v % 6;
            bool atB = k == 1 || k == 4 || k == 5;
            float across = (k == 2 || k == 3 || k == 5) ? 1.0 : -1.0;
            float2 at = (atB ? pb + u * e : pa - u * e) + n * (e * across);
            LineOut o;
            // A little nearer than the faces it edges, so faces drawn before hide only what is behind them.
            o.Pos = keep ? ClipAt(at, max(DepthOf(atB ? b.z : a.z) - 0.004, 0.0)) : Nowhere;
            o.Ends = float4(pa, pb);
            o.Zs = float3(a.z, b.z, edge ? 1.0 : 0.0);
            return o;
        }

        float4 LinePs(LineOut i) : SV_Target
        {
            float2 a = i.Ends.xy;
            float2 d = i.Ends.zw - a;
            float2 q = i.Pos.xy - a;
            float l2 = dot(d, d);
            float t = l2 < 1e-6 ? 0.0 : saturate(dot(q, d) / l2);
            float cover = saturate(HalfW - length(q - d * t) + 0.5);
            if (i.Zs.z < 0.5 && Side != 0.0)
            {
                // Cut where the piece crosses the middle plane.
                float z = lerp(i.Zs.x, i.Zs.y, t);
                if ((Side > 0.0) != (z >= 0.0)) cover = 0.0;
            }
            clip(cover - 0.001);
            return float4(Col.rgb * cover, cover);
        }

        struct FaceOut { float4 Pos : SV_Position; nointerpolation float4 C : COLOR0; };

        // Shaded by how much the face faces the viewer, as the CPU's 0.45 + 0.55 |n.z|.
        FaceOut FaceVs(uint v : SV_VertexID)
        {
            Corner c = Corners[v];
            float3 p = Turn(c.P.xyz);
            float nz = dot(M2.xyz, c.N.xyz);
            float k = 0.45 + 0.55 * abs(nz);
            FaceOut o;
            o.C = float4(saturate(Col.rgb * k) * Col.a, Col.a);
            o.Pos = (TwoSided > 0.5 || nz > 0.0) ? ClipAt(OnScreen(p), DepthOf(p.z)) : Nowhere;
            return o;
        }

        float4 FacePs(FaceOut i) : SV_Target { return i.C; }

        struct DiskOut { float4 Pos : SV_Position; };

        DiskOut DiskVs(uint v : SV_VertexID)
        {
            uint k = v % 6;
            float e = Radius + 1.0;
            float2 at = Centre + float2((k == 1 || k == 4 || k == 5) ? e : -e, (k == 2 || k == 3 || k == 5) ? e : -e);
            DiskOut o;
            o.Pos = ClipAt(at, 0.0);
            return o;
        }

        // Direct2D's radial gradient from the lit point (Col2) to the rim (Col), smooth-edged.
        float4 DiskPs(DiskOut i) : SV_Target
        {
            float2 p = i.Pos.xy - Centre;
            float cover = saturate(Radius - length(p) + 0.5);
            clip(cover - 0.001);
            float2 f = LitAt - Centre;
            float2 u = p - f;
            float a = Radius * Radius - dot(f, f);
            float fu = dot(f, u);
            float s = a > 1e-3 ? saturate((fu + sqrt(max(fu * fu + a * dot(u, u), 0.0))) / a) : 1.0;
            float4 c = lerp(Col2, Col, s);
            float al = c.a * cover;
            return float4(c.rgb * al, al);
        }

        struct FlatV { float4 P; float4 E; float4 C; };
        StructuredBuffer<FlatV> Flats : register(t3);
        struct FlatOut
        {
            float4 Pos : SV_Position;
            nointerpolation float4 Ends : TEXCOORD0;
            nointerpolation float2 Kind : TEXCOORD1;
            nointerpolation float4 C : COLOR0;
        };

        FlatOut FlatVs(uint v : SV_VertexID)
        {
            FlatV f = Flats[v];
            FlatOut o;
            o.Pos = ClipAt(f.P.xy + Centre, 0.0);
            o.Ends = f.E + float4(Centre, Centre);
            o.Kind = f.P.zw;
            o.C = f.C;
            return o;
        }

        // A fill as it is; a line covered as a round-ended strip, smooth-edged.
        float4 FlatPs(FlatOut i) : SV_Target
        {
            if (i.Kind.x < 0.5) return i.C;
            float2 a = i.Ends.xy;
            float2 d = i.Ends.zw - a;
            float2 q = i.Pos.xy - a;
            float l2 = dot(d, d);
            float t = l2 < 1e-6 ? 0.0 : saturate(dot(q, d) / l2);
            float cover = saturate(i.Kind.y - length(q - d * t) + 0.5);
            clip(cover - 0.001);
            return i.C * cover;
        }

        float4 FullVs(uint v : SV_VertexID) : SV_Position
        {
            float2 uv = float2((v << 1) & 2, v & 2);
            return float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 0.0, 1.0);
        }

        float4 CopyPs(float4 pos : SV_Position) : SV_Target
        {
            return Layer.Load(int3(pos.xy, 0)) * Col.a;
        }
        """;
}
