using System;
using System.Drawing;
using System.IO;
using System.Text.Json;
using System.Windows.Media.Imaging;
using ZXing;
using ZXing.Common;

namespace WinCAPTCHA.ServiceHandler;

/// <summary>
/// 处理二维码相关的操作
///  </summary>
public static class QRCodeHandler
{
    /** 生成加密后的二维码内容 */
    public static string GenerateEncryptedQRCode()
    {
        // 创建配对信息
        var pairingInfo = new
        {
            deviceName = Environment.MachineName,
            deviceId = ConnectInfoHandler.GetDeviceID(),
            deviceType = ConnectInfoHandler.GetDeviceType(),
            windowsPublicKey = KeyHandler.WindowsPublicKey // Win 端的公钥
        };
        var pairingInfoJson = JsonSerializer.Serialize(pairingInfo);

        // 加密配对信息
        var encryptedPairingInfo = KeyHandler.EncryptString(pairingInfoJson);
        var signature = KeyHandler.SignData(pairingInfoJson);
        var qrContent = $"{encryptedPairingInfo}.{signature}"; // 合并加密内容和签名

        return qrContent;
    }

    /** 生成二维码图像 */
    public static BitmapImage GenerateQrCodeImage(string qrData, int width, int height)
    {
        var qrCodeWriter = new BarcodeWriterPixelData
        {
            Format = BarcodeFormat.QR_CODE,
            Options = new EncodingOptions
            {
                Width = width,
                Height = height
            }
        };

        // 生成像素数据
        var pixelData = qrCodeWriter.Write(qrData);

        using var bitmap = new Bitmap(pixelData.Width, pixelData.Height,
            System.Drawing.Imaging.PixelFormat.Format32bppRgb);
        var bitmapData = bitmap.LockBits(
            new Rectangle(0, 0, pixelData.Width, pixelData.Height),
            System.Drawing.Imaging.ImageLockMode.WriteOnly,
            System.Drawing.Imaging.PixelFormat.Format32bppRgb
        );
        try
        {
            // 拷贝像素数据到位图
            System.Runtime.InteropServices.Marshal.Copy(pixelData.Pixels, 0, bitmapData.Scan0, pixelData.Pixels.Length);
        }
        finally
        {
            bitmap.UnlockBits(bitmapData);
        }

        // 将位图保存到内存流
        using var memory = new MemoryStream();
        bitmap.Save(memory, System.Drawing.Imaging.ImageFormat.Bmp);
        memory.Position = 0;

        // 加载 BitmapImage
        var bitmapImage = new BitmapImage();
        bitmapImage.BeginInit();
        bitmapImage.StreamSource = new MemoryStream(memory.ToArray()); // 确保流不被释放
        bitmapImage.CacheOption = BitmapCacheOption.OnLoad; // 确保立即加载流
        bitmapImage.EndInit();
        bitmapImage.Freeze(); // 确保线程安全

        return bitmapImage;
    }
}