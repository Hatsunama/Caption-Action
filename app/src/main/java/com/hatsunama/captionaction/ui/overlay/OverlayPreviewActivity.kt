package com.hatsunama.captionaction.ui.overlay

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import kotlinx.coroutines.launch

class OverlayPreviewActivity : AppCompatActivity() {
    private var boxX = 40
    private var boxY = 80
    private var boxW = 600
    private var boxH = 140
    private var fontIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_preview)
        val area = findViewById<FrameLayout>(R.id.previewArea)
        val fontSpinner = findViewById<Spinner>(R.id.fontSpinner)
        fontSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Bold Sans", "Regular Sans", "Monospace")
        )

        val sample = TextView(this).apply {
            text = "Sample live caption\nDrag me · pinch edges to resize feel"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24, 16, 24, 16)
            textSize = 18f
        }
        val lp = FrameLayout.LayoutParams(boxW, boxH).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = boxX
            topMargin = boxY
        }
        area.addView(sample, lp)

        lifecycleScope.launch {
            val s = (application as CaptionActionApp).settings.current()
            boxX = s.overlayX
            boxY = s.overlayY
            boxW = s.overlayWidth
            boxH = s.overlayHeight
            fontIndex = s.fontIndex
            fontSpinner.setSelection(fontIndex.coerceIn(0, 2))
            lp.width = boxW
            lp.height = boxH
            lp.leftMargin = boxX.coerceAtLeast(0)
            lp.topMargin = boxY.coerceAtLeast(0)
            sample.layoutParams = lp
        }

        var lastX = 0f
        var lastY = 0f
        sample.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lastX).toInt()
                    val dy = (e.rawY - lastY).toInt()
                    lastX = e.rawX
                    lastY = e.rawY
                    val p = v.layoutParams as FrameLayout.LayoutParams
                    p.leftMargin = (p.leftMargin + dx).coerceAtLeast(0)
                    p.topMargin = (p.topMargin + dy).coerceAtLeast(0)
                    v.layoutParams = p
                    boxX = p.leftMargin
                    boxY = p.topMargin
                    true
                }
                else -> false
            }
        }

        // Simple resize: long-press grows width
        sample.setOnLongClickListener {
            val p = sample.layoutParams as FrameLayout.LayoutParams
            p.width = (p.width + 80).coerceAtMost(area.width)
            p.height = (p.height + 20).coerceAtMost(area.height)
            sample.layoutParams = p
            boxW = p.width
            boxH = p.height
            sample.textSize = (p.height / 10f).coerceIn(14f, 36f)
            true
        }

        fontSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                fontIndex = position
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<MaterialButton>(R.id.btnSavePreview).setOnClickListener {
            lifecycleScope.launch {
                (application as CaptionActionApp).settings.update {
                    it.copy(
                        overlayX = boxX,
                        overlayY = boxY,
                        overlayWidth = boxW,
                        overlayHeight = boxH,
                        fontIndex = fontIndex
                    )
                }
                Toast.makeText(this@OverlayPreviewActivity, "Overlay layout saved", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
