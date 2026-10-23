package com.didichuxing.dokit.migration.consumer;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import com.didichuxing.doraemonkit.DoKit;

/** Device-visible assertions against the published no-op API after R8. */
public final class ConsumerActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        new DoKit.Builder(getApplication()).disableUpload().build();
        assertNoOp("before show");
        DoKit.show();
        assertNoOp("after show");
        DoKit.hide();
        assertNoOp("after hide");
        TextView result = new TextView(this);
        result.setText("Published DoKit no-op verification passed after R8");
        setContentView(result);
        Log.i("DoKitMigration", "NO_OP_VERIFIED: builder, show and hide remain no-op after R8");
    }

    private static void assertNoOp(String phase) {
        if (DoKit.isMainIconShow()) {
            throw new AssertionError("DoKit no-op reported a visible icon " + phase);
        }
    }
}
