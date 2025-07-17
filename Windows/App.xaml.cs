using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Security.Principal;
using System.Threading.Tasks;
using System.Windows;
using Autofac;
using log4net;
using log4net.Config;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Primitives;
using Newtonsoft.Json;
using OTPAutoForward.ServiceHandler;

namespace OTPAutoForward
{
    /// <summary>
    /// Interaction logic for App.xaml
    /// </summary>
    public partial class App
    {
        private static IConfiguration Configuration { get; }
        public static OptionsMonitor<AppSettings> AppSettings { get; private set; }

        private static readonly string AppSettingsPath =
            Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "appsettings.json");

        private static IContainer _container;

        /** 托盘图标与托盘菜单栏 */
        private NotifyIconHandler _notifyIconHandler;

        private WebSocketHandler _webSocketHandler;

        private BluetoothHandler _bluetoothHandler;

        private static readonly ILog Log = LogManager.GetLogger(typeof(App));

        static App()
        {
            var builder = new ConfigurationBuilder()
                .SetBasePath(AppDomain.CurrentDomain.BaseDirectory)
                .AddJsonFile(AppSettingsPath, optional: false, reloadOnChange: true);

            Configuration = builder.Build();
        }

        public App()
        {
            // 监听配置文件对象
            AppSettings = new OptionsMonitor<AppSettings>(Configuration, "AppSettings");
        }

        /** 配置依赖注入容器 */
        private static void ConfigureContainer(ContainerBuilder builder)
        {
            builder.RegisterType<NotifyIconHandler>().SingleInstance();
            builder.RegisterType<WebSocketHandler>().SingleInstance();
            builder.RegisterType<BluetoothHandler>().SingleInstance();
        }

        /** 重写后的程序启动方法 */
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            ConfigLog4Net();

            CheckAdministrator();

            OpenFirewallPort(AppSettings.CurrentValue.WebSocketPort);

            // 配置依赖注入容器
            var builder = new ContainerBuilder();
            ConfigureContainer(builder);
            _container = builder.Build();

            using (var scope = _container.BeginLifetimeScope())
            {
                _notifyIconHandler = scope.Resolve<NotifyIconHandler>();
                _webSocketHandler = scope.Resolve<WebSocketHandler>();
                _bluetoothHandler = scope.Resolve<BluetoothHandler>();

                _notifyIconHandler.Initialize();
                KeyHandler.SetNotifyIconHandler(_notifyIconHandler);

                if (!CheckWebSocketPort(AppSettings.CurrentValue.WebSocketPort))
                {
                    MessageBox.Show($"启动失败，端口 {AppSettings.CurrentValue.WebSocketPort} 已被占用！", "端口异常",
                        MessageBoxButton.OK, MessageBoxImage.Error);
                    Shutdown();
                    return;
                }

                _webSocketHandler.StartWebSocketServer().ContinueWith(task =>
                {
                    if (!task.IsFaulted) return;
                    MessageBox.Show("启动失败，WebSocket 服务启动失败", "核心服务异常",
                        MessageBoxButton.OK, MessageBoxImage.Error);

                    _notifyIconHandler.Dispose();
                    Shutdown();
                }, TaskScheduler.FromCurrentSynchronizationContext());

                if (AppSettings.CurrentValue.BluetoothMode)
                {
                    _ = _bluetoothHandler.StartBluetoothServer();
                }
            }
        }

        /// <summary>
        /// 从依赖注入容器中获取指定类型的实例
        /// </summary>
        /// <typeparam name="T">已经进行依赖注入管理的类型</typeparam>
        /// <returns>指定类型的依赖注入实例</returns>
        public static T Resolve<T>()
        {
            try
            {
                return _container.Resolve<T>();
            }
            catch (Autofac.Core.Registration.ComponentNotRegisteredException ex)
            {
                throw new InvalidOperationException($"类型 {typeof(T).Name} 未在依赖注入容器中注册。", ex);
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
                        Arguments = $"advfirewall firewall show rule name=\"{AppSettings.CurrentValue.AppName}\"",
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
                            $"advfirewall firewall add rule name=\"{AppSettings.CurrentValue.AppName}\" dir=in action=allow protocol=TCP localport={port} profile=any",
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

        /// <summary>
        /// 更新配置文件
        /// </summary>
        /// <param name="updateAction"></param>
        public static void UpdateAppSettings(Action<AppSettings> updateAction)
        {
            var currentSettings = AppSettings.CurrentValue;
            updateAction(currentSettings);

            var configJson = File.ReadAllText(AppSettingsPath);
            var configDictionary = JsonConvert.DeserializeObject<Dictionary<string, object>>(configJson);

            configDictionary["AppSettings"] = currentSettings;

            var updatedJson = JsonConvert.SerializeObject(configDictionary, Formatting.Indented);
            File.WriteAllText(AppSettingsPath, updatedJson);
        }
    }

    /// <summary>
    /// 监听配置文件的修改
    /// </summary>
    /// <typeparam name="T"></typeparam>
    public class OptionsMonitor<T> where T : class, new()
    {
        public OptionsMonitor(IConfiguration configuration, string sectionName)
        {
            var configuration1 = configuration;
            var sectionName1 = sectionName;
            CurrentValue = configuration1.GetSection(sectionName1).Get<T>() ?? new T();

            ChangeToken.OnChange(
                () => configuration1.GetReloadToken(),
                () => CurrentValue = configuration1.GetSection(sectionName1).Get<T>() ?? new T()
            );
        }

        public T CurrentValue { get; private set; }
    }
}