package com.autocaptcha.handler

data class ResponseContent(
    var status: Boolean = false,
    var code: String? = null,
    var message: String? = null,
    var data: Any? = null
) {
    companion object {
        val instance: ResponseContent
            get() = ResponseContent()
    }

    fun ok(): ResponseContent {
        this.status = true
        return this
    }

    fun ok(message: String?, data: Any? = null): ResponseContent {
        this.status = true
        this.message = message
        this.data = data
        return this
    }

    fun error(message: String?): ResponseContent {
        this.status = false
        this.message = message
        return this
    }

    fun set(
        responseType: ResponseType,
        message: String? = null,
        status: Boolean? = null
    ): ResponseContent {
        this.status = status ?: this.status
        this.code = responseType.code.toString()
        this.message = message ?: responseType.getMsg()
        return this
    }

    fun ok(responseType: ResponseType): ResponseContent {
        return set(responseType, status = true)
    }

    fun error(responseType: ResponseType): ResponseContent {
        return set(responseType, status = false)
    }
}

enum class ResponseType(val code: Int, private val msg: String) {
    SUCCESS(200, "操作成功"),
    ERROR(500, "操作失败");

    fun getMsg(): String {
        return this.msg
    }
}
