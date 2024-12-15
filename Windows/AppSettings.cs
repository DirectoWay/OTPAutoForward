using System.Collections.Generic;

namespace WinCAPTCHA;

public class AppSettings
{
    /** 程序名称 默认为 WinCAPTCHA */
    public string AppName { get; init; } = "WinCAPTCHA";

    /** WebSocket 运行时的端口号 默认为 9224 端口 */
    public int WebSocketPort { get; init; } = 9224;

    /** 验证 WebSocket 请求头中时间戳的超时时间 默认为 600 秒 */
    public int WebSocketVerifyTimeout { get; init; } = 600;

    /** 在 appsettings.json中没有配置用户反馈地址的时候 默认返回一个不为空的地址 */
    private readonly string _feedbackUrl = "https://gitee.com/dashboard";

    /** 用户反馈的 Url 地址 */
    public string FeedbackUrl
    {
        get => string.IsNullOrWhiteSpace(_feedbackUrl) ? "https://gitee.com/dashboard" : _feedbackUrl;
        init => _feedbackUrl = value;
    }

    /** 短信识别关键字 只有包含该关键字的短信才会被识别为验证码类型的短信 */
    public List<string>? MessageKeyword { get; init; }
}