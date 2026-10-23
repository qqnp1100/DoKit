package com.didichuxing.doraemonkit.kit.connect;

import com.didichuxing.doraemonkit.kit.connect.ws.ConnectStatus;
import com.didichuxing.doraemonkit.kit.connect.ws.OkHttpWebSocketSession;
import com.didichuxing.doraemonkit.kit.connect.ws.OnWebSocketStatusChangeListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;

import static org.junit.Assert.*;

/** Real session I/O against a loopback server; no Android state or external service. */
public class WebSocketSessionTest {
    private MockWebServer server;
    private OkHttpClient client;
    private OkHttpWebSocketSession session;

    @Before
    public void init() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient.Builder()
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build();
        session = new OkHttpWebSocketSession(client);
    }

    @After
    public void cleanup() throws Exception {
        if (session != null) session.cancel();
        if (client != null) {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
        if (server != null) server.shutdown();
    }

    @Test(timeout = 15000)
    public void exchangesTextAndBinaryPacketsAndCloses() throws Exception {
        BlockingQueue<String> serverTexts = new LinkedBlockingQueue<>();
        BlockingQueue<ByteString> serverBytes = new LinkedBlockingQueue<>();
        BlockingQueue<String> clientTexts = new LinkedBlockingQueue<>();
        BlockingQueue<ByteString> clientBytes = new LinkedBlockingQueue<>();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) {
                serverTexts.add(text);
                socket.send(text + "-echo");
            }
            @Override public void onMessage(WebSocket socket, ByteString bytes) {
                serverBytes.add(bytes);
                socket.send(bytes);
            }
            @Override public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }
        }));
        session.setOnWebSocketMessageListener((socket, text) -> clientTexts.add(text));
        session.setOnWebSocketBytesMessageListener((socket, bytes) -> clientBytes.add(bytes));
        session.setOnWebSocketStatusChangeListener(new OnWebSocketStatusChangeListener() {
            @Override public void onOpen(OkHttpWebSocketSession socket, String response) { opened.countDown(); }
            @Override public void onClosed(OkHttpWebSocketSession socket, int code, String reason) { closed.countDown(); }
            @Override public void onClosing(OkHttpWebSocketSession socket, int code, String reason) { }
            @Override public void onFailure(OkHttpWebSocketSession socket, Throwable error, String response) {
                failure.set(error);
                opened.countDown();
                closed.countDown();
            }
        });
        assertEquals(ConnectStatus.OFF_LINE, session.getConnectStatus());
        assertFalse(session.send("before-connect"));
        session.connect(server.url("/socket").toString());
        assertTrue("WebSocket did not open", opened.await(3, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(ConnectStatus.CONNECT, session.getConnectStatus());
        assertTrue(session.send("hello"));
        assertEquals("hello", serverTexts.poll(3, TimeUnit.SECONDS));
        assertEquals("hello-echo", clientTexts.poll(3, TimeUnit.SECONDS));

        ByteString packet = new ByteParserTest().getByteString();
        assertTrue(session.send(packet));
        assertEquals(packet, serverBytes.poll(3, TimeUnit.SECONDS));
        assertEquals(packet, clientBytes.poll(3, TimeUnit.SECONDS));
        assertTrue(session.close(1000, "complete"));
        assertTrue("WebSocket did not close", closed.await(3, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(ConnectStatus.OFF_LINE, session.getConnectStatus());
    }

    @Test(timeout = 10000)
    public void reportsFailedHandshake() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        session.setOnWebSocketStatusChangeListener(new OnWebSocketStatusChangeListener() {
            @Override public void onOpen(OkHttpWebSocketSession socket, String response) { }
            @Override public void onClosed(OkHttpWebSocketSession socket, int code, String reason) { }
            @Override public void onClosing(OkHttpWebSocketSession socket, int code, String reason) { }
            @Override public void onFailure(OkHttpWebSocketSession socket, Throwable error, String response) {
                failure.set(error);
                failed.countDown();
            }
        });
        session.connect(server.url("/unavailable").toString());
        assertTrue("Missing failed-handshake callback", failed.await(3, TimeUnit.SECONDS));
        assertNotNull(failure.get());
        assertEquals(ConnectStatus.FAILED, session.getConnectStatus());
    }
}
