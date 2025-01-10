using System;
using System.IO;
using System.Text.Json;
using Microsoft.Toolkit.Uwp.Notifications;
using ZXing;
using ZXing.Common;


namespace OTPAutoForward.ServiceHandler
{
    /// <summary>
    /// 处理二维码相关的操作
    ///  </summary>
    public static class QRCodeHandler
    {
        private static string _qrCodePath;

        /** 生成加密后的二维码内容 */
        private static string GenerateEncryptedQRCode()
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

        /// <summary>
        /// 生成二维码图像
        /// </summary>
        /// <param name="qrData">需要生成图像的内容</param>
        /// <param name="width">二维码宽度</param>
        /// <param name="height">二维码高度</param>
        /// <returns>二维码图像的磁盘路径</returns>
        private static string GenerateQrCodeImage(string qrData, int width, int height)
        {
            var writer = new BarcodeWriter
            {
                Format = BarcodeFormat.QR_CODE, 
                Options = new EncodingOptions
                {
                    Width = width,
                    Height = height,
                    Margin = 0
                }
            };
            var qrCodeImage = writer.Write(qrData);
            var filePath = Path.Combine(Path.GetTempPath(), "QRCode.png");
            using (var stream = File.OpenWrite(filePath))
            {
                qrCodeImage.Save(stream, System.Drawing.Imaging.ImageFormat.Png);
            }

            return filePath;
        }

        /** 在 Toast 弹窗中显示配对二维码 */
        public static void ShowQRCode()
        {
            var qrData = GenerateEncryptedQRCode();
            _qrCodePath = GenerateQrCodeImage(qrData, 1024, 1024);
            new ToastContentBuilder()
                .AddText("请用 App 端扫描该二维码以进行配对")
                .AddInlineImage(new Uri(_qrCodePath))
                .SetToastDuration(ToastDuration.Long)
                .Show(); // 设置为长时间显示(大概 30 秒)

            // 释放图像资源
            var timer = new System.Timers.Timer(60000);
            timer.Elapsed += (sender, e) =>
            {
                if (File.Exists(_qrCodePath))
                {
                    File.Delete(_qrCodePath);
                }

                timer.Stop();
                timer.Dispose();
            };
            timer.Start();
        }
    }
}