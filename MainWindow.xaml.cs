using System;
using System.Drawing;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Media.Imaging;
using Microsoft.Toolkit.Uwp.Notifications;
using ZXing;
using ZXing.Common;
using ZXing.Rendering;
using Clipboard = System.Windows.Clipboard;

namespace WinCAPTCHA
{
    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window
    {
        public MainWindow()
        {
            InitializeComponent();
        }

        private void Button_Pair(object sender, RoutedEventArgs e)
        {
            if (IsPaired())
            {
                MessageBox.Show("已配对！");
            }
            else
            {
                // 生成配对信息
                var pairingInfo = new
                {
                    broker = "mqtt.example.com",
                    port = 1883,
                    username = "user123",
                    password = "pass456",
                    clientId = Guid.NewGuid().ToString()
                };

                var qrData = JsonSerializer.Serialize(pairingInfo);
                BitmapImage qrCodeImage = GenerateQrCodeImage(qrData, 300, 300);

                // 显示二维码
                QrCodeImage.Source = qrCodeImage;
                QrCodeImage.Visibility = Visibility.Visible;
            }
        }

        private BitmapImage GenerateQrCodeImage(string qrData, int width, int height)
        {
            // 生成二维码图片
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
            var bitmapData = bitmap.LockBits(new System.Drawing.Rectangle(0, 0, pixelData.Width, pixelData.Height),
                System.Drawing.Imaging.ImageLockMode.WriteOnly, System.Drawing.Imaging.PixelFormat.Format32bppRgb);
            try
            {
                System.Runtime.InteropServices.Marshal.Copy(pixelData.Pixels, 0, bitmapData.Scan0,
                    pixelData.Pixels.Length);
            }
            finally
            {
                bitmap.UnlockBits(bitmapData);
            }

            using (var memory = new MemoryStream())
            {
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

        private bool IsPaired()
        {
            // 这里添加检查配对状态的逻辑
            return false;
        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            var toastId = Guid.NewGuid().ToString();

            new ToastContentBuilder()
                .AddArgument("action", "copy")
                .AddArgument("toastId", toastId)
                .AddText("成功获取到验证码: 123456")
                .Show();

            ToastNotificationManagerCompat.OnActivated += toastArgs =>
            {
                Application.Current.Dispatcher.Invoke(() =>
                {
                    var arguments = ToastArguments.Parse(toastArgs.Argument);
                    if (arguments["action"] == "copy" && arguments["toastId"] == toastId)
                    {
                        Clipboard.SetText("123456");
                    }
                });
            };
        }
    }
}