package com.hatsunama.captionaction.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.hatsunama.refract.RefractBackgroundView as CanonicalRefractView

/** Lifecycle/accessibility host around the unchanged canonical Hatsu renderer. */
class RefractBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private var renderer: CanonicalRefractView? = null
    private var ownerActive = false
    private var observing = false
    private val syncAfterVisibility = Runnable { syncMotion() }
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        // Upstream visibility callbacks may re-arm its loop. Apply host policy
        // after those callbacks and before drawing, without altering simulation.
        syncMotion()
        true
    }
    private val motionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            syncMotion()
            invalidate()
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        renderer = CanonicalRefractView(context).also { child ->
            // INVISIBLE still participates in layout, but upstream callbacks cannot
            // self-enable motion while the host draws the frozen frame explicitly.
            child.visibility = INVISIBLE
            child.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { syncMotion() }
                override fun onViewDetachedFromWindow(v: View) { child.setAnimating(false) }
            })
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    /** Owner intent is not overwritten by a window/visibility callback. */
    fun setAnimating(enabled: Boolean) {
        ownerActive = enabled
        syncMotion()
    }

    fun isAnimating(): Boolean = renderer?.isAnimating() == true

    private fun syncMotion() {
        val allowed = ownerActive && isAttachedToWindow && isShown &&
            windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        renderer?.let { child ->
            child.visibility = if (allowed) VISIBLE else INVISIBLE
            child.setAnimating(allowed)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        syncMotion()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        renderer?.takeIf { it.visibility != VISIBLE }?.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(beforeDraw)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver)
        observing = true
        syncMotion()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(syncAfterVisibility)
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(beforeDraw)
        if (observing) context.contentResolver.unregisterContentObserver(motionObserver)
        observing = false
        renderer?.setAnimating(false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncMotion()
        // The parent notification precedes child visibility dispatch.
        removeCallbacks(syncAfterVisibility)
        if (isAttachedToWindow) post(syncAfterVisibility)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncMotion()
        removeCallbacks(syncAfterVisibility)
        if (isAttachedToWindow) post(syncAfterVisibility)
    }
}
