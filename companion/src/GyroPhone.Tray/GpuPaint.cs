using System.Drawing;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using GyroPhone.Host;
using GyroPhone.Host.Settings;
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
using Filter = Vortice.Direct3D11.Filter;
using BlendDescription = Vortice.Direct3D11.BlendDescription;
using Blend = Vortice.Direct3D11.Blend;
using BlendOperation = Vortice.Direct3D11.BlendOperation;
using FillMode = Vortice.Direct3D11.FillMode;

namespace GyroPhone.Tray;

/// <summary>
/// A pointer's paint and Snake drawn on the graphics card (<see cref="PaintRenderer"/> falls back to the CPU when this
/// can't run). The paint map (<see cref="LaserPaint"/>) lives on the card as textures, and only its rows that changed go
/// up; a pixel shader (the phone's PointerPaintShader, and <see cref="PaintPicture"/>'s maths) finds each pixel's point
/// on the sphere, reads the map there and shades it, so a turning sphere costs the CPU nothing. The Snake's pieces are
/// worked out on the CPU (<see cref="PaintRenderer"/>) and drawn by the card as round-ended strips, each pixel keeping
/// its strongest piece and its strongest glow (a "max" blend), as the CPU's. The three pictures (paint, glow, line) are
/// then drawn by Direct2D, the glow and the line at the colours' opacity. The picture matches the CPU's, except where
/// differently coloured pieces of the Snake cross (each colour channel keeps its own strongest).
/// </summary>
internal static class GpuPaint
{
    /// <summary>The device-wide parts: shaders, states and buffers (made once per device).</summary>
    private sealed class Kit : IDisposable
    {
        public ID3D11VertexShader FullVs = null!, SnakeVs = null!;
        public ID3D11PixelShader PaintPs = null!, SnakePs = null!;
        public ID3D11SamplerState Smooth = null!;
        public ID3D11BlendState Max = null!;
        public ID3D11RasterizerState Raster = null!;
        public ID3D11Buffer LookCb = null!, SnakeCb = null!;

        public void Dispose()
        {
            FullVs?.Dispose(); SnakeVs?.Dispose(); PaintPs?.Dispose(); SnakePs?.Dispose();
            Smooth?.Dispose(); Max?.Dispose(); Raster?.Dispose(); LookCb?.Dispose(); SnakeCb?.Dispose();
        }
    }

    /// <summary>One picture on the card: its texture, the views to draw into and read it, and Direct2D's bitmap of it.</summary>
    private sealed class Target : IDisposable
    {
        public readonly ID3D11Texture2D Texture;
        public readonly ID3D11RenderTargetView View;
        public readonly ID2D1Bitmap1 Bitmap;
        public readonly int Size;

        public Target(ID3D11Device d3d, ID2D1DeviceContext dc, int size)
        {
            Size = size;
            Texture = d3d.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, (uint)size, (uint)size, 1, 1,
                BindFlags.RenderTarget | BindFlags.ShaderResource));
            View = d3d.CreateRenderTargetView(Texture);
            using var surface = Texture.QueryInterface<IDXGISurface>();
            Bitmap = dc.CreateBitmapFromDxgiSurface(surface,
                new BitmapProperties1(new D2DPixelFormat(Format.B8G8R8A8_UNorm, D2DAlphaMode.Premultiplied), 96, 96, BitmapOptions.None));
        }

        public void Dispose()
        {
            Bitmap.Dispose();
            View.Dispose();
            Texture.Dispose();
        }
    }

    /// <summary>One half's pictures and what they show.</summary>
    private sealed class Half : IDisposable
    {
        public Target? Paint, Line, Glow;
        public object? PaintKey, SnakeKey;
        public bool HasSnake, HasGlow;
        public float Alpha;

        public void Dispose()
        {
            Paint?.Dispose(); Line?.Dispose(); Glow?.Dispose();
            Paint = Line = Glow = null;
            PaintKey = SnakeKey = null;
        }
    }

    /// <summary>A pointer's maps on the card and its two halves.</summary>
    private sealed class Trace : IDisposable
    {
        public GpuDevice? Gpu;
        public LaserPaint? Map;
        public ID3D11Texture2D[] Maps = [];
        public ID3D11ShaderResourceView[] MapViews = [];
        public ID3D11Texture2D? Way;
        public ID3D11ShaderResourceView? WayView;
        // The map Version the textures hold (MinValue: nothing yet); the way texture holds every row too.
        public long Uploaded = long.MinValue;
        public bool WayFull;
        public readonly Half Near = new(), Far = new();
        public ID3D11Buffer? Pieces;
        public ID3D11ShaderResourceView? PiecesView;
        public int PieceRoom;
        public readonly WeakReference<LaserTrace> Owner;

        public Trace(LaserTrace owner) => Owner = new WeakReference<LaserTrace>(owner);

        public void DropMaps()
        {
            foreach (var v in MapViews) v.Dispose();
            foreach (var t in Maps) t.Dispose();
            WayView?.Dispose(); Way?.Dispose();
            Maps = []; MapViews = []; Way = null; WayView = null;
            Map = null;
            Uploaded = long.MinValue;
            WayFull = false;
            Near.PaintKey = Far.PaintKey = null;
        }

        public void Dispose()
        {
            DropMaps();
            Near.Dispose(); Far.Dispose();
            PiecesView?.Dispose(); Pieces?.Dispose();
            PiecesView = null; Pieces = null; PieceRoom = 0;
            Gpu = null;
        }
    }

    private static readonly object KitGate = new();
    private static readonly ConditionalWeakTable<GpuDevice, Kit> Kits = new();
    // Devices the kit couldn't be made on (an old card, no shader compiler): the CPU draws there.
    private static readonly ConditionalWeakTable<GpuDevice, object> Failed = new();
    private static readonly ConditionalWeakTable<LaserTrace, Trace> Traces = new();
    // Every pointer's card objects, so those of a pointer that's gone are let go of (they hold card memory).
    private static readonly List<Trace> All = [];

    /// <summary>
    /// Draws the paint (<paramref name="lp"/>, null for none) and, with <paramref name="snake"/>, the Snake of
    /// <paramref name="trace"/> on its <paramref name="front"/> or far half into <paramref name="dest"/>, through the
    /// card. False when the card can't (the CPU draws it then).
    /// </summary>
    public static bool Draw(D2DCanvas g, LaserTrace trace, LaserPaint? lp, bool snake, OverlaySettings s, bool front, RectangleF dest, int size,
        float r, float e, float[]? turned, uint[] moves,
        (float X, float Y, float Z) colX, (float X, float Y, float Z) colY, (float X, float Y, float Z) colZ,
        Func<int, float, float, bool> buildPieces, PaintRendererPieces pieces)
    {
        var gpu = g.Gpu;
        if (gpu == null) return false;
        var kit = KitFor(gpu);
        if (kit == null) return false;
        var t = TraceFor(trace);
        if (t.Gpu != gpu)
        {
            t.Dispose();
            t.Gpu = gpu;
        }
        var half = front ? t.Near : t.Far;
        try
        {
            var dc = g.Dc;
            if (half.Paint?.Size != size)
            {
                half.Dispose();
                half.Paint = new Target(gpu.D3D, dc, size);
            }
            // What the paint shows: drawn again only when any of it changes.
            object? paintKey = null;
            if (lp != null)
            {
                var scX = LaserTrace.ToView(1f, 0f, 0f, s.LaserAxonometric);
                var scY = LaserTrace.ToView(0f, 1f, 0f, s.LaserAxonometric);
                var scZ = LaserTrace.ToView(0f, 0f, 1f, s.LaserAxonometric);
                int look = HashCode.Combine(
                    HashCode.Combine(s.Perspective, s.LaserShade, s.LaserInside, s.LaserLight, s.LaserLightFrom, s.LaserShine, s.LaserColourByWay),
                    HashCode.Combine(s.LaserGlow, s.LaserFireGlow, s.LaserAimGlow, s.LaserOutline, s.Color("outline")),
                    HashCode.Combine(s.Color("fire"), s.Color("fire_behind"), s.Color("aim"), s.Color("aim_behind")),
                    HashCode.Combine(moves[0], moves[1], moves[2], moves[3], moves[4], moves[5]));
                paintKey = (lp, lp.Version, size, colX, colY, colZ, scX, scY, scZ, look, lp.Way != null);
                if (!Equals(paintKey, half.PaintKey))
                {
                    gpu.Enter();
                    try
                    {
                        Upload(t, gpu, lp, s.LaserColourByWay);
                        DrawPaint(kit, t, gpu, half.Paint!, lp, s, front, moves, colX, colY, colZ, scX, scY, scZ);
                    }
                    finally
                    {
                        gpu.Leave();
                    }
                    half.PaintKey = paintKey;
                }
            }
            else half.PaintKey = null;

            if (snake)
            {
                int hash = HashCode.Combine(
                    HashCode.Combine(s.Perspective, s.LaserColourByWay, s.LaserShade, s.LaserInside, s.LaserLight, s.LaserLightFrom, s.LaserShine, s.LaserAxonometric),
                    HashCode.Combine(s.LaserSnakeDeg, s.LaserSnakeBrush, s.Color("fire"), s.Color("fire_behind"), s.Color("aim"), s.Color("aim_behind"), s.LaserFireBrush, s.LaserAimBrush),
                    HashCode.Combine(s.LaserSnakeTaper, s.LaserSnakeFade, s.LaserSnakeGlow),
                    HashCode.Combine(moves[0], moves[1], moves[2], moves[3], moves[4], moves[5], s.LaserSmoothColours));
                var snakeKey = (trace.Strokes.Count, trace.Strokes.Count > 0 ? trace.Strokes.Along(trace.Strokes.Count - 1) : 0, trace.Version, size, r,
                    colX, colY, colZ, hash);
                if (!Equals(snakeKey, half.SnakeKey))
                {
                    float glow = Math.Clamp(s.LaserSnakeGlow, 0f, 1f);
                    float alpha = 0f;
                    foreach (var m in moves) alpha += (m >> 24) / 255f;
                    alpha /= 6f;
                    bool any = buildPieces(size, e, r);
                    half.HasSnake = any;
                    half.HasGlow = any && glow > 0f;
                    half.Alpha = alpha;
                    if (any)
                    {
                        half.Line ??= new Target(gpu.D3D, dc, size);
                        if (half.HasGlow) half.Glow ??= new Target(gpu.D3D, dc, size);
                        gpu.Enter();
                        try
                        {
                            DrawSnake(kit, t, gpu, half, pieces, size, glow);
                        }
                        finally
                        {
                            gpu.Leave();
                        }
                    }
                    half.SnakeKey = snakeKey;
                }
            }
            else
            {
                half.SnakeKey = null;
                half.HasSnake = false;
                half.HasGlow = false;
            }
        }
        catch (Exception)
        {
            // Anything the card refused: this device draws on the CPU from now on.
            lock (KitGate) Failed.AddOrUpdate(gpu, new object());
            t.Dispose();
            return false;
        }
        var rect = new Rect(dest.X, dest.Y, dest.Width, dest.Height);
        var dcs = g.Dc;
        if (lp != null && half.Paint != null) dcs.DrawBitmap(half.Paint.Bitmap, rect, 1f, BitmapInterpolationMode.Linear, null);
        if (half.HasSnake)
        {
            // The glow, then the line, over the paint at the colours' opacity.
            if (half.HasGlow && half.Glow != null) dcs.DrawBitmap(half.Glow.Bitmap, rect, half.Alpha, BitmapInterpolationMode.Linear, null);
            if (half.Line != null) dcs.DrawBitmap(half.Line.Bitmap, rect, half.Alpha, BitmapInterpolationMode.Linear, null);
        }
        return true;
    }

    /// <summary>Lets go of a pointer's card objects while it shows nothing (they come back when it does).</summary>
    public static void Idle(LaserTrace trace)
    {
        if (Traces.TryGetValue(trace, out var t) && t.Gpu != null) t.Dispose();
    }

    private static Trace TraceFor(LaserTrace trace)
    {
        if (Traces.TryGetValue(trace, out var t)) return t;
        lock (All)
        {
            // Pointers that are gone: their card objects go too.
            for (int i = All.Count - 1; i >= 0; i--)
            {
                if (All[i].Owner.TryGetTarget(out _)) continue;
                All[i].Dispose();
                All.RemoveAt(i);
            }
            t = Traces.GetValue(trace, k => new Trace(k));
            if (!All.Contains(t)) All.Add(t);
            return t;
        }
    }

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
                kit = MakeKit(gpu.D3D);
                Kits.Add(gpu, kit);
                return kit;
            }
            catch (Exception)
            {
                kit?.Dispose();
                Failed.AddOrUpdate(gpu, new object());
                return null;
            }
        }
    }

    private static byte[] CompileShader(string entry, string profile)
    {
        var result = Compiler.Compile(Shaders, entry, "GpuPaint", profile, out Blob blob, out Blob error);
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
            kit.FullVs = d3d.CreateVertexShader(CompileShader("FullVs", "vs_5_0"));
            kit.PaintPs = d3d.CreatePixelShader(CompileShader("PaintPs", "ps_5_0"));
            kit.SnakeVs = d3d.CreateVertexShader(CompileShader("SnakeVs", "vs_5_0"));
            kit.SnakePs = d3d.CreatePixelShader(CompileShader("SnakePs", "ps_5_0"));
            // Round the sphere in longitude (u wraps), clamped at the poles.
            kit.Smooth = d3d.CreateSamplerState(new SamplerDescription(Filter.MinMagMipLinear, TextureAddressMode.Wrap, TextureAddressMode.Clamp, TextureAddressMode.Clamp));
            var blend = new BlendDescription { AlphaToCoverageEnable = false, IndependentBlendEnable = false };
            blend.RenderTarget[0] = new RenderTargetBlendDescription
            {
                BlendEnable = true,
                SourceBlend = Blend.One, DestinationBlend = Blend.One, BlendOperation = BlendOperation.Max,
                SourceBlendAlpha = Blend.One, DestinationBlendAlpha = Blend.One, BlendOperationAlpha = BlendOperation.Max,
                RenderTargetWriteMask = ColorWriteEnable.All,
            };
            kit.Max = d3d.CreateBlendState(blend);
            kit.Raster = d3d.CreateRasterizerState(new RasterizerDescription(CullMode.None, FillMode.Solid));
            kit.LookCb = d3d.CreateBuffer(new BufferDescription((uint)Marshal.SizeOf<LookData>(), BindFlags.ConstantBuffer));
            kit.SnakeCb = d3d.CreateBuffer(new BufferDescription((uint)Marshal.SizeOf<SnakeData>(), BindFlags.ConstantBuffer));
            return kit;
        }
        catch
        {
            kit.Dispose();
            throw;
        }
    }

    /// <summary>Brings the map's textures up to date: only the rows of tiles changed since the last upload (all the first time).</summary>
    private static unsafe void Upload(Trace t, GpuDevice gpu, LaserPaint lp, bool byWay)
    {
        var d3d = gpu.D3D;
        var ctx = d3d.ImmediateContext;
        int w = lp.Width, h = lp.Height;
        if (!ReferenceEquals(t.Map, lp))
        {
            t.DropMaps();
            t.Map = lp;
            t.Maps = new ID3D11Texture2D[6];
            t.MapViews = new ID3D11ShaderResourceView[6];
            for (int i = 0; i < 6; i++)
            {
                t.Maps[i] = d3d.CreateTexture2D(new Texture2DDescription(Format.R8_UNorm, (uint)w, (uint)h, 1, 1, BindFlags.ShaderResource));
                t.MapViews[i] = d3d.CreateShaderResourceView(t.Maps[i]);
            }
        }
        var way = byWay ? lp.Way : null;
        if (way != null && t.Way == null)
        {
            t.Way = d3d.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, (uint)w, (uint)h, 1, 1, BindFlags.ShaderResource));
            t.WayView = d3d.CreateShaderResourceView(t.Way);
            t.WayFull = false;
        }
        // Read before uploading: a stamp that lands meanwhile marks its tiles past it, so they go up next time.
        long version = lp.Version;
        bool all = t.Uploaded == long.MinValue;
        bool wayAll = way != null && !t.WayFull;
        int tile = 1 << LaserPaint.TileShift;
        int tilesX = (w + tile - 1) / tile, tileRows = (lp.TileCount + tilesX - 1) / tilesX;
        var maps = new[] { lp.Amount, lp.Fire, lp.Aim, lp.Glow, lp.GlowFire, lp.GlowAim };
        // Runs of changed tile rows, each sent in one go.
        int run = -1;
        for (int row = 0; row <= tileRows; row++)
        {
            bool changed = false;
            if (row < tileRows)
            {
                if (all) changed = true;
                else
                    for (int x = 0; x < tilesX && !changed; x++)
                        changed = lp.TileVersion(row * tilesX + x) > t.Uploaded;
            }
            if (changed) { if (run < 0) run = row; continue; }
            if (run < 0) continue;
            int y0 = run * tile, y1 = Math.Min(h, row * tile);
            run = -1;
            var box = new Box(0, y0, 0, w, y1, 1);
            for (int i = 0; i < 6; i++)
                fixed (byte* p = &maps[i][y0 * w])
                    ctx.UpdateSubresource(t.Maps[i], 0, box, (IntPtr)p, (uint)w, 0);
            if (way != null && !wayAll)
                fixed (int* p = &way[y0 * w])
                    ctx.UpdateSubresource(t.Way!, 0, box, (IntPtr)p, (uint)(w * 4), 0);
        }
        if (wayAll)
        {
            // The way colours weren't kept up while colour by way was off: all of them.
            fixed (int* p = way)
                ctx.UpdateSubresource(t.Way!, 0, null, (IntPtr)p, (uint)(w * 4), 0);
            t.WayFull = true;
        }
        if (way == null) t.WayFull = false;
        t.Uploaded = version;
    }

    private static Vector4 V((float X, float Y, float Z) v) => new(v.X, v.Y, v.Z, 0f);

    private static Vector4 Straight(uint argb) => new((argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >> 24) / 255f);

    private static Vector4 Straight(int argb) => Straight((uint)argb);

    private static void DrawPaint(Kit kit, Trace t, GpuDevice gpu, Target target, LaserPaint lp, OverlaySettings s, bool front, uint[] moves,
        (float X, float Y, float Z) colX, (float X, float Y, float Z) colY, (float X, float Y, float Z) colZ,
        (float X, float Y, float Z) scX, (float X, float Y, float Z) scY, (float X, float Y, float Z) scZ)
    {
        var ctx = gpu.D3D.ImmediateContext;
        var light = PaintPicture.LightDir(s.LaserLightFrom);
        float moveAlpha = 0f;
        foreach (var m in moves) moveAlpha += (m >> 24) / 255f;
        bool byWay = s.LaserColourByWay && lp.Way != null && t.WayView != null;
        var look = new LookData
        {
            ColX = V(colX), ColY = V(colY), ColZ = V(colZ),
            SceneX = V(scX), SceneY = V(scY), SceneZ = V(scZ),
            LightDir = V(light),
            M0 = Straight(moves[0]), M1 = Straight(moves[1]), M2 = Straight(moves[2]),
            M3 = Straight(moves[3]), M4 = Straight(moves[4]), M5 = Straight(moves[5]),
            FireAhead = Straight(s.Color("fire")), FireBehind = Straight(s.Color("fire_behind")),
            AimAhead = Straight(s.Color("aim")), AimBehind = Straight(s.Color("aim_behind")),
            OutlineColor = Straight(s.Color("outline")),
            Eye = s.Perspective ? LaserTrace.CameraDistance : 0f,
            Front = front ? 1f : 0f,
            WayMode = byWay ? 1f : 0f,
            WayAlpha = moveAlpha / 6f,
            GlowK = s.LaserGlow, FireGlowK = s.LaserFireGlow, AimGlowK = s.LaserAimGlow,
            Outline = s.LaserOutline ? 1f : 0f,
            Shade = s.LaserShade, Inside = s.LaserInside, Light = s.LaserLight, Shine = s.LaserShine,
            Extent = 1.05f,
        };
        ctx.UpdateSubresource(in look, kit.LookCb);
        ctx.OMSetRenderTargets(target.View);
        ctx.ClearRenderTargetView(target.View, new Color4(0f, 0f, 0f, 0f));
        ctx.OMSetBlendState(null);
        ctx.RSSetState(kit.Raster);
        ctx.RSSetViewport(0f, 0f, target.Size, target.Size);
        ctx.IASetInputLayout(null);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.VSSetShader(kit.FullVs);
        ctx.PSSetShader(kit.PaintPs);
        ctx.PSSetConstantBuffer(0, kit.LookCb);
        ctx.PSSetSampler(0, kit.Smooth);
        var views = new ID3D11ShaderResourceView[7];
        for (int i = 0; i < 6; i++) views[i] = t.MapViews[i];
        views[6] = byWay ? t.WayView! : t.MapViews[0];
        ctx.PSSetShaderResources(0, views);
        ctx.Draw(3, 0);
        Unbind(ctx);
    }

    private static void DrawSnake(Kit kit, Trace t, GpuDevice gpu, Half half, PaintRendererPieces pieces, int size, float glow)
    {
        var d3d = gpu.D3D;
        var ctx = d3d.ImmediateContext;
        int n = pieces.Count;
        if (t.PieceRoom < n)
        {
            t.PiecesView?.Dispose(); t.Pieces?.Dispose();
            int room = Math.Max(256, t.PieceRoom);
            while (room < n) room *= 2;
            int stride = Marshal.SizeOf<PieceData>();
            t.Pieces = d3d.CreateBuffer(new BufferDescription((uint)(room * stride), BindFlags.ShaderResource, ResourceUsage.Dynamic,
                CpuAccessFlags.Write, ResourceOptionFlags.BufferStructured, (uint)stride));
            t.PiecesView = d3d.CreateShaderResourceView(t.Pieces, new ShaderResourceViewDescription(t.Pieces, Format.Unknown, 0, (uint)room));
            t.PieceRoom = room;
        }
        var mapped = ctx.Map(t.Pieces!, 0, MapMode.WriteDiscard);
        try
        {
            unsafe
            {
                var dst = (PieceData*)mapped.DataPointer;
                for (int i = 0; i < n; i++)
                {
                    ref var p = ref pieces.Items[i];
                    float reach = (glow > 0f ? p.Half * 3.2f : p.Half) + 1f;
                    dst[i] = new PieceData
                    {
                        Ends = new Vector4(p.Ax, p.Ay, p.Bx, p.By),
                        Look = new Vector4(p.Half, p.Op, reach, glow),
                        Col = new Vector4((p.Col >> 16 & 0xFF) / 255f, (p.Col >> 8 & 0xFF) / 255f, (p.Col & 0xFF) / 255f, 1f),
                    };
                }
            }
        }
        finally
        {
            ctx.Unmap(t.Pieces!, 0);
        }
        var snake = new SnakeData { TargetW = size, TargetH = size };
        ctx.UpdateSubresource(in snake, kit.SnakeCb);
        var line = half.Line!;
        var glowTarget = half.HasGlow ? half.Glow : null;
        ctx.ClearRenderTargetView(line.View, new Color4(0f, 0f, 0f, 0f));
        if (glowTarget != null)
        {
            ctx.ClearRenderTargetView(glowTarget.View, new Color4(0f, 0f, 0f, 0f));
            ctx.OMSetRenderTargets([line.View, glowTarget.View]);
        }
        else ctx.OMSetRenderTargets(line.View);
        ctx.OMSetBlendState(kit.Max);
        ctx.RSSetState(kit.Raster);
        ctx.RSSetViewport(0f, 0f, size, size);
        ctx.IASetInputLayout(null);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.VSSetShader(kit.SnakeVs);
        ctx.VSSetShaderResource(7, t.PiecesView!);
        ctx.VSSetConstantBuffer(1, kit.SnakeCb);
        ctx.PSSetShader(kit.SnakePs);
        ctx.PSSetShaderResource(7, t.PiecesView!);
        ctx.PSSetConstantBuffer(1, kit.SnakeCb);
        ctx.DrawInstanced(6, (uint)n, 0, 0);
        Unbind(ctx);
    }

    /// <summary>Leaves nothing of ours bound (Direct2D reads these pictures next).</summary>
    private static void Unbind(ID3D11DeviceContext ctx)
    {
        ctx.UnsetRenderTargets();
        ctx.OMSetBlendState(null);
        ctx.PSSetShaderResources(0u, 8u, NoViews);
        ctx.VSSetShaderResources(0u, 8u, NoViews);
    }

    // Empty slots (null views unbind).
    private static readonly ID3D11ShaderResourceView[] NoViews = new ID3D11ShaderResourceView[8];

    [StructLayout(LayoutKind.Sequential)]
    private struct LookData
    {
        public Vector4 ColX, ColY, ColZ, SceneX, SceneY, SceneZ, LightDir;
        public Vector4 M0, M1, M2, M3, M4, M5;
        public Vector4 FireAhead, FireBehind, AimAhead, AimBehind, OutlineColor;
        public float Eye, Front, WayMode, WayAlpha;
        public float GlowK, FireGlowK, AimGlowK, Outline;
        public float Shade, Inside, Light, Shine;
        public float Extent, Pad0, Pad1, Pad2;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SnakeData
    {
        public float TargetW, TargetH, Pad0, Pad1;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PieceData
    {
        public Vector4 Ends, Look, Col;
    }

    /// <summary>The shaders (HLSL, shader model 5): the paint as <see cref="PaintPicture"/> shades it, the Snake as <see cref="PaintRenderer"/>'s.</summary>
    private const string Shaders = """
        cbuffer Look : register(b0)
        {
            float4 ColX; float4 ColY; float4 ColZ;
            float4 SceneX; float4 SceneY; float4 SceneZ;
            float4 LightDir;
            float4 Moves[6];
            float4 FireAhead; float4 FireBehind; float4 AimAhead; float4 AimBehind; float4 OutlineColor;
            float Eye; float Front; float WayMode; float WayAlpha;
            float GlowK; float FireGlowK; float AimGlowK; float Outline;
            float Shade; float Inside; float Light; float Shine;
            float Extent; float3 LookPad;
        };
        cbuffer SnakeLook : register(b1)
        {
            float2 TargetSize; float2 SnakePad;
        };
        Texture2D<float> AmountMap : register(t0);
        Texture2D<float> FireMap : register(t1);
        Texture2D<float> AimMap : register(t2);
        Texture2D<float> GlowMap : register(t3);
        Texture2D<float> GlowFireMap : register(t4);
        Texture2D<float> GlowAimMap : register(t5);
        Texture2D<float4> WayMap : register(t6);
        SamplerState Smooth : register(s0);

        struct FullOut { float4 Pos : SV_Position; float2 Uv : TEXCOORD0; };

        // One triangle over the whole picture; Uv 0 .. 1 across it.
        FullOut FullVs(uint id : SV_VertexID)
        {
            FullOut o;
            float2 uv = float2((id << 1) & 2, id & 2);
            o.Pos = float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
            o.Uv = uv;
            return o;
        }

        float4 PaintPs(FullOut i) : SV_Target
        {
            float2 q = float2(-Extent + i.Uv.x * 2.0 * Extent, Extent - i.Uv.y * 2.0 * Extent);
            bool front = Front > 0.5;
            float3 p;
            float facing;
            if (Eye > 0.0)
            {
                float3 d = normalize(float3(q, -Eye));
                float b = Eye * d.z;
                float disc = b * b - (Eye * Eye - 1.0);
                if (disc < 0.0) return float4(0.0, 0.0, 0.0, 0.0);
                float t = front ? -b - sqrt(disc) : -b + sqrt(disc);
                p = float3(d.x * t, d.y * t, Eye + d.z * t);
                facing = abs(dot(p, d));
            }
            else
            {
                float rr = dot(q, q);
                if (rr >= 1.0) return float4(0.0, 0.0, 0.0, 0.0);
                float z = sqrt(1.0 - rr);
                p = float3(q, front ? z : -z);
                facing = z;
            }
            // Where the point is in the map.
            float3 v = float3(dot(ColX.xyz, p), dot(ColY.xyz, p), dot(ColZ.xyz, p));
            float lon = atan2(v.x, -v.z);
            float lat = asin(clamp(v.y, -1.0, 1.0));
            float2 uv = float2(lon / 6.28318531 + 0.5, 0.5 - lat / 3.14159265);
            float c = AmountMap.SampleLevel(Smooth, uv, 0);
            float gl = GlowMap.SampleLevel(Smooth, uv, 0);
            if (c < 0.004 && gl < 0.004) return float4(0.0, 0.0, 0.0, 0.0);
            float cs = max(c, 0.004);
            float fired = clamp(FireMap.SampleLevel(Smooth, uv, 0) / cs, 0.0, 1.0);
            float aimed = clamp(AimMap.SampleLevel(Smooth, uv, 0) / cs, 0.0, 1.0 - fired);
            float gs = max(gl, 0.004);
            float gFired = clamp(GlowFireMap.SampleLevel(Smooth, uv, 0) / gs, 0.0, 1.0);
            float gAimed = clamp(GlowAimMap.SampleLevel(Smooth, uv, 0) / gs, 0.0, 1.0 - gFired);
            // The point in the scene's frame: what its colour goes by.
            float3 g = float3(dot(SceneX.xyz, p), dot(SceneY.xyz, p), dot(SceneZ.xyz, p));
            float4 base;
            if (WayMode > 0.5)
            {
                base = float4(min(WayMap.SampleLevel(Smooth, uv, 0).rgb / cs, 1.0), WayAlpha);
            }
            else
            {
                float4 mid = (Moves[0] + Moves[1] + Moves[2] + Moves[3] + Moves[4] + Moves[5]) / 6.0;
                float3 n = abs(g);
                base = mid + n.x * ((g.x >= 0.0 ? Moves[0] : Moves[1]) - mid)
                    + n.y * ((g.y >= 0.0 ? Moves[2] : Moves[3]) - mid)
                    + n.z * ((g.z >= 0.0 ? Moves[4] : Moves[5]) - mid);
                base = saturate(base);
            }
            float kb = saturate((1.0 + g.z) / 2.0);
            float4 fc = lerp(FireAhead, FireBehind, kb);
            float4 ac = lerp(AimAhead, AimBehind, kb);
            float pb = 1.0 - fired - aimed;
            float gb = 1.0 - gFired - gAimed;
            float4 col = base * pb + fc * fired + ac * aimed;
            float4 hcol = base * gb + fc * gFired + ac * gAimed;
            float gk = GlowK * gb + FireGlowK * gFired + AimGlowK * gAimed;
            // Facing shade, inside and the scene's light, and its highlight.
            float kk = (1.0 + (0.25 + 0.75 * facing - 1.0) * Shade) * (front ? 1.0 : Inside);
            float3 nv = front ? p : -p;
            float3 l = LightDir.xyz;
            kk *= 1.0 + (0.3 + 0.9 * max(dot(nv, l), 0.0) - 1.0) * Light;
            float spec = Shine * pow(max(dot(nv, normalize(l + float3(0.0, 0.0, 1.0))), 0.0), 24.0);
            col.rgb = min(col.rgb * kk + spec, 1.0);
            hcol.rgb = min(hcol.rgb * kk, 1.0);
            // A glow over 100% also lights the line's core toward white, as hot light.
            float hot = 0.6 * (saturate((FireGlowK - 1.0) / 2.0) * fired + saturate((AimGlowK - 1.0) / 2.0) * aimed);
            col.rgb += (1.0 - col.rgb) * hot;
            // Glow under an outline under the paint (premultiplied); over 100% the glow blooms.
            float pa = c * col.a;
            float ga = (gk <= 1.0 ? gk * 0.45 * gl : min(0.9, 0.45 * pow(gl, 1.0 / gk) * sqrt(gk))) * hcol.a;
            float4 o = float4(hcol.rgb * ga, ga);
            if (Outline > 0.5)
            {
                float la = smoothstep(0.2, 0.4, gl) * (1.0 - c) * OutlineColor.a;
                o = float4(OutlineColor.rgb * la, la) + o * (1.0 - la);
            }
            o = float4(col.rgb * pa, pa) + o * (1.0 - pa);
            return o;
        }

        struct Piece { float4 Ends; float4 Look; float4 Col; };
        StructuredBuffer<Piece> Pieces : register(t7);

        struct SnakeOut { float4 Pos : SV_Position; nointerpolation uint Id : TEXCOORD0; };

        // Each piece's box (its glow's reach round it), as two triangles.
        SnakeOut SnakeVs(uint v : SV_VertexID, uint id : SV_InstanceID)
        {
            Piece pc = Pieces[id];
            float reach = pc.Look.z;
            float2 lo = min(pc.Ends.xy, pc.Ends.zw) - reach;
            float2 hi = max(pc.Ends.xy, pc.Ends.zw) + reach;
            uint k = v % 6;
            float2 at = float2((k == 1 || k == 4 || k == 5) ? hi.x : lo.x, (k == 2 || k == 3 || k == 5) ? hi.y : lo.y);
            SnakeOut o;
            o.Pos = float4(at.x / TargetSize.x * 2.0 - 1.0, 1.0 - at.y / TargetSize.y * 2.0, 0.0, 1.0);
            o.Id = id;
            return o;
        }

        struct SnakeTargets { float4 Line : SV_Target0; float4 Glow : SV_Target1; };

        // The round-ended piece's cover and its glow (wider, fainter copies), as the CPU's Capsule.
        SnakeTargets SnakePs(SnakeOut i)
        {
            Piece pc = Pieces[i.Id];
            float2 a = pc.Ends.xy;
            float2 d = pc.Ends.zw - a;
            float2 q = i.Pos.xy - a;
            float l2 = dot(d, d);
            float t = l2 < 1e-6 ? 0.0 : saturate(dot(q, d) / l2);
            float dist = length(q - d * t);
            float hw = pc.Look.x, op = pc.Look.y, glow = pc.Look.w;
            float cover = saturate(hw - dist + 0.5) * op;
            float clear = (1.0 - 0.18 * glow * saturate(hw * 3.2 - dist + 0.5))
                * (1.0 - 0.3 * glow * saturate(hw * 2.2 - dist + 0.5))
                * (1.0 - 0.45 * glow * saturate(hw * 1.5 - dist + 0.5));
            float g = (1.0 - clear) * op;
            SnakeTargets o;
            o.Line = float4(pc.Col.rgb * cover, cover);
            o.Glow = float4(pc.Col.rgb * g, g);
            return o;
        }
        """;
}
