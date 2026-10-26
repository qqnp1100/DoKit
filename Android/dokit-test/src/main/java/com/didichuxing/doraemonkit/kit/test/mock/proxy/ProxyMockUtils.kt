package com.didichuxing.doraemonkit.kit.test.mock.proxy

import com.didichuxing.doraemonkit.extension.string
import com.didichuxing.doraemonkit.kit.test.event.ControlEventManager
import com.didichuxing.doraemonkit.kit.network.NetworkManager
import com.didichuxing.doraemonkit.kit.network.okhttp.InterceptorUtil
import com.didichuxing.doraemonkit.util.EncryptUtils
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.*

/**
 * didi Create on 2022/3/10 .
 *
 * Copyright (c) 2022/3/10 by didiglobal.com.
 *
 * @author <a href="realonlyone@126.com">zhangjun</a>
 * @version 1.0
 * @Date 2022/3/10 7:45 下午
 * @Description 用一句话说明文件功能
 */
object ProxyMockUtils {

    private fun createHeaders(headers: Headers): String {
        return headers.toString()
    }

    fun parseHeaders(headers: String): Headers {
        val builder = Headers.Builder()
        headers.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) builder.add(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
        }
        return builder.build()
    }

    fun createProxyRequest(did: String, request: Request): ProxyRequest {
        val aid = ControlEventManager.getCurrentEventId()
        val url = request.url
        val scheme = url.scheme
        val host = url.host
        val path = url.encodedPath
        val query = string(url.encodedQuery)
        val fragment = string(url.fragment)
        val headers = createHeaders(request.headers)
        val body = request.body
        val contentType = (body?.contentType() ?: "").toString()
        val contentLength = body?.contentLength() ?: 0
        val bodyString = body?.string() ?: ""
        val searchKey = EncryptUtils.encryptMD5ToString(path)
        val method = request.method
        val clientProtocol = "http"
        return ProxyRequest(
            did, aid, url.toString(), scheme, host, path, query, fragment,
            headers, contentType, contentLength, bodyString, searchKey, method, clientProtocol
        )
    }

    fun createEmptyProxyResponse(did: String): ProxyResponse {
        return ProxyResponse(
            did, "",
            "", 0, "", 404, false, "data", "local"
        )
    }

    fun createProxyResponse(did: String, response: Response): ProxyResponse {
        val headers = createHeaders(response.headers)
        val code = response.code
        val body = response.peekBody(Long.MAX_VALUE)
        val contentType = (body?.contentType() ?: "").toString()
        val image = InterceptorUtil.isImg(contentType)
        val contentLength = body?.contentLength() ?: 0
        var bodyString = ""
        var source = ""
        if (!image) {
            try {
                bodyString = body?.string() ?: ""
            } catch (e: Exception) {
                e.printStackTrace()
            }
            source = "data"
        } else {
            source = "image"
        }

        val protocol = response.protocol.toString()

        return ProxyResponse(did, headers, contentType, contentLength, bodyString, code, image, source, protocol)
    }

    fun filterRequest(request: Request): Boolean {
        val scheme = request.url.scheme
        val host = request.url.host
        if (com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig.isPlatformUrl(request.url.toString())) return true
        val wsKey: String? = request.header("Sec-WebSocket-Key")
        val wsVersion: String? = request.header("Sec-WebSocket-Version")
        if (wsKey != null && wsVersion != null) {
            return true
        }
        return false
    }

    private fun string(arg: String?): String {
        return arg ?: ""

    }

}
