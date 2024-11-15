﻿using System;
using System.Collections.Generic;
using System.Linq;
using System.Management;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace WinCAPTCHA.ServiceHandler;

public static class ConnectInfoHandler
{
    public static string? GetLocalIpAddress()
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0);
            socket.Connect("www.baidu.com", 80); // Ping一下百度的地址以获取IP
            if (socket.LocalEndPoint is not IPEndPoint endPoint) throw new Exception("无法获取本地IP地址的端点信息。");
            Console.WriteLine("本机当前的IP地址为" + endPoint.Address);
            return endPoint.Address.ToString();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"IPv4 地址匹配错误: {ex.Message}");
            return null;
        }
    }

    // 通过网络适配器品牌来获取 IP 地址
    public static string? GetLocalIpAddressByBrand()
    {
        // 获取真实 IP, 排除掉类似于 zerotier和 virtualbox 这样的虚拟 IP
        try
        {
            // 排除的网络描述
            var excludeList = new List<string> { "virtual", "zerotier" };
            //  正向匹配的网络描述
            var includeList = new List<string> { "intel", "realtek", "mediatek", "qualcomm" };

            var localIpAddress = NetworkInterface.GetAllNetworkInterfaces()
                .Where(networkInterface => networkInterface.OperationalStatus == OperationalStatus.Up &&
                                           networkInterface.NetworkInterfaceType != NetworkInterfaceType.Loopback &&
                                           includeList.Any(brand =>
                                               networkInterface.Description.ToLower().Contains(brand)) && // 网络适配器品牌匹配
                                           !excludeList.Any(exclude =>
                                               networkInterface.Description.ToLower().Contains(exclude) || // 排除特殊适配器
                                               networkInterface.Name.ToLower().Contains(exclude)))
                .SelectMany(networkInterface => networkInterface.GetIPProperties().UnicastAddresses).FirstOrDefault(a =>
                    a.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a.Address))
                ?.Address // 排除回环地址
                .ToString();

            return localIpAddress ?? "无法找到系统中匹配的 IPv4 地址";
        }
        catch (Exception ex)
        {
            Console.WriteLine($"IPv4 地址匹配错误: {ex.Message}");
            return null;
        }
    }

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
                if (chassisType is 3 or 4 or 5)
                {
                    deviceType = "Desktop";
                }
                else if (chassisType is 8 or 9 or 10 or 12 or 14)
                {
                    deviceType = "Laptop";
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

    public static string? GetDeviceUniqueId()
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

        Console.WriteLine("设备的ID为" + uniqueId);
        return uniqueId;
    }
}