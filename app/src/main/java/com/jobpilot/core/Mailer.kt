package com.jobpilot.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.jobpilot.data.EmailLead
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Profile
import com.jobpilot.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlin.random.Random

object Mailer {

    fun sentToday(): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return Store.data.emails.count { it.status == EmailStatus.SENT && it.sentAt >= cal.timeInMillis }
    }

    /** Sends via SMTP (e.g. Gmail with an App Password). Throws on failure. */
    suspend fun sendSmtp(lead: EmailLead, p: Profile, resume: File?) = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.host", p.smtpHost)
            put("mail.smtp.port", p.smtpPort.toString())
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
            if (p.smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(p.smtpUser, p.smtpPassword)
        })
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(p.smtpUser, p.name.ifBlank { p.smtpUser }))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(lead.to))
        msg.setSubject(lead.subject, "UTF-8")
        val multipart = MimeMultipart()
        multipart.addBodyPart(MimeBodyPart().apply { setText(lead.body, "UTF-8") })
        if (lead.attachResume && resume != null) {
            multipart.addBodyPart(MimeBodyPart().apply {
                attachFile(resume)
                fileName = attachmentName(p, resume)
            })
        }
        msg.setContent(multipart)
        Transport.send(msg)
    }

    private fun attachmentName(p: Profile, f: File): String {
        val ext = f.extension.ifBlank { "pdf" }
        val n = p.name.ifBlank { "Resume" }.replace(Regex("[^A-Za-z0-9]+"), "_")
        return "${n}_Resume.$ext"
    }

    /** Builds an intent that opens the Gmail app (or any mail app) with everything pre-filled. */
    fun composeIntent(context: Context, lead: EmailLead): Intent {
        val resume = Store.resumeFile()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (lead.attachResume && resume != null) "application/octet-stream" else "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(lead.to))
            putExtra(Intent.EXTRA_SUBJECT, lead.subject)
            putExtra(Intent.EXTRA_TEXT, lead.body)
            if (lead.attachResume && resume != null) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", resume)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val gmail = "com.google.android.gm"
        val hasGmail = runCatching { context.packageManager.getPackageInfo(gmail, 0) }.isSuccess
        return if (hasGmail) intent.setPackage(gmail) else Intent.createChooser(intent, "Send email")
    }

    /**
     * Sends every APPROVED email via SMTP, respecting the daily cap and spacing sends out.
     * Returns a human-readable summary.
     */
    suspend fun sendApproved(onProgress: (String) -> Unit = {}): String {
        val p = Store.data.profile
        if (!p.smtpConfigured) return "SMTP isn't set up — use \"Open in Gmail\" on each email instead."
        val queue = Store.data.emails.filter { it.status == EmailStatus.APPROVED }
        if (queue.isEmpty()) return "No approved emails to send."
        val resume = Store.resumeFile()
        var sent = 0; var failed = 0
        for (lead in queue) {
            if (sentToday() >= p.dailyEmailCap) {
                return "Sent $sent, failed $failed. Daily cap of ${p.dailyEmailCap} reached — the rest stay approved for tomorrow."
            }
            onProgress("Sending to ${lead.to} (${sent + failed + 1}/${queue.size})")
            try {
                sendSmtp(lead, p, resume)
                sent++
                Store.updateEmail(lead.id) { it.copy(status = EmailStatus.SENT, sentAt = System.currentTimeMillis(), via = "SMTP", error = "") }
                lead.jobKey.takeIf { it.isNotBlank() }?.let { key ->
                    Store.updateJob(key) { j -> if (j.status == com.jobpilot.data.JobStatus.NEW || j.status == com.jobpilot.data.JobStatus.SAVED) j.copy(status = com.jobpilot.data.JobStatus.APPLIED) else j }
                }
            } catch (e: Exception) {
                failed++
                val msg = e.message ?: e.javaClass.simpleName
                Store.updateEmail(lead.id) { it.copy(status = EmailStatus.FAILED, error = msg) }
                if (msg.contains("Authentication", true) || msg.contains("535")) {
                    return "Login to ${p.smtpHost} failed. For Gmail use an App Password (not your normal password)."
                }
            }
            delay(4000L + Random.nextLong(4000))
        }
        return "Sent $sent email(s)" + if (failed > 0) ", $failed failed (see Emails tab)." else "."
    }
}
