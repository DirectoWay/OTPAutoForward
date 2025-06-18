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
        private const uint WsPopup = 0x80000000;

        /// <summary>
        /// 检测当前前台窗口是否处于全屏状态
        /// </summary>
        public static bool IsUserInFullScreen()
        {
            try
            {
                var hWnd = GetForegroundWindow();
                if (hWnd == IntPtr.Zero)
                    return false;

                var isCoveringScreen = IsWindowCoveringScreen(hWnd, tolerance: 5);

                var isBorderless = !HasWindowBorder(hWnd);
                var isPopupStyle = IsPopupWindow(hWnd);
                return isCoveringScreen && (isBorderless || isPopupStyle);
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// 窗口是否覆盖整个屏幕
        /// </summary>
        private static bool IsWindowCoveringScreen(IntPtr hWnd, int tolerance)
        {
            GetWindowRect(hWnd, out var windowRect);
            var screen = Screen.FromHandle(hWnd);
            var screenBounds = screen.Bounds;

            return windowRect.Left <= screenBounds.Left + tolerance &&
                   windowRect.Top <= screenBounds.Top + tolerance &&
                   windowRect.Right >= screenBounds.Right - tolerance &&
                   windowRect.Bottom >= screenBounds.Bottom - tolerance;
        }

        /// <summary>
        /// 窗口是否有边框
        /// </summary>
        private static bool HasWindowBorder(IntPtr hWnd)
        {
            var style = GetWindowLong(hWnd, GwlStyle);
            return (style & WsBorder) != 0;
        }

        /// <summary>
        /// POPUP 样式
        /// </summary>
        private static bool IsPopupWindow(IntPtr hWnd)
        {
            var style = GetWindowLong(hWnd, GwlStyle);
            return (style & WsPopup) != 0;
        }
    }
}