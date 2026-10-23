# Runtime recorder JVM tests

This standalone build compiles the three Android-independent recorder sources and
their seven tests directly from `dokit/src`. It uses Kotlin 2.4.21, JVM 1.8
bytecode, JUnit 4.13.2, and the repository's Gradle 9.8.2 wrapper. It does not
require the Android SDK or configure the Android modules.

From `Android/`:

```sh
rtk proxy ./gradlew -p migration-tests/runtime-recorders clean test --stacktrace
```

Results are written to `build/reports/tests/test/index.html` in this fixture.
The tests cover recursive calls, overloads, receiver identity, concurrent timers
and invocation trees, bounded recursion depth, and the threshold boundary.
