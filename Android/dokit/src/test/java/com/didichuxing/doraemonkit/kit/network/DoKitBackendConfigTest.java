package com.didichuxing.doraemonkit.kit.network;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class DoKitBackendConfigTest {
    private DoKitBackendConfig.Options original;

    @Before public void saveConfiguration() {
        original = new DoKitBackendConfig.Options();
    }

    @After public void restoreConfiguration() {
        DoKitBackendConfig.configure(original);
    }

    private DoKitBackendConfig.Options emptyOptions(String value) throws Exception {
        DoKitBackendConfig.Options options = new DoKitBackendConfig.Options();
        for (Field field : DoKitBackendConfig.Options.class.getFields()) {
            if (field.getType() == String.class) field.set(options, value);
        }
        options.queryTimeoutMs = null;
        return options;
    }

    private void assertDisabled() {
        assertEquals("", DoKitBackendConfig.PLATFORM_BASE_URL);
        assertEquals("", DoKitBackendConfig.MOCK_BASE_URL);
        assertEquals("", DoKitBackendConfig.RESOURCE_BASE_URL);
        assertEquals("", DoKitBackendConfig.HYBRID_JS_URL);
        assertEquals("", DoKitBackendConfig.VCONSOLE_URL);
        assertEquals("", DoKitBackendConfig.WEB_SOCKET_URL);
        assertEquals("", DoKitBackendConfig.PROJECT_ID);
        assertEquals("", DoKitBackendConfig.SDK_TOKEN);
        assertEquals(8000L, DoKitBackendConfig.QUERY_TIMEOUT_MS);
        assertEquals("", DoKitBackendConfig.host(DoKitBackendConfig.PLATFORM_BASE_URL));
        assertEquals("", DoKitBackendConfig.endpoint(DoKitBackendConfig.PLATFORM_BASE_URL, "/uploadAppData"));
        assertFalse(DoKitBackendConfig.isPlatformUrl("https://business.example/api"));
    }

    @Test public void allFieldsAcceptNull() throws Exception {
        DoKitBackendConfig.configure(emptyOptions(null));
        assertDisabled();
    }

    @Test public void allStringsAcceptEmptyAndWhitespace() throws Exception {
        for (String value : new String[]{"", " \t\r\n "}) {
            DoKitBackendConfig.configure(emptyOptions(value));
            assertDisabled();
        }
    }

    @Test public void emptyFieldsDoNotHideOtherConfiguredBackends() throws Exception {
        DoKitBackendConfig.Options options = emptyOptions(null);
        options.mockBaseUrl = "https://mock.example:8443/dokit/";
        options.sdkToken = "test_token";
        DoKitBackendConfig.configure(options);
        assertEquals("https://mock.example:8443/dokit", DoKitBackendConfig.MOCK_BASE_URL);
        assertTrue(DoKitBackendConfig.isPlatformUrl("https://mock.example:8443/dokit/api/app/interface"));
        assertFalse(DoKitBackendConfig.isPlatformUrl("https://mock.example:8443/dokit-other"));
        assertFalse(DoKitBackendConfig.isPlatformUrl("https://mock.example/dokit"));
        assertFalse(DoKitBackendConfig.isPlatformUrl(null));
        assertFalse(DoKitBackendConfig.isPlatformUrl(""));
    }

    @Test public void explicitNullClearsPreviousConfiguration() throws Exception {
        DoKitBackendConfig.Options options = emptyOptions(null);
        options.platformBaseUrl = "https://backend.example";
        options.projectId = "test-project";
        options.sdkToken = "test-token";
        options.queryTimeoutMs = 12000L;
        DoKitBackendConfig.configure(options);
        DoKitBackendConfig.configure(emptyOptions(null));
        assertDisabled();
    }

    @Test public void unassignedFieldsRetainCurrentConfiguration() throws Exception {
        DoKitBackendConfig.Options options = emptyOptions(null);
        options.platformBaseUrl = "https://backend.example";
        options.webSocketUrl = "wss://backend.example/ws?pairing=test";
        options.queryTimeoutMs = 60000L;
        DoKitBackendConfig.configure(options);
        DoKitBackendConfig.configure(new DoKitBackendConfig.Options());
        assertEquals(options.platformBaseUrl, DoKitBackendConfig.PLATFORM_BASE_URL);
        assertEquals(options.webSocketUrl, DoKitBackendConfig.WEB_SOCKET_URL);
        assertEquals(60000L, DoKitBackendConfig.QUERY_TIMEOUT_MS);
    }

    @Test public void nonemptyInvalidUrlsStillFailWithoutPublishingPartialConfiguration() throws Exception {
        DoKitBackendConfig.configure(emptyOptions(null));
        for (String field : new String[]{"platformBaseUrl", "mockBaseUrl", "resourceBaseUrl",
                "hybridJsUrl", "vConsoleUrl", "webSocketUrl"}) {
            for (String invalid : new String[]{"not-a-url", "ftp://backend.example", "https://bad host", "https://user@backend.example"}) {
                DoKitBackendConfig.Options options = emptyOptions(null);
                options.projectId = "must-not-be-published";
                DoKitBackendConfig.Options.class.getField(field).set(options, invalid);
                assertRejected(options);
                assertDisabled();
            }
        }
    }

    @Test public void nonemptyInvalidTokenAndTimeoutStillFail() throws Exception {
        DoKitBackendConfig.Options options = emptyOptions(null);
        options.sdkToken = "token\r\nInjected: header";
        assertRejected(options);
        options.sdkToken = null;
        for (long timeout : new long[]{0, 99, 60001}) {
            options.queryTimeoutMs = timeout;
            assertRejected(options);
        }
        options.queryTimeoutMs = 100L;
        DoKitBackendConfig.configure(options);
        assertEquals(100L, DoKitBackendConfig.QUERY_TIMEOUT_MS);
    }

    @Test public void emptyHostAndEndpointNeverProduceRelativeBackendUrls() {
        for (String value : new String[]{null, "", " \t "}) {
            assertEquals("", DoKitBackendConfig.host(value));
            assertEquals("", DoKitBackendConfig.endpoint(value, "/api/app/interface"));
        }
        assertEquals("", DoKitBackendConfig.host("/api/app/interface"));
        assertEquals("backend.example", DoKitBackendConfig.host("https://backend.example/dokit"));
        assertEquals("https://backend.example/dokit/api", DoKitBackendConfig.endpoint("https://backend.example/dokit", "/api"));
    }

    private void assertRejected(DoKitBackendConfig.Options options) {
        try {
            DoKitBackendConfig.configure(options);
            fail("Invalid nonempty configuration must still be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }
}
