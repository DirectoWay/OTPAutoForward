using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Forms;
using Windows.UI.Notifications;
using Microsoft.Win32;
using FontAwesome.Sharp;
using log4net;
using Microsoft.Toolkit.Uwp.Notifications;
using Application = System.Windows.Application;
using Clipboard = System.Windows.Forms.Clipboard;

namespace OTPAutoForward.ServiceHandler
{
    /** 用于管理软件的托盘图标与托盘菜单 */
    public class NotifyIconHandler
    {
        private static readonly ILog Log = LogManager.GetLogger(typeof(NotifyIconHandler));

        private readonly WebSocketHandler _webSocketHandler = App.Resolve<WebSocketHandler>();
        private readonly BluetoothHandler _bluetoothHandler = App.Resolve<BluetoothHandler>();
        private readonly ConnectInfoHandler _connectInfoHandler = new ConnectInfoHandler();
        private readonly QRCodeHandler _qrCodeHandler = new QRCodeHandler();

        private NotifyIcon _notifyIcon;

        private readonly string _appName = App.AppSettings.CurrentValue.AppName;
        private static readonly bool SilentMode = App.AppSettings.CurrentValue.SilentMode;
        private static readonly bool BluetoothMode = App.AppSettings.CurrentValue.BluetoothMode;
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
            _bluetoothHandler.OnMessageReceived += ShowToastNotification;
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

            var quietModeItem = new ToolStripMenuItem("全屏状态免打扰");
            quietModeItem.CheckOnClick = true;
            quietModeItem.Checked = SilentMode; // 默认勾选状态
            quietModeItem.CheckedChanged += (sender, args) =>
            {
                if (quietModeItem.Checked)
                {
                    EnableSilentMode();
                }
                else
                {
                    DisableSilentMode();
                }
            };
            contextMenu.Items.Add(quietModeItem);

            var bluetoothModeItem = new ToolStripMenuItem("优先使用蓝牙模式");
            bluetoothModeItem.CheckOnClick = false;
            bluetoothModeItem.Checked = BluetoothMode; // 默认勾选状态
            bluetoothModeItem.Click += async (sender, args) =>
            {
                var targetState = !bluetoothModeItem.Checked;
                var success = targetState
                    ? await EnableBluetoothMode()
                    : await DisableBluetoothMode();

                if (success)
                {
                    bluetoothModeItem.Checked = targetState;
                }
            };
            contextMenu.Items.Add(bluetoothModeItem);

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

            contextMenu.Items.Add(new ToolStripMenuItem("通过 IP 地址进行配对",
                IconChar.Hashtag.ToBitmap(IconFont.Solid, 16, Color.Black),
                (sender, args) => ShowIPNotification()));

            contextMenu.Items.Add(new ToolStripMenuItem("显示配对二维码",
                IconChar.Link.ToBitmap(IconFont.Solid, 16, Color.Black),
                (sender, args) => _qrCodeHandler.ShowQRCode()));

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

        private static void EnableSilentMode()
        {
            try
            {
                App.UpdateAppSettings(settings => { settings.SilentMode = true; });
            }
            catch (Exception ex)
            {
                Console.WriteLine($"开启全屏免打扰时发生异常: {ex.Message}");
                Log.Error($"开启全屏免打扰时发生异常: {ex.Message}");
            }
        }

        private static void DisableSilentMode()
        {
            try
            {
                App.UpdateAppSettings(settings => { settings.SilentMode = false; });
            }
            catch (Exception ex)
            {
                Console.WriteLine($"禁用全屏免打扰时发生异常: {ex.Message}");
                Log.Error($"禁用全屏免打扰时发生异常: {ex.Message}");
            }
        }

        /// <summary>
        /// 开启蓝牙优先模式
        /// </summary>
        /// <returns>true 表示成功开启</returns>
        private async Task<bool> EnableBluetoothMode()
        {
            var bluetoothRequest = await ShowBluetoothNotificationAsync("需 Android 端一并开启蓝牙优先模式才可生效");
            if (!bluetoothRequest) return false;

            var bluetoothServer = await _bluetoothHandler.StartBluetoothServer();
            if (bluetoothServer.Status)
            {
                App.UpdateAppSettings(settings => { settings.BluetoothMode = true; });
                return true;
            }

            System.Windows.MessageBox.Show($"蓝牙服务器启动失败: {bluetoothServer.Message}",
                "核心服务异常", MessageBoxButton.OK, MessageBoxImage.Error);
            return false;
        }

        private async Task<bool> DisableBluetoothMode()
        {
            // 直接关闭蓝牙服务
            await _bluetoothHandler.StopBluetoothServer();
            App.UpdateAppSettings(settings => { settings.BluetoothMode = false; });
            return true;
        }

        private static async Task<bool> ShowBluetoothNotificationAsync(string message)
        {
            var tcs = new TaskCompletionSource<bool>();
            Application.Current.Dispatcher.Invoke(() =>
            {
                ToastNotificationManagerCompat.History.Clear();

                var toastBuilder = new ToastContentBuilder()
                    .AddText(message)
                    .SetToastDuration(ToastDuration.Long)
                    .AddButton(new ToastButton()
                        .SetContent("确定")
                        .AddArgument("action", "bluetooth")
                        .AddArgument("source", "NotifyIconHandler")
                        .SetBackgroundActivation())
                    .AddButton(new ToastButton()
                        .SetContent("取消")
                        .AddArgument("action", "cancelBluetooth")
                        .AddArgument("source", "NotifyIconHandler")
                        .SetBackgroundActivation());

                ToastNotificationManagerCompat.OnActivated += (toastArgs) =>
                {
                    var args = ToastArguments.Parse(toastArgs.Argument);

                    if (args.TryGetValue("action", out var action) && action == "bluetooth")
                    {
                        tcs.TrySetResult(true);
                        return;
                    }

                    tcs.TrySetResult(false);
                };
                toastBuilder.Show();

                _ = Task.Delay(TimeSpan.FromSeconds(25)).ContinueWith(_ =>
                {
                    tcs.TrySetResult(false); // 超时未操作
                });
            });

            return await tcs.Task;
        }

        private static void ShowToastNotification(string message)
        {
            var isUserInFullScreen = FullScreenHandler.IsUserInFullScreen();
            var isSilentMode = App.AppSettings.CurrentValue.SilentMode;

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
                                .AddArgument("content", value)) // 点击按钮时传递的内容
                            .AddArgument("source", "WebSocketMessage"); // 给 Toast 弹窗添加来源标识
                    }
                }

                // 点击 Toast 弹窗本身可以复制整条短信的内容
                toastBuilder.AddArgument("action", "copy")
                    .AddArgument("content", message)
                    .AddArgument("source", "WebSocketMessage"); // 给 Toast 弹窗添加来源标识

                if (isSilentMode && isUserInFullScreen)
                {
                    var toastContent = toastBuilder.GetToastContent();

                    var toast = new ToastNotification(toastContent.GetXml())
                    {
                        SuppressPopup = true // 有全屏应用运行时, 静默 Toast 弹窗
                    };

                    ToastNotificationManagerCompat.CreateToastNotifier().Show(toast);
                    return;
                }

                toastBuilder.Show();
            });
        }

        /** 提取短信中的关键信息 (验证码、识别码、电话号码等) */
        private static List<string> ExtractInfoFromMessage(string message)
        {
            try
            {
                // 读取配置文件中的短信关键字
                var keywordList = App.AppSettings.CurrentValue.MessageKeyword;

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
                Log.Error($"处理短信关键字列表时发生异常: {ex.Message}");
                Console.WriteLine($"处理短信关键字列表时发生异常: {ex}");
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
                if (!arguments.Contains("source"))
                    return;

                if (arguments["action"] != "copy") return;

                var textToCopy = arguments["content"];
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

        private void ShowIPNotification()
        {
            var ip = _connectInfoHandler.GetLocalIP();
            var toastBuilder = new ToastContentBuilder();
            toastBuilder
                .AddText($"请在 App 输入 IP 地址: {ip}")
                .AddText("点击弹窗可复制 IP 地址")
                .SetToastDuration(ToastDuration.Long) // 设置为长时间显示(大概 30 秒)
                .AddArgument("action", "copy")
                .AddArgument("content", ip.ToString())
                .AddArgument("source", "PairByIP");
            toastBuilder.Show();
        }

        /** 释放托盘图标资源 */
        public void Dispose()
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
        }
    }
}