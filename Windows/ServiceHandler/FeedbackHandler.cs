using System;
using System.Diagnostics;

namespace WinCAPTCHA.ServiceHandler
{
    public static class FeedbackHandler
    {
        public static void OpenFeedbackUrl()
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = App.AppSettings.FeedbackUrl, UseShellExecute = true
                });
            }
            catch (Exception ex)
            {
                Console.WriteLine($"URL 处理异常: {ex.Message}");
            }
        }
    }
}