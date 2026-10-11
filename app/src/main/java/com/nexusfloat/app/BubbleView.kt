package com.nexusfloat.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** Bubble ringan: hanya menggambar lingkaran + huruf "N". Tanpa bitmap, tanpa layer software. */
class BubbleView(context: Context) : View(context) {

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    var accent: Int = OverlaySettings.DEFAULT_COLOR
        set(value) { field = value; invalidate() }

    /** true saat timer selesai dan belum dilihat pengguna. */
    var alert: Boolean = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        glow.color = (accent and 0x00FFFFFF) or (0x46 shl 24)
        canvas.drawCircle(cx, cy, r, glow)

        val body = r * 0.8f
        fill.color = if (alert) (accent and 0x00FFFFFF) or (0xE6 shl 24) else Color.parseColor("#E60B0614")
        canvas.drawCircle(cx, cy, body, fill)

        ring.color = accent
        ring.strokeWidth = r * 0.07f
        canvas.drawCircle(cx, cy, body - ring.strokeWidth / 2f, ring)

        label.textSize = r * 0.8f
        canvas.drawText("N", cx, cy - (label.ascent() + label.descent()) / 2f, label)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
