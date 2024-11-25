using System;
using System.Collections.Generic;
using System.Linq;
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

        // 排除掉包含 "验证" 字眼但不携带四位或六位数字的短信
        if (!message.Contains("验证码") && !message.Contains("验证") && !Regex.IsMatch(message, @"\b\d{4,6}\b"))
            return false;

        // 正则匹配规则表(规则先后顺序会影响匹配结果!!)
        var rules = new List<Regex>
        {
            // 带前缀的字母+符号+数字形式的验证码
            new(
                @"(?:验证码\s*[:：]?\s*|是您的验证码|是您.*?验证码|验证码是|验证码为|是验证码|[^\w]是|[^\w])\s*([A-Za-z][-_.]?[A-Za-z0-9]*[-_.]?[\d]{4,10})"),

            // 字母数字混合验证码
            new(@"\b([A-Za-z0-9-]{5,10})\b.*?(验证码|临时密码|动态码)"),

            // 核心关键字匹配
            new(@"(?:验证码\s*[:：]?\s*|是您的验证码|是您.*?验证码|验证码是|验证码为|是验证码|[^\w]是|[^\w])\s*([\d]{4,6})(?![\d-])"),

            // 4-6位数字验证码(简易场景)
            new(@"\b([\d]{4,6})\b.*?(验证码|临时密码|动态码)")
        };

        verificationCode = string.Empty;

        // 遍历规则表并匹配验证码
        var match = rules.Select(rule => rule.Match(message)).FirstOrDefault(m => m.Success);
        if (match == null) return false;
        verificationCode = match.Groups[1].Value.Trim();
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