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
import com.jobpilot.core.JobRunner
import com.jobpilot.core.Mailer
import com.jobpilot.core.Report
import com.jobpilot.core.Templates
import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.ContactKind
import com.jobpilot.data.ContactStatus
import com.jobpilot.data.EmailLead
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Profile
import com.jobpilot.data.SearchPref
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

enum class Tab(val title: String) { HOME("Home"), JOBS("Jobs"), PEOPLE("People"), EMAILS("Emails"), BROWSER("LinkedIn") }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()

    val data: StateFlow<AppData> = Store.state

    private val _tab = MutableStateFlow(Tab.HOME)
    val tab = _tab.asStateFlow()

    /** Overlays on top of the tabs */
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

    /** In-app browser: where to go next, and which job captured people/emails belong to. */
    private val _browserUrl = MutableStateFlow<String?>(null)
    val browserUrl = _browserUrl.asStateFlow()
    private val _browserJob = MutableStateFlow<String?>(null)
    val browserJob = _browserJob.asStateFlow()

    fun setTab(t: Tab) { _tab.value = t }
    fun openJob(key: String?) { _openJob.value = key }
    fun showSettings(v: Boolean) { _showSettings.value = v }
    fun closeReport() { _reportFile.value = null }
    fun toast(msg: String) { _toast.value = msg }
    fun toastShown() { _toast.value = null }
    fun browserNavigated() { _browserUrl.value = null }
    fun setBrowserJob(key: String?) { _browserJob.value = key }

    private fun launchBusy(label: String, block: suspend () -> Unit) {
        if (_busy.value != null) { toast("Please wait — ${_busy.value}"); return }
        _busy.value = label
        viewModelScope.launch {
            try { block() } catch (e: Exception) { toast("Error: ${e.message}") } finally { _busy.value = null }
        }
    }

    // ---------------- Searching ----------------

    fun runSearch() = launchBusy("Searching…") {
        val log = JobRunner.runAll { msg -> _busy.value = msg }
        val msg = buildString {
            append("${log.newJobs} new jobs (${log.jobsSeen} seen)")
            if (log.newEmails > 0) append(", ${log.newEmails} recruiter emails")
            if (log.newRecruiters > 0) append(", ${log.newRecruiters} recruiters")
            if (log.errors.isNotEmpty()) append(". ").append(log.errors.first())
        }
        toast(msg)
    }

    fun fetchDetails(key: String) = launchBusy("Reading job…") {
        val errs = mutableListOf<String>()
        val ok = JobRunner.enrich(key, errs)
        if (ok) {
            Store.data.jobs.firstOrNull { it.key == key }?.let { JobRunner.queueOutreach(listOf(it)) }
            toast("Job details updated")
        } else toast(errs.firstOrNull() ?: "Couldn't load details")
    }

    fun rescoreAll() = viewModelScope.launch {
        Store.update { d ->
            val kw = d.searches.associate { it.id to it.keywords }
            d.copy(jobs = d.jobs.map { JobRunner.rescore(it, d.profile, kw[it.searchId] ?: JobRunner.defaultRole(d)) })
        }
    }

    // ---------------- Jobs ----------------

    fun setJobStatus(key: String, s: JobStatus) = viewModelScope.launch { Store.updateJob(key) { it.copy(status = s) } }
    fun setJobNotes(key: String, n: String) = viewModelScope.launch { Store.updateJob(key) { it.copy(notes = n) } }
    fun deleteJob(key: String) = viewModelScope.launch { Store.deleteJob(key); _openJob.value = null }

    fun clearIgnored() = viewModelScope.launch {
        Store.update { d -> d.copy(jobs = d.jobs.filterNot { it.status == JobStatus.IGNORED || it.status == JobStatus.REJECTED }) }
    }

    fun findReferrers(job: Job) {
        val role = job.title.split(Regex("[-–(|,]")).first().trim()
        val q = URLEncoder.encode("${job.company} $role", "UTF-8")
        _browserJob.value = job.key
        _browserUrl.value = "https://www.linkedin.com/search/results/people/?keywords=$q&origin=GLOBAL_SEARCH_HEADER"
        _openJob.value = null
        _tab.value = Tab.BROWSER
    }

    fun findHiringPosts(job: Job?) {
        val role = job?.title?.split(Regex("[-–(|,]"))?.first()?.trim() ?: JobRunner.defaultRole(Store.data)
        val q = URLEncoder.encode("hiring $role send resume email", "UTF-8")
        _browserJob.value = job?.key
        _browserUrl.value = "https://www.linkedin.com/search/results/content/?keywords=$q&sortBy=%22date_posted%22"
        _openJob.value = null
        _tab.value = Tab.BROWSER
    }

    fun openInBrowserTab(url: String) {
        _browserUrl.value = url
        _tab.value = Tab.BROWSER
    }

    fun queueEmailsFromJob(job: Job) = viewModelScope.launch {
        var n = 0
        Emails.extract(job.description).forEach {
            if (JobRunner.addEmailLead(it.email, it.context, job.url, job.company, job.title, job.key)) n++
        }
        toast(if (n > 0) "$n email draft(s) added" else "Those emails are already queued")
    }

    // ---------------- People / referrals ----------------

    fun addCapturedPeople(people: List<CapturedPerson>, jobKey: String?) = viewModelScope.launch {
        val job = Store.data.jobs.firstOrNull { it.key == jobKey }
        val p = Store.data.profile
        var n = 0
        for (cp in people) {
            if (Store.data.contacts.any { Store.sameProfile(it.profileUrl, cp.url) }) continue
            Store.upsertContact(
                Contact(
                    id = Store.newId(), name = cp.name, headline = cp.headline, profileUrl = cp.url,
                    company = job?.company.orEmpty(), jobKey = job?.key.orEmpty(),
                    kind = if (cp.headline.contains(Regex("(?i)recruit|talent|hiring|HR\\b"))) ContactKind.RECRUITER else ContactKind.EMPLOYEE,
                    message = JobRunner.referralMessage(p, cp.name, job?.company.orEmpty(), job?.title.orEmpty(), job?.url.orEmpty()),
                )
            )
            n++
        }
        toast("Added $n people" + if (job != null) " for ${job.company}" else "")
    }

    fun addCapturedEmails(found: List<Emails.Found>, sourceUrl: String, jobKey: String?) = viewModelScope.launch {
        val job = Store.data.jobs.firstOrNull { it.key == jobKey }
        var n = 0
        for (f in found) {
            if (JobRunner.addEmailLead(f.email, f.context, sourceUrl, job?.company.orEmpty(), job?.title.orEmpty(), job?.key.orEmpty())) n++
        }
        toast("$n email draft(s) added — review them in Emails")
    }

    /** The one-tap flow: copy the note, open the profile in LinkedIn, mark as contacted. */
    fun contactPerson(c: Contact, followUp: Boolean = false) {
        val text = if (followUp) followUpMessage(c) else c.message.ifBlank { regenerateMessageText(c) }
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Referral note", text))
        openUrl(c.profileUrl)
        viewModelScope.launch {
            Store.updateContact(c.id) { it.copy(status = ContactStatus.CONTACTED, contactedAt = System.currentTimeMillis()) }
            if (c.jobKey.isNotBlank()) Store.updateJob(c.jobKey) { j ->
                if (j.status == JobStatus.NEW || j.status == JobStatus.SAVED) j.copy(status = JobStatus.REFERRAL_ASKED) else j
            }
        }
        toast("Note copied. In LinkedIn tap Connect → Add a note (or Message) → paste → Send.")
    }

    fun followUpMessage(c: Contact): String {
        val job = Store.data.jobs.firstOrNull { it.key == c.jobKey }
        val tpl = "Hi {firstName}, just following up on my note about the {role} role at {company}. " +
            "I'd really appreciate a referral if you think I'm a fit — happy to send my resume. Thanks!"
        return Templates.fit(
            Templates.fill(tpl, Store.data.profile, c.name, job?.company ?: c.company, job?.title.orEmpty(), job?.url.orEmpty()),
            Store.data.profile.referralCharLimit
        )
    }

    private fun regenerateMessageText(c: Contact): String {
        val job = Store.data.jobs.firstOrNull { it.key == c.jobKey }
        return JobRunner.referralMessage(
            Store.data.profile, c.name, job?.company ?: c.company, job?.title.orEmpty(), job?.url.orEmpty(),
            recruiter = c.kind == ContactKind.RECRUITER
        )
    }

    fun regenerateMessage(c: Contact) = viewModelScope.launch {
        Store.updateContact(c.id) { it.copy(message = regenerateMessageText(it)) }
    }

    fun linkContactToJob(c: Contact, job: Job) = viewModelScope.launch {
        Store.updateContact(c.id) {
            val linked = it.copy(jobKey = job.key, company = job.company)
            linked.copy(message = regenerateMessageText(linked))
        }
    }

    fun setContactStatus(id: String, s: ContactStatus) = viewModelScope.launch { Store.updateContact(id) { it.copy(status = s) } }
    fun setContactMessage(id: String, m: String) = viewModelScope.launch { Store.updateContact(id) { it.copy(message = m) } }
    fun deleteContact(id: String) = viewModelScope.launch { Store.deleteContact(id) }

    // ---------------- Emails ----------------

    fun saveEmail(e: EmailLead) = viewModelScope.launch { Store.upsertEmail(e) }
    fun setEmailStatus(id: String, s: EmailStatus) = viewModelScope.launch { Store.updateEmail(id) { it.copy(status = s) } }
    fun deleteEmail(id: String) = viewModelScope.launch { Store.deleteEmail(id) }

    fun approveAllDrafts() = viewModelScope.launch {
        Store.update { d -> d.copy(emails = d.emails.map { if (it.status == EmailStatus.DRAFT) it.copy(status = EmailStatus.APPROVED) else it }) }
    }

    fun sendApprovedSmtp() = launchBusy("Sending emails…") {
        val summary = Mailer.sendApproved { _busy.value = it }
        toast(summary)
    }

    /** Opens the email in Gmail with resume attached; user taps Send there. */
    fun openInGmail(e: EmailLead) {
        try {
            val i = Mailer.composeIntent(ctx, e).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            viewModelScope.launch {
                Store.updateEmail(e.id) { it.copy(status = EmailStatus.SENT, sentAt = System.currentTimeMillis(), via = "Gmail app") }
                if (e.jobKey.isNotBlank()) Store.updateJob(e.jobKey) { j ->
                    if (j.status == JobStatus.NEW || j.status == JobStatus.SAVED) j.copy(status = JobStatus.APPLIED) else j
                }
            }
        } catch (ex: Exception) {
            toast("No email app found")
        }
    }

    fun openNextApprovedInGmail() {
        val next = Store.data.emails.firstOrNull { it.status == EmailStatus.APPROVED }
        if (next == null) toast("No approved emails left") else openInGmail(next)
    }

    // ---------------- Settings ----------------

    fun saveProfile(p: Profile) = viewModelScope.launch {
        Store.setProfile(p)
        SearchWorker.schedule(ctx)
        rescoreAll()
        toast("Saved")
    }

    fun saveSearch(s: SearchPref) = viewModelScope.launch { Store.upsertSearch(s) }
    fun deleteSearch(id: String) = viewModelScope.launch { Store.deleteSearch(id) }

    fun importResume(uri: Uri) = viewModelScope.launch {
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
                } ?: error("Can't read file")
                target
            }
            Store.setProfile(Store.data.profile.copy(resumeFileName = name))
            toast("Resume attached")
        } catch (e: Exception) {
            toast("Couldn't import resume: ${e.message}")
        }
    }

    fun importProfileFromPage(name: String, headline: String, url: String) = viewModelScope.launch {
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

    fun buildReport() = launchBusy("Building report…") {
        val f = withContext(Dispatchers.IO) { Report.save(ctx.filesDir, Store.data) }
        _reportFile.value = f
    }

    fun exportCsv(onReady: (File) -> Unit) = viewModelScope.launch {
        val f = withContext(Dispatchers.IO) { Report.saveCsv(ctx.filesDir, Store.data) }
        onReady(f)
    }

    // ---------------- Shared into app ----------------

    fun handleShared(text: String) = launchBusy("Reading shared item…") {
        val r = JobRunner.handleShared(text)
        toast(r.summary)
    }

    fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Can't open link")
        }
    }
}

data class CapturedPerson(val name: String, val headline: String, val url: String)
