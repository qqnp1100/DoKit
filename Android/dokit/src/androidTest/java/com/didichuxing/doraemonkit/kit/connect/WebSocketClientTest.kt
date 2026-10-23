package com.didichuxing.doraemonkit.kit.connect

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.didichuxing.doraemonkit.kit.connect.ws.OnWebSocketLoginSuccessListener
import com.didichuxing.doraemonkit.kit.connect.ws.OnWebSocketStatusChangeListener
import com.didichuxing.doraemonkit.kit.connect.ws.OkHttpWebSocketSession
import com.didichuxing.doraemonkit.kit.connect.ws.WebSocketClient
import com.didichuxing.doraemonkit.util.Utils
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device integration: login uses Android device/package information and preferences. */
@RunWith(AndroidJUnit4::class)
class WebSocketClientTest {
    @Test(timeout = 15000)
    fun logsInToExplicitIntegrationEndpoint() {
        val endpoint = InstrumentationRegistry.getArguments().getString("dokit.websocket.endpoint")
        assumeTrue("Set dokit.websocket.endpoint to opt into the DoKit server login test", !endpoint.isNullOrBlank())
        require(endpoint!!.startsWith("ws://") || endpoint.startsWith("wss://"))
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        Utils.init(application)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val client = WebSocketClient()
        client.addOnWebSocketLoginSuccessListener(object : OnWebSocketLoginSuccessListener {
            override fun onWebSocketLoginSuccess() { completed.countDown() }
        })
        client.addOnWebSocketStatusChangeListener(object : OnWebSocketStatusChangeListener {
            override fun onOpen(webSocket: OkHttpWebSocketSession, response: String) = Unit
            override fun onClosed(webSocket: OkHttpWebSocketSession, code: Int, reason: String) = Unit
            override fun onClosing(webSocket: OkHttpWebSocketSession, code: Int, reason: String) = Unit
            override fun onFailure(webSocket: OkHttpWebSocketSession, t: Throwable, response: String?) {
                failure.set(t)
                completed.countDown()
            }
        })
        try {
            client.connect(endpoint)
            assertTrue("DoKit login did not finish within 10 seconds", completed.await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue(client.isLoginSuccess())
        } finally {
            client.destroy()
        }
    }
}
