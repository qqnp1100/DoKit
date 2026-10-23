package com.didichuxing.doraemondemo

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.didichuxing.doraemonkit.DoKit

/** A Release launcher that only depends on the public no-op API. */
class MainDoKitActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            text = "DoKit Release (no-op)\nMain icon visible: ${DoKit.isMainIconShow}"
        })
    }
}
