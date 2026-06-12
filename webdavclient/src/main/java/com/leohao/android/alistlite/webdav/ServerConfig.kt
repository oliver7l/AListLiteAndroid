package com.leohao.android.alistlite.webdav

data class ServerConfig(
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = ""
) {
    init {
        require(url.isNotBlank()) { "URL不能为空" }
    }

    fun getNormalizedUrl(): String {
        return if (url.endsWith("/")) url else "$url/"
    }

    fun getBaseUrl(): String {
        return url.trimEnd('/')
    }

    fun requiresAuth(): Boolean = username.isNotBlank() || password.isNotBlank()
}
