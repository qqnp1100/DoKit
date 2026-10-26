package com.didichuxing.doraemonkit.kit.h5_help

import android.text.TextUtils
import android.webkit.WebResourceResponse
import com.didichuxing.doraemonkit.util.ConvertUtils
import com.didichuxing.doraemonkit.util.ToastUtils
import com.didichuxing.doraemonkit.okhttp_api.OkHttpWrap
import com.didichuxing.doraemonkit.kit.core.DoKitManager
import com.didichuxing.doraemonkit.kit.h5_help.bean.JsRequestBean
import com.didichuxing.doraemonkit.kit.network.NetworkManager
import com.didichuxing.doraemonkit.kit.network.bean.WhiteHostBean
import com.didichuxing.doraemonkit.kit.network.room_db.DokitDbManager
import com.didichuxing.doraemonkit.kit.network.room_db.MockInterceptApiBean
import com.didichuxing.doraemonkit.kit.network.room_db.MockTemplateApiBean
import com.didichuxing.doraemonkit.kit.network.utils.bodyContent
import com.didichuxing.doraemonkit.util.DoKitCommUtil
import com.didichuxing.doraemonkit.util.LogHelper
import okhttp3.*
import org.json.JSONObject
import java.util.Locale

/**
 * ================================================
 * 作    者：jint（金台）
 * 版    本：1.0
 * 创建日期：2020/9/1-11:14
 * 描    述：
 * 修订历史：
 * ================================================
 */
object JsHttpUtil {
    val TAG = "JsHttpUtil"

    /**
     * 将request query 转化成json字符串
     *
     * @return
     */
    fun transformQuery(query: String?): String {
        var json = ""
        if (query.isNullOrBlank()) {
            return json
        }
        try {
            //query 类似 ccc=ccc&ddd=ddd
            json = DoKitCommUtil.param2Json(query)
            //测试是否是json字符串
            JSONObject(json)
        } catch (e: Exception) {
            //e.printStackTrace();
            json = DokitDbManager.IS_NOT_NORMAL_QUERY_PARAMS
            //LogHelper.e(TAG, "===query json====>" + json);
        }
        return json
    }


    /**
     * 将request body 转化成json字符串
     *
     * @return
     */
    fun transformRequestBody(
        method: String?,
        requestBody: String?,
        headers: MutableMap<String?, String?>?
    ): String {
        //form :"application/x-www-form-urlencoded"
        //json :"application/json;"
        var json = ""
        if (method.equals("GET", true) || requestBody.isNullOrBlank()) {
            return json
        }

        try {

            headers?.let {
                val contentType = it["Content-Type"]
                if (contentType.isNullOrBlank()) {
                    return json
                }

                //表单类型的post
                if (contentType.contains(DokitDbManager.MEDIA_TYPE_FORM)) {
                    //类似 ccc=ccc&ddd=ddd
                    json = DoKitCommUtil.param2Json(requestBody)
                    //测试是否是json字符串
                    JSONObject(json)
                } else if (contentType.contains(DokitDbManager.MEDIA_TYPE_JSON)) {
                    json = requestBody
                    JSONObject(json)
                } else {
                    json = DokitDbManager.IS_NOT_NORMAL_BODY_PARAMS
                }
            }
        } catch (e: java.lang.Exception) {
            //e.printStackTrace();
            json = ""
            LogHelper.e(
                TAG, "===body json====>$json"
            )
        }
        return json
    }

    private val sceneClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()

    fun tryScene(path: String, id: String?): Response? {
        if (NetworkManager.MOCK_DOMAIN.isEmpty() || id.isNullOrBlank()) return null
        val api = DokitDbManager.getInstance().getInterceptApiByIdInMap(path, id, DokitDbManager.FROM_SDK_OTHER) as? MockInterceptApiBean ?: return null
        if (!api.isOpen || api.selectedSceneId.isNullOrBlank()) return null
        return try {
            val request = Request.Builder().url(NetworkManager.MOCK_DOMAIN + "/api/app/scene/" + android.net.Uri.encode(api.selectedSceneId))
                .header("X-DoKit-Token", com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig.SDK_TOKEN).get().build()
            sceneClient.newCall(request).execute()
        } catch (unavailable: java.io.IOException) { null }
    }

    fun createNormalWebResourceResponse(response: Response): WebResourceResponse {
        val type = response.header("Content-Type", "application/json")!!.substringBefore(';')
        val bytes = OkHttpWrap.toResponseBody(response)?.bytes() ?: ByteArray(0)
        val result = WebResourceResponse(type, "UTF-8", java.io.ByteArrayInputStream(bytes))
        if (android.os.Build.VERSION.SDK_INT >= 21 && response.code() !in 300..399) {
            result.setStatusCodeAndReasonPhrase(response.code(), response.message().ifBlank { "Mock" })
            result.responseHeaders = response.headers().toMultimap().mapValues { it.value.joinToString(", ") }
        }
        return result
    }

    fun createX5WebResourceResponse(response: Response): com.tencent.smtt.export.external.interfaces.WebResourceResponse {
        val type = response.header("Content-Type", "application/json")!!.substringBefore(';')
        val bytes = OkHttpWrap.toResponseBody(response)?.bytes() ?: ByteArray(0)
        val result = com.tencent.smtt.export.external.interfaces.WebResourceResponse(type, "UTF-8", java.io.ByteArrayInputStream(bytes))
        if (response.code() !in 300..399) {
            result.setStatusCodeAndReasonPhrase(response.code(), response.message().ifBlank { "Mock" })
            result.responseHeaders = response.headers().toMultimap().mapValues { it.value.joinToString(", ") }
        }
        return result
    }

    /**
     *是否命中模板功能
     */
    fun matchedTemplateRule(response: Response, path: String, templateMatchedId: String) {

        //命中模板规则
        if (TextUtils.isEmpty(templateMatchedId)) {
            return
        }
        val templateApiBean = DokitDbManager.getInstance().getTemplateApiByIdInMap(
            path,
            templateMatchedId,
            DokitDbManager.FROM_SDK_OTHER
        )
        //LogHelper.i("MOCK_TEMPLATE", "path=====>" + path + "isOpen===>" + templateApiBean.isOpen());
        //LogHelper.i("MOCK_TEMPLATE", "path=====>" + path + "isOpen===>" + templateApiBean.isOpen());
        templateApiBean?.let {
            it as MockTemplateApiBean
            if (it.isOpen) {
                //保存老的response 数据到数据库
                saveResponse2DB(response, it)
            }
        }

    }

    /**
     * 保存匹配中的数据到本地数据库
     *
     * @param response
     * @param mockApi
     * @throws Exception
     */
    private fun saveResponse2DB(response: Response, mockApi: MockTemplateApiBean) {
        if (OkHttpWrap.toResponseCode(response) != 200) {
            return
        }
        if (OkHttpWrap.toResponseBody(response) == null) {
            return
        }
        try {
            val host = OkHttpWrap.toResponseHost(response)
            //LogHelper.i(TAG, "host====>" + host);
            //这里不能直接使用response.body().string()的方式输出日志
            //因为response.body().string()之后，response中的流会被关闭，程序会报错，我们需要创建出一
            //个新的response给应用层处理
            val responseBody = response.peekBody(Long.MAX_VALUE)
            val strResponseBody = responseBody.string()
            if (TextUtils.isEmpty(strResponseBody)) {
                return
            }
            if (com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig.isPlatformUrl(response.request().url().toString())) {
                mockApi.responseFrom = MockTemplateApiBean.RESPONSE_FROM_MOCK
            } else {
                mockApi.responseFrom = MockTemplateApiBean.RESPONSE_FROM_REAL
            }
            mockApi.strResponse = strResponseBody
            //更新本地数据库
            DokitDbManager.getInstance().updateTemplateApi(mockApi)
            //拦截命中提示
            ToastUtils.showShort("模板别名:==" + mockApi.mockApiName + "==已被保存")
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }


    fun createOkHttpRequest(requestBean: JsRequestBean, userAgent: String): Request {
        requestBean.headers?.let {
            if (!it.containsKey("content-type")) {
                it["content-type"] = "application/json"
            }

            if (!it.containsKey("User-Agent")) {
                it["User-Agent"] = userAgent
            }
        }
        val builder = Headers.Builder()
        requestBean.headers?.forEach {
            builder.add(it.key!!, it.value!!)
        }


        val headers = builder.build()
        return when (requestBean.method?.uppercase(Locale.ROOT)) {
            "GET" -> {
                Request.Builder()
                    .url(requestBean.url!!)
                    .headers(headers)
                    .get()
                    .build()
            }
            "POST" -> {
                var contentType: String? = ""
                contentType = requestBean.headers?.get("Content-Type")
                if (contentType.isNullOrBlank()) {
                    contentType = requestBean.headers?.get("content-type")
                }


                val requestBody =
                    OkHttpWrap.toRequestBody(requestBean.body, OkHttpWrap.toMediaType(contentType))

                Request.Builder()
                    .url(requestBean.url!!)
                    .headers(headers)
                    .post(requestBody!!)
                    .build()
            }
            else -> {
                Request.Builder()
                    .url(requestBean.url!!)
                    .headers(headers)
                    .get()
                    .build()
            }
        }

    }

    /**
     * 是否命中白名单规则
     *
     * @return bool
     */
    fun matchWhiteHost(request: Request): Boolean {
        val whiteHostBeans: List<WhiteHostBean> = DoKitManager.WHITE_HOSTS
        if (whiteHostBeans.isEmpty()) {
            return true
        }

        for (whiteHostBean in whiteHostBeans) {
            if (TextUtils.isEmpty(whiteHostBean.host)) {
                continue
            }
            val realHost = OkHttpWrap.toRequestHost(request)
            //正则判断
            if (whiteHostBean.host.equals(realHost, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

}
