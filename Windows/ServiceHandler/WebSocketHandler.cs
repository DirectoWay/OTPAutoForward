using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using log4net;
using Microsoft.Toolkit.Uwp.Notifications;

namespace OTPAutoForward.ServiceHandler
{
    /* 用于启动 WebSocket 服务器 */
    public class WebSocketHandler
    {
        private HttpListener _httpListener;

        private static readonly ILog Log = LogManager.GetLogger(typeof(WebSocketHandler));

        /** WebSocket 服务的 IP 地址 */
        private readonly IPAddress _ipAddress = ConnectInfoHandler.GetLocalIP();

        /** WebSocket 服务的端口号 */
        private readonly int _port = App.AppSettings.WebSocketPort;

        /** WebSocket 请求头自定义字段 */
        private const string WebSocketHeaderField = "X-OTPAutoForward-Auth";

        /** WebSocket 请求头中必须包含的密钥 */
        private const string WebSocketHeaderKey = "OTPAForward-encryptedKey";

        /** 用于验证 WebSocket 身份的字段名称 */
        private const string ValidationField = "verification";

        private const string PairingField = "pairByIp";

        private const string PairInfoField = "pairInfo";

        /** WebSocket 消息的确认字段 */
        private const string ConfirmedField = "confirmed";

        /** 控制 WebSocket 服务启动和停止时的并发访问 */
        private readonly SemaphoreSlim _startStopSemaphore = new SemaphoreSlim(1, 1);

        /** 存储从 App 端接收到的消息 */
        private static readonly ConcurrentQueue<string> ReceivedMessages = new ConcurrentQueue<string>();

        /** 订阅消息事件 */
        public event Action<string> OnMessageReceived;


        /** WebSocket 服务监听与保活 */
        private async Task MonitorWebSocketServer()
        {
            while (true)
            {
                if (_httpListener == null || !_httpListener.IsListening)
                {
                    Log.Warn("\"WebSocket 服务停止，尝试重新启动...");
                    Console.WriteLine("WebSocket 服务停止，尝试重新启动...");
                    await StartWebSocketServer();
                }

                await Task.Delay(3600000); // 检查间隔
            }
        }

        public async Task StartWebSocketServer()
        {
            await _startStopSemaphore.WaitAsync();
            try
            {
                if (_httpListener != null) // 防止并发初始化
                {
                    Log.Info("WebSocket 服务器已在运行");
                    Console.WriteLine("WebSocket 服务器已在运行");
                    return;
                }

                _httpListener = new HttpListener();
                _httpListener.Prefixes.Add($"http://{_ipAddress}:{_port}/");

                _httpListener.Start();
                Log.Info($"WebSocket 服务器已启动 - IP地址: {_ipAddress},监听端口: {_port}");
                Console.WriteLine($"WebSocket 服务器已启动 - IP地址: {_ipAddress},监听端口: {_port}");

                _ = MonitorWebSocketServer();
                _ = AcceptWebSocketClientsAsync();
            }
            catch (Exception ex)
            {
                Log.Fatal($"启动 WebSocket 服务器时发生错误: {ex.Message}");
                Console.WriteLine($"启动 WebSocket 服务器时发生错误: {ex}");
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
                Console.WriteLine("收到请求, 请求地址: " + context.Request.Url);

                // 回应客户端的 Ping 请求
                if (context.Request.Url?.AbsolutePath == "/ping")
                {
                    context.Response.StatusCode = 200;
                    using (var writer = new StreamWriter(context.Response.OutputStream))
                    {
                        await writer.WriteAsync("Pong");
                    }

                    context.Response.Close();
                    continue;
                }

                if (context.Request.Url?.AbsolutePath.Contains("/pair") == true)
                {
                    // 验证请求头中的认证信息
                    if (VerifyWebSocketHeader(context.Request.Headers))
                    {
                        var webSocketContext = await context.AcceptWebSocketAsync(null);
                        await PairByIpAddressAsync(webSocketContext.WebSocket);
                        continue;
                    }

                    InvalidRequest(context);
                    continue;
                }

                if (context.Request.IsWebSocketRequest)
                {
                    // 验证请求头中的认证信息
                    if (VerifyWebSocketHeader(context.Request.Headers))
                    {
                        var webSocketContext = await context.AcceptWebSocketAsync(null);
                        await HandleSmsAsync(webSocketContext.WebSocket);
                        continue;
                    }

                    InvalidRequest(context);
                    continue;
                }

                Log.Warn($"收到未处理路径的请求: {context.Request.Url?.AbsolutePath}");
                Console.WriteLine(DateTime.Now + $" - 收到未处理路径的请求: {context.Request.Url?.AbsolutePath}");

                context.Response.StatusCode = 400;
                context.Response.Close();
            }
        }

        private static void InvalidRequest(HttpListenerContext context)
        {
            Log.Warn("收到非法的 WebSocket 请求");
            Console.WriteLine(DateTime.Now + " - 收到非法的 WebSocket 请求");
            context.Response.StatusCode = 401; // 未授权
            context.Response.Close();
        }

        /// <summary>
        /// 建立 WebSocket 连接时验证客户端的请求头 请求头中必须包含关键密钥与时间戳
        /// </summary>
        /// <param name="headers">键值对形式的请求头参数 如: X-OTPAutoForward-Auth: encryptedToken</param>
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
                Console.WriteLine($"WebSocket 请求头验证异常: {ex}");
                return false;
            }
        }

        /** 处理 App 端转发过来的短信 */
        private async Task HandleSmsAsync(WebSocket webSocket)
        {
            var buffer = new byte[1024];
            try
            {
                var verifyInfo = GenerateWebsocketVerifyInfo();
                await SendWebSocketMessage(webSocket, verifyInfo);

                while (webSocket.State == WebSocketState.Open)
                {
                    var message = await ReceiveWebSocketMessage(webSocket, buffer);
                    if (message == null) continue;

                    ReceivedMessages.Enqueue(message);
                    OnMessageReceived?.Invoke(message);

                    var responseMessage = $"{ConfirmedField}.已收到消息: {message}";
                    await SendWebSocketMessage(webSocket, responseMessage);
                    Console.WriteLine("已发送确认消息");
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"WebSocket 异常: {ex}");
                Log.Error($"WebSocket 异常: {ex.Message}");
            }
            finally
            {
                await CloseWebSocket(webSocket);
            }
        }

        /** 通过 IP 地址进行配对 */
        private static async Task PairByIpAddressAsync(WebSocket webSocket)
        {
            var buffer = new byte[1024];
            try
            {
                var verifyInfo = GeneratePairVerifyInfo();
                await SendWebSocketMessage(webSocket, verifyInfo);

                while (webSocket.State == WebSocketState.Open)
                {
                    var message = await ReceiveWebSocketMessage(webSocket, buffer);
                    if (message == null) continue;

                    var pairRequest = await ShowPairRequestAsync(message);
                    if (pairRequest)
                    {
                        var pairInfo = GenerateFullPairInfo();
                        await SendWebSocketMessage(webSocket, pairInfo);
                    }

                    var responseMessage = $"{ConfirmedField}.配对结束";
                    await SendWebSocketMessage(webSocket, responseMessage);
                    Console.WriteLine("已发送确认消息");
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"WebSocket 异常: {ex}");
                Log.Error($"WebSocket 异常: {ex.Message}");
            }
            finally
            {
                await CloseWebSocket(webSocket);
            }
        }

        private static async Task<string> ReceiveWebSocketMessage(WebSocket webSocket,
            byte[] buffer)
        {
            var result = await webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);

            if (result.MessageType == WebSocketMessageType.Close)
            {
                Log.Info("App 端主动关闭了 WebSocket 连接");
                await webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", CancellationToken.None);
                return null;
            }

            if (result.MessageType != WebSocketMessageType.Text)
            {
                Console.WriteLine("收到了不符合规范的 WebSocket 报文");
                Log.Warn("收到了不符合规范的 WebSocket 报文");
                return null;
            }

            var message = Encoding.UTF8.GetString(buffer, 0, result.Count);

            if (string.IsNullOrWhiteSpace(message))
            {
                Log.Warn("收到 App 端的 WebSocket 空消息");
                Console.WriteLine("收到空消息，忽略处理");
                return null;
            }

            message = KeyHandler.DecryptString(message);
            Console.WriteLine($"{DateTime.Now} 已收到来自 App 端的消息:{message}");
            Log.Info($"已收到来自 App 端的消息:{message}");

            return message;
        }

        private static async Task SendWebSocketMessage<T>(WebSocket webSocket, T message)
        {
            byte[] messageBytes;

            if (message is byte[] byteArray)
            {
                messageBytes = byteArray;
            }
            else if (message is string str)
            {
                messageBytes = Encoding.UTF8.GetBytes(str);
            }
            else
            {
                throw new ArgumentException("无法发送不支持的 WebSocket 消息格式");
            }

            await webSocket.SendAsync(new ArraySegment<byte>(messageBytes), WebSocketMessageType.Text, true,
                CancellationToken.None);
        }

        private static async Task CloseWebSocket(WebSocket webSocket)
        {
            if (webSocket.State != WebSocketState.Closed && webSocket.State != WebSocketState.Aborted)
            {
                Console.WriteLine($"WebSocket 当前状态: {webSocket.State}. 即将关闭连接...");
                Log.Info($"WebSocket 当前状态: {webSocket.State}. 即将关闭连接...");

                await webSocket.CloseAsync(WebSocketCloseStatus.InternalServerError,
                    "WebSocket 连接异常中断", CancellationToken.None);

                Console.WriteLine($"WebSocket 关闭状态: {webSocket.CloseStatus}, 描述: {webSocket.CloseStatusDescription}");
                Log.Info($"WebSocket 关闭状态: {webSocket.CloseStatus}, 描述: {webSocket.CloseStatusDescription}");
            }

            webSocket.Dispose();
            Console.WriteLine("WebSocket 连接已关闭");
            Log.Info("WebSocket 连接已关闭");
        }

        /** App 进行短信转发时, Win 端需要提供的认证信息 */
        private static byte[] GenerateWebsocketVerifyInfo()
        {
            // 获取设备 ID 并加密
            var deviceId = ConnectInfoHandler.GetDeviceID();
            var encryptedText = KeyHandler.EncryptString(ConnectInfoHandler.GetDeviceID());
            var signature = KeyHandler.SignData(deviceId);

            return Encoding.UTF8.GetBytes(ValidationField + "." + encryptedText + "." + signature);
        }

        /** 通过 IP 地址进行配对时, Win 端需要提供的认证信息 */
        private static byte[] GeneratePairVerifyInfo()
        {
            var serverTimestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            var plainText = $"{PairingField}+{serverTimestamp}";
            var encryptedText = KeyHandler.EncryptString(plainText);
            return Encoding.UTF8.GetBytes(PairingField + "." + encryptedText);
        }

        /** 通过 IP 地址进行配对时, Win 端提供的完整身份信息 */
        private static byte[] GenerateFullPairInfo()
        {
            // 创建配对信息
            var pairingInfo = new
            {
                deviceName = Environment.MachineName,
                deviceId = ConnectInfoHandler.GetDeviceID(),
                deviceType = ConnectInfoHandler.GetDeviceType(),
                windowsPublicKey = KeyHandler.WindowsPublicKey // Win 端的公钥
            };
            var pairingInfoJson = JsonSerializer.Serialize(pairingInfo);

            // 加密配对信息
            var encryptedText = KeyHandler.EncryptString(pairingInfoJson);
            var signature = KeyHandler.SignData(pairingInfoJson);
            return Encoding.UTF8.GetBytes(PairInfoField + "." + encryptedText + "." + signature);
        }

        private static async Task<bool> ShowPairRequestAsync(string message)
        {
            var tcs = new TaskCompletionSource<bool>();

            Application.Current.Dispatcher.Invoke(() =>
            {
                ToastNotificationManagerCompat.History.Clear();

                var toastBuilder = new ToastContentBuilder()
                    .AddText("您有一条来自 App 端的消息")
                    .AddText(message)
                    .SetToastDuration(ToastDuration.Long)
                    .AddButton(new ToastButton()
                        .SetContent("同意配对")
                        .AddArgument("action", "pair")
                        .AddArgument("source", "WebSocketHandler")
                        .SetBackgroundActivation())
                    .AddButton(new ToastButton()
                        .SetContent("拒绝")
                        .AddArgument("action", "cancelPair")
                        .AddArgument("source", "WebSocketHandler")
                        .SetBackgroundActivation());

                ToastNotificationManagerCompat.OnActivated += (toastArgs) =>
                {
                    var args = ToastArguments.Parse(toastArgs.Argument);

                    if (args.TryGetValue("action", out var action) && action == "pair")
                    {
                        tcs.TrySetResult(true);
                        return;
                    }

                    tcs.TrySetResult(false);
                };

                toastBuilder.Show();

                _ = Task.Delay(TimeSpan.FromSeconds(25)).ContinueWith(_ =>
                {
                    tcs.TrySetResult(false); // 超时未操作
                });
            });

            return await tcs.Task;
        }

        public async Task StopWebSocketServer()
        {
            await _startStopSemaphore.WaitAsync();
            try
            {
                if (_httpListener == null)
                {
                    Console.WriteLine("WebSocket 服务器未运行");
                    Log.Warn("WebSocket 服务器未运行");
                    return;
                }

                // 防止并发释放
                _httpListener.Stop();
                _httpListener = null;
                Console.WriteLine("WebSocket 服务器已停止");
                Log.Warn("WebSocket 服务器已停止");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"停止 WebSocket 服务器时发生错误: {ex}");
                Log.Error($"停止 WebSocket 服务器时发生错误: {ex.Message}");
            }
            finally
            {
                _startStopSemaphore.Release();
            }
        }
    }
}