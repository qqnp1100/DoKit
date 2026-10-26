package com.didichuxing.doraemondemo;

import androidx.test.runner.AndroidJUnit4;

import com.didichuxing.doraemonkit.DoKit;
import com.didichuxing.doraemonkit.aop.OkHttpHook;
import com.didichuxing.doraemonkit.kit.network.okhttp.interceptor.AbsDoKitInterceptor;
import com.didichuxing.doraemonkit.kit.network.okhttp.interceptor.DokitMockInterceptor;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

/** Runs against the Demo's real plugin-instrumented OkHttp, after Application startup. */
@RunWith(AndroidJUnit4.class)
public class OkHttpHookInstrumentedTest {
    @Test(timeout = 10000) public void businessClientsAndClonesKeepExactlyOneMockInterceptor() throws Exception {
        assertTrue("The Demo must initialize DoKit before creating clients", DoKit.isInit());
        OkHttpClient client = new OkHttpClient.Builder().build();
        assertBusinessClient(client);
        assertBusinessClient(client.newBuilder().build());
        assertBusinessClient(new OkHttpClient.Builder().build());
    }

    @Test(timeout = 10000) public void manuallyCreatedMockInterceptorUsesUnhookedTransport() throws Exception {
        assertInternalClient(platformClient(new DokitMockInterceptor()));
        assertInternalClient(OkHttpHook.buildWithoutHook(new OkHttpClient.Builder()));
        // Suppression must not leak to the next ordinary client on this thread.
        assertBusinessClient(new OkHttpClient.Builder().build());
    }

    @Test(timeout = 10000) public void failedInternalBuildRestoresHookForNextClient() throws Exception {
        OkHttpClient.Builder invalid = new OkHttpClient.Builder();
        invalid.interceptors().add(null);
        try {
            OkHttpHook.buildWithoutHook(invalid);
            fail("OkHttp must reject a null interceptor");
        } catch (IllegalStateException expected) {
            // Validation occurs inside build(), while the hook is suppressed.
        }
        assertBusinessClient(new OkHttpClient.Builder().build());
    }

    private void assertBusinessClient(OkHttpClient client) throws Exception {
        int count = 0;
        for (Interceptor interceptor : client.interceptors()) {
            if (interceptor instanceof DokitMockInterceptor) {
                count++;
                assertInternalClient(platformClient((DokitMockInterceptor) interceptor));
            }
        }
        assertEquals("Instrumentation must remain enabled for business clients", 1, count);
    }

    private OkHttpClient platformClient(DokitMockInterceptor interceptor) throws Exception {
        Field field = DokitMockInterceptor.class.getDeclaredField("platformClient");
        field.setAccessible(true);
        return (OkHttpClient) field.get(interceptor);
    }

    private void assertInternalClient(OkHttpClient client) {
        for (Interceptor interceptor : client.interceptors()) {
            assertFalse("SDK transport must not recursively inject DoKit", interceptor instanceof AbsDoKitInterceptor);
        }
        for (Interceptor interceptor : client.networkInterceptors()) {
            assertFalse(interceptor instanceof AbsDoKitInterceptor);
        }
    }
}
