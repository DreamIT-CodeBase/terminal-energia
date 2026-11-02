package com.terminalscout.app.prod.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * Drawing helpers used purely to visualize pipeline stages on screen — none of this feeds
 * back into the actual detection/OCR pipeline, it just annotates a copy for display.
 */
object BitmapOverlay {

    fun withBoxes(source: Bitmap, boxes: List<Rect>, color: Int = Color.GREEN, strokeWidthPx: Float = 4f): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            isAntiAlias = true
        }
        for (box in boxes) {
            canvas.drawRect(box, paint)
        }
        return out
    }

    fun withBox(source: Bitmap, box: Rect?, color: Int = Color.RED, strokeWidthPx: Float = 6f): Bitmap {
        if (box == null) return source
        return withBoxes(source, listOf(box), color, strokeWidthPx)
    }
}
