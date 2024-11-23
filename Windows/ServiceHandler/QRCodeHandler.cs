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

// ReSharper disable once InconsistentNaming
/// <summary>
///  QRCodeHandler 用于处理二维码相关的操作
///  </summary>
public static class QRCodeHandler
{
    /** 对称加密密钥 */
    private const string Key = "autoCAPTCHA-encryptedKey";

    /** RSA加密对象，包含公钥和私钥 */
    private static readonly RSA Rsa = RSA.Create();

    /** 生成加密后的二维码内容 */
    // ReSharper disable once InconsistentNaming
    public static string GenerateEncryptedQRCode(string webSocketServerUrl)
    {
        // 生成临时密钥对
        var windowsPublicKey = Convert.ToBase64String(Rsa.ExportSubjectPublicKeyInfo()); // 把裸公钥转换成 X.509 格式
        var windowsPrivateKey = Rsa.ExportRSAPrivateKey(); // 私钥

        // 创建配对信息
        var pairingInfo = new
        {
            serverUrl = webSocketServerUrl,
            windowsPublicKey, // Win端的临时公钥
            deviceId = ConnectInfoHandler.GetDeviceUniqueId(),
            deviceType = ConnectInfoHandler.GetDeviceType(),
            deviceName = Environment.MachineName
        };
        var pairingInfoJson = JsonSerializer.Serialize(pairingInfo);

        var encryptedPairingInfo = EncryptString(pairingInfoJson, Key);
        var signature = SignData(encryptedPairingInfo, Rsa);
        var qrContent = $"{encryptedPairingInfo}.{signature}"; // 合并加密内容和签名

        return qrContent;
    }

    /** 加密配对信息 */
    private static string EncryptString(string plainText, string key)
    {
        using var aes = Aes.Create();
        aes.Mode = CipherMode.CBC;
        aes.Padding = PaddingMode.PKCS7;
        aes.Key = Encoding.UTF8.GetBytes(key);
        aes.IV = new byte[16]; // 初始化向量
        var encryptor = aes.CreateEncryptor(aes.Key, aes.IV);

        using var ms = new MemoryStream();
        using var cs = new CryptoStream(ms, encryptor, CryptoStreamMode.Write);
        using (var sw = new StreamWriter(cs))
        {
            sw.Write(plainText);
        }

        return Convert.ToBase64String(ms.ToArray());
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

        var pixelData = qrCodeWriter.Write(qrData);
        using var bitmap = new Bitmap(pixelData.Width, pixelData.Height,
            System.Drawing.Imaging.PixelFormat.Format32bppRgb);
        var bitmapData = bitmap.LockBits(new Rectangle(0, 0, pixelData.Width, pixelData.Height),
            System.Drawing.Imaging.ImageLockMode.WriteOnly,
            System.Drawing.Imaging.PixelFormat.Format32bppRgb);
        try
        {
            System.Runtime.InteropServices.Marshal.Copy(pixelData.Pixels, 0, bitmapData.Scan0,
                pixelData.Pixels.Length);
        }
        finally
        {
            bitmap.UnlockBits(bitmapData);
        }

        using var memory = new MemoryStream();
        bitmap.Save(memory, System.Drawing.Imaging.ImageFormat.Bmp);
        memory.Position = 0;

        var bitmapImage = new BitmapImage();
        bitmapImage.BeginInit();
        bitmapImage.StreamSource = memory;
        bitmapImage.CacheOption = BitmapCacheOption.OnLoad;
        bitmapImage.EndInit();

        return bitmapImage;
    }
}