using System;
using System.Drawing;
using System.IO;
using System.Security.Cryptography;
using System.Text;
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
    /** Win 端的固定公钥 */
    private static readonly string? WindowsPublicKey;

    /** RSA加密对象, 包含公钥和私钥 */
    private static readonly RSA Rsa = RSA.Create();

    static QRCodeHandler()
    {
        byte[]? windowsPrivateKey;
        if (KeyHandler.CheckRSAKeys())
        {
            var rsaKeys = KeyHandler.LoadRSAKeys();
            WindowsPublicKey = rsaKeys.PublicKey;
            windowsPrivateKey = rsaKeys.PrivateKey;
            Rsa.ImportRSAPrivateKey(windowsPrivateKey, out _); // 往 RSA 加密对象中导入私钥
            Console.WriteLine("密钥已初始化");
        }
        else
        {
            // 若本地没有密钥对, 则生成新的密钥对并保存
            WindowsPublicKey = Convert.ToBase64String(Rsa.ExportSubjectPublicKeyInfo());
            windowsPrivateKey = Rsa.ExportRSAPrivateKey();
            KeyHandler.SaveRSAKeys(WindowsPublicKey, windowsPrivateKey);
        }
    }

    /** 生成加密后的二维码内容 */
    public static string GenerateEncryptedQRCode()
    {
        // 创建配对信息
        var pairingInfo = new
        {
            deviceName = Environment.MachineName,
            deviceId = ConnectInfoHandler.GetDeviceID(),
            deviceType = ConnectInfoHandler.GetDeviceType(),
            windowsPublicKey = WindowsPublicKey // Win 端的公钥
        };
        var pairingInfoJson = JsonSerializer.Serialize(pairingInfo);

        // 加密配对信息
        var encryptedPairingInfo = KeyHandler.EncryptString(pairingInfoJson);
        var signature = SignData(encryptedPairingInfo, Rsa);
        var qrContent = $"{encryptedPairingInfo}.{signature}"; // 合并加密内容和签名

        return qrContent;
    }

    /** 签名加密后的配对信息 */
    private static string SignData(string data, RSA rsa)
    {
        var dataBytes = Encoding.UTF8.GetBytes(data);
        var signedBytes = rsa.SignData(dataBytes, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        return Convert.ToBase64String(signedBytes);
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