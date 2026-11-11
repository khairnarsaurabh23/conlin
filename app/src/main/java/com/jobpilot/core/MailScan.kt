package com.jobpilot.core

import com.jobpilot.data.EmailStatus
import com.jobpilot.data.JobStatus
import com.jobpilot.data.MailScanLog
import com.jobpilot.data.ReplyKind
import com.jobpilot.data.Stage
import com.jobpilot.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.Date
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * Read-only scan of the Gmail inbox over IMAP, using the same App Password as sending.
 * It only reads headers of recent mail (and the body of mail that matches someone you contacted),
 * never modifies or deletes anything, and nothing leaves the phone.
 */
object MailScan {

    private class Mail(val from: String, val fromName: String, val subject: String, val at: Long, val body: String)

    private val GENERIC = listOf("gmail.", "yahoo.", "outlook.", "hotmail.", "icloud.", "proton", "rediffmail.", "live.", "aol.")

    suspend fun run(): MailScanLog {
        val d0 = Store.data
        val p = d0.profile
        val now = System.currentTimeMillis()
        if (!p.smtpConfigured) return MailScanLog(now, error = "Add your Gmail address and App Password in Settings first.")

        val since = maxOf(d0.mailScan.at - TimeUnit.DAYS.toMillis(2), now - TimeUnit.DAYS.toMillis(45))
        val sentLeads = d0.emails.filter { it.sentAt > 0 }
        val watchAddrs = sentLeads.map { it.to.lowercase() }.toSet()
        val watchDomains = watchAddrs.map { it.substringAfter("@") }.filter { dom -> GENERIC.none { dom.startsWith(it) } }.toSet()
        val companies = d0.jobs.filter { it.status == JobStatus.APPLIED || it.status == JobStatus.REFERRAL_ASKED || it.status == JobStatus.INTERVIEW }
            .map { it.company.lowercase() }.filter { it.length >= 4 }.toSet()

        val mails = try {
            withContext(Dispatchers.IO) {
                val props = Properties().apply {
                    put("mail.store.protocol", "imaps")
                    put("mail.imaps.host", p.imapHost)
                    put("mail.imaps.port", "993")
                    put("mail.imaps.ssl.enable", "true")
                    put("mail.imaps.connectiontimeout", "20000")
                    put("mail.imaps.timeout", "30000")
                }
                val store = Session.getInstance(props).getStore("imaps")
                store.connect(p.imapHost, 993, p.smtpUser, p.smtpPassword)
                try {
                    val inbox = store.getFolder("INBOX")
                    inbox.open(Folder.READ_ONLY)
                    val found = inbox.search(ReceivedDateTerm(ComparisonTerm.GE, Date(since))).toList().takeLast(500)
                    inbox.fetch(found.toTypedArray(), FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
                    val out = found.mapNotNull { m ->
                        runCatching {
                            val f = m.from?.firstOrNull() as? InternetAddress
                            val addr = f?.address.orEmpty().lowercase()
                            val name = f?.personal.orEmpty()
                            val subject = m.subject.orEmpty()
                            val at = (m.receivedDate ?: m.sentDate)?.time ?: 0L
                            val dom = addr.substringAfter("@")
                            val hay = (name + " " + subject).lowercase()
                            // Bodies are only read for mail that plausibly relates to your outreach.
                            val relevant = addr in watchAddrs || dom in watchDomains ||
                                MailRules.isBounce(addr, subject) || companies.any { hay.contains(it) }
                            Mail(addr, name, subject, at, if (relevant) text(m).take(6000) else "")
                        }.getOrNull()
                    }
                    inbox.close(false)
                    out
                } finally {
                    runCatching { store.close() }
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            val nice = when {
                msg.contains("AUTHENTICATIONFAILED", true) || msg.contains("Invalid credentials", true) ->
                    "Gmail rejected the login. Check the App Password, and that IMAP is on in Gmail settings."
                else -> "Couldn't read the inbox: $msg"
            }
            val log = MailScanLog(now, error = nice)
            Store.update { it.copy(mailScan = it.mailScan.copy(error = nice)) }
            return log
        }

        var accepted = 0; var replies = 0; var interviews = 0; var rejections = 0; var bounces = 0

        for (m in mails) {
            val d = Store.data
            val dom = m.from.substringAfter("@")

            // LinkedIn notifications: accepted invitations and replies to messages
            if (dom.endsWith("linkedin.com")) {
                if (MailRules.isAcceptance(m.subject)) {
                    val c = d.contacts.firstOrNull { (it.stage == Stage.INVITED || it.stage == Stage.APPROVED) && MailRules.mentions(m.subject, it.name) }
                    if (c != null) {
                        accepted++
                        Store.updateContact(c.id) { it.copy(stage = Stage.CONNECTED, connectedAt = m.at, openedAt = 0, invitedAt = if (it.invitedAt > 0) it.invitedAt else m.at) }
                    }
                } else if (MailRules.isLinkedInMessage(m.subject)) {
                    val c = d.contacts.firstOrNull { it.stage == Stage.ASKED && m.at > it.askedAt && MailRules.mentions(m.subject + " " + m.fromName, it.name) }
                    if (c != null) {
                        replies++
                        Store.updateContact(c.id) { it.copy(stage = Stage.REPLIED, repliedAt = m.at) }
                    }
                }
                continue
            }

            // Bounces
            if (MailRules.isBounce(m.from, m.subject)) {
                val body = m.body.lowercase()
                val lead = d.emails.firstOrNull { it.status == EmailStatus.SENT && body.contains(it.to.lowercase()) }
                if (lead != null) {
                    bounces++
                    Store.updateEmail(lead.id) { it.copy(status = EmailStatus.BOUNCED, error = "Address bounced") }
                }
                continue
            }

            // Replies from people you emailed (same address, or someone else at the same company domain)
            val lead = d.emails.filter { it.status == EmailStatus.SENT && it.reply == ReplyKind.NONE && m.at > it.sentAt }
                .let { c -> c.firstOrNull { it.to.equals(m.from, true) } ?: c.firstOrNull { dom in watchDomains && it.to.endsWith("@$dom", true) } }
            if (lead != null) {
                val kind = MailRules.classify(m.subject, m.body)
                when (kind) {
                    ReplyKind.INTERVIEW -> interviews++
                    ReplyKind.REJECTION -> rejections++
                    ReplyKind.REPLY -> replies++
                    else -> {}
                }
                Store.updateEmail(lead.id) { it.copy(reply = kind, repliedAt = m.at, replySubject = m.subject.take(120)) }
                if (lead.jobKey.isNotBlank()) applyToJob(lead.jobKey, kind)
                continue
            }

            // Application updates from a company you applied to (ATS mail)
            val hay = (m.fromName + " " + m.subject).lowercase()
            val job = d.jobs.firstOrNull {
                (it.status == JobStatus.APPLIED || it.status == JobStatus.REFERRAL_ASKED) && it.company.length >= 4 &&
                    Regex("(?<![a-z0-9])" + Regex.escape(it.company.lowercase()) + "(?![a-z0-9])").containsMatchIn(hay)
            }
            if (job != null) {
                when (MailRules.classify(m.subject, m.body)) {
                    ReplyKind.INTERVIEW -> { interviews++; applyToJob(job.key, ReplyKind.INTERVIEW) }
                    ReplyKind.REJECTION -> { rejections++; applyToJob(job.key, ReplyKind.REJECTION) }
                    else -> {}
                }
            }
        }

        val log = MailScanLog(now, mails.size, accepted, replies, interviews, rejections, bounces)
        Store.update { it.copy(mailScan = log) }
        return log
    }

    private suspend fun applyToJob(key: String, kind: ReplyKind) {
        when (kind) {
            ReplyKind.INTERVIEW -> Store.updateJob(key) { it.copy(status = JobStatus.INTERVIEW) }
            ReplyKind.REJECTION -> Store.updateJob(key) { if (it.status != JobStatus.INTERVIEW) it.copy(status = JobStatus.REJECTED) else it }
            else -> {}
        }
    }

    private fun text(p: Part, depth: Int = 0): String = try {
        when {
            depth > 4 -> ""
            p.isMimeType("text/plain") -> p.content as? String ?: ""
            p.isMimeType("text/html") -> Jsoup.parse(p.content as? String ?: "").text()
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                (0 until mp.count).asSequence().map { text(mp.getBodyPart(it), depth + 1) }.firstOrNull { it.isNotBlank() } ?: ""
            }
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }

    fun summary(l: MailScanLog): String {
        if (l.error.isNotBlank()) return l.error
        val parts = buildList {
            if (l.accepted > 0) add("${l.accepted} invite(s) accepted")
            if (l.replies > 0) add("${l.replies} repl${if (l.replies == 1) "y" else "ies"}")
            if (l.interviews > 0) add("${l.interviews} interview mail(s)")
            if (l.rejections > 0) add("${l.rejections} rejection(s)")
            if (l.bounces > 0) add("${l.bounces} bounced")
        }
        return if (parts.isEmpty()) "Inbox checked (${l.scanned} recent mails). Nothing new." else parts.joinToString(", ")
    }
}
