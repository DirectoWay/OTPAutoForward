using System.Collections.Generic;

namespace OTPAutoForward
{
    public class AppSettings
    {
        /** 程序名称 */
        public string AppName { get; set; } = "OTPAutoForward";

        /** 当前版本号 */
        public string CurrentVersion { get; set; }

        /** WebSocket 运行时的端口号 默认为 9224 端口 */
        public int WebSocketPort { get; set; } = 9224;

        /** 验证 WebSocket 请求头中时间戳的超时时间 默认为 600 秒 */
        public int WebSocketVerifyTimeout { get; set; } = 600;

        /** 在 appsettings.json中没有配置用户反馈地址的时候 默认返回一个不为空的地址 */
        private string _feedbackUrl = "https://gitee.com/dashboard";

        /** 用户反馈的 Url 地址 */
        public string FeedbackUrl
        {
            get => string.IsNullOrWhiteSpace(_feedbackUrl) ? "https://gitee.com/dashboard" : _feedbackUrl;
            set => _feedbackUrl = value;
        }

        /** 远程仓库的项目作者 */
        public string RepositoryOwner { get; set; }

        /* 远程仓库中的项目名称 */
        public string Repository { get; set; }

        /** 项目的发布仓库源 (仅支持 GitHub 和 Gitee ) */
        public string ReleasesSource { get; set; }

        /** 短信识别关键字 只有包含该关键字的短信才会被识别为验证码类型的短信 */
        public List<string> MessageKeyword { get; set; }
    }
}