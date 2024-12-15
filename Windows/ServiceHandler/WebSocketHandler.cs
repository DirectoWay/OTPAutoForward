using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace WinCAPTCHA.ServiceHandler;

/* 用于启动 WebSocket 服务器 */
public class WebSocketHandler
{
    private HttpListener? _httpListener;

    /** WebSocket 服务的 IP 地址 */
    private readonly IPAddress _ipAddress = ConnectInfoHandler.GetLocalIP();

    /** WebSocket 服务的端口号 */
    private readonly int _port = App.AppSettings.WebSocketPort;

    /** WebSocket 请求头自定义字段 */
    private const string WebSocketHeaderField = "X-WinCAPTCHA-Auth";

    /** WebSocket 请求头中必须包含的密钥 */
    private const string WebSocketHeaderKey = "autoCAPTCHA-encryptedKey";

    /** 控制 WebSocket 服务启动和停止时的并发访问 */
    private readonly SemaphoreSlim _startStopSemaphore = new(1, 1);

    /** 存储从 App 端接收到的消息 */
    private static readonly ConcurrentQueue<string> ReceivedMessages = new();

    /** 订阅消息事件 */
    public event Action<string>? OnMessageReceived;


    /** WebSocket 服务监听与保活 */
    private async Task MonitorWebSocketServer()
    {
        while (true)
        {
            if (_httpListener is not { IsListening: true })
            {
                Console.WriteLine("WebSocket 服务停止，尝试重新启动...");
                await StartWebSocketServer();
            }

            await Task.Delay(3600000); // 检查间隔
        }
    }

    public async Task StartWebSocketServer()
    {
        if (_httpListener != null)
        {
            Console.WriteLine("WebSocket 服务器已在运行");
            return;
        }

        await _startStopSemaphore.WaitAsync();
        try
        {
            if (_httpListener == null) // 防止并发初始化
            {
                _httpListener = new HttpListener();
                _httpListener.Prefixes.Add($"http://{_ipAddress}:{_port}/");

                _httpListener.Start();
                Console.WriteLine($"WebSocket 服务器已启动 - IP地址: {_ipAddress},监听端口: {_port}");

                _ = MonitorWebSocketServer();
                _ = AcceptWebSocketClientsAsync();
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"启动 WebSocket 服务器时发生错误: {ex.Message}");
            throw;
        }
        finally
        {
            _startStopSemaphore.Release();
        }
    }

    /** 持续监听并接受来自客户端的 WebSocket 连接请求 */
    private async Task AcceptWebSocketClientsAsync()
    {
        while (_httpListener?.IsListening == true)
        {
            var context = await _httpListener.GetContextAsync();
            if (context.Request.IsWebSocketRequest)
            {
                // 验证请求头中的认证信息
                if (VerifyWebSocketHeader(context.Request.Headers))
                {
                    var webSocketContext = await context.AcceptWebSocketAsync(null);
                    await HandleWebSocketConnectionAsync(webSocketContext.WebSocket);
                }
                else
                {
                    Console.WriteLine(DateTime.Now + " - 收到非法的 WebSocket 请求");
                    context.Response.StatusCode = 401; // 未授权
                    context.Response.Close();
                }
            }
            else
            {
                // 回应客户端的 Http 请求
                if (context.Request.Url?.AbsolutePath == "/ping")
                {
                    context.Response.StatusCode = 200;
                    await using (var writer = new StreamWriter(context.Response.OutputStream))
                    {
                        await writer.WriteAsync("Pong");
                    }

                    context.Response.Close();
                }
                else
                {
                    context.Response.StatusCode = 400;
                    context.Response.Close();
                }
            }
        }
    }

    /// <summary>
    /// 建立 WebSocket 连接时验证客户端的请求头 请求头中必须包含关键密钥与时间戳
    /// </summary>
    /// <param name="headers">键值对形式的请求头参数 如: X-WinCAPTCHA-Auth: encryptedToken</param>
    /// <returns>true 代表请求头验证通过 允许建立 WebSocket 连接</returns>
    private static bool VerifyWebSocketHeader(System.Collections.Specialized.NameValueCollection headers)
    {
        // 先检查请求头中是否包含自定义字段
        var encryptedAuth = headers[WebSocketHeaderField];
        if (string.IsNullOrEmpty(encryptedAuth))
        {
            Console.WriteLine("WebSocket 请求头中缺少验证字段");
            return false;
        }

        try
        {
            // 解密请求头中的认证信息
            var decryptedAuth = KeyHandler.DecryptString(encryptedAuth);
            var parts = decryptedAuth.Split('+');
            if (parts.Length != 2 || parts[0] != WebSocketHeaderKey)
            {
                Console.WriteLine($"WebSocket 请求头内容错误: {parts}");
                return false;
            }

            // 验证时间戳 超时的 WebSocket 请求为无效请求
            if (!long.TryParse(parts[1], out var clientTimestamp))
            {
                Console.WriteLine($"WebSocket 请求头包含无效时间戳: {clientTimestamp}");
                return false;
            }

            var serverTimestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            var differenceInSeconds = Math.Abs(serverTimestamp - clientTimestamp); // 检查时间戳是否在超时时间以内
            if (differenceInSeconds <= App.AppSettings.WebSocketVerifyTimeout)
            {
                return true;
            }

            Console.WriteLine("WebSocket 请求已过期");
            return false;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"WebSocket 请求头验证异常: {ex.Message}");
            return false;
        }
    }

    /** 处理客户端的 WebSocket 连接请求 */
    private async Task HandleWebSocketConnectionAsync(WebSocket webSocket)
    {
        var buffer = new byte[1024];
        try
        {
            // 往 App 端发送 Win 端的身份验证信息
            var verifyInfo = GenerateWebsocketVerifyInfo();
            await webSocket.SendAsync(new ArraySegment<byte>(verifyInfo), WebSocketMessageType.Text, true,
                CancellationToken.None);

            while (webSocket.State == WebSocketState.Open)
            {
                var result = await webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);
                if (result.MessageType != WebSocketMessageType.Text)
                {
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        await webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing",
                            CancellationToken.None);
                    }
                }
                else
                {
                    var message = Encoding.UTF8.GetString(buffer, 0, result.Count);
                    message = KeyHandler.DecryptString(message); // 解密 App 端发来的消息
                    Console.WriteLine(DateTime.Now + message);
                    if (string.IsNullOrWhiteSpace(message))
                    {
                        Console.WriteLine("收到空消息，忽略处理");
                        continue;
                    }

                    ReceivedMessages.Enqueue(message);

                    try
                    {
                        OnMessageReceived?.Invoke(message);
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"消息处理订阅触发异常: {ex.Message}");
                    }

                    // 发送确认消息给 App 端
                    var responseMessage = $"已收到消息: {message}";
                    var responseBuffer = Encoding.UTF8.GetBytes(responseMessage);
                    await webSocket.SendAsync(new ArraySegment<byte>(responseBuffer), WebSocketMessageType.Text, true,
                        CancellationToken.None);
                    Console.WriteLine($"{DateTime.Now}已发送确认消息 ");
                }
            }
        }
        catch (WebSocketException ex)
        {
            Console.WriteLine($"WebSocket 异常: {ex.Message}");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"WebSocket 异常: {ex.Message}");
        }
        finally
        {
            if (webSocket.State != WebSocketState.Closed && webSocket.State != WebSocketState.Aborted)
            {
                await webSocket.CloseAsync(WebSocketCloseStatus.InternalServerError,
                    "WebSocket 连接异常中断", CancellationToken.None);
            }

            webSocket.Dispose();
            Console.WriteLine("WebSocket 连接已关闭");
        }
    }

    /** 生成 Win 端的身份验证信息, 可让 App 端验证 */
    private static byte[] GenerateWebsocketVerifyInfo()
    {
        // 获取设备 ID 并加密
        var deviceId = ConnectInfoHandler.GetDeviceID();
        var encryptedText = KeyHandler.EncryptString(ConnectInfoHandler.GetDeviceID());
        var signature = KeyHandler.SignData(deviceId);

        return Encoding.UTF8.GetBytes("verification" + "." + encryptedText + "." + signature);
    }

    public async Task StopWebSocketServer()
    {
        if (_httpListener == null)
        {
            Console.WriteLine("WebSocket 服务器未运行");
            return;
        }

        await _startStopSemaphore.WaitAsync();
        try
        {
            if (_httpListener != null) // 防止并发释放
            {
                _httpListener.Stop();
                _httpListener = null;
                Console.WriteLine("WebSocket 服务器已停止");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"停止 WebSocket 服务器时发生错误: {ex.Message}");
        }
        finally
        {
            _startStopSemaphore.Release();
        }
    }
}