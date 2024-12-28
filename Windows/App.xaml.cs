using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Security.Principal;
using System.Windows;
using Autofac;
using log4net;
using log4net.Config;
using Microsoft.Extensions.Configuration;
using OTPAutoForward.ServiceHandler;

namespace OTPAutoForward
{
    /// <summary>
    /// Interaction logic for App.xaml
    /// </summary>
    public partial class App
    {
        private static IConfiguration Configuration { get; }
        public static AppSettings AppSettings { get; private set; }

        private IContainer _container;

        /** 托盘图标与托盘菜单栏 */
        private NotifyIconHandler _notifyIconHandler;

        private static readonly ILog Log = LogManager.GetLogger(typeof(App));

        static App()
        {
            // 配置文件路径
            var builder = new ConfigurationBuilder()
                .SetBasePath(AppDomain.CurrentDomain.BaseDirectory) // 设置基础路径为应用程序目录
                .AddJsonFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "appsettings.json"), optional: false,
                    reloadOnChange: true);

            Configuration = builder.Build();
        }

        public App()
        {
            // 绑定配置文件对象
            var appSettingsSection = Configuration.GetSection("AppSettings");
            AppSettings = appSettingsSection.Get<AppSettings>();
        }

        /** 配置依赖注入容器 */
        private static void ConfigureContainer(ContainerBuilder builder)
        {
            builder.RegisterType<MainWindow>().SingleInstance();
            builder.RegisterType<NotifyIconHandler>().SingleInstance();
            builder.RegisterType<WebSocketHandler>().SingleInstance();
        }

        /** 重写后的程序启动方法 */
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            ConfigLog4Net();

            CheckAdministrator();

            OpenFirewallPort(AppSettings.WebSocketPort);

            // 配置依赖注入容器
            var builder = new ContainerBuilder();
            ConfigureContainer(builder);
            _container = builder.Build();

            using (var scope = _container.BeginLifetimeScope())
            {
                var mainWindow = scope.Resolve<MainWindow>();
                _notifyIconHandler = scope.Resolve<NotifyIconHandler>();
                var webSocketHandler = scope.Resolve<WebSocketHandler>();

                KeyHandler.SetNotifyIconHandler(_notifyIconHandler);


                if (!CheckWebSocketPort(AppSettings.WebSocketPort))
                {
                    MessageBox.Show($"启动失败，端口 {AppSettings.WebSocketPort} 已被占用！", "端口异常",
                        MessageBoxButton.OK, MessageBoxImage.Error);
                    Shutdown();
                    return;
                }

                webSocketHandler.StartWebSocketServer().ContinueWith(task =>
                {
                    if (!task.IsFaulted) return;
                    MessageBox.Show("启动失败，WebSocket 服务启动失败", "核心服务异常",
                        MessageBoxButton.OK, MessageBoxImage.Error);

                    _notifyIconHandler.Dispose();
                    Shutdown();
                });

                InitializeNotifyIcon(_notifyIconHandler);

                // 判断程序启动时是否包含最小化参数
                var isMinimized = e.Args.Contains("--StartMinimized");
                if (isMinimized)
                {
                    RunInMinimized();
                }
                else
                {
                    mainWindow.Show();
                }
            }
        }

        /** 配置 Log4Net */
        private static void ConfigLog4Net()
        {
            var logDirectory = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "logs");
            if (!Directory.Exists(logDirectory))
            {
                Directory.CreateDirectory(logDirectory);
            }

            var logFilePath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "logs\\AppLog.log");
            GlobalContext.Properties["LogFileName"] = logFilePath;
            XmlConfigurator.Configure(new FileInfo("Log4Net.config"));

            Log.Info("Log4Net 已被初始化 -- 这是一条测试日志...");

            var configFile = new FileInfo("Log4Net.config");
            if (configFile.Exists)
            {
                XmlConfigurator.Configure(configFile);
                Log.Info("Log4Net 配置文件已加载");
            }
            else
            {
                Console.WriteLine("未找到 Log4Net 配置文件");
            }

            Log.Info("当前日志文件路径为: " + Path.Combine(logDirectory, "AppLog.log"));
        }

        /** 判断当前运行环境是否有管理员权限 */
        private void CheckAdministrator()
        {
            var identity = WindowsIdentity.GetCurrent();
            var principal = new WindowsPrincipal(identity);
            var isAdministrator = principal.IsInRole(WindowsBuiltInRole.Administrator);
            if (isAdministrator) return;
            MessageBox.Show("该程序的运行需要开放防火墙端口, 使用 WebSocket 服务\n请您联系管理员获取权限后再进行重试", "缺少管理员权限", MessageBoxButton.OK,
                MessageBoxImage.Error);
            Log.Fatal("缺少管理员权限, 程序被迫终止");
            Shutdown();
        }

        /** 开放防火墙端口 */
        private void OpenFirewallPort(int port)
        {
            try
            {
                // 先检查防火墙规则是否已经存在
                var checkProcess = new Process
                {
                    StartInfo = new ProcessStartInfo
                    {
                        FileName = "netsh",
                        Arguments = $"advfirewall firewall show rule name=\"{AppSettings.AppName}\"",
                        RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false,
                        CreateNoWindow = true
                    }
                };
                checkProcess.Start();
                var checkOutput = checkProcess.StandardOutput.ReadToEnd();
                checkProcess.WaitForExit();
                if (checkOutput.Contains(port.ToString()))
                {
                    Log.Info("防火墙端口已开放");
                    return;
                }

                // 防火墙规则不存在, 新建一条规则
                var addProcess = new Process
                {
                    StartInfo = new ProcessStartInfo
                    {
                        FileName = "netsh",
                        Arguments =
                            $"advfirewall firewall add rule name=\"{AppSettings.AppName}\" dir=in action=allow protocol=TCP localport={port} profile=any",
                        RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false,
                        CreateNoWindow = true
                    }
                };
                addProcess.Start();
                var addError = addProcess.StandardError.ReadToEnd();
                addProcess.WaitForExit();

                // 处理结果
                if (addProcess.ExitCode == 0)
                {
                    MessageBox.Show($"端口 {port} 已成功开放。", "防火墙配置成功", MessageBoxButton.OK, MessageBoxImage.Information);
                    Log.Info("防火墙端口已开放");
                }
                else
                {
                    MessageBox.Show($"无法开放端口 {port}，错误信息: {addError}", "防火墙配置错误", MessageBoxButton.OK,
                        MessageBoxImage.Error);
                    Log.Fatal($"无法开放端口 {port}，错误信息: {addError}");
                    Shutdown();
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"无法开放端口 {port}，错误信息: {ex.Message}", "防火墙配置错误", MessageBoxButton.OK,
                    MessageBoxImage.Error);
                Log.Fatal($"无法开放端口 {port}，错误信息: {ex.Message}");
                Shutdown();
            }
        }

        /** 程序启动时初始化托盘图标 */
        private void InitializeNotifyIcon(NotifyIconHandler notifyIconHandler)
        {
            notifyIconHandler.Initialize(() =>
            {
                using (var scope = _container.BeginLifetimeScope())
                {
                    var mainWindow = scope.Resolve<MainWindow>();
                    mainWindow.Show();
                    mainWindow.WindowState = WindowState.Normal;
                    mainWindow.Activate();
                }
            }, () =>
            {
                notifyIconHandler.Dispose();
                Shutdown();
            });
        }

        /** 以最小化的方式启动程序 */
        private static void RunInMinimized()
        {
            Console.WriteLine("程序正在后台运行..");
        }

        ///<summary>
        /// 检查端口是否已经被占用
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
                Log.Error($"进行端口检测时发生异常：{ex.Message}");
                Console.WriteLine($"进行端口检测时发生异常：{ex.Message}");
                return false;
            }
        }
    }
}