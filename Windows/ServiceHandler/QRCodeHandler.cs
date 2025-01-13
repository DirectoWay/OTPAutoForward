using System;
using System.IO;
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
            var (pairInfo, signature) = ConnectInfoHandler.GetPairInfo();
            var qrData = pairInfo + "." + signature;

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