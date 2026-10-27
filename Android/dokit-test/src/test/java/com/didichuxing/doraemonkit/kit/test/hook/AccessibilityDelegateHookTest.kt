package com.didichuxing.doraemonkit.kit.test.hook

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import com.didichuxing.doraemonkit.kit.test.DoKitTestManager
import com.didichuxing.doraemonkit.kit.test.TestMode
import com.didichuxing.doraemonkit.kit.test.event.ControlEvent
import com.didichuxing.doraemonkit.kit.test.event.ControlEventManager
import com.didichuxing.doraemonkit.kit.test.event.OnControlEventActionListener
import com.didichuxing.doraemonkit.kit.test.utils.ReflectHookUtil
import com.didichuxing.doraemonkit.util.ReflectUtils
import com.didichuxing.doraemonkit.util.Utils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityDelegateHookTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var content: FrameLayout
    private lateinit var hook: AccessibilityDelegateHook
    private val events = mutableListOf<Pair<View, AccessibilityEvent>>()
    private val dialogs = mutableListOf<Dialog>()

    @Before
    fun setUp() {
        activity = controller.setup().visible().get()
        content = FrameLayout(activity)
        activity.setContentView(content)
        hook = AccessibilityDelegateHook { view, event ->
            events.add(view to AccessibilityEvent.obtain(event))
        }
    }

    @After
    fun tearDown() {
        hook.stop()
        ReflectHookUtil.hookAccessibilityManager(activity, false)
        dialogs.forEach { it.dismiss() }
        controller.pause().stop().destroy()
        events.forEach { it.second.recycle() }
    }

    @Test
    fun recordsSupportedEventsWithTheirSourceAndPayload() {
        val input = EditText(activity)
        input.setText("recorded text")
        content.addView(input)
        hook.start()
        val types = listOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        types.forEach { type ->
            val event = AccessibilityEvent.obtain(type)
            event.beforeText = "before"
            event.fromIndex = 2
            event.toIndex = 5
            event.scrollX = 10
            event.scrollY = 20
            input.onInitializeAccessibilityEvent(event)
            val recorded = events.last().second
            assertEquals(event.fromIndex, recorded.fromIndex)
            assertEquals(event.toIndex, recorded.toIndex)
            assertEquals(event.scrollX, recorded.scrollX)
            assertEquals(event.scrollY, recorded.scrollY)
            event.recycle()
        }

        assertEquals(types, events.map { it.second.eventType })
        events.forEach { (view, event) ->
            assertSame(input, view)
            assertEquals("before", event.beforeText.toString())
            assertNotNull(event.className)
            assertEquals(activity.packageName, event.packageName.toString())
        }
    }

    @Test
    fun preservesOriginalDelegateAndRecordsAfterInitialization() {
        val view = Button(activity)
        val provider = object : AccessibilityNodeProvider() {}
        val original = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                super.onInitializeAccessibilityEvent(host, event)
                event.contentDescription = "original delegate"
            }

            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.contentDescription = "original node"
            }

            override fun getAccessibilityNodeProvider(host: View) = provider

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) = action == 42

            override fun addExtraDataToAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo, extraDataKey: String, arguments: Bundle?) {
                info.extras.putString(extraDataKey, "extra")
            }

            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent) = false
        }
        view.accessibilityDelegate = original
        content.addView(view)
        hook.start()
        val installed = view.accessibilityDelegate!!
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        view.onInitializeAccessibilityEvent(event)
        event.recycle()

        assertEquals("original delegate", events.single().second.contentDescription)
        assertSame(provider, view.accessibilityNodeProvider)
        assertTrue(view.performAccessibilityAction(42, null))
        assertFalse(installed.onRequestSendAccessibilityEvent(content, view, events.single().second))
        val info = AccessibilityNodeInfo.obtain()
        installed.onInitializeAccessibilityNodeInfo(view, info)
        installed.addExtraDataToAccessibilityNodeInfo(view, info, "key", null)
        assertEquals("original node", info.contentDescription)
        assertEquals("extra", info.extras.getString("key"))
        info.recycle()

        hook.stop()
        assertSame(original, view.accessibilityDelegate)
    }

    @Test
    fun hooksNewChildrenAndDialogWindows() {
        hook.start()
        val child = Button(activity)
        content.addView(child)
        content.viewTreeObserver.dispatchOnGlobalLayout()
        assertNotNull(child.accessibilityDelegate)

        val dialog = Dialog(activity)
        dialogs.add(dialog)
        val dialogButton = Button(activity)
        dialog.setContentView(dialogButton)
        dialog.show()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertNotNull(dialogButton.accessibilityDelegate)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        dialogButton.onInitializeAccessibilityEvent(event)
        event.recycle()
        assertSame(dialogButton, events.last().first)

        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertNull(dialogButton.accessibilityDelegate)
    }

    @Test
    fun repeatedStartDoesNotDuplicateAndStopRemovesCallbacks() {
        val view = Button(activity)
        content.addView(view)
        hook.start()
        val installed = view.accessibilityDelegate
        hook.start()
        assertSame(installed, view.accessibilityDelegate)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        view.onInitializeAccessibilityEvent(event)
        assertEquals(1, events.size)

        hook.stop()
        assertNull(view.accessibilityDelegate)
        installed!!.onInitializeAccessibilityEvent(view, event)
        assertEquals(1, events.size)
        val later = Button(activity)
        content.addView(later)
        content.viewTreeObserver.dispatchOnGlobalLayout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertNull(later.accessibilityDelegate)
        event.recycle()
    }

    @Test
    fun doesNotOverwriteDelegateChangedByApplicationOnStop() {
        val view = Button(activity)
        content.addView(view)
        hook.start()
        val replacement = View.AccessibilityDelegate()
        view.accessibilityDelegate = replacement
        hook.stop()
        assertSame(replacement, view.accessibilityDelegate)
    }

    @Test
    fun rewrappingApplicationDelegateDoesNotRecordTwice() {
        val view = Button(activity)
        content.addView(view)
        hook.start()
        val previous = view.accessibilityDelegate!!
        val replacement = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                previous.onInitializeAccessibilityEvent(host, event)
            }
        }
        view.accessibilityDelegate = replacement
        content.viewTreeObserver.dispatchOnGlobalLayout()
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        view.onInitializeAccessibilityEvent(event)
        event.recycle()
        assertEquals(1, events.size)

        hook.stop()
        assertSame(replacement, view.accessibilityDelegate)
    }

    @Test
    fun recordingFailureDoesNotInterruptViewInitialization() {
        hook = AccessibilityDelegateHook { _, _ -> error("recorder unavailable") }
        val view = Button(activity)
        content.addView(view)
        hook.start()
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        view.onInitializeAccessibilityEvent(event)
        assertNotNull(event.className)
        event.recycle()
    }

    @Test
    fun startsAndStopsOnMainThreadAndCanRestart() {
        val view = Button(activity)
        content.addView(view)
        Thread { hook.start() }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(view.accessibilityDelegate)
        Thread { hook.stop() }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(view.accessibilityDelegate)
        hook.start()
        assertNotNull(view.accessibilityDelegate)
    }

    @Test
    fun hostModeProducesControlEventsAndClientModeStopsRecording() {
        Utils.init(activity.application)
        val view = Button(activity).apply { setOnClickListener { } }
        content.addView(view)
        val controlEvents = mutableListOf<ControlEvent>()
        val listener = object : OnControlEventActionListener {
            override fun onControlEventAction(activity: Activity?, view: View?, event: ControlEvent) {
                controlEvents.add(event)
            }
        }
        ControlEventManager.addOnControlEventActionListener(listener)
        try {
            DoKitTestManager.startTest(TestMode.HOST)
            val manager = activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            shadowOf(manager).setEnabled(true)
            view.performClick()
            assertTrue("Click must reach the recording pipeline", controlEvents.isNotEmpty())
            val count = controlEvents.size
            DoKitTestManager.startTest(TestMode.CLIENT)
            view.performClick()
            assertEquals(count, controlEvents.size)
        } finally {
            DoKitTestManager.closeTest()
            ControlEventManager.removeOnControlEventActionListener(listener)
        }
    }

    @Test
    fun restoresAccessibilityManagerStateAfterRepeatedEnable() {
        val manager = activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        listOf(false, true).forEach { initiallyEnabled ->
            // Robolectric 的 isEnabled() 使用独立的 shadow 状态；这里验证框架字段的保存和恢复。
            ReflectUtils.reflect(manager).field("mIsEnabled", initiallyEnabled)
            ReflectHookUtil.hookAccessibilityManager(activity, true)
            ReflectHookUtil.hookAccessibilityManager(activity, true)
            assertTrue(ReflectUtils.reflect(manager).field("mIsEnabled").get<Boolean>())
            ReflectHookUtil.hookAccessibilityManager(activity, false)
            assertEquals(initiallyEnabled, ReflectUtils.reflect(manager).field("mIsEnabled").get<Boolean>())
        }
    }
}
