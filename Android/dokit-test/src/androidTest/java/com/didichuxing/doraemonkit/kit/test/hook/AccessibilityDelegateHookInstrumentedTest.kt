package com.didichuxing.doraemonkit.kit.test.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.didichuxing.doraemonkit.kit.test.DoKitTestManager
import com.didichuxing.doraemonkit.kit.test.TestMode
import com.didichuxing.doraemonkit.kit.test.event.ControlEvent
import com.didichuxing.doraemonkit.kit.test.event.ControlEventManager
import com.didichuxing.doraemonkit.kit.test.event.OnControlEventActionListener
import com.didichuxing.doraemonkit.kit.test.utils.ReflectHookUtil
import com.didichuxing.doraemonkit.util.Utils
import org.junit.Assert.*
import org.junit.Test

class AccessibilityRecordingTestActivity : Activity() {
    lateinit var button: Button
    lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        button = Button(this).apply {
            text = "Record click"
            setOnClickListener { }
        }
        input = EditText(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button)
            addView(input)
        })
    }
}

class AccessibilityDelegateHookInstrumentedTest {
    @Test
    fun collectsRealViewEventsAndRestoresState() = withActivity { activity ->
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val wasEnabled = manager.isEnabled
        val events = mutableListOf<Pair<View, Int>>()
        val original = activity.button.accessibilityDelegate
        val hook = AccessibilityDelegateHook { view, event -> events.add(view to event.eventType) }
        try {
            ReflectHookUtil.hookAccessibilityManager(activity, true)
            assertTrue("Accessibility event generation must be enabled", manager.isEnabled)
            hook.start()
            activity.button.performClick()
            activity.input.requestFocus()
            activity.input.setText("recorded text")
            assertTrue(events.contains(activity.button to AccessibilityEvent.TYPE_VIEW_CLICKED))
            assertTrue(events.contains(activity.input to AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
        } finally {
            hook.stop()
            ReflectHookUtil.hookAccessibilityManager(activity, false)
        }
        assertSame(original, activity.button.accessibilityDelegate)
        assertEquals(wasEnabled, manager.isEnabled)
    }

    @Test
    fun hostModeProducesControlEventsAndClientModeStopsRecording() = withActivity { activity ->
        Utils.init(activity.application)
        val events = mutableListOf<ControlEvent>()
        val listener = object : OnControlEventActionListener {
            override fun onControlEventAction(activity: Activity?, view: View?, event: ControlEvent) {
                events.add(event)
            }
        }
        ControlEventManager.addOnControlEventActionListener(listener)
        try {
            DoKitTestManager.startTest(TestMode.HOST)
            activity.button.performClick()
            assertTrue("A real click must reach the recording pipeline", events.isNotEmpty())
            val count = events.size
            DoKitTestManager.startTest(TestMode.CLIENT)
            activity.button.performClick()
            assertEquals(count, events.size)
        } finally {
            DoKitTestManager.closeTest()
            ControlEventManager.removeOnControlEventActionListener(listener)
        }
    }

    private fun withActivity(block: (AccessibilityRecordingTestActivity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, AccessibilityRecordingTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as AccessibilityRecordingTestActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { block(activity) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
