package com.hatsunama.captionaction.ui.thanks

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.R

class ThankYouActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_thank_you)
        findViewById<MaterialButton>(R.id.btnThankYouBack).setOnClickListener {
            finish()
        }
    }
}
