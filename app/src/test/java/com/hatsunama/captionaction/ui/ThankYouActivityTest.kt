package com.hatsunama.captionaction.ui

import android.app.Application
import android.content.res.Configuration
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.ui.thanks.ThankYouActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ThankYouActivityTest {
    @Test fun rotation_retainsLocalThankYouAndBackAction() {
        val controller = Robolectric.buildActivity(ThankYouActivity::class.java).setup().visible()
        try {
            val config = Configuration(controller.get().resources.configuration)
            config.orientation = Configuration.ORIENTATION_LANDSCAPE
            controller.configurationChange(config)
            val activity = controller.get()
            assertEquals("Hatsu Is Here For You", activity.getString(R.string.open_thank_you))
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            assertTrue(content.getChildAt(0) is ScrollView)
            activity.findViewById<MaterialButton>(R.id.btnThankYouBack).performClick()
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }
}
