package com.hatsunama.captionaction.ui.home

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.view.View
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
    @After fun restoreAnimations() { scale(1f) }

    @Test fun ownerPause_cannotBeOverriddenByWindowVisibility() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = RefractBackgroundView(controller.get())
            controller.get().setContentView(view)
            controller.visible()
            assertTrue("test must attach the real view", view.isAttachedToWindow)
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

}
