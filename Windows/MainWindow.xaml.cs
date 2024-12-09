using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Windows.UI.Notifications;
using Microsoft.Toolkit.Uwp.Notifications;
using WinCAPTCHA.ServiceHandler;
using Clipboard = System.Windows.Clipboard;

namespace WinCAPTCHA;

/// <summary>
/// Interaction logic for MainWindow.xaml
/// </summary>
public partial class MainWindow
{
    private readonly WebSocketHandler _webSocketHandler = new();

    /** WebSocket 服务 IP 地址*/
    private readonly IPAddress _ipAddress = ConnectInfoHandler.GetLocalIP();

    /** WebSocket 服务端口 */
    private const string Port = "9224";

    /** 短信订阅标志 */
    private int _isEventSubscribed;

    public MainWindow()
    {
        InitializeComponent();
        ToastNotificationManagerCompat.OnActivated += OnToastActivated;

        if (CheckWebSocketPort(int.Parse(Port)))
        {
            ShowPortInUseNotification();
            Task.Delay(3000).ContinueWith(_ => Application.Current.Dispatcher.Invoke(Application.Current.Shutdown));
        }
        else
        {
            StartWebSocketServer();
        }
    }

    /** 检查 9224 端口是否已经被占用 */
    private static bool CheckWebSocketPort(int port)
    {
        var isAvailable = false;

        try
        {
            // 检查 TCP 端口
            var tcpListeners = IPGlobalProperties.GetIPGlobalProperties()
                .GetActiveTcpListeners();
            if (tcpListeners.Any(t => t.Port == port))
            {
                isAvailable = true;
            }

            // 检查 UDP 端口
            var udpListeners = IPGlobalProperties.GetIPGlobalProperties()
                .GetActiveUdpListeners();
            if (udpListeners.Any(u => u.Port == port))
            {
                isAvailable = true;
            }

            return isAvailable;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"端口检测异常：{ex.Message}");
            return false;
        }
    }

    /** 9224 端口被占用时弹出 Toast 弹窗提示用户 */
    private static void ShowPortInUseNotification()
    {
        var content = new ToastContentBuilder()
            .AddText($"端口 {Port} 已被占用，程序将于 3 秒后关闭。")
            .GetToastContent();

        var notification = new ToastNotification(content.GetXml());
        ToastNotificationManagerCompat.CreateToastNotifier().Show(notification);
    }

    private async void StartWebSocketServer()
    {
        if (Interlocked.CompareExchange(ref _isEventSubscribed, 1, 0) == 0)
        {
            _webSocketHandler.OnMessageReceived += ShowToastNotification;
        }

        await _webSocketHandler.StartWebSocketServer(_ipAddress, Port);
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
    protected override void OnClosing(CancelEventArgs e)
    {
        e.Cancel = true;
        Hide();
    }

    private static void ShowToastNotification(string message)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            var toastBuilder = new ToastContentBuilder()
                .AddText(message);

            // 提取短信中的关键信息并动态生成按钮
            var extractedInfo = ExtractInfoFromMessage(message);

            if (extractedInfo.Any())
            {
                foreach (var (key, value) in extractedInfo)
                {
                    // 限制按钮文本的长度
                    var buttonText = value.Length > 20 ? string.Concat(value.AsSpan(0, 17), "...") : value;
                    toastBuilder.AddButton(new ToastButton()
                        .SetContent($"{key}: {buttonText}") // 按钮显示的内容, 如 "验证码: 123456"
                        .AddArgument("action", "copy")
                        .AddArgument("message", value)); // 点击按钮时传递的内容
                }
            }
            else
            {
                // 对于非验证码类型的短信, 点击 Toast 弹窗复制整条短信的内容
                toastBuilder.AddArgument("action", "copy")
                    .AddArgument("message", message);
            }

            toastBuilder.Show();
        });
    }

    /** 提取短信中的关键信息 (验证码、识别码、电话号码等) */
    private static Dictionary<string, string> ExtractInfoFromMessage(string message)
    {
        var extractedInfo = new Dictionary<string, string>();

        // const string test = "您好朋友, 这是一条短信";
        // 正则规则表
        var patterns = new List<(string key, string pattern)>
        {
            // 带前缀的字母+符号+数字形式的验证码
            ("验证码",
                @"(?:验证码\s*[:：]?\s*|是您的验证码|是您.*?验证码|验证码是|验证码为|是验证码|[^\w]是|[^\w])\s*([A-Za-z][-_.]?[A-Za-z0-9]*[-_.]?[\d]{4,10})"),

            // 字母数字混合验证码
            ("验证码", @"\b([A-Za-z0-9-]{5,10})\b.*?(验证码|临时密码|动态码)"),

            // 核心关键字匹配
            ("验证码", @"(?:验证码\s*[:：]?\s*|是您的验证码|是您.*?验证码|验证码是|验证码为|是验证码|[^\w]是|[^\w])\s*([\d]{4,6})(?![\d-])"),

            // 4-6位数字验证码（简易场景）
            ("验证码", @"\b([\d]{4,6})\b.*?(验证码|临时密码|动态码)"),

            ("识别码", @"(?:识别码|识别码是)\s*[:：]?\s*([A-Za-z0-9-_.]+)"),
            ("电话号码", @"(?:电话|致电|热线)[:：]?\s*([0-9-]+)")
        };
        foreach (var (key, pattern) in patterns)
        {
            var match = Regex.Match(message, pattern);
            if (match.Success)
            {
                extractedInfo[key] = match.Groups[1].Value;
            }
        }

        return extractedInfo;
    }

    /** Toast 弹窗被点击时的事件 */
    private static void OnToastActivated(ToastNotificationActivatedEventArgsCompat toastArgs)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            var arguments = ToastArguments.Parse(toastArgs.Argument);
            if (arguments["action"] != "copy") return;

            var textToCopy = arguments["message"];
            CopyToClipboard(textToCopy);
        });
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

    /** 配对按钮: 获取配对用的二维码 */
    private void GetQRCode(object sender, RoutedEventArgs e)
    {
        var qrData = QRCodeHandler.GenerateEncryptedQRCode(_ipAddress, Port);
        var qrCodeImage = QRCodeHandler.GenerateQrCodeImage(qrData, 300, 300);

        QrCodeImage.Source = qrCodeImage;
        QrCodeImage.Visibility = Visibility.Visible;
    }
}