package com.vagujhelyigergely.calculatorm3.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import java.io.File

/**
 * Turns the pad's strokes into the picture the model reads: black ink on white whatever the
 * theme, cropped to the ink and fitted by [InkGeometry.fit].
 */
object InkRasterizer {

    /** The ink as a bitmap, or null when there's none. The caller recycles it. */
    fun render(strokes: List<InkStroke>, maxEdge: Int): Bitmap? {
        val bounds = InkGeometry.bounds(strokes) ?: return null
        val fit = InkGeometry.fit(bounds, maxEdge)
        val bitmap = createBitmap(fit.widthPx, fit.heightPx)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = fit.lineWidthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        fun px(p: InkPoint) = p.x * fit.scale + fit.offsetX
        fun py(p: InkPoint) = p.y * fit.scale + fit.offsetY
        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.size == 1) {
                canvas.drawCircle(px(pts[0]), py(pts[0]), fit.lineWidthPx / 2, dot)
                continue
            }
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p), py(p)) else path.lineTo(px(p), py(p)) }
            canvas.drawPath(path, line)
        }
        return bitmap
    }

    /** Render the ink to a JPEG in [dir] and return it, or null when there's no ink. */
    fun writeJpeg(strokes: List<InkStroke>, dir: File, maxEdge: Int): File? {
        val bitmap = render(strokes, maxEdge) ?: return null
        return try {
            val file = File(dir, "ink_${System.nanoTime()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            file
        } finally {
            bitmap.recycle()
        }
    }
}
