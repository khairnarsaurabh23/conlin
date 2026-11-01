package com.jobpilot.core

import com.jobpilot.data.AppData
import com.jobpilot.data.ContactStatus
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
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
    private fun d(ms: Long) = if (ms <= 0) "—" else dayFmt.format(Date(ms))

    fun html(data: AppData): String {
        val now = System.currentTimeMillis()
        val day = TimeUnit.DAYS.toMillis(1)
        val jobs = data.jobs
        val p = data.profile
        val new24 = jobs.count { now - it.foundAt < day }
        val new7 = jobs.count { now - it.foundAt < 7 * day }
        val active = jobs.filter { it.status != JobStatus.IGNORED && it.status != JobStatus.REJECTED }
        val avg = if (active.isEmpty()) 0 else active.sumOf { it.matchScore } / active.size
        val strong = active.count { it.matchScore >= 70 }
        val contacted = data.contacts.count { it.status != ContactStatus.TO_CONTACT }
        val replied = data.contacts.count { it.status == ContactStatus.REPLIED || it.status == ContactStatus.REFERRED }
        val referred = data.contacts.count { it.status == ContactStatus.REFERRED }
        val sent = data.emails.count { it.status == EmailStatus.SENT }
        val pendingEmails = data.emails.count { it.status == EmailStatus.DRAFT || it.status == EmailStatus.APPROVED }
        val followUps = data.contacts.filter {
            it.status == ContactStatus.CONTACTED && it.contactedAt > 0 &&
                now - it.contactedAt > TimeUnit.DAYS.toMillis(p.followUpDays.toLong())
        }
        val jobByKey = jobs.associateBy { it.key }

        // Skill gaps among the best-matching jobs
        val top = active.filter { it.detailsFetched }.sortedByDescending { it.matchScore }.take(30)
        val gaps = top.flatMap { it.missingSkills }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        val companies = active.groupingBy { it.company }.eachCount().entries.sortedByDescending { it.value }.take(10)

        val sb = StringBuilder()
        sb.append(
            """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>JobPilot report</title><style>
body{font-family:-apple-system,Roboto,Segoe UI,sans-serif;margin:0;padding:16px;color:#1b1f24;background:#f4f6f8;line-height:1.4}
h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:24px 0 8px;color:#0a66c2}
.sub{color:#5b6570;font-size:13px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:8px;margin-top:12px}
.card{background:#fff;border-radius:10px;padding:12px;box-shadow:0 1px 2px rgba(0,0,0,.06)}
.n{font-size:24px;font-weight:700}.l{font-size:12px;color:#5b6570}
table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;font-size:13px}
th,td{padding:8px;text-align:left;border-bottom:1px solid #eef0f2;vertical-align:top}th{background:#eef3f8;font-weight:600}
a{color:#0a66c2;text-decoration:none}.pill{display:inline-block;padding:1px 8px;border-radius:10px;font-size:11px;background:#eef3f8}
.hi{background:#dff3e4}.mid{background:#fff4d6}.lo{background:#fde7e7}.muted{color:#8a949e}
</style></head><body>"""
        )
        sb.append("<h1>JobPilot report</h1><div class='sub'>${esc(p.name.ifBlank { "Your" })} job search · generated ${dateFmt.format(Date(now))}</div>")

        sb.append("<div class='grid'>")
        fun stat(n: Any, l: String) = sb.append("<div class='card'><div class='n'>$n</div><div class='l'>$l</div></div>")
        stat(jobs.size, "jobs tracked"); stat(new24, "new in last 24h"); stat(new7, "new this week")
        stat(strong, "strong matches (70+)"); stat("$avg%", "average match")
        stat(jobs.count { it.status == JobStatus.APPLIED }, "applied")
        stat("$contacted/${data.contacts.size}", "people contacted"); stat(replied, "replies"); stat(referred, "referrals")
        stat(sent, "emails sent"); stat(pendingEmails, "emails awaiting review"); stat(followUps.size, "follow-ups due")
        sb.append("</div>")

        // Searches
        sb.append("<h2>Your searches</h2><table><tr><th>Search</th><th>Jobs</th><th>Strong</th><th>Last 7 days</th></tr>")
        for (s in data.searches) {
            val js = jobs.filter { it.searchId == s.id }
            sb.append("<tr><td>${esc(s.label())}<div class='muted'>${s.experience.label} · ${s.workType.label} · ${s.postedWithin.label}${if (!s.enabled) " · paused" else ""}</div></td>")
            sb.append("<td>${js.size}</td><td>${js.count { it.matchScore >= 70 }}</td><td>${js.count { now - it.foundAt < 7 * day }}</td></tr>")
        }
        sb.append("</table>")

        // Top matches
        sb.append("<h2>Top matches</h2><table><tr><th>Role</th><th>Match</th><th>Status</th></tr>")
        for (j in active.sortedWith(compareByDescending<Job> { it.matchScore }.thenByDescending { it.foundAt }).take(40)) {
            val cls = when { j.matchScore >= 70 -> "hi"; j.matchScore >= 45 -> "mid"; else -> "lo" }
            sb.append("<tr><td><a href='${esc(j.url)}'>${esc(j.title)}</a><div>${esc(j.company)}</div><div class='muted'>${esc(j.location)} · ${esc(j.source)} · found ${d(j.foundAt)}</div>")
            if (j.matchedSkills.isNotEmpty()) sb.append("<div class='muted'>✓ ${esc(j.matchedSkills.joinToString(", "))}</div>")
            if (j.missingSkills.isNotEmpty() && j.detailsFetched) sb.append("<div class='muted'>✗ ${esc(j.missingSkills.take(5).joinToString(", "))}</div>")
            sb.append("</td><td><span class='pill $cls'>${j.matchScore}%</span></td><td>${j.status.label}</td></tr>")
        }
        if (active.isEmpty()) sb.append("<tr><td colspan='3' class='muted'>No jobs yet — run a search.</td></tr>")
        sb.append("</table>")

        // Referral pipeline
        sb.append("<h2>Referral outreach</h2><table><tr><th>Person</th><th>For</th><th>Status</th></tr>")
        for (c in data.contacts.sortedByDescending { maxOf(it.contactedAt, it.addedAt) }) {
            val j = jobByKey[c.jobKey]
            sb.append("<tr><td><a href='${esc(c.profileUrl)}'>${esc(c.name)}</a><div class='muted'>${esc(c.headline.take(90))}</div></td>")
            sb.append("<td>${esc(j?.title ?: c.company)}<div class='muted'>${esc(j?.company ?: "")}</div></td>")
            sb.append("<td>${c.status.label}<div class='muted'>${if (c.contactedAt > 0) "contacted ${d(c.contactedAt)}" else c.kind.label}</div></td></tr>")
        }
        if (data.contacts.isEmpty()) sb.append("<tr><td colspan='3' class='muted'>No people yet — use “Find referrers” on a job.</td></tr>")
        sb.append("</table>")

        if (followUps.isNotEmpty()) {
            sb.append("<h2>Follow-ups due</h2><table><tr><th>Person</th><th>Contacted</th></tr>")
            followUps.forEach { sb.append("<tr><td><a href='${esc(it.profileUrl)}'>${esc(it.name)}</a> · ${esc(it.company)}</td><td>${d(it.contactedAt)}</td></tr>") }
            sb.append("</table>")
        }

        // Emails
        sb.append("<h2>Emails to recruiters</h2><table><tr><th>To</th><th>Role</th><th>Status</th></tr>")
        for (e in data.emails.sortedByDescending { maxOf(it.sentAt, it.createdAt) }) {
            sb.append("<tr><td>${esc(e.to)}<div class='muted'>${esc(e.company)}</div></td><td>${esc(e.role)}</td>")
            sb.append("<td>${e.status.label}<div class='muted'>${if (e.sentAt > 0) "${d(e.sentAt)} via ${esc(e.via)}" else esc(e.error.take(60))}</div></td></tr>")
        }
        if (data.emails.isEmpty()) sb.append("<tr><td colspan='3' class='muted'>No recruiter emails found yet.</td></tr>")
        sb.append("</table>")

        if (gaps.isNotEmpty()) {
            sb.append("<h2>Skill gaps in your best matches</h2><table><tr><th>Skill you listed</th><th>Missing from # of top jobs</th></tr>")
            gaps.forEach { sb.append("<tr><td>${esc(it.key)}</td><td>${it.value}</td></tr>") }
            sb.append("</table><div class='sub'>These are skills on your profile that top jobs don't mention — consider leading with the ones that do.</div>")
        }
        if (companies.isNotEmpty()) {
            sb.append("<h2>Companies hiring most</h2><table><tr><th>Company</th><th>Open roles</th></tr>")
            companies.forEach { sb.append("<tr><td>${esc(it.key.ifBlank { "—" })}</td><td>${it.value}</td></tr>") }
            sb.append("</table>")
        }

        sb.append("<h2>Recent runs</h2><table><tr><th>When</th><th>Seen</th><th>New</th><th>Emails</th><th>Notes</th></tr>")
        data.runs.take(10).forEach { r ->
            sb.append("<tr><td>${dateFmt.format(Date(r.at))}</td><td>${r.jobsSeen}</td><td>${r.newJobs}</td><td>${r.newEmails}</td><td class='muted'>${esc(r.errors.joinToString("; ").take(160))}</td></tr>")
        }
        sb.append("</table><p class='sub'>Generated by JobPilot on your phone.</p></body></html>")
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
