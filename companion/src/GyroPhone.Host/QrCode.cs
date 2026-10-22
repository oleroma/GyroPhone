using System.Collections;
using QRCoder;

namespace GyroPhone.Host;

/// <summary>A QR code as modules, for the tray to draw. Error correction M, with the 4-module quiet zone.</summary>
public static class QrCode
{
    /// <summary>Rows of dark (true) and light modules; the code is square.</summary>
    public static IReadOnlyList<BitArray> Modules(string text)
    {
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        return data.ModuleMatrix.Select(r => new BitArray(r)).ToList();
    }
}
