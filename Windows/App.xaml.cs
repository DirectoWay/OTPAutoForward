using System;
using System.IO;
using System.Linq;
using System.Windows;
using Autofac;
using Microsoft.Extensions.Configuration;
using WinCAPTCHA.ServiceHandler;

namespace WinCAPTCHA;

/// <summary>
/// Interaction logic for App.xaml
/// </summary>
public partial class App
{
    private static IConfiguration Configuration { get; set; }
    public static AppSettings AppSettings { get; private set; } = default!;

    private IContainer _container = default!;

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
        AppSettings = Configuration.GetSection("AppSettings").Get<AppSettings>() ?? new AppSettings();
    }

    /** 重写后的程序启动方法 */
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        var builder = new ContainerBuilder();
        ConfigureContainer(builder);
        _container = builder.Build();

        using var scope = _container.BeginLifetimeScope();
        var mainWindow = scope.Resolve<MainWindow>();
        var notifyIconHandler = scope.Resolve<NotifyIconHandler>();
        InitializeNotifyIcon(notifyIconHandler);

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

    private static void ConfigureContainer(ContainerBuilder builder)
    {
        builder.RegisterType<MainWindow>().SingleInstance();
        builder.RegisterType<NotifyIconHandler>().SingleInstance();
    }

    /** 程序启动时初始化托盘图标 */
    private void InitializeNotifyIcon(NotifyIconHandler notifyIconHandler)
    {
        notifyIconHandler.Initialize(() =>
        {
            using var scope = _container.BeginLifetimeScope();
            var mainWindow = scope.Resolve<MainWindow>();
            mainWindow.Show();
            mainWindow.WindowState = WindowState.Normal;
            mainWindow.Activate();
        }, () =>
        {
            notifyIconHandler.Dispose();
            Shutdown();
        });
    }

    /** 以最小化的方式启动程序 */
    private void RunInMinimized()
    {
        Console.WriteLine("WinCAPTCHA 正在后台运行..");
    }
}