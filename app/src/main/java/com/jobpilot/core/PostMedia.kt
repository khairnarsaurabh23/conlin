package com.jobpilot.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.jobpilot.data.Diagram
import com.jobpilot.data.Lesson
import com.jobpilot.data.Slide
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Images for posts, drawn on the phone: an architecture diagram (PNG) and a carousel (PDF). */
object PostMedia {
    private const val INK = 0xFF1F1E1D.toInt()
    private const val MUTED = 0xFF6B6863.toInt()
    private const val ACCENT = 0xFFC96442.toInt()
    private const val BOX = 0xFFF6F1EA.toInt()
    private const val LINE = 0xFFB9B2A8.toInt()

    private fun paint(size: Float, color: Int = INK, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(text: String, p: TextPaint, width: Int, center: Boolean = false, maxLines: Int = 20): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()

    private fun drawText(c: Canvas, l: StaticLayout, x: Float, y: Float) { c.save(); c.translate(x, y); l.draw(c); c.restore() }

    /** Columns by data-flow order: each node sits one column after the furthest node pointing to it. */
    private fun ranks(d: Diagram): Map<String, Int> {
        val ids = d.nodes.map { it.id }
        val rank = ids.associateWith { 0 }.toMutableMap()
        repeat(ids.size) {
            d.edges.forEach { e ->
                val a = rank[e.from] ?: return@forEach
                val b = rank[e.to] ?: return@forEach
                if (b < a + 1 && a + 1 < ids.size) rank[e.to] = a + 1
            }
        }
        return rank
    }

    fun diagram(d: Diagram, title: String, author: String, w: Int = 1200, h: Int = 1200): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val pad = 64f
        drawText(c, layout(title.ifBlank { d.title }, paint(46f, INK, true), (w - 2 * pad).toInt(), maxLines = 2), pad, pad)
        val rank = ranks(d)
        val cols = (rank.values.maxOrNull() ?: 0) + 1
        val byCol = d.nodes.groupBy { rank[it.id] ?: 0 }
        val top = 220f; val bottom = h - 120f
        val colW = (w - 2 * pad) / cols
        val boxW = minOf(colW * 0.78f, 300f)
        val boxH = 110f
        val pos = HashMap<String, RectF>()
        for ((col, nodes) in byCol) {
            val rowH = (bottom - top) / nodes.size
            nodes.forEachIndexed { i, n ->
                val cx = pad + colW * col + colW / 2
                val cy = top + rowH * i + rowH / 2
                pos[n.id] = RectF(cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2)
            }
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; strokeWidth = 4f; style = Paint.Style.STROKE }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; style = Paint.Style.FILL }
        val edgeText = paint(24f, MUTED)
        for (e in d.edges) {
            val a = pos[e.from] ?: continue
            val b = pos[e.to] ?: continue
            val (x1, y1) = edgePoint(a, b.centerX(), b.centerY())
            val (x2, y2) = edgePoint(b, a.centerX(), a.centerY())
            c.drawLine(x1, y1, x2, y2, linePaint)
            val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            val head = Path().apply {
                moveTo(x2, y2)
                lineTo((x2 - 22 * cos(ang - 0.45)).toFloat(), (y2 - 22 * sin(ang - 0.45)).toFloat())
                lineTo((x2 - 22 * cos(ang + 0.45)).toFloat(), (y2 - 22 * sin(ang + 0.45)).toFloat())
                close()
            }
            c.drawPath(head, headPaint)
            if (e.label.isNotBlank()) {
                val l = layout(e.label, edgeText, 220, center = true, maxLines = 2)
                val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
                val tw = minOf(edgeText.measureText(e.label), 220f)
                c.drawRect(mx - tw / 2 - 8, my - l.height / 2f - 4, mx + tw / 2 + 8, my + l.height / 2f + 4, Paint().apply { color = Color.WHITE })
                drawText(c, l, mx - 110, my - l.height / 2f)
            }
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BOX }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = 4f; style = Paint.Style.STROKE }
        val label = paint(30f, INK, true)
        for (n in d.nodes) {
            val r = pos[n.id] ?: continue
            c.drawRoundRect(r, 22f, 22f, fill); c.drawRoundRect(r, 22f, 22f, stroke)
            val l = layout(n.label, label, (r.width() - 24).toInt(), center = true, maxLines = 2)
            drawText(c, l, r.left + 12, r.centerY() - l.height / 2f)
        }
        if (author.isNotBlank()) c.drawText(author, pad, h - 56f, paint(26f, MUTED))
        return bmp
    }

    /** Where the line from a box's centre towards (tx, ty) leaves the box. */
    private fun edgePoint(r: RectF, tx: Float, ty: Float): Pair<Float, Float> {
        val dx = tx - r.centerX(); val dy = ty - r.centerY()
        if (dx == 0f && dy == 0f) return r.centerX() to r.centerY()
        val sx = if (dx != 0f) (r.width() / 2) / kotlin.math.abs(dx) else Float.MAX_VALUE
        val sy = if (dy != 0f) (r.height() / 2) / kotlin.math.abs(dy) else Float.MAX_VALUE
        val s = minOf(sx, sy)
        return (r.centerX() + dx * s) to (r.centerY() + dy * s)
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "posts").apply { mkdirs() }

    fun savePng(ctx: Context, l: Lesson, author: String): File {
        val f = File(dir(ctx), "diagram-${l.id}.png")
        f.outputStream().use { diagram(l.diagram, l.title, author).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** Square slides, the shape LinkedIn shows best for document posts. */
    fun carouselPdf(ctx: Context, l: Lesson, slides: List<Slide>, author: String): File {
        val size = 1080
        val doc = PdfDocument()
        val all = slides.ifEmpty { listOf(Slide(l.title, l.summary)) }
        val pad = 96f
        all.forEachIndexed { i, s ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(size, size, i + 1).create())
            val c = page.canvas
            c.drawColor(if (i == 0) 0xFF1F1E1D.toInt() else Color.WHITE)
            val ink = if (i == 0) Color.WHITE else INK
            c.drawRect(pad, pad, pad + 90f, pad + 10f, Paint().apply { color = ACCENT })
            val t = layout(s.title, paint(if (i == 0) 78f else 60f, ink, true), (size - 2 * pad).toInt(), maxLines = 4)
            drawText(c, t, pad, pad + 60)
            if (s.body.isNotBlank()) {
                val b = layout(s.body, paint(40f, if (i == 0) 0xFFD8D3CB.toInt() else 0xFF3D3A36.toInt()), (size - 2 * pad).toInt(), maxLines = 12)
                drawText(c, b, pad, pad + 100 + t.height)
            }
            val foot = paint(28f, if (i == 0) 0xFFB0AAA1.toInt() else MUTED)
            c.drawText(author, pad, size - pad + 20, foot)
            c.drawText("${i + 1}/${all.size}", size - pad - 60, size - pad + 20, foot)
            doc.finishPage(page)
        }
        if (!l.diagram.isEmpty) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(size, size, all.size + 1).create())
            val bmp = diagram(l.diagram, l.diagram.title.ifBlank { "How it fits together" }, author, size, size)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)
        }
        val f = File(dir(ctx), "carousel-${l.id}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
