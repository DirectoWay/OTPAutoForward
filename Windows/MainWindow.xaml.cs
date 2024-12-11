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

        var portStatus = CheckWebSocketPort(int.Parse(Port));
        if (portStatus)
        {
            StartWebSocketServer();
        }
        else
        {
            ShowPortInUseNotification();
            Task.Delay(3000).ContinueWith(_ => Application.Current.Dispatcher.Invoke(Application.Current.Shutdown));
        }
    }

    ///<summary>
    /// 检查 9224 端口是否已经被占用
    /// </summary>
    /// <returns>true 代表着端口可用</returns>
    private static bool CheckWebSocketPort(int port)
    {
        var isAvailable = true;

        try
        {
            // 检查 TCP 端口
            var tcpListeners = IPGlobalProperties.GetIPGlobalProperties()
                .GetActiveTcpListeners();
            if (tcpListeners.Any(t => t.Port == port))
            {
                isAvailable = false;
            }

            // 检查 UDP 端口
            var udpListeners = IPGlobalProperties.GetIPGlobalProperties()
                .GetActiveUdpListeners();
            if (udpListeners.Any(u => u.Port == port))
            {
                isAvailable = false;
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

    public static void ShowToastNotification(string message)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            var toastBuilder = new ToastContentBuilder()
                .AddText(message);

            // 提取短信中的关键信息并动态生成按钮
            var extractedInfo = ExtractInfoFromMessage(message);

            if (extractedInfo.Any())
            {
                foreach (var value in extractedInfo)
                {
                    // 限制按钮文本的长度
                    var buttonText = value.Length > 20 ? string.Concat(value.AsSpan(0, 17), "...") : value;
                    toastBuilder.AddButton(new ToastButton()
                        .SetContent($"{buttonText}") // 按钮显示的内容
                        .AddArgument("action", "copy")
                        .AddArgument("message", value)); // 点击按钮时传递的内容
                }
            }

            // 点击 Toast 弹窗本身可以复制整条短信的内容
            toastBuilder.AddArgument("action", "copy")
                .AddArgument("message", message);

            toastBuilder.Show();
        });
    }

    /** 提取短信中的关键信息 (验证码、识别码、电话号码等) */
    private static List<string> ExtractInfoFromMessage(string message)
    {
        // 正则规则表
        var patterns = new List<string>
        {
            // 提取 4 位数字，确保前后没有其他数字
            @"(?<!\d)(\d{4})(?!\d)",

            // 提取 6 位数字，确保前后没有其他数字
            @"(?<!\d)(\d{6})(?!\d)",

            // 识别码
            @"(?:识别码|识别码是)\s*[:：]?\s*([A-Za-z0-9-_.]+)",
        };

        var uniqueResults = new HashSet<string>();

        foreach (var value in from pattern in patterns
                 select Regex.Matches(message, pattern)
                 into matches
                 from Match match in matches
                 where match.Success && match.Groups.Count > 1
                 select match.Groups[1].Value)
        {
            // 处理数字：只保留 4 位数和 6 位数的结果
            if (Regex.IsMatch(value, @"^\d{4}$") || Regex.IsMatch(value, @"^\d{6}$"))
            {
                // 确保提取的结果不是电话号码的子串
                if (!Regex.IsMatch(message, "(?:电话|致电|热线)[^0-9]*" + Regex.Escape(value)))
                {
                    uniqueResults.Add(value);
                }
            }
            // 识别码等其他结果不做长度检查
            else if (!string.IsNullOrEmpty(value))
            {
                uniqueResults.Add(value);
            }
        }

        return uniqueResults.ToList();
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