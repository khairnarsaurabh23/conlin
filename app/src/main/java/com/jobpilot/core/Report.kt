package com.jobpilot.core

import com.jobpilot.data.AppData
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.ReplyKind
import com.jobpilot.data.Stage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Builds a self-contained HTML report (viewable in-app, shareable, printable) and CSV exports. */
object Report {
    private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun d(ms: Long) = if (ms <= 0) "-" else dayFmt.format(Date(ms))
    private fun pct(v: Int?) = if (v == null) "-" else "$v%"

    fun html(data: AppData): String {
        val now = System.currentTimeMillis()
        val day = TimeUnit.DAYS.toMillis(1)
        val jobs = data.jobs
        val p = data.profile
        val ins = Insights.of(data, now)
        val usage = Guard.inviteUsage(data, now)
        val active = jobs.filter { it.status != JobStatus.IGNORED && it.status != JobStatus.REJECTED }
        val jobByKey = jobs.associateBy { it.key }
        val followUps = data.contacts.filter { it.stage == Stage.ASKED && now - it.askedAt > TimeUnit.DAYS.toMillis(p.followUpDays.toLong()) }
        val top = active.filter { it.detailsFetched }.sortedByDescending { it.matchScore }.take(30)
        val gaps = top.flatMap { it.missingSkills }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        val companies = active.groupingBy { it.company }.eachCount().entries.sortedByDescending { it.value }.take(10)

        val sb = StringBuilder()
        sb.append(
            """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>JobPilot report</title><style>
body{font-family:Roboto,-apple-system,Segoe UI,sans-serif;margin:0;padding:18px;color:#1f1e1c;background:#faf9f6;line-height:1.45;font-size:14px}
h1{font-family:Georgia,serif;font-weight:500;font-size:24px;margin:0 0 2px}
h2{font-family:Georgia,serif;font-weight:500;font-size:17px;margin:28px 0 8px}
.sub{color:#6b6a66;font-size:12.5px}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:7px 6px;text-align:left;border-bottom:1px solid #e4e2dc;vertical-align:top}
th{font-weight:500;color:#6b6a66;font-size:12px}
td.n,th.n{text-align:right;white-space:nowrap}
a{color:#2f5d50;text-decoration:none}.muted{color:#6b6a66}
.kv td:first-child{color:#6b6a66}.kv td:last-child{text-align:right;font-variant-numeric:tabular-nums}
</style></head><body>"""
        )
        sb.append("<h1>Job search report</h1><div class='sub'>${esc(p.name.ifBlank { "JobPilot" })} · ${dateFmt.format(Date(now))}</div>")

        fun kv(title: String, rows: List<Pair<String, String>>) {
            sb.append("<h2>$title</h2><table class='kv'>")
            rows.forEach { sb.append("<tr><td>${it.first}</td><td>${it.second}</td></tr>") }
            sb.append("</table>")
        }
        kv("Jobs", listOf(
            "Found" to "${ins.jobsFound}", "New in the last 24 hours" to "${jobs.count { now - it.foundAt < day }}",
            "New this week" to "${jobs.count { now - it.foundAt < 7 * day }}", "Kept" to "${ins.jobsKept}",
            "Strong matches (70%+)" to "${active.count { it.matchScore >= 70 }}", "Applied" to "${ins.applied}",
            "Interviews" to "${ins.interviews}", "Rejected" to "${ins.rejected}",
        ))
        kv("Referral outreach", listOf(
            "People suggested" to "${ins.suggested}", "Invites sent" to "${ins.invited}",
            "Accepted" to "${ins.accepted} (${pct(ins.acceptanceRate)})", "Referral asks sent" to "${ins.asked}",
            "Replies" to "${ins.replied} (${pct(ins.askReplyRate)})", "Referrals" to "${ins.referred}",
            "Invites in the last 7 days" to "${usage.week} of ${usage.limits.week}", "Follow-ups due" to "${followUps.size}",
        ))
        kv("Recruiter emails", listOf(
            "Sent" to "${ins.emailsSent}", "Replies" to "${ins.emailReplies} (${pct(ins.emailReplyRate)})",
            "Interview replies" to "${ins.emailInterviews}", "Rejections" to "${ins.emailRejections}", "Bounced" to "${ins.bounced}",
            "Median time to reply" to (ins.medianReplyHours?.let { if (it < 48) "$it h" else "${it / 24} days" } ?: "-"),
            "Waiting for review" to "${data.emails.count { it.status == EmailStatus.DRAFT || it.status == EmailStatus.APPROVED }}",
        ))
        val tpl = ins.emailTemplates + ins.noteTemplates
        if (tpl.any { it.sent > 0 }) {
            sb.append("<h2>Templates</h2><table><tr><th>Template</th><th class='n'>Sent</th><th class='n'>Positive</th><th class='n'>Rate</th></tr>")
            tpl.forEach { sb.append("<tr><td>${it.label}</td><td class='n'>${it.sent}</td><td class='n'>${it.positive}</td><td class='n'>${pct(it.rate)}</td></tr>") }
            sb.append("</table>")
        }

        sb.append("<h2>Searches</h2><table><tr><th>Search</th><th class='n'>Jobs</th><th class='n'>70%+</th><th class='n'>7 days</th></tr>")
        for (s in data.searches) {
            val js = jobs.filter { it.searchId == s.id }
            sb.append("<tr><td>${esc(s.label())}<div class='muted'>${s.experience.label}, ${s.workType.label}, ${s.postedWithin.label}${if (!s.enabled) ", paused" else ""}</div></td>")
            sb.append("<td class='n'>${js.size}</td><td class='n'>${js.count { it.matchScore >= 70 }}</td><td class='n'>${js.count { now - it.foundAt < 7 * day }}</td></tr>")
        }
        sb.append("</table>")

        sb.append("<h2>Top matches</h2><table><tr><th>Role</th><th class='n'>Match</th><th>Status</th></tr>")
        for (j in active.sortedWith(compareByDescending<Job> { it.matchScore }.thenByDescending { it.foundAt }).take(40)) {
            sb.append("<tr><td><a href='${esc(j.url)}'>${esc(j.title)}</a><div>${esc(j.company)}</div><div class='muted'>${esc(j.location)} · ${esc(j.source)} · found ${d(j.foundAt)}</div>")
            if (j.matchedSkills.isNotEmpty()) sb.append("<div class='muted'>Mentions: ${esc(j.matchedSkills.joinToString(", "))}</div>")
            sb.append("</td><td class='n'>${j.matchScore}%</td><td>${j.status.label}</td></tr>")
        }
        if (active.isEmpty()) sb.append("<tr><td colspan='3' class='muted'>No jobs yet.</td></tr>")
        sb.append("</table>")

        sb.append("<h2>People</h2><table><tr><th>Person</th><th>For</th><th>Stage</th></tr>")
        for (c in data.contacts.filter { it.stage != Stage.SKIPPED }.sortedByDescending { maxOf(it.askedAt, it.invitedAt, it.addedAt) }) {
            val j = jobByKey[c.jobKey]
            sb.append("<tr><td><a href='${esc(c.profileUrl)}'>${esc(c.name)}</a><div class='muted'>${esc(c.headline.take(90))}</div></td>")
            sb.append("<td>${esc(j?.title ?: c.company)}<div class='muted'>${esc(j?.company ?: "")}</div></td>")
            val whenText = when {
                c.askedAt > 0 -> "asked ${d(c.askedAt)}"
                c.connectedAt > 0 -> "connected ${d(c.connectedAt)}"
                c.invitedAt > 0 -> "invited ${d(c.invitedAt)}"
                else -> esc(c.why)
            }
            sb.append("<td>${c.stage.label}<div class='muted'>$whenText</div></td></tr>")
        }
        if (data.contacts.isEmpty()) sb.append("<tr><td colspan='3' class='muted'>No people yet.</td></tr>")
        sb.append("</table>")

        sb.append("<h2>Emails</h2><table><tr><th>To</th><th>Role</th><th>Status</th></tr>")
        for (e in data.emails.sortedByDescending { maxOf(it.sentAt, it.createdAt) }) {
            val status = if (e.reply != ReplyKind.NONE) e.reply.label else e.status.label
            sb.append("<tr><td>${esc(e.to)}<div class='muted'>${esc(e.company)}</div></td><td>${esc(e.role)}</td>")
            sb.append("<td>$status<div class='muted'>${if (e.sentAt > 0) "sent ${d(e.sentAt)}" else esc(e.error.take(60))}</div></td></tr>")
        }
        if (data.emails.isEmpty()) sb.append("<tr><td colspan='3' class='muted'>No recruiter emails yet.</td></tr>")
        sb.append("</table>")

        if (gaps.isNotEmpty()) {
            sb.append("<h2>Your skills that top jobs rarely mention</h2><table><tr><th>Skill</th><th class='n'>Missing from</th></tr>")
            gaps.forEach { sb.append("<tr><td>${esc(it.key)}</td><td class='n'>${it.value} of ${top.size}</td></tr>") }
            sb.append("</table>")
        }
        if (companies.isNotEmpty()) {
            sb.append("<h2>Companies with the most openings</h2><table><tr><th>Company</th><th class='n'>Roles</th></tr>")
            companies.forEach { sb.append("<tr><td>${esc(it.key.ifBlank { "-" })}</td><td class='n'>${it.value}</td></tr>") }
            sb.append("</table>")
        }

        sb.append("<h2>Recent checks</h2><table><tr><th>When</th><th class='n'>Seen</th><th class='n'>New</th><th>Notes</th></tr>")
        data.runs.take(10).forEach { r ->
            sb.append("<tr><td>${dateFmt.format(Date(r.at))}</td><td class='n'>${r.jobsSeen}</td><td class='n'>${r.newJobs}</td><td class='muted'>${esc(r.errors.joinToString("; ").take(160))}</td></tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }

    fun save(dir: File, data: AppData): File {
        val reports = File(dir, "reports").apply { mkdirs() }
        val name = "JobPilot-report-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".html"
        return File(reports, name).apply { writeText(html(data)) }
    }

    private fun csv(v: String) = "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\""

    fun saveCsv(dir: File, data: AppData): File {
        val reports = File(dir, "reports").apply { mkdirs() }
        val f = File(reports, "JobPilot-jobs-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".csv")
        val searches = data.searches.associateBy { it.id }
        val sb = StringBuilder("Title,Company,Location,Match,Status,Source,Search,Posted,Found,Matched skills,Missing skills,Emails,URL\n")
        for (j in data.jobs) {
            sb.append(
                listOf(
                    j.title, j.company, j.location, j.matchScore.toString(), j.status.label, j.source,
                    searches[j.searchId]?.label().orEmpty(), j.postedAt, dateFmt.format(Date(j.foundAt)),
                    j.matchedSkills.joinToString("; "), j.missingSkills.joinToString("; "),
                    j.emailsFound.joinToString("; "), j.url,
                ).joinToString(",") { csv(it) }
            ).append("\n")
        }
        f.writeText(sb.toString())
        return f
    }
}
