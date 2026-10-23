package com.didichuxing.dokit.migration.fullconsumer;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import com.didichuxing.doraemonkit.DoKit;
import com.didichuxing.doraemonkit.kit.AbstractKit;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

/** Public API and service-metadata smoke test, without installing native hooks. */
public final class ConsumerActivity extends Activity {
    private DoKit.Builder builder;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        // build() triggers toolkit startup and onAppInit/native features. This
        // fixture checks artifact/API consumption and discovery independently.
        builder = new DoKit.Builder(getApplication())
                .productId("migration-maven-consumer")
                .customKits(Collections.<AbstractKit>emptyList());
        Set<String> providers = new HashSet<>();
        for (AbstractKit provider : ServiceLoader.load(AbstractKit.class, getClassLoader())) {
            providers.add(provider.getClass().getName());
        }
        Set<String> expected = new HashSet<>(Arrays.asList(
                "com.didichuxing.doraemonkit.kit.network.NetworkKit",
                "com.didichuxing.doraemonkit.kit.filemanager.FileTransferKit",
                "com.didichuxing.doraemonkit.kit.mc.MultiControlKit",
                "com.didichuxing.doraemonkit.kit.autotest.AutoTestControlKit",
                "com.didichuxing.doraemonkit.gps_mock.gpsmock.GpsMockKit",
                "com.kronos.dokit.pthread.PThreadKit"));
        if (!providers.containsAll(expected)) {
            expected.removeAll(providers);
            throw new AssertionError("Published service providers missing: " + expected);
        }
        try {
            if (!getClassLoader().getResources("META-INF/services/" + AbstractKit.class.getName()).hasMoreElements()) {
                throw new AssertionError("Published service descriptor is missing");
            }
        } catch (IOException error) {
            throw new AssertionError("Cannot read published service descriptor", error);
        }
        TextView result = new TextView(this);
        result.setText("Full Maven consumer passed: " + providers.size() + " service providers");
        setContentView(result);
        Log.i("DoKitMigration", "FULL_MAVEN_VERIFIED: Builder API and " + providers.size() + " service providers");
    }
}
