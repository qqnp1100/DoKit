package com.didichuxing.doraemonkit.volley

import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.Volley
import com.didichuxing.doraemonkit.DoKit

/**
 * ================================================
 * 作    者：jint（金台）
 * 版    本：1.0
 * 创建日期：2020/9/15-14:53
 * 描    述：
 * 修订历史：
 * ================================================
 */
object VolleyManager {
    private val requestQueue: RequestQueue by lazy {
        Volley.newRequestQueue(DoKit.APPLICATION, object : com.android.volley.toolbox.HurlStack() {
            override fun createConnection(url: java.net.URL): java.net.HttpURLConnection {
                val connection = super.createConnection(url)
                if (com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig.isPlatformUrl(url.toString()) && com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig.SDK_TOKEN.isNotEmpty()) {
                    connection.setRequestProperty("X-DoKit-Token", com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig.SDK_TOKEN)
                    connection.instanceFollowRedirects = false
                }
                return connection
            }
        })
    }

    fun <T> add(request: Request<T>) {
        // Empty backends must not enqueue relative URLs. Preserve asynchronous error callbacks.
        if (com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig.host(request.url).isEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!request.isCanceled) {
                    request.deliverError(com.android.volley.VolleyError("DoKit backend URL is not configured"))
                }
            }
            return
        }
        requestQueue.add(request)
    }


}
