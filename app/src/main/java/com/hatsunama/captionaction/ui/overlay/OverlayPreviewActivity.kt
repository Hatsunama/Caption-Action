package com.hatsunama.captionaction.ui.overlay

import android.os.Bundle
import android.util.TypedValue
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
import kotlin.math.max
import kotlin.math.min

class OverlayPreviewActivity : AppCompatActivity() {
    private var boxX = 40
    private var boxY = 80
    private var boxW = 600
    private var boxH = 140
    private var fontIndex = 0

    private enum class Mode { NONE, MOVE, RESIZE }

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

        val density = resources.displayMetrics.density
        val minW = (160 * density).toInt()
        val minH = (100 * density).toInt()
        val handleSize = (28 * density).toInt()

        val sample = TextView(this).apply {
            text = "Sample live caption\nDrag me · pull ▣ to resize"
            setTextColor(0xFF2D2140.toInt())
            setBackgroundResource(R.drawable.bg_overlay_bubble)
            setPadding(24, 16, 24, 16)
            textSize = 18f
        }
        val handle = View(this).apply {
            setBackgroundResource(R.drawable.bg_resize_handle)
        }

        val lp = FrameLayout.LayoutParams(boxW, boxH).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = boxX
            topMargin = boxY
        }
        val handleLp = FrameLayout.LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        area.addView(sample, lp)
        area.addView(handle, handleLp)

        fun syncHandle() {
            val p = sample.layoutParams as FrameLayout.LayoutParams
            handleLp.leftMargin = p.leftMargin + p.width - handleSize / 2
            handleLp.topMargin = p.topMargin + p.height - handleSize / 2
            handle.layoutParams = handleLp
            val sp = ((p.height / density) / 7.5f).coerceIn(14f, 40f)
            sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }

        lifecycleScope.launch {
            val s = (application as CaptionActionApp).settings.current()
            boxX = s.overlayX
            boxY = s.overlayY
            boxW = s.overlayWidth
            boxH = s.overlayHeight
            fontIndex = s.fontIndex
            fontSpinner.setSelection(fontIndex.coerceIn(0, 2))
            lp.width = max(minW, boxW)
            lp.height = max(minH, boxH)
            lp.leftMargin = boxX.coerceAtLeast(0)
            lp.topMargin = boxY.coerceAtLeast(0)
            sample.layoutParams = lp
            syncHandle()
        }

        var mode = Mode.NONE
        var lastX = 0f
        var lastY = 0f

        fun onTouch(v: View, e: MotionEvent, forceResize: Boolean): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    mode = if (forceResize || v === handle) Mode.RESIZE else Mode.MOVE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lastX).toInt()
                    val dy = (e.rawY - lastY).toInt()
                    lastX = e.rawX
                    lastY = e.rawY
                    val p = sample.layoutParams as FrameLayout.LayoutParams
                    when (mode) {
                        Mode.MOVE -> {
                            p.leftMargin = (p.leftMargin + dx).coerceAtLeast(0)
                            p.topMargin = (p.topMargin + dy).coerceAtLeast(0)
                        }
                        Mode.RESIZE -> {
                            p.width = min(area.width, max(minW, p.width + dx))
                            p.height = min(area.height, max(minH, p.height + dy))
                        }
                        else -> {}
                    }
                    sample.layoutParams = p
                    boxX = p.leftMargin
                    boxY = p.topMargin
                    boxW = p.width
                    boxH = p.height
                    syncHandle()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mode = Mode.NONE
                    true
                }
                else -> false
            }
            return true
        }

        sample.setOnTouchListener { v, e -> onTouch(v, e, false) }
        handle.setOnTouchListener { v, e -> onTouch(v, e, true) }

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
