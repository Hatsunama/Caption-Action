package com.hatsunama.captionaction.ui

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.hatsunama.captionaction.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Android resource inflation/measurement under Robolectric, not screenshot approval. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "land-w640dp-h360dp-mdpi")
class OrientationLayoutTest {
    private fun measure(view: View, width: Int = 640, height: Int = 320) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    @Test fun landscapeMic_hasTranscriptAlongsideReachableControls() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_CaptionAction)
            val view = activity.layoutInflater.inflate(R.layout.activity_mic_translator, null)
            measure(view)
            val transcript = view.findViewById<ScrollView>(R.id.captionScroll)
            val controls = view.findViewById<View>(R.id.bottomControls).parent as ScrollView
            assertTrue(transcript.measuredWidth >= 300)
            assertTrue(transcript.measuredHeight >= 200)
            assertTrue(transcript.right <= controls.left)
            assertTrue(controls.right <= 640)
            for (id in listOf(R.id.btnMic, R.id.pickerFrom, R.id.pickerTo, R.id.btnBack)) {
                assertNotNull(view.findViewById<View>(id))
            }
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun permissionAndModelGate_shortHeightRetainsScrollableContent() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_CaptionAction)
            for (layout in listOf(R.layout.activity_permission_step, R.layout.dialog_model_gate)) {
                val root = activity.layoutInflater.inflate(layout, null) as ScrollView
                measure(root, 640, 160)
                val content = root.getChildAt(0)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, content.layoutParams.height)
                assertTrue("content must extend rather than compress into short viewport", content.measuredHeight > root.measuredHeight)
                root.scrollTo(0, content.measuredHeight)
                assertTrue(root.scrollY > 0)
            }
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun homeScroll_hasStableIdentityForNormalViewStateRestoration() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_CaptionAction)
            val view = activity.layoutInflater.inflate(R.layout.activity_home, null)
            val scroll = view.findViewById<ScrollView>(R.id.homeScroll)
            assertNotNull(scroll)
            measure(view)
            assertTrue(scroll.getChildAt(0).measuredHeight > scroll.measuredHeight)
        } finally { controller.pause().stop().destroy() }
    }
}
