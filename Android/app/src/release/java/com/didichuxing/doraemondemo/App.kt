package com.didichuxing.doraemondemo

import android.app.Application
import com.didichuxing.doraemonkit.DoKit

/** Release exercises the same public initializer against the no-op artifact. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DoKit.Builder(this)
            .disableUpload()
            .build()
    }
}
