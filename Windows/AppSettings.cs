namespace WinCAPTCHA;

public class AppSettings
{
    /** 程序名称 默认为 WinCAPTCHA */
    public string AppName { get; init; } = "WinCAPTCHA";

    /** 验证 WebSocket 请求头中时间戳的超时时间 默认为 600 秒 */
    public int WebSocketVerifyTimeout { get; init; } = 600;
}