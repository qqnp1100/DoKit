package com.didichuxing.dokit.migration.consumer;

import android.app.Application;
import com.didichuxing.doraemonkit.DoKit;

/** Compiles against the published no-op API in a standalone Android consumer. */
public final class ConsumerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        new DoKit.Builder(this).disableUpload().build();
        DoKit.show();
        DoKit.hide();
    }
}
