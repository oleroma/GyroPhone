using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace GyroPhone.Tray;

internal enum TrayState
{
    Waiting,
    Connected,
    Warning,
    Error,
}

/// <summary>
/// Notification-area icons drawn with GDI+ at startup (no .ico resources): a coloured disc with a
/// phone outline. Grey = waiting, green = connected, amber = no driver or pad off, red = error.
/// </summary>
internal sealed class TrayIcons : IDisposable
{
    private readonly Icon[] _icons;

    public TrayIcons()
    {
        int size = Math.Max(16, SystemInformation.SmallIconSize.Width);
        _icons =
        [
            Draw(Color.FromArgb(128, 128, 128), size),
            Draw(Color.FromArgb(46, 160, 67), size),
            Draw(Color.FromArgb(230, 162, 0), size),
            Draw(Color.FromArgb(215, 45, 45), size),
        ];
    }

    public Icon For(TrayState state) => _icons[(int)state];

    private static Icon Draw(Color color, int size)
    {
        using var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using (var disc = new SolidBrush(color))
                g.FillEllipse(disc, 0.5f, 0.5f, size - 1.5f, size - 1.5f);

            // Phone outline in the middle of the disc, with a small "screen" dot for the gyro.
            float w = size * 0.42f, h = size * 0.62f;
            var phone = new RectangleF((size - w) / 2f, (size - h) / 2f, w, h);
            float stroke = Math.Max(1.25f, size / 12f);
            using (var path = RoundedRect(phone, size * 0.09f))
            using (var pen = new Pen(Color.White, stroke))
                g.DrawPath(pen, path);
            float dot = Math.Max(1.5f, size / 9f);
            using (var white = new SolidBrush(Color.White))
                g.FillEllipse(white, (size - dot) / 2f, phone.Bottom - stroke - dot * 1.4f, dot, dot);
        }
        nint handle = bmp.GetHicon();
        try
        {
            using var temp = Icon.FromHandle(handle);
            return (Icon)temp.Clone(); // the clone owns a copy; the GDI handle is freed below
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    private static GraphicsPath RoundedRect(RectangleF r, float radius)
    {
        float d = radius * 2;
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(nint hIcon);

    public void Dispose()
    {
        foreach (var i in _icons) i.Dispose();
    }
}
