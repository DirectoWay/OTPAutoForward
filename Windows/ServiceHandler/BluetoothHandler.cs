using System.IO;
using System.Linq;
using System.Net.Sockets;
using Windows.Devices.Radios;
using log4net;

namespace OTPAutoForward.ServiceHandler
{
    using System;
    using System.Text;
    using System.Threading;
    using System.Threading.Tasks;
    using InTheHand.Net.Bluetooth;
    using InTheHand.Net.Sockets;

    public class BluetoothHandler
    {
        private static readonly ILog Log = LogManager.GetLogger(typeof(BluetoothHandler));

        private static readonly ResponseContent ResponseContent = new ResponseContent();

        private BluetoothListener _bluetoothListener;

        /** 控制蓝牙服务启动和停止时的并发访问 */
        private readonly SemaphoreSlim _startStopSemaphore = new SemaphoreSlim(1, 1);

        private bool _bluetoothMonitor;

        private Radio _bluetoothRadio;

        private CancellationTokenSource _cts;

        private readonly Guid _serviceUUID = new Guid("00001101-0000-1000-8000-00805F9B34FB"); // 蓝牙 SPP服务 UUID

        /** 蓝牙消息的确认字段 */
        private const string ConfirmedField = "confirmed";

        /** 订阅消息事件 */
        public event Action<string> OnMessageReceived;

        /** 监听系统的蓝牙状态变化 */
        private void MonitorBluetoothState()
        {
            if (_bluetoothMonitor) return;

            _bluetoothRadio.StateChanged -= BluetoothRadio_StateChanged;
            _bluetoothRadio.StateChanged += BluetoothRadio_StateChanged;

            _bluetoothMonitor = true;
            Console.WriteLine("正在监听系统蓝牙状态");
            Log.Info("正在监听系统蓝牙状态");
        }

        /** 系统蓝牙模式发生变化时调整服务状态 */
        private void BluetoothRadio_StateChanged(Radio sender, object args)
        {
            Console.WriteLine($"系统蓝牙状态改变: {_bluetoothRadio.State}");
            Log.Info($"系统蓝牙状态改变: {_bluetoothRadio.State}");

            _ = _bluetoothRadio.State == RadioState.On ? StartBluetoothServer() : StopBluetoothServer();
        }

        public async Task<ResponseContent> StartBluetoothServer()
        {
            // 检查设备是否支持蓝牙硬件
            var radios = await Radio.GetRadiosAsync();
            _bluetoothRadio = radios.FirstOrDefault(r => r.Kind == RadioKind.Bluetooth);

            if (_bluetoothRadio == null)
            {
                App.UpdateAppSettings(settings => { settings.BluetoothMode = false; });
                Log.Warn("未检测到蓝牙硬件, 无法开启蓝牙优先模式");
                Console.WriteLine("未检测到蓝牙硬件, 无法开启蓝牙优先模式");
                return ResponseContent.Error("未检测到蓝牙硬件, 无法开启蓝牙优先模式");
            }

            Log.Info($"当前蓝牙状态: {_bluetoothRadio.State}");
            Console.WriteLine($"当前蓝牙状态: {_bluetoothRadio.State}");

            if (!BluetoothRadio.IsSupported)
            {
                Console.WriteLine("蓝牙适配器不可用或未安装驱动, 蓝牙优先模式不可用");
                Log.Warn("蓝牙适配器不可用或未安装驱动, 蓝牙优先模式不可用");
                return ResponseContent.Error("蓝牙适配器不可用或未安装驱动, 蓝牙优先模式不可用");
            }

            BluetoothRadio.PrimaryRadio.Mode = RadioMode.Discoverable;

            if (_bluetoothRadio.State != RadioState.On)
            {
                Console.WriteLine("Windows 蓝牙未开启, 蓝牙服务器启动失败");
                Log.Info("Windows 蓝牙未开启, 蓝牙服务器启动失败");
                return ResponseContent.Error("Windows 蓝牙未开启, 蓝牙服务器启动失败");
            }

            if (_bluetoothListener != null)
            {
                Console.WriteLine("蓝牙服务器已在运行");
                Log.Info("蓝牙服务器已在运行");
                return ResponseContent.OK();
            }

            await _startStopSemaphore.WaitAsync();
            try
            {
                _bluetoothListener = new BluetoothListener(_serviceUUID);

                _bluetoothListener.Start();
                Log.Info($"蓝牙服务器已启动, 等待Android设备连接 - 服务UUID: {_serviceUUID}");
                Console.WriteLine($"蓝牙服务器已启动, 等待Android设备连接 - 服务UUID:{_serviceUUID}");

                MonitorBluetoothState();
                _cts = new CancellationTokenSource();
                _ = AcceptBluetoothClientsAsync(_cts.Token);
                return ResponseContent.OK();
            }
            catch (Exception ex)
            {
                Log.Error($"启动蓝牙服务器时发生错误: {ex.Message}");
                Console.WriteLine($"启动蓝牙服务器时发生错误: {ex}");
                return ResponseContent.Error($"启动蓝牙服务器时发生错误: {ex.Message}");
            }
            finally
            {
                _startStopSemaphore.Release();
            }
        }

        /** 持续监听并接受来自客户端的蓝牙连接请求 */
        private async Task AcceptBluetoothClientsAsync(CancellationToken cancellationToken)
        {
            try
            {
                while (!cancellationToken.IsCancellationRequested)
                {
                    var client = await Task.Run(() => _bluetoothListener.AcceptBluetoothClient(), cancellationToken);
                    Console.WriteLine($"收到来自设备 {client.RemoteMachineName} 的蓝牙连接");
                    await HandleSmsAsync(client, cancellationToken);
                }
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.Interrupted)
            {
                // 处理手动中断蓝牙
                if (cancellationToken.IsCancellationRequested)
                {
                    Console.WriteLine("蓝牙监听被手动关闭");
                }
                else
                {
                    throw;
                }
            }
            catch (Exception ex)
            {
                Log.Error($"接受蓝牙连接时发生错误: {ex}");
                Console.WriteLine($"接受蓝牙连接时发生错误: {ex.Message}");
            }
        }

        private async Task HandleSmsAsync(BluetoothClient client, CancellationToken cancellationToken)
        {
            using (var stream = client.GetStream())
            {
                {
                    using (var reader = new StreamReader(stream, Encoding.UTF8))
                    {
                        while (!cancellationToken.IsCancellationRequested && stream.CanRead)
                        {
                            var line = await reader.ReadLineAsync();
                            if (line == null) break;
                            var message = ReceiveBluetoothMessage(Encoding.UTF8.GetBytes(line), line.Length);
                            if (message == null) continue;

                            OnMessageReceived?.Invoke(message);

                            var responseMessage = Encoding.UTF8.GetBytes($"{ConfirmedField}.已收到消息\r\n");
                            await stream.WriteAsync(responseMessage, 0, responseMessage.Length, cancellationToken);
                            Console.WriteLine("已发送确认消息");
                        }

                        Console.WriteLine("蓝牙客户端已断开");
                    }
                }
            }
        }

        private static string ReceiveBluetoothMessage(byte[] buffer, int bytesRead)
        {
            var message = Encoding.UTF8.GetString(buffer, 0, bytesRead);

            if (string.IsNullOrWhiteSpace(message))
            {
                Log.Warn("收到 App 端的蓝牙空消息");
                Console.WriteLine("收到空消息，忽略处理");
                return null;
            }

            message = KeyHandler.DecryptString(message);
            Console.WriteLine($"{DateTime.Now} 已收到来自 App 端的消息:{message}");
            Log.Info($"已收到来自 App 端的消息:{message}");

            return message;
        }

        public async Task<ResponseContent> StopBluetoothServer()
        {
            if (_bluetoothListener == null)
            {
                Console.WriteLine("停止蓝牙服务时发生异常: 蓝牙服务器未运行");
                Log.Warn("停止蓝牙服务时发生异常: 蓝牙服务器未运行");
                return ResponseContent.Error("停止蓝牙服务时发生异常: 蓝牙服务器未运行");
            }

            await _startStopSemaphore.WaitAsync();
            try
            {
                _bluetoothListener.Stop();
                _bluetoothListener = null;
                _cts?.Cancel();
                Console.WriteLine("蓝牙服务器已被手动关闭");
                Log.Warn("蓝牙服务器已被手动关闭");
                return ResponseContent.OK();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"停止蓝牙服务器时发生错误: {ex}");
                Log.Error($"停止蓝牙服务器时发生错误: {ex.Message}");
                return ResponseContent.Error($"停止蓝牙服务器时发生错误: {ex.Message}");
            }
            finally
            {
                _startStopSemaphore.Release();
            }
        }
    }
}