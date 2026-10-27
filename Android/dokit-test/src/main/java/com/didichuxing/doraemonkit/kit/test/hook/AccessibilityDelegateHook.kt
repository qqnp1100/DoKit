package com.didichuxing.doraemonkit.kit.test.hook

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import com.didichuxing.doraemonkit.kit.test.event.monitor.AccessibilityEventMonitor
import java.util.WeakHashMap

/** Android 10 及以上通过公开的 View 代理采集事件，避免依赖 ART 方法 hook。 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class AccessibilityDelegateHook(
    private val onEvent: (View, AccessibilityEvent) -> Unit = AccessibilityEventMonitor::onAccessibilityEvent
) {
    private val handler = Handler(Looper.getMainLooper())
    private val delegates = WeakHashMap<View, RecordingDelegate>()
    private val roots = mutableMapOf<View, RootObserver>()
    private var enabled = false

    // 新的 Activity、Dialog、PopupWindow 不会触发已有窗口的布局回调。
    private val refreshTask = object : Runnable {
        override fun run() {
            if (!enabled) return
            refreshWindows()
            handler.postDelayed(this, 200L)
        }
    }

    fun start() = onMainThread {
        if (!enabled) {
            enabled = true
            refreshTask.run()
        }
    }

    fun stop() = onMainThread {
        enabled = false
        handler.removeCallbacks(refreshTask)
        roots.values.forEach { it.remove() }
        roots.clear()
        delegates.forEach { (view, delegate) -> restoreDelegate(view, delegate) }
        delegates.clear()
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post { action() }
        }
    }

    private fun refreshWindows() {
        val windowViews = WindowInspector.getGlobalWindowViews().toSet()
        val rootIterator = roots.iterator()
        while (rootIterator.hasNext()) {
            val (root, observer) = rootIterator.next()
            if (root !in windowViews) {
                observer.remove()
                rootIterator.remove()
            }
        }
        windowViews.forEach { root ->
            roots.getOrPut(root) { RootObserver(root) }
            installDelegates(root)
        }
        val delegateIterator = delegates.iterator()
        while (delegateIterator.hasNext()) {
            val (view, delegate) = delegateIterator.next()
            if (view.rootView !in windowViews) {
                restoreDelegate(view, delegate)
                delegateIterator.remove()
            }
        }
    }

    private fun installDelegates(view: View) {
        val current = view.accessibilityDelegate
        val installed = delegates[view]
        if (installed == null || current !== installed) {
            // 业务或 RecyclerView 可能在录制期间更换代理，旧代理只转发，不再采集。
            installed?.recording = false
            val delegate = RecordingDelegate(current)
            delegates[view] = delegate
            view.accessibilityDelegate = delegate
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                installDelegates(view.getChildAt(index))
            }
        }
    }

    private fun restoreDelegate(view: View, delegate: RecordingDelegate) {
        delegate.recording = false
        if (view.accessibilityDelegate === delegate) {
            view.accessibilityDelegate = delegate.original
        }
    }

    private inner class RootObserver(private val root: View) : ViewTreeObserver.OnGlobalLayoutListener {
        private val observer = root.viewTreeObserver

        init {
            observer.addOnGlobalLayoutListener(this)
        }

        override fun onGlobalLayout() {
            if (enabled) installDelegates(root)
        }

        fun remove() {
            if (observer.isAlive) {
                observer.removeOnGlobalLayoutListener(this)
            } else {
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        }
    }

    private inner class RecordingDelegate(val original: View.AccessibilityDelegate?) : View.AccessibilityDelegate() {
        private val delegate = original ?: View.AccessibilityDelegate()
        var recording = true

        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
            delegate.onInitializeAccessibilityEvent(host, event)
            if (enabled && recording) {
                try {
                    onEvent(host, event)
                } catch (e: Exception) {
                    // 采集失败不能中断应用正常的无障碍事件分发。
                    Log.e("AccessibilityEventHook", "Unable to record accessibility event", e)
                }
            }
        }

        override fun sendAccessibilityEvent(host: View, eventType: Int) =
            delegate.sendAccessibilityEvent(host, eventType)

        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) =
            delegate.sendAccessibilityEventUnchecked(host, event)

        override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean =
            delegate.dispatchPopulateAccessibilityEvent(host, event)

        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) =
            delegate.onPopulateAccessibilityEvent(host, event)

        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) =
            delegate.onInitializeAccessibilityNodeInfo(host, info)

        override fun addExtraDataToAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo, extraDataKey: String, arguments: Bundle?) =
            delegate.addExtraDataToAccessibilityNodeInfo(host, info, extraDataKey, arguments)

        override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean =
            delegate.onRequestSendAccessibilityEvent(host, child, event)

        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider? =
            delegate.getAccessibilityNodeProvider(host)

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
            delegate.performAccessibilityAction(host, action, args)
    }
}
