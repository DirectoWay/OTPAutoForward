using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace OTPAutoForward.ServiceHandler
{
    public static class FullScreenHandler
    {
        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern bool GetWindowRect(IntPtr hWnd, out Rect lpRect);

        [DllImport("user32.dll")]
        private static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

        [StructLayout(LayoutKind.Sequential)]
        private struct Rect
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;
        }

        private const int GwlStyle = -16;
        private const int WsBorder = 0x00800000;

        public static bool IsUserInFullScreen()
        {
            try
            {
                var isWindowCoveringScreen = IsForegroundWindowCoveringScreen();
                var isTaskbarVisible = IsTaskbarVisible();
                var hasNoBorder = DoesForegroundWindowHaveBorder();
                return (isWindowCoveringScreen && !isTaskbarVisible) ||
                       (hasNoBorder && isWindowCoveringScreen);
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// 前台窗口是否覆盖整个屏幕
        /// </summary>
        private static bool IsForegroundWindowCoveringScreen()
        {
            var hWnd = GetForegroundWindow();
            if (hWnd == IntPtr.Zero) return false;

            GetWindowRect(hWnd, out var rect);
            var screen = Screen.FromHandle(hWnd);
            var screenBounds = screen.Bounds;
            const int tolerance = 1; // 1 像素的误差值

            return rect.Left <= screenBounds.Left + tolerance &&
                   rect.Top <= screenBounds.Top + tolerance &&
                   rect.Right >= screenBounds.Right - tolerance &&
                   rect.Bottom >= screenBounds.Bottom - tolerance;
        }

        /// <summary>
        /// 任务栏是否可见, 任务栏不可见大概率使用全屏应用, 全屏播放视频不算在内
        /// </summary>
        private static bool IsTaskbarVisible()
        {
            var hTaskbar = FindWindow("Shell_TrayWnd", null);
            if (hTaskbar == IntPtr.Zero) return false;

            var isVisible = IsWindowVisible(hTaskbar);

            GetWindowRect(hTaskbar, out var rect);
            var hasSize = (rect.Right - rect.Left) > 0 && (rect.Bottom - rect.Top) > 0;

            return isVisible && hasSize;
        }

        /// <summary>
        /// 前台窗口是否有边框, 针对无边框模式玩游戏
        /// </summary>
        private static bool DoesForegroundWindowHaveBorder()
        {
            var hWnd = GetForegroundWindow();
            if (hWnd == IntPtr.Zero) return false;

            var style = GetWindowLong(hWnd, GwlStyle);
            return (style & WsBorder) != 0; // 有边框返回true
        }
    }
}