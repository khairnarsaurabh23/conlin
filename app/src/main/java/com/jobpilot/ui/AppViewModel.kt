package com.jobpilot.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jobpilot.core.Emails
import com.jobpilot.core.Guard
import com.jobpilot.core.JobRunner
import com.jobpilot.core.MailScan
import com.jobpilot.core.Mailer
import com.jobpilot.core.Rank
import com.jobpilot.core.Report
import com.jobpilot.core.ResumePdf
import com.jobpilot.core.Tailor
import com.jobpilot.core.Why
import com.jobpilot.data.ActionKind
import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.EmailLead
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Profile
import com.jobpilot.data.SearchPref
import com.jobpilot.data.Stage
import com.jobpilot.data.Store
import com.jobpilot.work.SearchWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class Tab(val title: String) { INBOX("Inbox"), TRACKER("Tracker"), LINKEDIN("LinkedIn"), INSIGHTS("Insights") }

data class CapturedPerson(
    val name: String, val headline: String, val url: String, val degree: Int = 0,
    /** Text of the hiring post this person wrote, when captured from a posts page. */
    val post: String = "", val postUrl: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()

    val data: StateFlow<AppData> = Store.state

    private val _tab = MutableStateFlow(Tab.INBOX)
    val tab = _tab.asStateFlow()

    /** Full-screen pages on top of the tabs */
    private val _openJob = MutableStateFlow<String?>(null)
    val openJob = _openJob.asStateFlow()
    private val _showSettings = MutableStateFlow(false)
    val showSettings = _showSettings.asStateFlow()
    private val _reportFile = MutableStateFlow<File?>(null)
    val reportFile = _reportFile.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    /** Built-in browser: where to go next, which job captures belong to, and why we are searching. */
    private val _browserUrl = MutableStateFlow<String?>(null)
    val browserUrl = _browserUrl.asStateFlow()
    private val _browserJob = MutableStateFlow<String?>(null)
    val browserJob = _browserJob.asStateFlow()
    private val _browserTag = MutableStateFlow("")
    val browserTag = _browserTag.asStateFlow()

    init {
        viewModelScope.launch { JobRunner.housekeeping() }
    }

    fun setTab(t: Tab) { _tab.value = t }
    fun openJob(key: String?) { _openJob.value = key }
    fun showSettings(v: Boolean) { _showSettings.value = v }
    fun closeReport() { _reportFile.value = null }
    fun toast(msg: String) { _toast.value = msg }
    fun toastShown() { _toast.value = null }
    fun browserNavigated() { _browserUrl.value = null }
    fun setBrowserJob(key: String?) { _browserJob.value = key }
    fun setBrowserTag(tag: String) { _browserTag.value = tag }

    private fun launchBusy(label: String, block: suspend () -> Unit) {
        if (_busy.value != null) { toast("One moment: ${_busy.value}"); return }
        _busy.value = label
        viewModelScope.launch {
            try { block() } catch (e: Exception) { toast("Something went wrong: ${e.message}") } finally { _busy.value = null }
        }
    }

    private fun edit(block: suspend () -> Unit) = viewModelScope.launch { block() }

    // ---------------- Checking for jobs and mail ----------------

    fun runSearch() = launchBusy("Checking for jobs") {
        val log = JobRunner.runAll { msg -> _busy.value = msg }
        var msg = buildString {
            append("${log.newJobs} new job(s)")
            if (log.newEmails > 0) append(", ${log.newEmails} recruiter email(s)")
            if (log.newRecruiters > 0) append(", ${log.newRecruiters} recruiter(s)")
            if (log.errors.isNotEmpty()) append(". ").append(log.errors.first())
        }
        val p = Store.data.profile
        if (p.mailScanEnabled && p.smtpConfigured) {
            _busy.value = "Checking your inbox for replies"
            val scan = MailScan.run()
            if (scan.error.isBlank() && scan.accepted + scan.replies + scan.interviews + scan.rejections > 0) msg += ". " + MailScan.summary(scan)
        }
        toast(msg)
    }

    fun scanMail() = launchBusy("Checking your inbox for replies") { toast(MailScan.summary(MailScan.run())) }

    fun fetchDetails(key: String) = launchBusy("Reading the job page") {
        val errs = mutableListOf<String>()
        if (JobRunner.enrich(key, errs)) {
            Store.data.jobs.firstOrNull { it.key == key }?.let { JobRunner.queueEmails(listOf(it)) }
            toast("Job details updated")
        } else toast(errs.firstOrNull() ?: "Couldn't load the details")
    }

    fun rescoreAll() = edit {
        Store.update { d -> JobRunner.rescoreAll(d) }
    }

    // ---------------- Jobs ----------------

    // Keeping and skipping teaches the scorer, so the remaining jobs are re-scored each time.
    fun keepJob(key: String) = edit {
        Store.update { d -> JobRunner.rescoreAll(d.copy(jobs = d.jobs.map { if (it.key == key) it.copy(status = JobStatus.SAVED) else it })) }
    }
    fun skipJob(key: String) = edit {
        Store.update { d -> JobRunner.rescoreAll(d.copy(jobs = d.jobs.map { if (it.key == key) it.copy(status = JobStatus.IGNORED) else it })) }
    }
    fun setJobStatus(key: String, s: JobStatus) = edit { Store.updateJob(key) { it.copy(status = s) } }
    fun setJobNotes(key: String, n: String) = edit { Store.updateJob(key) { it.copy(notes = n) } }
    fun dismissPeoplePrompt(key: String) = edit { Store.updateJob(key) { it.copy(peoplePrompted = true) } }
    fun deleteJob(key: String) = edit { Store.deleteJob(key); _openJob.value = null }

    fun skipWeakJobs(below: Int) = edit {
        Store.update { d -> d.copy(jobs = d.jobs.map { if (it.status == JobStatus.NEW && it.matchScore < below) it.copy(status = JobStatus.IGNORED) else it }) }
    }

    fun clearSkipped() = edit {
        Store.update { d -> d.copy(jobs = d.jobs.filterNot { it.status == JobStatus.IGNORED || it.status == JobStatus.REJECTED }) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Opens a people search for the job. tag: "" (same role), "alumni", "colleague", "recruiter". */
    fun findPeople(job: Job, tag: String = "") {
        val p = Store.data.profile
        val extra = when (tag) {
            "alumni" -> p.college
            "colleague" -> p.pastCompanyList().firstOrNull().orEmpty()
            "recruiter" -> "recruiter"
            else -> JobRunner.roleOf(job)
        }
        _browserJob.value = job.key
        _browserTag.value = tag
        _browserUrl.value = "https://www.linkedin.com/search/results/people/?keywords=${enc((job.company + " " + extra).trim())}"
        _openJob.value = null
        _tab.value = Tab.LINKEDIN
        edit { Store.updateJob(job.key) { it.copy(peoplePrompted = true) } }
    }

    fun findHiringPosts(job: Job?) {
        val role = job?.let { JobRunner.roleOf(it) } ?: JobRunner.defaultRole(Store.data)
        _browserJob.value = job?.key
        _browserTag.value = ""
        _browserUrl.value = "https://www.linkedin.com/search/results/content/?keywords=${enc("hiring $role send resume email")}&sortBy=%22date_posted%22"
        _openJob.value = null
        _tab.value = Tab.LINKEDIN
    }

    fun queueEmailsFromJob(job: Job) = edit {
        var n = 0
        Emails.extract(job.description).forEach {
            if (JobRunner.addEmailLead(it.email, it.context, job.url, job.company, JobRunner.roleOf(job), job.key)) n++
        }
        toast(if (n > 0) "$n email draft(s) added to your inbox" else "Those emails are already queued")
    }

    // ---------------- Resume tailoring ----------------

    fun tailor(job: Job): Tailor.Result = Tailor.build(Store.data.profile, job)

    fun createTailoredResume(job: Job, summary: String, skills: List<String>) = launchBusy("Building the resume") {
        val f = withContext(Dispatchers.IO) { ResumePdf.build(ctx.filesDir, Store.data.profile, job, summary, skills) }
        Store.updateJob(job.key) { it.copy(tailoredSummary = summary, tailoredResume = f.name) }
        toast("Tailored resume saved. Emails for this job will attach it.")
    }

    fun removeTailoredResume(job: Job) = edit {
        File(ctx.filesDir, job.tailoredResume).delete()
        Store.updateJob(job.key) { it.copy(tailoredResume = "") }
    }

    // ---------------- People ----------------

    fun addCapturedPeople(people: List<CapturedPerson>, jobKey: String?, tag: String) = edit {
        val job = Store.data.jobs.firstOrNull { it.key == jobKey }
        var added = 0; var connected = 0
        for (cp in people) {
            val existing = Store.data.contacts.firstOrNull { Store.sameProfile(it.profileUrl, cp.url) }
            if (existing != null) {
                // Seeing an invited person as a 1st-degree connection means they accepted.
                if (cp.degree == 1 && (existing.stage == Stage.INVITED || existing.stage == Stage.APPROVED || existing.stage == Stage.SUGGESTED)) {
                    Store.updateContact(existing.id) { it.copy(stage = Stage.CONNECTED, degree = 1, connectedAt = System.currentTimeMillis(), openedAt = 0) }
                    connected++
                }
                continue
            }
            val snippet = Why.snippet(cp.post)
            val c = JobRunner.newContact(
                cp.name, cp.headline, cp.url, job, degree = cp.degree, tag = tag,
                source = if (snippet.isNotBlank()) "post" else "search", postSnippet = snippet, postUrl = cp.postUrl,
            )
            if (Store.addContact(c)) added++
        }
        toast(buildString {
            append("$added suggestion(s) added to your inbox")
            if (connected > 0) append(", $connected marked as connected")
        })
    }

    fun addCapturedEmails(found: List<Emails.Found>, sourceUrl: String, jobKey: String?) = edit {
        val job = Store.data.jobs.firstOrNull { it.key == jobKey }
        var n = 0
        for (f in found) {
            if (JobRunner.addEmailLead(f.email, f.context, sourceUrl, job?.company.orEmpty(), JobRunner.roleOf(job), job?.key.orEmpty())) n++
        }
        toast("$n email draft(s) added to your inbox")
    }

    fun approveInvite(id: String) = edit { Store.updateContact(id) { it.copy(stage = Stage.APPROVED) } }
    fun approveAsk(id: String) = edit { Store.updateContact(id) { it.copy(stage = Stage.ASK_APPROVED) } }
    fun unapprove(id: String) = edit {
        Store.updateContact(id) {
            when (it.stage) {
                Stage.APPROVED -> it.copy(stage = Stage.SUGGESTED, openedAt = 0)
                Stage.ASK_APPROVED -> it.copy(stage = if (it.followUps > 0) Stage.ASKED else Stage.CONNECTED, openedAt = 0)
                else -> it
            }
        }
    }
    fun skipContact(id: String) = edit { Store.updateContact(id) { it.copy(stage = Stage.SKIPPED, openedAt = 0) } }
    fun setStage(id: String, s: Stage) = edit {
        val now = System.currentTimeMillis()
        Store.updateContact(id) {
            it.copy(
                stage = s, openedAt = 0,
                connectedAt = if (s == Stage.CONNECTED && it.connectedAt == 0L) now else it.connectedAt,
                repliedAt = if ((s == Stage.REPLIED || s == Stage.REFERRED) && it.repliedAt == 0L) now else it.repliedAt,
            )
        }
    }
    fun saveContactText(id: String, note: String, ask: String) = edit { Store.updateContact(id) { it.copy(note = note, ask = ask) } }
    fun deleteContact(id: String) = edit { Store.deleteContact(id) }

    fun approveFollowUp(c: Contact) = edit {
        val job = Store.data.jobs.firstOrNull { it.key == c.jobKey }
        Store.updateContact(c.id) {
            it.copy(stage = Stage.ASK_APPROVED, ask = JobRunner.draftFollowUp(Store.data.profile, it, job), followUps = it.followUps + 1)
        }
    }

    fun closeStaleInvites() = edit {
        val now = System.currentTimeMillis()
        val limit = TimeUnit.DAYS.toMillis(Store.data.profile.staleInviteDays.toLong())
        Store.update { d ->
            d.copy(contacts = d.contacts.map { if (it.stage == Stage.INVITED && now - it.invitedAt > limit) it.copy(stage = Stage.NO_RESPONSE) else it })
        }
    }

    fun redraft(c: Contact) = edit {
        val job = Store.data.jobs.firstOrNull { it.key == c.jobKey }
        val d = Store.data
        Store.updateContact(c.id) { it.copy(note = JobRunner.draftNote(d, it, job), ask = JobRunner.draftAsk(d, it, job)) }
    }

    fun linkContactToJob(c: Contact, job: Job) = edit {
        val d = Store.data
        Store.updateContact(c.id) {
            val linked = it.copy(jobKey = job.key, company = job.company)
            val r = Rank.score(
                linked.headline, linked.degree, linked.kind, d.profile, job,
                wroteHiringPost = linked.postSnippet.isNotBlank(), namedOnPosting = linked.source == "posting",
                openings = Why.openingsAt(job.company, d).size,
            )
            linked.copy(rank = r.score, why = r.why, note = JobRunner.draftNote(d, linked, job), ask = JobRunner.draftAsk(d, linked, job))
        }
    }

    /**
     * Step one of sending: budget check, copy the text, open the profile. Nothing is counted yet;
     * the user confirms afterwards whether it actually went out.
     */
    fun openForSend(c: Contact) {
        val d = Store.data
        val now = System.currentTimeMillis()
        val isInvite = c.stage == Stage.APPROVED
        val check = if (isInvite) Guard.canInvite(d, now) else Guard.canMessage(d, now)
        if (!check.ok) { toast(check.reason); return }
        val text = if (isInvite) c.note else c.ask
        if (text.isNotBlank()) {
            ctx.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(if (isInvite) "Connection note" else "Message", text))
        }
        if (d.profile.openInLinkedInApp) openUrl(c.profileUrl) else {
            _browserUrl.value = c.profileUrl
            _tab.value = Tab.LINKEDIN
        }
        edit { Store.updateContact(c.id) { it.copy(openedAt = now) } }
        toast(
            when {
                !isInvite -> "Message copied. Tap Message on the profile, paste and send."
                text.isBlank() -> "Tap Connect on the profile."
                else -> "Note copied. Tap Connect, then Add a note, paste and send."
            }
        )
    }

    fun confirmSent(c: Contact, sent: Boolean) = edit {
        val now = System.currentTimeMillis()
        if (!sent) { Store.updateContact(c.id) { it.copy(openedAt = 0) }; return@edit }
        if (c.stage == Stage.APPROVED) {
            Store.updateContact(c.id) { it.copy(stage = Stage.INVITED, invitedAt = now, openedAt = 0) }
            Store.recordAction(ActionKind.INVITE, c.id)
        } else if (c.stage == Stage.ASK_APPROVED) {
            Store.updateContact(c.id) { it.copy(stage = Stage.ASKED, askedAt = now, openedAt = 0) }
            Store.recordAction(ActionKind.MESSAGE, c.id)
            if (c.jobKey.isNotBlank()) Store.updateJob(c.jobKey) { j ->
                if (j.status == JobStatus.NEW || j.status == JobStatus.SAVED || j.status == JobStatus.APPLIED) j.copy(status = JobStatus.REFERRAL_ASKED) else j
            }
        }
    }

    // ---------------- Account safety ----------------

    /** kind: "limit" (weekly invitation limit), "restricted", "challenge" (security check). */
    fun onRestrictionSignal(kind: String, fromBrowser: Boolean = true) = edit {
        val now = System.currentTimeMillis()
        val d = Store.data
        when (kind) {
            "limit" -> {
                if (d.safety.pausedUntil > now + TimeUnit.DAYS.toMillis(6)) return@edit
                Store.pause(24 * 7, "LinkedIn showed the weekly invitation limit.", invitesOnly = true)
                toast("Connection requests paused for 7 days")
            }
            "restricted" -> {
                if (d.safety.pausedUntil > now + TimeUnit.DAYS.toMillis(6) && !d.safety.invitesOnly) return@edit
                Store.pause(24 * 7, "LinkedIn restricted the account. Don't send anything until it clears.", invitesOnly = false)
                toast("All outreach paused for 7 days")
            }
            "challenge" -> {
                // A security check right after outreach is a warning sign; on its own it is just a login step.
                val recent = d.actions.any { it.kind != ActionKind.EMAIL && now - it.at < TimeUnit.HOURS.toMillis(2) }
                if ((!fromBrowser || recent) && d.safety.pausedUntil < now) {
                    Store.pause(24, "LinkedIn asked for a security check after outreach.", invitesOnly = false)
                    toast("Outreach paused for 24 hours")
                }
            }
        }
    }

    fun resumeOutreach() = edit { Store.resume() }

    // ---------------- Emails ----------------

    fun saveEmail(e: EmailLead) = edit { Store.upsertEmail(e) }
    fun setEmailStatus(id: String, s: EmailStatus) = edit { Store.updateEmail(id) { it.copy(status = s, openedAt = 0) } }
    fun deleteEmail(id: String) = edit { Store.deleteEmail(id) }

    fun approveAllDrafts() = edit {
        Store.update { d -> d.copy(emails = d.emails.map { if (it.status == EmailStatus.DRAFT) it.copy(status = EmailStatus.APPROVED) else it }) }
    }

    fun sendApprovedSmtp() = launchBusy("Sending emails") { toast(Mailer.sendApproved { _busy.value = it }) }

    /** Opens the email in Gmail with the resume attached. The user confirms afterwards. */
    fun openInGmail(e: EmailLead) {
        if (Guard.emailsLeftToday(Store.data, System.currentTimeMillis()) <= 0) { toast("Daily email limit reached"); return }
        try {
            ctx.startActivity(Mailer.composeIntent(ctx, e).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            edit { Store.updateEmail(e.id) { it.copy(openedAt = System.currentTimeMillis()) } }
        } catch (ex: Exception) {
            toast("No email app found")
        }
    }

    fun confirmEmailSent(e: EmailLead, sent: Boolean) = edit {
        if (sent) Mailer.markSent(e, "Gmail app") else Store.updateEmail(e.id) { it.copy(openedAt = 0) }
    }

    // ---------------- Settings ----------------

    fun saveProfile(p: Profile) = edit {
        Store.setProfile(p)
        SearchWorker.schedule(ctx)
        rescoreAll()
        toast("Saved")
    }

    fun saveSearch(s: SearchPref) = edit { Store.upsertSearch(s) }
    fun deleteSearch(id: String) = edit { Store.deleteSearch(id) }

    fun importResume(uri: Uri) = edit {
        try {
            val name = withContext(Dispatchers.IO) {
                var display = "resume.pdf"
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) display = c.getString(0) ?: display
                }
                val ext = display.substringAfterLast('.', "pdf").lowercase().take(5)
                val target = "resume.$ext"
                ctx.filesDir.listFiles()?.filter { it.name.startsWith("resume.") }?.forEach { it.delete() }
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    File(ctx.filesDir, target).outputStream().use { input.copyTo(it) }
                } ?: error("Can't read the file")
                target
            }
            Store.setProfile(Store.data.profile.copy(resumeFileName = name))
            toast("Resume attached")
        } catch (e: Exception) {
            toast("Couldn't import the resume: ${e.message}")
        }
    }

    fun importProfileFromPage(name: String, headline: String, url: String) = edit {
        val p = Store.data.profile
        Store.setProfile(
            p.copy(
                name = name.ifBlank { p.name },
                headline = headline.ifBlank { p.headline },
                linkedinUrl = url.ifBlank { p.linkedinUrl },
            )
        )
        toast("Profile updated from LinkedIn")
    }

    // ---------------- Report ----------------

    fun buildReport() = launchBusy("Building the report") {
        _reportFile.value = withContext(Dispatchers.IO) { Report.save(ctx.filesDir, Store.data) }
    }

    fun exportCsv(onReady: (File) -> Unit) = edit {
        onReady(withContext(Dispatchers.IO) { Report.saveCsv(ctx.filesDir, Store.data) })
    }

    // ---------------- Shared into the app ----------------

    fun handleShared(text: String) = launchBusy("Reading what you shared") { toast(JobRunner.handleShared(text)) }

    fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Can't open the link")
        }
    }

    fun fileFor(name: String): File = File(ctx.filesDir, name)
}
