package com.didichuxing.doraemondemo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.RequestFuture;
import com.didichuxing.doraemonkit.aop.OkHttpHook;
import com.didichuxing.doraemonkit.kit.connect.ws.WebSocketClient;
import com.didichuxing.doraemonkit.kit.core.DoKitManager;
import com.didichuxing.doraemonkit.kit.filemanager.HttpServer;
import com.didichuxing.doraemonkit.kit.health.AppHealthInfoUtil;
import com.didichuxing.doraemonkit.kit.health.UploadAppHealthCallback;
import com.didichuxing.doraemonkit.kit.network.DoKitBackendConfig;
import com.didichuxing.doraemonkit.kit.network.NetworkManager;
import com.didichuxing.doraemonkit.kit.network.bean.MockApiResponseBean;
import com.didichuxing.doraemonkit.kit.network.room_db.DokitDbManager;
import com.didichuxing.doraemonkit.kit.network.room_db.MockInterceptApiBean;
import com.didichuxing.doraemonkit.kit.performance.PerformanceDataManager;
import com.didichuxing.doraemonkit.util.GsonUtils;
import com.didichuxing.doraemonkit.volley.VolleyManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in tests against a real private backend. No credentials are stored in this source. */
@RunWith(AndroidJUnit4.class)
public class BackendIntegrationTest {
    private static JSONObject fixture;
    private static Activity activity;
    private static final JSONObject results = new JSONObject();

    @BeforeClass public static void startDemo() throws Exception {
        assumeTrue("Pass -e dokitBackendE2e true and provision files/dokit-sdk-e2e.json",
                "true".equals(InstrumentationRegistry.getArguments().getString("dokitBackendE2e")));
        File file = new File(InstrumentationRegistry.getTargetContext().getFilesDir(), "dokit-sdk-e2e.json");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0, count;
            while ((count = stream.read(bytes, offset, bytes.length - offset)) > 0) offset += count;
            fixture = new JSONObject(new String(bytes, "UTF-8"));
        }
        assertEquals(DoKitBackendConfig.PROJECT_ID, DoKitManager.PRODUCT_ID);
    }

    @Before public void launchActivityForTest() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(InstrumentationRegistry.getTargetContext(), MainDoKitActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = instrumentation.startActivitySync(intent);
        instrumentation.runOnMainSync(() -> activity.getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED));
        instrumentation.waitForIdleSync();
    }

    @Test public void sdkListUsesVolleyAuthenticationAndParsesPublishedScenes() throws Exception {
        JSONObject response = volley(com.android.volley.Request.Method.GET,
                NetworkManager.MOCK_DOMAIN + "/api/app/interface?projectId=" + DoKitManager.PRODUCT_ID,
                null);
        assertEquals(200, response.getInt("code"));
        MockApiResponseBean parsed = GsonUtils.fromJson(response.toString(), MockApiResponseBean.class);
        assertNotNull(parsed.getData().getDatalist());
        JSONArray interfaces = response.getJSONObject("data").getJSONArray("datalist");
        boolean found = false;
        for (int i = 0; i < interfaces.length(); i++) {
            JSONObject item = interfaces.getJSONObject(i);
            if (fixture.getString("interfaceId").equals(item.getString("id"))) {
                assertTrue(item.getJSONArray("sceneList").length() >= 2);
                found = true;
            }
        }
        assertTrue("The phone must see the published backend fixture", found);
        record("sdkList", response.getJSONObject("data").getInt("total"));
    }

    @Test public void mockPreservesBodyStatusAndUploadsTemplateViaSdkVolley() throws Exception {
        String success = mock(fixture.getString("successSceneId"), 200, fixture.getString("successBody"));
        mock(fixture.getString("errorSceneId"), 503, fixture.getString("errorBody"));
        JSONObject body = new JSONObject().put("projectId", DoKitManager.PRODUCT_ID)
                .put("id", fixture.getString("interfaceId")).put("tempData", success);
        JSONObject upload = volley(com.android.volley.Request.Method.PATCH,
                NetworkManager.MOCK_DOMAIN + "/api/app/interface", body);
        assertEquals(200, upload.getInt("code"));
        record("templateId", upload.getJSONObject("data").getString("id"));
        record("mockStatuses", new JSONArray().put(200).put(503));
    }

    @Test public void backendRejectsMissingAndInvalidTokens() throws Exception {
        OkHttpClient client = OkHttpHook.buildWithoutHook(new OkHttpClient.Builder());
        for (String token : new String[] {"", "invalid-e2e-token"}) {
            Request.Builder request = new Request.Builder().url(NetworkManager.MOCK_DOMAIN + "/api/app/interface");
            if (!token.isEmpty()) request.header("X-DoKit-Token", token);
            try (Response response = client.newCall(request.build()).execute()) {
                assertEquals(401, response.code());
            }
        }
        record("authentication", "missing and invalid tokens rejected with 401");
    }

    @Test public void realHealthSamplesUploadThroughSdk() throws Exception {
        AppHealthInfoUtil health = AppHealthInfoUtil.getInstance();
        Method setBase = AppHealthInfoUtil.class.getDeclaredMethod("setBaseInfo", String.class, String.class);
        setBase.setAccessible(true);
        setBase.invoke(health, fixture.getString("runName"), "SDK device integration");
        boolean wasRunning = DoKitManager.APP_HEALTH_RUNNING;
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch complete = new CountDownLatch(1);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                DoKitManager.APP_HEALTH_RUNNING = true;
                health.start();
            });
            SystemClock.sleep(6500); // Collect actual device samples, not synthetic measurements.
            record("healthDiagnostics", new JSONObject()
                    .put("foreground", com.didichuxing.doraemonkit.util.AppUtils.isAppForeground())
                    .put("topActivity", String.valueOf(com.didichuxing.doraemonkit.util.ActivityUtils.getTopActivity()))
                    .put("healthRunning", DoKitManager.APP_HEALTH_RUNNING)
                    .put("activityFinishing", activity.isFinishing())
                    .put("activityDestroyed", activity.isDestroyed())
                    .put("cpu", health.getLastPerformanceInfo(PerformanceDataManager.PERFORMANCE_TYPE_CPU) != null)
                    .put("memory", health.getLastPerformanceInfo(PerformanceDataManager.PERFORMANCE_TYPE_MEMORY) != null)
                    .put("fps", health.getLastPerformanceInfo(PerformanceDataManager.PERFORMANCE_TYPE_FPS) != null));
            assertNotNull("Health must contain actual memory samples before upload",
                    health.getLastPerformanceInfo(PerformanceDataManager.PERFORMANCE_TYPE_MEMORY));
            health.post(new UploadAppHealthCallback() {
                @Override public void onSuccess(String value) { result.set(value); complete.countDown(); }
                @Override public void onError(String value) { error.set(value); complete.countDown(); }
            });
            assertTrue("Health upload timed out", complete.await(20, TimeUnit.SECONDS));
            assertNull(error.get());
            JSONObject response = new JSONObject(result.get());
            assertEquals(200, response.getInt("code"));
            record("healthReportId", response.getJSONObject("data").getString("reportId"));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                health.stop();
                DoKitManager.APP_HEALTH_RUNNING = wasRunning;
            });
        }
    }

    @Test public void webSocketSdkPairsAndReconnects() throws Exception {
        WebSocketClient client = new WebSocketClient();
        AtomicReference<CountDownLatch> login = new AtomicReference<>(new CountDownLatch(1));
        client.addOnWebSocketLoginSuccessListener(() -> login.get().countDown());
        try {
            client.connect(fixture.getString("wsUrl"));
            assertTrue("WebSocket LOGIN timed out", login.get().await(15, TimeUnit.SECONDS));
            assertTrue(client.isLoginSuccess());
            client.close();
            login.set(new CountDownLatch(1));
            client.reConnect(fixture.getString("wsUrl"));
            assertTrue("WebSocket reconnect timed out", login.get().await(15, TimeUnit.SECONDS));
            assertTrue(client.isLoginSuccess());
            record("webSocket", "LOGIN and reconnect passed");
        } finally { client.destroy(); }
    }

    @Test public void fileServiceAllowsBackendToReadOnlyTheTestDirectory() throws Exception {
        File directory = new File(InstrumentationRegistry.getTargetContext().getFilesDir(), "dokit-sdk-e2e");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, "probe.txt"))) {
            stream.write(fixture.getString("runName").getBytes("UTF-8"));
        }
        File acknowledgement = new File(directory, "backend-verified.txt");
        if (acknowledgement.exists()) assertTrue(acknowledgement.delete());
        try (android.database.sqlite.SQLiteDatabase database = android.database.sqlite.SQLiteDatabase
                .openOrCreateDatabase(new File(directory, "probe.db"), null)) {
            database.execSQL("CREATE TABLE IF NOT EXISTS sdk_probe (id INTEGER PRIMARY KEY, run_name TEXT)");
            database.execSQL("INSERT OR REPLACE INTO sdk_probe VALUES (1, ?)",
                    new Object[] {fixture.getString("runName")});
        }
        HttpServer.INSTANCE.getServer().start(false);
        try {
            OkHttpClient client = OkHttpHook.buildWithoutHook(new OkHttpClient.Builder());
            try (Response response = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:9001/getDeviceInfo").build()).execute()) {
                assertEquals(200, response.code());
                assertEquals(200, new JSONObject(response.body().string()).getInt("code"));
            }
            record("fileService", "ready");
            // The host verifier pairs through the real backend while this test is alive.
            long deadline = SystemClock.elapsedRealtime() + 45000;
            while (!acknowledgement.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250);
            assertTrue("Host backend must verify file download and database query", acknowledgement.exists());
            record("fileBridge", "verified by host backend");
        } finally { HttpServer.INSTANCE.getServer().stop(100, 1000); }
    }

    @SuppressWarnings("unchecked") private String mock(String sceneId, int status, String expected) throws Exception {
        String path = fixture.getString("path");
        MockInterceptApiBean rule = new MockInterceptApiBean();
        rule.setId(fixture.getString("interfaceId")); rule.setPath(path);
        rule.setQuery("{}"); rule.setBody("{}"); rule.setMethod("GET");
        rule.setMockApiName("SDK E2E"); rule.setSelectedSceneId(sceneId); rule.setOpen(true);
        Map<String, List<MockInterceptApiBean>> rules = DokitDbManager.getInstance().getGlobalInterceptApiMaps();
        List<MockInterceptApiBean> previous = rules.put(path, Collections.singletonList(rule));
        try {
            // No real server exists here: the actual plugin-injected SDK interceptor must handle this.
            Request request = new Request.Builder().url("http://127.0.0.1:9" + path).build();
            try (Response response = new OkHttpClient.Builder().build().newCall(request).execute()) {
                assertEquals(status, response.code());
                assertNull(response.request().header("X-DoKit-Token"));
                String body = response.body().string();
                assertEquals(expected, body);
                return body;
            }
        } finally {
            if (previous == null) rules.remove(path); else rules.put(path, previous);
        }
    }

    private static JSONObject volley(int method, String url, JSONObject body) throws Exception {
        RequestFuture<JSONObject> future = RequestFuture.newFuture();
        JsonObjectRequest request = new JsonObjectRequest(method, url, body, future, future);
        request.setShouldCache(false);
        VolleyManager.INSTANCE.add(request);
        return future.get(20, TimeUnit.SECONDS);
    }

    private static synchronized void record(String key, Object value) throws Exception {
        results.put(key, value);
        try (FileOutputStream stream = new FileOutputStream(new File(
                InstrumentationRegistry.getTargetContext().getFilesDir(), "dokit-sdk-e2e-results.json"))) {
            stream.write(results.toString(2).getBytes("UTF-8"));
        }
    }
}
