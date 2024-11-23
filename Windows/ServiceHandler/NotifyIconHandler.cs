using System;
using System.Drawing;
using System.Windows.Forms;

namespace WinCAPTCHA.ServiceHandler;

/** 用于管理软件的托盘图标与托盘菜单 */
public class NotifyIconHandler
{
    private readonly NotifyIcon _notifyIcon;
    private readonly Action _onRestoreWindow;
    private readonly Action _onExitApplication;

    public NotifyIconHandler(Action onRestoreWindow, Action onExitApplication)
    {
        _onRestoreWindow = onRestoreWindow;
        _onExitApplication = onExitApplication;

        // 初始化托盘图标基本信息
        _notifyIcon = new NotifyIcon
        {
            Icon = new Icon("sms.ico"),
            Visible = true,
            Text = "WinCAPTCHA"
        };

        InitializeContextMenu();
        _notifyIcon.DoubleClick += (_, _) => _onRestoreWindow();
    }

    /** 初始化托盘菜单栏 */
    private void InitializeContextMenu()
    {
        var contextMenu = new ContextMenuStrip();
        contextMenu.Items.Add("打开主窗口", null, (_, _) => _onRestoreWindow());
        contextMenu.Items.Add("退出", null, (_, _) => _onExitApplication());
        _notifyIcon.ContextMenuStrip = contextMenu;
    }

    public void Dispose()
    {
        _notifyIcon.Dispose();
    }
    
}