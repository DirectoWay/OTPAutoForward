using System;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Windows;
using System.Windows.Threading;
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
        private static IConfiguration Configuration { get; set; }
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

                    var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
                    timer.Tick += (sender, args) =>
                    {
                        timer.Stop();
                        Shutdown();
                    };
                    timer.Start();
                    return;
                }

                webSocketHandler.StartWebSocketServer().ContinueWith(task =>
                {
                    if (!task.IsFaulted) return;
                    MessageBox.Show("启动失败，WebSocket 服务启动失败", "核心服务异常",
                        MessageBoxButton.OK, MessageBoxImage.Error);

                    var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
                    timer.Tick += (sender, args) =>
                    {
                        timer.Stop();
                        _notifyIconHandler.Dispose();
                        Shutdown();
                    };
                    timer.Start();
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
        private void RunInMinimized()
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