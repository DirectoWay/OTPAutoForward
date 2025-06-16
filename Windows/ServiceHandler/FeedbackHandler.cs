using System;
using System.Diagnostics;
using log4net;

namespace OTPAutoForward.ServiceHandler
{
    public static class FeedbackHandler
    {
        private static readonly ILog Log = LogManager.GetLogger(typeof(FeedbackHandler));

        public static void OpenFeedbackUrl()
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = App.AppSettings.CurrentValue.FeedbackUrl, UseShellExecute = true
                });
            }
            catch (Exception ex)
            {
                Log.Error("访问反馈页面时发生异常: " + ex.Message);
                Console.WriteLine("访问反馈页面时发生异常: " + ex);
            }
        }
    }
}