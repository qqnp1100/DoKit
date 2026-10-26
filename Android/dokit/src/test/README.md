# Connection tests

`rtk proxy ./gradlew :dokit:testDebugUnitTest` runs the portable JVM tests. Packet
tests construct explicit `TextPackage` values and check the wire header and binary
roundtrip. WebSocket session tests use MockWebServer 4.7.0 on loopback, assert text
and binary exchange, clean closure and failed handshakes, and bound every wait.
No private server address is used by the JVM suite.

The Demo also has device regression tests for the **plugin-instrumented** OkHttp
constructor. Ordinary library JVM tests do not inject `OkHttpHook` and cannot
detect recursive interceptor construction. Run the following on a connected
device to check startup, business-client instrumentation, unhooked SDK transport,
cloned clients, and cleanup after a failed internal build (no network requests):

```sh
rtk proxy ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.didichuxing.doraemondemo.OkHttpHookInstrumentedTest
```

The high-level `WebSocketClient` performs Android device/package lookup and uses
SharedPreferences during login. Its existing connection test lives in
`src/androidTest` and is skipped unless an explicit integration endpoint is given.
On a connected Android device, run it against a compatible DoKit login server:

```sh
rtk proxy ./gradlew :dokit:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.didichuxing.doraemonkit.kit.connect.WebSocketClientTest \
  -Pandroid.testInstrumentationRunnerArguments.dokit.websocket.endpoint=ws://HOST:PORT/PATH
```

The test waits at most 10 seconds for login, has a 15-second JUnit timeout, and
destroys the client in `finally`. This manual device check is separate from default
JVM CI; absence of an endpoint is reported as a skipped test.
