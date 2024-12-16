using System;
using System.Collections.Generic;
using System.Linq;
using System.Management;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace WinCAPTCHA.ServiceHandler
{
    public static class ConnectInfoHandler
    {
        /** 获取本机 IP 地址 */
        public static IPAddress GetLocalIP()
        {
            try
            {
                using (var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0))
                {
                    socket.Connect("www.baidu.com", 80); // Ping一下百度的地址以获取IP
                    var endPoint = socket.LocalEndPoint as IPEndPoint;
                    if (endPoint == null) throw new Exception("无法获取本地IP地址的端点信息。");
                    return endPoint.Address;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"IPv4 地址匹配错误: {ex.Message}");
                return IPAddress.Any; // 使用默认 IP 地址（监听所有网络接口）
            }
        }

        /** 通过网络适配器品牌来获取 IP 地址 */
        public static IPAddress GetLocalIPByBrand()
        {
            try
            {
                // 获取真实 IP, 排除掉类似于 zerotier和 virtualbox 这样的虚拟 IP
                var excludeList = new List<string> { "virtual", "zerotier" };
                var includeList = new List<string> { "intel", "realtek", "mediatek", "qualcomm" }; //  正向匹配的网络描述

                var localIpAddress = NetworkInterface.GetAllNetworkInterfaces()
                    .Where(networkInterface => networkInterface.OperationalStatus == OperationalStatus.Up &&
                                               networkInterface.NetworkInterfaceType != NetworkInterfaceType.Loopback &&
                                               includeList.Any(brand =>
                                                   networkInterface.Description.ToLower()
                                                       .Contains(brand)) && // 网络适配器品牌匹配
                                               !excludeList.Any(exclude =>
                                                   networkInterface.Description.ToLower()
                                                       .Contains(exclude) || // 排除特殊适配器
                                                   networkInterface.Name.ToLower().Contains(exclude)))
                    .SelectMany(networkInterface => networkInterface.GetIPProperties().UnicastAddresses).FirstOrDefault(
                        a =>
                            a.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a.Address))
                    ?.Address // 排除回环地址
                    .ToString();

                return localIpAddress != null ? IPAddress.Parse(localIpAddress) : IPAddress.Any;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"IPv4 地址匹配错误: {ex.Message}");
                return IPAddress.Any; // 使用默认 IP 地址（监听所有网络接口）
            }
        }

        /** 获取本设备的设备类型, 如笔记本电脑 台式电脑等 */
        public static string GetDeviceType()
        {
            var deviceType = "Unknown";

            try
            {
                var searcher = new ManagementObjectSearcher("SELECT * FROM Win32_SystemEnclosure");
                foreach (var o in searcher.Get())
                {
                    var obj = (ManagementObject)o;
                    var chassisTypes = (ushort[])(Array)obj["ChassisTypes"];
                    if (chassisTypes.Length <= 0) continue;
                    var chassisType = (int)chassisTypes[0];
                    switch (chassisType)
                    {
                        case 3:
                        case 4:
                        case 5:
                            deviceType = "Desktop";
                            break;
                        case 8:
                        case 9:
                        case 10:
                        case 12:
                        case 14:
                            deviceType = "Laptop";
                            break;
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine("获取设备类型失败: " + ex.Message);
                throw;
            }

            return deviceType;
        }

        /** 获取本设备的 ID */
        public static string GetDeviceID()
        {
            var uniqueId = "Unknown";
            try
            {
                var searcher = new ManagementObjectSearcher("SELECT * FROM Win32_ComputerSystemProduct");
                foreach (var o in searcher.Get())
                {
                    var obj = (ManagementObject)o;
                    uniqueId = obj["UUID"].ToString();
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine("获取设备ID失败: " + ex.Message);
                throw;
            }

            return uniqueId;
        }
    }
}