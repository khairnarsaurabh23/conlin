package com.jobpilot.core

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.jobpilot.data.Job
import com.jobpilot.data.Profile
import com.jobpilot.data.TailoredResume
import java.io.File

/** Lays a tailored resume out as a clean one-column A4 PDF. */
object ResumePdf {
    private const val W = 595 // A4 in points
    private const val H = 842
    private const val M = 44f

    private class Writer(val doc: PdfDocument) {
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = M

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }

        /** Wrapped text. [first] is drawn once to the left of the first line (a bullet). */
        fun text(s: String, paint: TextPaint, after: Float = 0f, indent: Float = 0f, first: String = "") {
            if (s.isBlank()) return
            if (page == null) newPage()
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, (W - 2 * M - indent).toInt())
                .setLineSpacing(0f, 1.18f).build()
            for (i in 0 until layout.lineCount) {
                val h = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
                if (y + h > H - M) newPage()
                val base = y + (layout.getLineBaseline(i) - layout.getLineTop(i))
                if (i == 0 && first.isNotEmpty()) page!!.canvas.drawText(first, M + indent - 10f, base, paint)
                val line = s.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd('\n', ' ')
                page!!.canvas.drawText(line, M + indent, base, paint)
                y += h
            }
            y += after
        }

        /** One line with text on the left and, right-aligned, on the right (e.g. role and dates). */
        fun pair(left: String, leftPaint: TextPaint, right: String, rightPaint: TextPaint, after: Float = 0f) {
            if (page == null) newPage()
            val h = leftPaint.fontSpacing * 1.15f
            if (y + h > H - M) newPage()
            val rightW = if (right.isBlank()) 0f else rightPaint.measureText(right) + 10f
            var l = left
            while (l.length > 4 && leftPaint.measureText(l) > W - 2 * M - rightW) l = l.dropLast(2)
            if (l != left) l = l.trimEnd() + "..."
            val base = y - leftPaint.ascent()
            page!!.canvas.drawText(l, M, base, leftPaint)
            if (right.isNotBlank()) page!!.canvas.drawText(right, W - M - rightPaint.measureText(right), base, rightPaint)
            y += h + after
        }

        fun rule(paint: Paint) {
            if (page == null) newPage()
            page!!.canvas.drawLine(M, y, W - M, y, paint)
            y += 7f
        }

        fun finish() { page?.let { doc.finishPage(it) } }
    }

    fun build(dir: File, p: Profile, job: Job, r: TailoredResume): File {
        val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        fun paint(tf: Typeface, size: Float, color: Long) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; this.color = color.toInt() }
        val name = paint(bold, 20f, 0xFF111111)
        val meta = paint(sans, 9.5f, 0xFF555555)
        val head = paint(bold, 9.5f, 0xFF111111).apply { letterSpacing = 0.08f }
        val body = paint(sans, 10f, 0xFF222222)
        val strong = paint(bold, 10.5f, 0xFF111111)
        val line = Paint().apply { color = 0xFFBBBBBB.toInt(); strokeWidth = 0.7f }

        val doc = PdfDocument()
        val w = Writer(doc)
        w.text(p.name.ifBlank { "Your name" }, name, 2f)
        w.text(r.headline.ifBlank { p.headline }, body, 2f)
        w.text(listOf(p.email.ifBlank { p.smtpUser }, p.phone, p.linkedinUrl).filter { it.isNotBlank() }.joinToString("   |   "), meta, 10f)

        fun section(title: String, content: () -> Unit) {
            w.text(title.uppercase(), head, 3f)
            w.rule(line)
            content()
            w.y += 9f
        }
        fun bullets(items: List<String>) = items.filter { it.isNotBlank() }.forEach { w.text(it.trim(), body, 1.5f, indent = 12f, first = "•") }

        if (r.summary.isNotBlank()) section("Summary") { w.text(r.summary.trim(), body) }
        if (r.skills.isNotEmpty()) section("Skills") { w.text(r.skills.joinToString(", "), body) }
        if (r.experience.isNotEmpty()) section("Experience") {
            r.experience.forEachIndexed { i, role ->
                val title = listOf(role.title, role.company).filter { it.isNotBlank() }.joinToString(", ")
                if (title.isNotBlank()) {
                    w.pair(title, strong, role.dates, meta, 1f)
                    bullets(role.bullets)
                } else role.bullets.forEach { w.text(it, body, 1.5f) } // free text entered in Settings
                if (i < r.experience.lastIndex) w.y += 5f
            }
        }
        if (r.projects.isNotEmpty()) section("Projects") {
            r.projects.forEachIndexed { i, pr ->
                if (pr.name.isNotBlank()) w.text(pr.name, strong, 1f)
                bullets(pr.bullets)
                if (i < r.projects.lastIndex) w.y += 4f
            }
        }
        if (r.education.isNotEmpty()) section("Education") { r.education.filter { it.isNotBlank() }.forEach { w.text(it, body, 1.5f) } }
        if (r.other.any { it.isNotBlank() }) section("Additional") { bullets(r.other) }
        w.finish()

        val safe = job.key.replace(Regex("[^A-Za-z0-9]+"), "_")
        val out = File(dir, "resume_$safe.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
