using System;
using System.Text.RegularExpressions;
using System.Threading;
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
    private readonly NotifyIconHandler _notifyIconHandler;
    private readonly WebSocketHandler _webSocketHandler;

    /** 程序的主窗口是否存在 */
    private bool _isExiting;
    
    /** 短信订阅标志 */
    private int _isEventSubscribed;

    public MainWindow()
    {
        InitializeComponent();
        _notifyIconHandler = new NotifyIconHandler(RestoreWindow, ExitApplication);
        _webSocketHandler = new WebSocketHandler();
        ToastNotificationManagerCompat.OnActivated += OnToastActivated;
        StartWebSocketServer();
    }

    private async void StartWebSocketServer()
    {
        if (Interlocked.CompareExchange(ref _isEventSubscribed, 1, 0) == 0)
        {
            _webSocketHandler.OnMessageReceived += ShowToastNotification;
        }

        await _webSocketHandler.StartWebSocketServer();
    }

    private void RestoreWindow()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void ExitApplication()
    {
        _isExiting = true;
        _notifyIconHandler.Dispose();
        Application.Current.Shutdown();
    }

    protected override void OnStateChanged(EventArgs e)
    {
        base.OnStateChanged(e);
        if (WindowState == WindowState.Minimized)
        {
            Hide();
        }
    }

    /** 阻止真正的程序窗口关闭并最小化至托盘 */
    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        if (!_isExiting)
        {
            e.Cancel = true;
            Hide();
        }
        else
        {
            base.OnClosing(e);
        }
    }

    /** 弹出含有短信内容的 Toast 弹窗 */
    private static void ShowToastNotification(string message)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            var toastId = Guid.NewGuid().ToString();
            new ToastContentBuilder()
                .AddArgument("action", "copy")
                .AddArgument("toastId", toastId)
                .AddArgument("message", message)
                .AddText(message)
                .Show();
        });
    }

    /** Toast 弹窗被点击时的事件 */
    private static void OnToastActivated(ToastNotificationActivatedEventArgsCompat toastArgs)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            var arguments = ToastArguments.Parse(toastArgs.Argument);
            if (arguments["action"] != "copy") return;
            var message = arguments["message"];
            var textToCopy = VerifyMessage(message, out var verificationCode) ? verificationCode : message;
            CopyToClipboard(textToCopy);
        });
    }

    /** 解析短信并判断其是否为验证码短信 非验证码短信复制整条消息 验证码短信则只复制验证码本身 */
    private static bool VerifyMessage(string message, out string verificationCode)
    {
        verificationCode = string.Empty;
        if (!message.Contains("验证码") && !message.Contains("验证")) return false;

        // 提取验证码 (设定验证码为4位或6位数字，断言前后没有其他数字)
        var match = Regex.Match(message, @"(?<!\d)[\d]{4,6}(?!\d)");

        if (!match.Success) return false;
        verificationCode = match.Value;
        return true;
    }

    /** 将短信内容复制进剪贴板 */
    private static void CopyToClipboard(string text)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            try
            {
                Clipboard.Clear();
                Clipboard.SetDataObject(text);
            }
            catch (Exception e)
            {
                Console.WriteLine(e);
                throw;
            }
        });
    }

    /** 配对按钮 */
    private void Button_Pair(object sender, RoutedEventArgs e)
    {
        // 先判断 Win 端和 App 端是否已经处于连接状态
        if (PairedStatus())
        {
            MessageBox.Show("已配对！");
        }
        else
        {
            StartWebSocketConnect();
        }
    }

    /** 打开 WebSocket 连接 */
    private void StartWebSocketConnect()
    {
        var localIp = ConnectInfoHandler.GetLocalIpAddress();
        var webSocketServerUrl = $"ws://{localIp}:9000";
        
        var qrData = QRCodeHandler.GenerateEncryptedQRCode(webSocketServerUrl);
        var qrCodeImage = QRCodeHandler.GenerateQrCodeImage(qrData, 300, 300);
        
        QrCodeImage.Source = qrCodeImage;
        QrCodeImage.Visibility = Visibility.Visible;
    }

    /** 检查配对状态 */
    private static bool PairedStatus()
    {
        return false;
    }
}