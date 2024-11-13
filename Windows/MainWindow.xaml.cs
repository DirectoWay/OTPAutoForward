using System;
using System.Windows;
using Microsoft.Toolkit.Uwp.Notifications;
using WinCAPTCHA.ServiceHandler;
using Clipboard = System.Windows.Clipboard;

namespace WinCAPTCHA;

/// <summary>
/// Interaction logic for MainWindow.xaml
/// </summary>
public partial class MainWindow
{
    private readonly WebSocketHandler _webSocketHandler;

    public MainWindow()
    {
        InitializeComponent();
        _webSocketHandler = new WebSocketHandler();
        StartWebSocketServer();
    }

    private async void StartWebSocketServer()
    {
        await _webSocketHandler.StartWebSocketServer();
    }

    private void Button_Pair(object sender, RoutedEventArgs e)
    {
        // 先判断 Windows 端和 Android 端是否已经处于连接状态
        if (IsPaired())
        {
            MessageBox.Show("已配对！");
        }
        else
        {
            StartWebSocketConnect();
        }
    }

    // ReSharper disable once InconsistentNaming
    private void StartWebSocketConnect()
    {
        // 获取 Win 端程序当前所在的内网 IP 地址
        var localIp = ConnectInfoHandler.GetLocalIpAddress();
        var webSocketServerUrl = $"ws://{localIp}:9000";

        // 加密连接信息并生成二维码图像
        var qrData = QRCodeHandler.GenerateEncryptedQRCode(webSocketServerUrl);
        var qrCodeImage = QRCodeHandler.GenerateQrCodeImage(qrData, 300, 300);

        // 显示二维码
        QrCodeImage.Source = qrCodeImage;
        QrCodeImage.Visibility = Visibility.Visible;

        // 连接WebSocket服务器
        WebSocketHandler.ConnectToWebSocketServer(webSocketServerUrl);
    }

    /* 检查配对状态 */
    private static bool IsPaired()
    {
        return false;
    }

    /* 按钮: 用于测试验证码消息的弹出 */
    private void Button_Click(object sender, RoutedEventArgs e)
    {
        var toastId = Guid.NewGuid().ToString();
        new ToastContentBuilder().AddArgument("action", "copy").AddArgument("toastId", toastId)
            .AddText("成功获取到验证码: 123456").Show();

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