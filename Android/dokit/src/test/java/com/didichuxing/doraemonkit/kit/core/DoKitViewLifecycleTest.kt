package com.didichuxing.doraemonkit.kit.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.didichuxing.doraemonkit.DoKitEnv
import com.didichuxing.doraemonkit.kit.colorpick.ColorPickManager
import com.didichuxing.doraemonkit.kit.colorpick.ColorPickerDoKitView
import com.didichuxing.doraemonkit.kit.colorpick.ImageCapture
import com.didichuxing.doraemonkit.util.Utils
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DoKitViewLifecycleTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var content: FrameLayout
    private val views = mutableListOf<AbsDoKitView>()

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as Application
        DoKitEnv.app = app
        Utils.init(app)
        DoKitManager.IS_NORMAL_FLOAT_MODE = true
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity = controller.get()
        content = FrameLayout(activity)
        activity.setContentView(content)
    }

    @After
    fun tearDown() {
        content.removeAllViews()
        views.forEach { it.performDestroy() }
        ColorPickManager.getInstance().setColorPickerDokitView(null)
        controller.pause().stop().destroy()
        DoKitEnv.app = null
    }

    @Test
    fun removingViewFromLiveWindowReleasesLayoutListenerAndReattachAddsOnlyOne() {
        val observer = content.viewTreeObserver
        val baseline = listenerCount(observer)
        val view = createView()
        val root = view.doKitView!!
        // Access before attachment to exercise Android's floating-observer merge.
        val floatingObserver = root.viewTreeObserver
        content.addView(root)
        assertFalse(floatingObserver.isAlive)
        assertSame(observer, root.viewTreeObserver)
        assertEquals(baseline + 1, listenerCount(observer))

        repeat(3) {
            content.removeView(root)
            assertTrue(observer.isAlive)
            assertEquals(baseline, listenerCount(observer))
            content.addView(root)
            assertEquals(baseline + 1, listenerCount(observer))
        }
        content.removeView(root)
        view.performDestroy()
        assertEquals(baseline, listenerCount(observer))
    }

    @Test
    fun destroyWhileAttachedRemovesOnlyItsOwnListener() {
        var appLayouts = 0
        val observer = content.viewTreeObserver
        val appListener = ViewTreeObserver.OnGlobalLayoutListener { appLayouts++ }
        observer.addOnGlobalLayoutListener(appListener)
        val baseline = listenerCount(observer)
        val view = createView()
        content.addView(view.doKitView)

        view.performDestroy()
        assertEquals(baseline, listenerCount(observer))
        observer.dispatchOnGlobalLayout()
        assertEquals(1, appLayouts)
        observer.removeOnGlobalLayoutListener(appListener)
    }

    @Test
    fun destroyBeforeAttachmentCannotRegisterListenerLater() {
        val observer = content.viewTreeObserver
        val baseline = listenerCount(observer)
        val view = createView()
        val root = view.doKitView!!
        view.performDestroy()

        content.addView(root)
        assertEquals(baseline, listenerCount(observer))
    }

    @Test
    fun destroyCancelsQueuedCallbacksAndReleasesChildView() {
        val view = createView()
        var callbacks = 0
        view.post(Runnable { callbacks++ })
        view.postDelayed(Runnable { callbacks++ }, TimeUnit.HOURS.toMillis(1))

        view.performDestroy()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.HOURS)
        assertEquals(0, callbacks)
        assertNull(ReflectionHelpers.getField<View?>(view, "mChildView"))
        assertNull(view.doKitView)
        assertFalse(view.doKitViewScope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun detachAllDestroysEveryViewAndClearsActivityReferences() {
        val manager = NormalDoKitViewManager()
        manager.attach(DoKitIntent(TestDoKitView::class.java, activity, tag = "first"))
        manager.attach(DoKitIntent(TestDoKitView::class.java, activity, tag = "second"))
        val attachedViews = manager.getDoKitViews(activity).values.map { it as TestDoKitView }
        views.addAll(attachedViews)
        assertEquals(2, attachedViews.size)

        manager.detachAll()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

        attachedViews.forEach {
            assertEquals(1, it.destroyCalls)
            assertEquals(0, it.resumeCalls)
            assertNull(it.doKitView)
            assertFalse(it.doKitViewScope.coroutineContext[Job]!!.isActive)
        }
        assertTrue(manager.getDoKitViews(activity).isEmpty())
        assertTrue(ReflectionHelpers.getField<Map<*, *>>(manager, "activityDoKitViewMap").isEmpty())
    }

    @Test
    fun detachByTagReleasesListenerAndCancelsDelayedResume() {
        val manager = NormalDoKitViewManager()
        val observer = content.viewTreeObserver
        val baseline = listenerCount(observer)
        manager.attach(DoKitIntent(TestDoKitView::class.java, activity))
        val view = manager.getDoKitViews(activity).values.single() as TestDoKitView
        views.add(view)

        manager.detach(view.tag)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

        assertEquals(baseline, listenerCount(observer))
        assertEquals(1, view.destroyCalls)
        assertEquals(0, view.resumeCalls)
        assertTrue(manager.getDoKitViews(activity).isEmpty())
    }

    @Test
    fun repeatedDestroyCallsRunCleanupOnce() {
        val view = createView()
        content.addView(view.doKitView)

        repeat(3) { view.performDestroy() }

        assertEquals(1, view.destroyCalls)
        assertFalse(view.doKitViewScope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun failedSystemViewCreationDoesNotUnregisterAnUnregisteredReceiver() {
        DoKitManager.IS_NORMAL_FLOAT_MODE = false
        val view = object : TestDoKitView() {
            override fun onCreateView(context: Context, rootView: FrameLayout): View {
                throw IllegalStateException("Creation failed before receiver registration")
            }
        }
        views.add(view)
        view.performCreate(activity)

        view.performDestroy()

        assertEquals(1, view.destroyCalls)
        assertNull(view.doKitView)
        assertFalse(view.doKitViewScope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun activityDestroyReleasesWindowListenersAndManagerEntry() {
        val manager = NormalDoKitViewManager()
        val observer = content.viewTreeObserver
        val baseline = listenerCount(observer)
        manager.attach(DoKitIntent(TestDoKitView::class.java, activity))
        val view = manager.getDoKitViews(activity).values.single() as TestDoKitView
        views.add(view)

        manager.onActivityDestroyed(activity)

        assertEquals(baseline, listenerCount(observer))
        assertEquals(1, view.destroyCalls)
        assertTrue(ReflectionHelpers.getField<Map<*, *>>(manager, "activityDoKitViewMap").isEmpty())
    }

    @Test
    fun colorPickerDestroyReleasesSingletonWithoutClearingNewerPicker() {
        val oldPicker = pickerWithoutScreenCapture()
        val newPicker = pickerWithoutScreenCapture()
        val manager = ColorPickManager.getInstance()
        manager.setColorPickerDokitView(newPicker)

        oldPicker.performDestroy()
        assertSame(newPicker, manager.colorPickerDokitView)
        newPicker.performDestroy()
        assertNull(manager.colorPickerDokitView)
    }

    private fun createView(): TestDoKitView = TestDoKitView().also {
        views.add(it)
        it.setActivity(activity)
        it.performCreate(activity)
        assertNotNull(it.doKitView)
        assertNotNull(it.normalLayoutParams)
    }

    private fun pickerWithoutScreenCapture(): ColorPickerDoKitView = ColorPickerDoKitView().also {
        views.add(it)
        // Capture permissions and the screen service are unrelated to singleton ownership.
        ReflectionHelpers.setField(it, "mImageCapture", ImageCapture())
    }

    private fun listenerCount(observer: ViewTreeObserver): Int {
        val listeners = ReflectionHelpers.getField<Any?>(observer, "mOnGlobalLayoutListeners")
            ?: return 0
        return ReflectionHelpers.callInstanceMethod(listeners, "size")
    }

    open class TestDoKitView : AbsDoKitView() {
        var destroyCalls = 0
        var resumeCalls = 0

        override fun onCreate(context: Context) = Unit
        override fun onCreateView(context: Context, rootView: FrameLayout): View = View(context)
        override fun onViewCreated(rootView: FrameLayout) = Unit
        override fun initDokitViewLayoutParams(params: DoKitViewLayoutParams) {
            params.width = 40
            params.height = 40
        }

        override fun onResume() {
            super.onResume()
            resumeCalls++
        }

        override fun onDestroy() {
            super.onDestroy()
            destroyCalls++
        }
    }
}
