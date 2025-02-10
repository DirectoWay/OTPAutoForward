<div align=center>    
<img src="https://github.com/user-attachments/assets/71588035-da47-4a89-b00b-c735afb2c4aa" alt="程序图标" width="15%"/> 

# OTPAutoForward - 验证码无缝化转发
</div>

一款用于办公室环境的验证码无缝化转发的双端程序，仅需简单的配对即可将验证码内容同步至 Win10

该程序需要 Win 端同时搭配 App 端一同食用，且仅支持 Win10

<br>

# 如何使用

Win 端与 App 端同处于一个局域网环境

右键 Win 端的托盘图标，可通过二维码或 IP 地址进行配对

![托盘图标](https://github.com/user-attachments/assets/fa086603-093b-498e-879c-c99df7516cda)

![Win 端菜单栏](https://github.com/user-attachments/assets/b61b7fde-2785-4d96-9b12-00f7b2f3e10c)

为了精简化程序，Win 端没有主页面，左键双击托盘图标后，发现程序没有任何反应，这是正常的

打开 App 端，通过 Win 端提供的配对信息进行相应的配对操作

<img src="https://github.com/user-attachments/assets/e55f0697-0f24-48e6-8039-a14b420912cb" alt="App 端页面" width="35%"/>

## 验证码转发

当手机收到验证码时，App 端将自动同步内容至 Win 端，Win 端将会以 Toast 弹窗显示短信内容

可根据需要复制短信中的核心内容，也可点击弹窗复制整条短信内容

![短信 Toast 演示](https://github.com/user-attachments/assets/5fdfbfe6-eed7-4c87-a239-664c6c4f8206)

## 清除 Win 端密钥

- Win 端在生成配对二维码时，也会生成一对密钥用于后续的业务操作

- Win 端的配对二维码长期有效，每次展示的二维码其实都是同一个

- 重置密钥后，将会生成新的二维码，此时 App 端需要重新与 Win 端进行配对

## 关于内网中 IP 变动的问题

- Win 端程序默认监听 9224 端口

- 使用 IP 进行配对时，Win 端提供的 IP 地址仅在配对过程中有效。配对完成后，即使 Win 端 IP 发生变化，也无需重新配对

<br>

# 为什么使用 OTPAutoForward

- 这里感谢 SmsForwarder 与 FnSync 提供的优秀设计思路

- 原本在开发 OTPAutoForward 之初，我只是想要一个能用于在电脑上能无缝化粘贴手机验证码内容的 App，但是鉴于办公室的 WiFi 环境，电脑的内网 IP 总是会发生变动，加之 SmsForwarder 的 Socket 发送渠道没有官方的 Win 端 (~~绝对没有催更大佬的意思~~) ，无奈只能自己重新搓

- 再说 FnSync，FnSync 不仅可同步消息通知，还可以在 Win 端操作 App 端的文件内容，是一个绝对能满足我需求的优秀项目，但是 FnSync 已经久未更新，作者也疑似删号，最重要的是 FnSync 的 Android 端貌似没有开源，并且在断开 WiFi 后需要重新打开 App 才能进行断线重连，不过 FnSync 给我提供了绝佳的方案思路，如此种种，更加确定了我要重新搓一套即拆即用的轮子

<br>

# 特别感谢

- https://github.com/plibither8/otp-forwarder - 思路来源

- https://gitee.com/holmium/fnsync -思路来源 (作者疑似已删号)

- https://github.com/lakent/fnsync

- https://github.com/pppscn/SmsForwarder - 参考借鉴

- https://support-cn.samsung.com/App/DeveloperChina/home/index - UI原型: Samsung ADB

- https://github.com/kongzue/DialogX - UI支持

- https://github.com/skydoves/ColorPickerView - 取色盘功能