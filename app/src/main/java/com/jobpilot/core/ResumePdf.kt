package com.jobpilot.core

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.jobpilot.data.Job
import com.jobpilot.data.Profile
import java.io.File

/** Builds a clean one-column resume PDF from the profile, ordered for a specific job. */
object ResumePdf {
    private const val W = 595 // A4 in points
    private const val H = 842
    private const val M = 48f

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

        fun text(s: String, paint: TextPaint, after: Float = 0f) {
            if (s.isBlank()) return
            if (page == null) newPage()
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, (W - 2 * M).toInt())
                .setLineSpacing(0f, 1.2f).build()
            for (i in 0 until layout.lineCount) {
                val h = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
                if (y + h > H - M) newPage()
                val line = s.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd('\n', ' ')
                page!!.canvas.drawText(line, M, y + (layout.getLineBaseline(i) - layout.getLineTop(i)), paint)
                y += h
            }
            y += after
        }

        fun rule(paint: Paint) {
            if (page == null) newPage()
            page!!.canvas.drawLine(M, y, W - M, y, paint)
            y += 8f
        }

        fun finish() { page?.let { doc.finishPage(it) } }
    }

    fun build(dir: File, p: Profile, job: Job, summary: String, skills: List<String>): File {
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; textSize = 22f; color = 0xFF111111.toInt() }
        val meta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 9.5f; color = 0xFF555555.toInt() }
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 9.5f; color = 0xFF111111.toInt(); letterSpacing = 0.08f
        }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 10.5f; color = 0xFF222222.toInt() }
        val line = Paint().apply { color = 0xFFBBBBBB.toInt(); strokeWidth = 0.7f }

        val doc = PdfDocument()
        val w = Writer(doc)
        w.text(p.name.ifBlank { "Your name" }, name, 2f)
        if (p.headline.isNotBlank()) w.text(p.headline, body, 2f)
        w.text(listOf(p.email.ifBlank { p.smtpUser }, p.phone, p.linkedinUrl).filter { it.isNotBlank() }.joinToString("   |   "), meta, 12f)

        fun section(title: String, content: String) {
            if (content.isBlank()) return
            w.text(title.uppercase(), head, 3f)
            w.rule(line)
            w.text(content.trim(), body, 14f)
        }
        section("Summary", summary)
        section("Skills", skills.joinToString(", "))
        section("Experience", p.experienceText)
        section("Education", p.educationText.ifBlank { p.college })
        w.finish()

        val safe = job.key.replace(Regex("[^A-Za-z0-9]+"), "_")
        val out = File(dir, "resume_$safe.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
