package com.hatsunama.captionaction.ui.home

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.view.View
import android.os.Looper
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RefractLifecycleTest {
    private fun scale(value: Float) {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, value)
    }
    // API28's headless window remains GONE even after ActivityController.visible().
    // Set only the framework window boundary, not the renderer/host policy state.
    private fun visibleWindow(view: View) {
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
    }
    @After fun restoreAnimations() { scale(1f) }

    @Test fun ownerPause_cannotBeOverriddenByWindowVisibility() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = RefractBackgroundView(controller.get())
            controller.get().setContentView(view)
            controller.visible()
            assertTrue("test must attach the real view", view.isAttachedToWindow)
            visibleWindow(view)
            view.setAnimating(true)
            view.setAnimating(false)
            view.visibility = View.GONE
            view.visibility = View.VISIBLE
            view.viewTreeObserver.dispatchOnPreDraw()
            assertFalse("visibility must not override owner pause", view.isAnimating())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun disabledSystemAnimations_keepAStillFrame() {
        scale(0f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = RefractBackgroundView(controller.get())
            controller.get().setContentView(view)
            controller.visible()
            assertTrue("test must attach the real view", view.isAttachedToWindow)
            visibleWindow(view)
            view.setAnimating(true)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertFalse("disabled animations must not queue motion", view.isAnimating())
            assertEquals(View.VISIBLE, view.visibility)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun activeOwner_resumesAfterHideAndDetach_withoutLosingIntent() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = RefractBackgroundView(controller.get())
            controller.get().setContentView(view)
            controller.visible().windowFocusChanged(true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            visibleWindow(view)
            view.setAnimating(true)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertTrue("active visible view must animate; attached=${view.isAttachedToWindow}, shown=${view.isShown}, window=${view.windowVisibility}", view.isAnimating())
            repeat(5) { view.setAnimating(true) }
            view.visibility = View.INVISIBLE
            view.viewTreeObserver.dispatchOnPreDraw()
            assertFalse(view.isAnimating())
            view.visibility = View.VISIBLE
            view.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(view.isAnimating())
            val parent = view.parent as android.view.ViewGroup
            parent.removeView(view)
            assertFalse(view.isAnimating())
            parent.addView(view)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(view.isAnimating())
            view.setAnimating(false)
            assertFalse(view.isAnimating())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun disabledMotionBeforeFirstSize_cannotBeRearmedByCanonicalLayout() {
        assertFirstLayoutRespectsPolicy(disabledMotion = true)
    }

    @Test fun ownerPauseBeforeFirstSize_cannotBeRearmedByCanonicalLayout() {
        assertFirstLayoutRespectsPolicy(disabledMotion = false)
    }

    private fun assertFirstLayoutRespectsPolicy(disabledMotion: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val view = RefractBackgroundView(controller.get())
            if (disabledMotion) scale(0f)
            controller.get().setContentView(view)
            visibleWindow(view)
            view.setAnimating(disabledMotion)
            assertTrue("fixture must attach before first layout", view.isAttachedToWindow)
            view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 640, 320)
            // Do not dispatch pre-draw: layout itself must not leave a queued loop.
            assertFalse("first layout must preserve disabled/paused policy", view.isAnimating())
            val child = view.getChildAt(0)
            val queued = child.javaClass.getDeclaredField("choreographerAttached").apply { isAccessible = true }
            assertFalse("no canonical frame callback may remain queued", queued.getBoolean(child))
        } finally { controller.pause().stop().destroy() }
    }

}
