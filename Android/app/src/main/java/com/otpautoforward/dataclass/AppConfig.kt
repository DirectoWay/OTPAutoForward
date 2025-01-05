package com.otpautoforward.dataclass


/**
 * 用于存储开发过程中需要用到的配置信息
 *
 */
sealed class AppConfig {
    data class StringConfig(val value: String) : AppConfig()

    companion object {
        val ReleasesSource = StringConfig("https://gitee.com/")
        val RepositoryOwner = StringConfig("DirectoWay")
        val Repository = StringConfig("OTPAutoForward")
    }
}