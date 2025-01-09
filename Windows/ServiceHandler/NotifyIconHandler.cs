using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Win32;
using FontAwesome.Sharp;
using log4net;
using Microsoft.Toolkit.Uwp.Notifications;
using Application = System.Windows.Application;

namespace OTPAutoForward.ServiceHandler
{
    /** 用于管理软件的托盘图标与托盘菜单 */
    public class NotifyIconHandler
    {
        private static readonly ILog Log = LogManager.GetLogger(typeof(NotifyIconHandler));

        private readonly WebSocketHandler _webSocketHandler = App.Resolve<WebSocketHandler>();

        private NotifyIcon _notifyIcon;

        private readonly string _appName = App.AppSettings.AppName;
        private readonly string _iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sms.ico");

        public void Initialize()
        {
            if (!File.Exists(_iconPath))
            {
                Log.Error($"托盘图标路径异常: {_iconPath}");
                throw new FileNotFoundException($"无法找到托盘图标 '{_iconPath}'.");
            }

            // 初始化托盘图标
            _notifyIcon = new NotifyIcon
            {
                Icon = new System.Drawing.Icon(_iconPath),
                Visible = true,
                Text = _appName
            };
            InitializeContextMenu();

            _webSocketHandler.OnMessageReceived += ShowToastNotification;
            ToastNotificationManagerCompat.OnActivated += OnToastActivated;
        }

        /** 初始化托盘菜单栏 */
        private void InitializeContextMenu()
        {
            const string testMessage = "尾号为 1234 的用户您好, 967431 是您的验证码, 请查收";

            var contextMenu = new ContextMenuStrip();

            var autoStartItem = new ToolStripMenuItem("开机自启动");

            autoStartItem.CheckOnClick = true;
            autoStartItem.Checked = CheckAutoStartEnabled(); // 默认勾选状态
            autoStartItem.CheckedChanged += (sender, args) =>
            {
                if (autoStartItem.Checked)
                {
                    EnableAutoStart();
                }
                else
                {
                    DisableAutoStart();
                }
            };
            contextMenu.Items.Add(autoStartItem);

            contextMenu.Items.Add(new ToolStripMenuItem("重置密钥",
                IconChar.Key.ToBitmap(IconFont.Solid, 16, Color.Black),
                (sender, args) => KeyHandler.DeleteRSAKeys()));

            contextMenu.Items.Add(new ToolStripMenuItem("检查更新",
                IconChar.Refresh.ToBitmap(IconFont.Solid, 16, Color.Black),
                (sender, args) => CheckUpdatesAsync()));

            contextMenu.Items.Add(new ToolStripMenuItem("问题反馈",
                IconChar.Question.ToBitmap(IconFont.Solid, 16, Color.Black),
                (sender, args) => FeedbackHandler.OpenFeedbackUrl()));

            contextMenu.Items.Add(new ToolStripMenuItem("显示短信效果",
                IconChar.Comment.ToBitmap(IconFont.Regular, 16, Color.Black),
                (sender, args) => ShowToastNotification(testMessage)));

            contextMenu.Items.Add(new ToolStripMenuItem("显示配对二维码",
                IconChar.Link.ToBitmap(IconFont.Solid, 16, Color.Black),
                (sender, args) => QRCodeHandler.ShowQRCode()));

            contextMenu.Items.Add(new ToolStripSeparator()); // 分隔符

            contextMenu.Items.Add(new ToolStripMenuItem("退出",
                IconChar.ArrowRightFromBracket.ToBitmap(IconFont.Auto, 16, Color.Black), (sender, args) =>
                {
                    Dispose();
                    Application.Current.Shutdown();
                }));

            _notifyIcon.ContextMenuStrip = contextMenu;
        }

        /// <summary>
        /// 通过注册表检查是否开机自启动是否生效
        /// </summary>
        /// <returns>false 代表注册表中找不到对应的键值 开机自启动未生效</returns>
        private bool CheckAutoStartEnabled()
        {
            try
            {
                using (var runs = Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run",
                           false))
                {
                    if (runs == null) return false;
                    return runs.GetValueNames().Any(strName =>
                        string.Equals(strName, _appName, StringComparison.CurrentCultureIgnoreCase));
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"检查开机自启时发生异常: {ex.Message}");
                Log.Error($"检查开机自启时发生异常: {ex.Message}");
                return false;
            }
        }

        /** 允许开机自启动 */
        private void EnableAutoStart()
        {
            try
            {
                var appPath = Process.GetCurrentProcess().MainModule?.FileName;
                if (appPath == null) return;
                appPath = $"\"{appPath}\""; // 给路径添加双引号
                SetAutoStart(true, appPath);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"允许开机自启时发生异常: {ex.Message}");
                Log.Error($"允许开机自启时发生异常: {ex.Message}");
            }
        }

        /** 禁止开机自启 */
        private void DisableAutoStart()
        {
            try
            {
                var appName = Process.GetCurrentProcess().MainModule?.ModuleName;
                if (appName != null) SetAutoStart(false, ""); // 关闭自启动
            }
            catch (Exception ex)
            {
                Console.WriteLine($"禁止开机自启时发生异常: {ex.Message}");
                Log.Error($"禁止开机自启时发生异常: {ex.Message}");
            }
        }

        /// <summary>
        /// 将应用程序设为或不设为开机自启动
        /// </summary>
        /// <param name="onOff">自启开关</param>
        /// <param name="appPath">应用程序完全路径</param>
        private void SetAutoStart(bool onOff, string appPath)
        {
            if (CheckAutoStartEnabled() != onOff)
            {
                SetRegKey(onOff, appPath);
            }
        }

        /// <summary>
        /// 写入或删除注册表键值对, 即设为开机启动或开机不启动
        /// </summary>
        /// <param name="isStart">是否开机启动</param>
        /// <param name="path">应用程序路径带程序名</param>
        /// <returns></returns>
        private void SetRegKey(bool isStart, string path)
        {
            try
            {
                using (var key =
                       Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run", true) ??
                       Registry.CurrentUser.CreateSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run"))
                {
                    if (isStart)
                    {
                        key?.SetValue(_appName, path + " --StartMinimized"); // 开机自启动时以最小化的方式启动
                    }
                    else
                    {
                        key?.DeleteValue(_appName, false);
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"操作注册表时发生异常: {ex.Message}");
                Log.Error($"操作注册表时发生异常: {ex.Message}");
            }
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
                    foreach (var value in extractedInfo)
                    {
                        // 限制按钮文本的长度
                        var buttonText = value.Length > 20 ? value.Substring(0, 17) + "..." : value;
                        toastBuilder.AddButton(new ToastButton()
                                .SetContent(buttonText) // 按钮显示的内容
                                .AddArgument("action", "copy")
                                .AddArgument("message", value)) // 点击按钮时传递的内容
                            .AddArgument("source", "WebSocketMessage"); // 给 Toast 弹窗添加来源标识
                    }
                }

                // 点击 Toast 弹窗本身可以复制整条短信的内容
                toastBuilder.AddArgument("action", "copy")
                    .AddArgument("message", message)
                    .AddArgument("source", "WebSocketMessage"); // 给 Toast 弹窗添加来源标识

                toastBuilder.Show();
            });
        }

        /** 提取短信中的关键信息 (验证码、识别码、电话号码等) */
        private static List<string> ExtractInfoFromMessage(string message)
        {
            try
            {
                // 读取配置文件中的短信关键字
                var keywordList = App.AppSettings.MessageKeyword;
                
                var keywords = keywordList?.ToHashSet() ?? new HashSet<string>();
                if (keywords.Count == 0)
                {
                    Log.Warn("未配置验证码短信的提取关键字");
                }
                else if (!keywords.Any(message.Contains))
                {
                    // 检查短信内容是否包含验证码关键词
                    return new List<string>();
                }
            }
            catch (Exception ex)
            {
                Log.Error($"Error processing keyword list: {ex.Message}");
                Console.WriteLine("处理");

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

                // 点击事件只针对特定标识的 Toast 弹窗生效
                if (!arguments.Contains("source") || arguments["source"] != "WebSocketMessage")
                    return;

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

        private static async void CheckUpdatesAsync()
        {
            try
            {
                await UpdateHandler.CheckUpdatesAsync();
            }
            catch (Exception ex)
            {
                Application.Current.Dispatcher.Invoke(() =>
                {
                    var toastBuilder = new ToastContentBuilder()
                        .AddText("检查更新失败，请稍后再试");
                    toastBuilder.Show();
                });
                Log.Error($"检查更新时发生异常: {ex.Message}");
                Console.WriteLine($"更新检查失败: {ex.Message}");
            }
        }

        /** 释放托盘图标资源 */
        public void Dispose()
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
        }
    }
}