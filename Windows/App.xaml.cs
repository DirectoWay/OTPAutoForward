using System;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Windows;
using System.Windows.Threading;
using Autofac;
using Microsoft.Extensions.Configuration;
using WinCAPTCHA.ServiceHandler;

namespace WinCAPTCHA
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

            var builder = new ContainerBuilder();
            ConfigureContainer(builder);
            _container = builder.Build();

            using (var scope = _container.BeginLifetimeScope())
            {
                var mainWindow = scope.Resolve<MainWindow>();
                _notifyIconHandler = scope.Resolve<NotifyIconHandler>();
                var webSocketHandler = scope.Resolve<WebSocketHandler>();

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
                Console.WriteLine($"端口检测异常：{ex.Message}");
                return false;
            }
        }
    }
}