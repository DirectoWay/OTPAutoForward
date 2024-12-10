using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows.Forms;
using Microsoft.Win32;

namespace WinCAPTCHA.ServiceHandler;

/** 用于管理软件的托盘图标与托盘菜单 */
public class NotifyIconHandler
{
    private NotifyIcon _notifyIcon = default!;
    private Action _onRestoreWindow = default!;
    private Action _onExitApplication = default!;
    private readonly string _appName = App.AppSettings.AppName;

    public void Initialize(Action onRestoreWindow, Action onExitApplication)
    {
        _onRestoreWindow = onRestoreWindow;
        _onExitApplication = onExitApplication;
        var iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sms.ico");
        if (!File.Exists(iconPath))
        {
            throw new FileNotFoundException($"无法找到托盘图标 '{iconPath}'.");
        }

        // 初始化托盘图标
        _notifyIcon = new NotifyIcon
        {
            Icon = new Icon(iconPath),
            Visible = true,
            Text = _appName
        };
        InitializeContextMenu();
        _notifyIcon.DoubleClick += (_, _) => _onRestoreWindow();
    }

    /** 初始化托盘菜单栏 */
    private void InitializeContextMenu()
    {
        const string testMessage = "尾号为 1234 的用户您好, 967431 是您的验证码, 请查收";

        var contextMenu = new ContextMenuStrip();

        var autoStartItem = new ToolStripMenuItem("开机自启动");
        autoStartItem.CheckOnClick = true;
        autoStartItem.Checked = CheckAutoStartEnabled(); // 默认勾选状态
        autoStartItem.CheckedChanged += (_, _) =>
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

        contextMenu.Items.Add("显示短信效果", null, (_, _) => MainWindow.ShowToastNotification(testMessage));
        contextMenu.Items.Add("显示主界面", null, (_, _) => _onRestoreWindow());

        contextMenu.Items.Add(new ToolStripSeparator()); // 分隔符

        contextMenu.Items.Add("退出", null, (_, _) =>
        {
            _notifyIcon.Visible = false; // 移除托盘图标
            _onExitApplication();
        });

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
            using var runs =
                Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run", false);
            if (runs == null) return false;
            return runs.GetValueNames().Any(strName =>
                string.Equals(strName, _appName, StringComparison.CurrentCultureIgnoreCase));
        }
        catch (Exception ex)
        {
            Console.WriteLine($"检查开机自启时发生异常: {ex.Message}");
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
            using var key =
                Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run", true) ??
                Registry.CurrentUser.CreateSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run");
            if (isStart)
            {
                key.SetValue(_appName, path + " --StartMinimized"); // 开机自启动时以最小化的方式启动
            }
            else
            {
                key.DeleteValue(_appName, false);
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"操作注册表时发生异常: {ex.Message}");
        }
    }

    public void Dispose()
    {
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose(); // 释放托盘图标资源 
    }
}