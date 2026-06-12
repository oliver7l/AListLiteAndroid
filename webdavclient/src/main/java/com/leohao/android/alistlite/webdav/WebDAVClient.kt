package com.leohao.android.alistlite.webdav

import android.util.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端 — 支持 PROPFIND 协议浏览远程文件
 */
class WebDAVClient {

    private var currentConfig: ServerConfig? = null
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    companion object {
        private const val TAG = "WebDAVClient"
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <d:getcontentlength/>
                    <d:getlastmodified/>
                    <d:getcontenttype/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        private val HTTP_DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
    }

    fun connect(config: ServerConfig): Boolean {
        currentConfig = config
        return try {
            val response = client.newCall(buildPropfindRequest(config.getNormalizedUrl(), config, "0")).execute()
            response.isSuccessful || response.code == 207
        } catch (e: Exception) {
            Log.e(TAG, "连接失败: ${e.message}")
            false
        }
    }

    fun listFiles(path: String): List<WebDAVResource> {
        val config = currentConfig ?: throw WebDAVException.ConnectionFailed(
            IllegalStateException("未配置服务器")
        )
        return listFilesViaPropfind(config, path)
    }

    private fun listFilesViaPropfind(config: ServerConfig, path: String): List<WebDAVResource> {
        val url = buildFullUrl(config, path)
        val request = buildPropfindRequest(url, config, "1")

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw WebDAVException.ConnectionFailed(e)
        }

        if (!response.isSuccessful && response.code != 207) {
            handleErrorResponse(response)
        }

        val responseBody = response.body?.string()
            ?: throw WebDAVException.InvalidResponse("响应体为空")

        return parseMultistatusResponse(responseBody, config.getBaseUrl())
    }

    fun getStreamUrl(path: String): String {
        val config = currentConfig ?: throw WebDAVException.ConnectionFailed(
            IllegalStateException("未配置服务器")
        )
        val baseUrl = config.getNormalizedUrl()
        val normalizedPath = if (path.startsWith("/")) path.substring(1) else path
        return "$baseUrl${normalizedPath.encodePath()}"
    }

    private fun buildFullUrl(config: ServerConfig, path: String): String {
        val baseUrl = config.getNormalizedUrl()
        val normalizedPath = if (path.startsWith("/")) path.substring(1) else path
        return if (normalizedPath.isNotEmpty() && !normalizedPath.endsWith("/")) {
            "$baseUrl${normalizedPath.encodePath()}/"
        } else {
            "$baseUrl${normalizedPath.encodePath()}"
        }
    }

    private fun buildPropfindRequest(url: String, config: ServerConfig, depth: String): Request {
        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth)

        if (config.requiresAuth()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder.build()
    }

    private fun handleErrorResponse(response: okhttp3.Response) {
        when (response.code) {
            401 -> throw WebDAVException.AuthenticationFailed()
            404 -> throw WebDAVException.ResourceNotFound("请求的资源")
            in 500..599 -> throw WebDAVException.ServerError(response.code, response.message)
            else -> throw WebDAVException.ServerError(response.code, response.message)
        }
    }

    private fun parseMultistatusResponse(xml: String, baseUrl: String): List<WebDAVResource> {
        val resources = mutableListOf<WebDAVResource>()

        val basePath = try {
            val uri = java.net.URI(baseUrl)
            uri.path?.trimEnd('/') ?: ""
        } catch (e: Exception) {
            baseUrl.substringAfter("://").substringAfter('/').let {
                if (it.contains('/')) "/${it.substringAfter('/')}" else ""
            }
        }

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var currentPath = ""
            var currentName = ""
            var currentIsDirectory = false
            var currentSize = 0L
            var currentLastModified = 0L
            var currentContentType: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    // 去掉命名空间前缀 (如 D:href → href)
                    val rawName = parser.name.lowercase()
                    val tagName = rawName.substringAfter(':')
                    when (tagName) {
                        "href" -> {
                            currentPath = parser.nextText().trim()
                            // URL解码路径
                            currentPath = try {
                                URLDecoder.decode(currentPath, "UTF-8")
                            } catch (e: Exception) {
                                currentPath
                            }
                        }
                        "displayname" -> {
                            currentName = parser.nextText().trim()
                        }
                        "getcontentlength" -> {
                            val text = parser.nextText().trim()
                            currentSize = text.toLongOrNull() ?: 0L
                        }
                        "getlastmodified" -> {
                            val dateStr = parser.nextText().trim()
                            currentLastModified = parseHttpDate(dateStr)
                        }
                        "getcontenttype" -> {
                            currentContentType = parser.nextText().trim().ifEmpty { null }
                        }
                        "resourcetype" -> {
                            // 检查子标签是否包含 collection（用深度遍历）
                            var depth = 1
                            currentIsDirectory = false
                            while (depth > 0) {
                                val evt = parser.next()
                                when (evt) {
                                    XmlPullParser.START_TAG -> {
                                        depth++
                                        val childName = parser.name.lowercase().substringAfter(':')
                                        if (childName == "collection") currentIsDirectory = true
                                    }
                                    XmlPullParser.END_TAG -> depth--
                                }
                            }
                        }
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG) {
                    val rawName = parser.name.lowercase()
                    val tagName = rawName.substringAfter(':')
                    if (tagName == "response") {
                        // 解析完成一个资源
                        if (currentPath.isNotEmpty()) {
                            // 跳过根路径自身
                            val relativePath = removeBasePath(currentPath, basePath)

                            if (relativePath.isNotEmpty()) {
                                val name = if (currentName.isNotEmpty()) currentName
                                else relativePath.trim('/').substringAfterLast('/')

                                val resourceType = WebDAVResource.determineResourceType(
                                    name, currentContentType, currentIsDirectory
                                )

                                resources.add(
                                    WebDAVResource(
                                        path = relativePath,
                                        name = name,
                                        isDirectory = currentIsDirectory,
                                        size = currentSize,
                                        lastModified = currentLastModified,
                                        contentType = currentContentType,
                                        resourceType = resourceType
                                    )
                                )
                            }

                            currentPath = ""
                            currentName = ""
                            currentIsDirectory = false
                            currentSize = 0L
                            currentLastModified = 0L
                            currentContentType = null
                        }
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            throw WebDAVException.InvalidResponse("XML解析失败: ${e.message}")
        }

        return resources
            .filter { it.name.isNotEmpty() }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun removeBasePath(path: String, basePath: String): String {
        if (basePath.isEmpty()) return path

        val normalizedPath = path.trimEnd('/')
        val normalizedBasePath = basePath.trimEnd('/')

        return when {
            normalizedPath == normalizedBasePath -> ""
            normalizedPath.startsWith("$normalizedBasePath/") ->
                normalizedPath.removePrefix(normalizedBasePath)
            else -> path
        }
    }

    private fun parseHttpDate(dateString: String): Long {
        for (format in HTTP_DATE_FORMATS) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                return sdf.parse(dateString)?.time ?: 0
            } catch (_: Exception) { }
        }
        return 0
    }

    private fun String.encodePath(): String {
        return this.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%2F", "/")
        }
    }
}
