using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace WinCAPTCHA.ServiceHandler
{
    /** 用于测试和监控剪贴板 */
    public class ClipboardHandler
    {
        [DllImport("user32.dll")]
        public static extern IntPtr CloseClipboard();

        [DllImport("user32.dll", SetLastError = true)]
        static extern int GetWindowThreadProcessId(IntPtr hWnd, out int lpdwProcessId);

        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr GetOpenClipboardWindow();

        [DllImport("user32.dll", SetLastError = true)]
        static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        public static Process ProcessHoldingClipboard()
        {
            Process theProc = null;
            IntPtr hwnd = GetOpenClipboardWindow();
            if (hwnd != IntPtr.Zero)
            {
                uint processId;
                GetWindowThreadProcessId(hwnd, out processId);
                Process[] procs = Process.GetProcesses();
                foreach (Process proc in procs)
                {
                    if (proc.Id == processId)
                    {
                        theProc = proc;
                        break;
                    }
                }
            }

            return theProc;
        }
    }
}