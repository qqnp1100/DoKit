# Keep the call sites so the published no-op API is verified by R8.
-keep class com.didichuxing.dokit.migration.consumer.ConsumerApp { *; }
-keep class com.didichuxing.dokit.migration.consumer.ConsumerActivity { *; }
