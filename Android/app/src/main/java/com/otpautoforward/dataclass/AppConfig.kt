package com.otpautoforward.dataclass


/**
 * 用于存储开发过程中需要用到的配置信息
 *
 */
sealed class AppConfig {
    data class StringConfig(val value: String) : AppConfig()

    companion object {
        val ReleasesSource = StringConfig("Gitee")
        val RepositoryOwner = StringConfig("DirectoWay")
        val Repository = StringConfig("OTPAutoForward")
        val GiteeApiUrl = StringConfig("https://gitee.com/api/v5/repos")
        val GitHubApiUrl = StringConfig("https://api.github.com/repos")

        val GiteeSource = StringConfig("https://gitee.com/DirectoWay/OTPAutoForward")
        val GitHubSource = StringConfig("https://github.com/DirectoWay/OTPAutoForward")

        val TestMessage = StringConfig("【测试短信】尾号为1234的用户您好, 987123 是您的验证码, 这是一条测试短信")
        val TestSender = StringConfig("测试员")

        /** 远程仓库中的 QA 数据 */
        val QAResource =
            StringConfig("https://gitee.com/DirectoWay/OTPAutoForward/raw/net472/Android/app/src/main/assets/QuestionAndAnswer.json")

        val FeedBackUrl = StringConfig("https://shimo.im/forms/25q5X4Wl48fWJQ3D/fill")
    }
}