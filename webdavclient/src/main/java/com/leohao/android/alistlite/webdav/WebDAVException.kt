package com.leohao.android.alistlite.webdav

sealed class WebDAVException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectionFailed(cause: Throwable) : WebDAVException("连接失败: ${cause.message}", cause)
    class AuthenticationFailed : WebDAVException("认证失败，请检查用户名和密码")
    class ResourceNotFound(path: String) : WebDAVException("资源不存在: $path")
    class ServerError(code: Int, message: String) : WebDAVException("服务器错误 [$code]: $message")
    class InvalidResponse(message: String) : WebDAVException(message)
    class UnsupportedOperation(message: String) : WebDAVException(message)
}
