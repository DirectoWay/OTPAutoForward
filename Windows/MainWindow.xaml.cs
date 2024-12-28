using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Text.RegularExpressions;
using System.Windows;
using log4net;
using Microsoft.Toolkit.Uwp.Notifications;
using OTPAutoForward.ServiceHandler;
using Clipboard = System.Windows.Clipboard;

namespace OTPAutoForward
{
    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow
    {
        private readonly WebSocketHandler _webSocketHandler;
        private static readonly ILog Log = LogManager.GetLogger(typeof(MainWindow));

        public MainWindow(WebSocketHandler webSocketHandler)
        {
            InitializeComponent();
            ToastNotificationManagerCompat.OnActivated += OnToastActivated;
            _webSocketHandler = webSocketHandler;
            _webSocketHandler.OnMessageReceived += ShowToastNotification;
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
                        var buttonText = value.Length > 20 ? value.Substring(0, 17) + "..." : value;
                        toastBuilder.AddButton(new ToastButton()
                            .SetContent(buttonText) // 按钮显示的内容
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
            // 读取配置文件中的短信关键字
            var keywordList = App.AppSettings.MessageKeyword;
            if (keywordList != null)
            {
                var keywords = new HashSet<string>(keywordList);
                // 检查短信内容是否包含验证码关键词
                if (!keywords.Any(message.Contains))
                {
                    return new List<string>();
                }
            }

            // 正则规则表
            var patterns = new List<string>
            {
                // 提取 4 位数字，确保前后没有其他数字
                @"(?<!\d)(\d{4})(?!\d)",

                // 提取 6 位数字，确保前后没有其他数字
                @"(?<!\d)(\d{6})(?!\d)",

                // 识别码
                @"(?:识别码|识别码是)\s*[:：]?\s*([A-Za-z0-9-_.]+)"
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

        private System.Timers.Timer _timer;

        /** 配对按钮: 获取配对用的二维码 */
        private void GetQRCode(object sender, RoutedEventArgs e)
        {
            try
            {
                var qrData = QRCodeHandler.GenerateEncryptedQRCode();
                var qrCodeImage = QRCodeHandler.GenerateQrCodeImage(qrData, 500, 500);

                QrCodeImage.Source = qrCodeImage;
                QrCodeImage.Visibility = Visibility.Visible;
            }

            catch (Exception ex)
            {
                Log.Error("生成二维码时发生异常: " + ex);
                MessageBox.Show("生成二维码时发生异常\n请重置密钥后再进行尝试",
                    "二维码异常", MessageBoxButton.OK, MessageBoxImage.Warning);
            }

            // 过一段时间后自动收起二维码
            if (_timer != null)
            {
                _timer.Stop();
                _timer.Dispose();
            }

            _timer = new System.Timers.Timer(1 * 60 * 1000);
            _timer.Elapsed += (s, ev) =>
            {
                Dispatcher.Invoke(() => { QrCodeImage.Visibility = Visibility.Collapsed; });
            };
            _timer.AutoReset = false;
            _timer.Enabled = true;
        }
    }
}