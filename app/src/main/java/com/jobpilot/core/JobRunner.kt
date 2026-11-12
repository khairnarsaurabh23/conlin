package com.jobpilot.core

import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.ContactKind
import com.jobpilot.data.EmailLead
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Profile
import com.jobpilot.data.RunLog
import com.jobpilot.data.Stage
import com.jobpilot.data.Store
import com.jobpilot.net.Http
import com.jobpilot.net.JSearch
import com.jobpilot.net.LinkedInJobs
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Runs the saved searches, stores new jobs, enriches them and proposes outreach for approval. */
object JobRunner {

    @Volatile var running = false
        private set

    suspend fun runAll(onProgress: (String) -> Unit = {}): RunLog {
        if (running) return RunLog(System.currentTimeMillis(), 0, 0, 0, 0, 0, listOf("A check is already running"))
        running = true
        try {
            return doRun(onProgress)
        } finally {
            running = false
        }
    }

    private suspend fun doRun(onProgress: (String) -> Unit): RunLog {
        val errors = mutableListOf<String>()
        val profile = Store.data.profile
        val searches = Store.data.searches.filter { it.enabled }
        if (searches.isEmpty()) {
            return RunLog(System.currentTimeMillis(), 0, 0, 0, 0, 0, listOf("Add at least one job search in Settings"))
        }

        // 1. Search every source
        val found = mutableListOf<Job>()
        for (s in searches) {
            if (profile.useLinkedIn) {
                onProgress("Searching LinkedIn: ${s.label()}")
                found += LinkedInJobs.search(s) { errors += it }
            }
            if (profile.useJSearch && profile.jsearchApiKey.isNotBlank()) {
                onProgress("Searching JSearch: ${s.label()}")
                found += JSearch.search(s, profile.jsearchApiKey) { errors += it }
            }
        }
        val excludeBySearch = searches.associate { s ->
            s.id to s.excludeWords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        }
        val filtered = found.filter { j ->
            val ex = excludeBySearch[j.searchId].orEmpty()
            j.title.isNotBlank() && ex.none { j.title.lowercase().contains(it) }
        }

        // 2. Merge new jobs (dedupe by key and by title+company across sources)
        val existing = Store.data.jobs
        val keys = existing.map { it.key }.toHashSet()
        val sigs = existing.map { sig(it) }.toHashSet()
        val newJobs = mutableListOf<Job>()
        for (j in filtered) {
            if (j.key in keys || sig(j) in sigs) continue
            keys += j.key; sigs += sig(j)
            newJobs += j
        }
        val kwById = searches.associate { it.id to it.keywords }
        val prefs = TitlePrefs.of(existing)
        val scoredNew = newJobs.map { rescore(it, profile, kwById[it.searchId].orEmpty(), prefs) }
        Store.update { d -> d.copy(jobs = scoredNew + d.jobs) }

        // 3. Fetch full descriptions for the best new LinkedIn jobs
        val toEnrich = scoredNew.filter { !it.detailsFetched && it.key.startsWith("li:") }
            .sortedByDescending { it.matchScore }
            .take(profile.detailFetchLimit)
        val recruitersBefore = Store.data.contacts.size
        var i = 0
        for (j in toEnrich) {
            i++
            onProgress("Reading job $i of ${toEnrich.size}: ${j.title}")
            enrich(j.key, errors)
            delay(1500L + Random.nextLong(1000))
        }

        // 4. Turn emails in descriptions into drafts for approval
        val keysNew = scoredNew.map { it.key }.toSet()
        val emailsAdded = queueEmails(Store.data.jobs.filter { it.key in keysNew })
        housekeeping()

        val log = RunLog(
            at = System.currentTimeMillis(),
            searches = searches.size,
            jobsSeen = filtered.size,
            newJobs = scoredNew.size,
            newEmails = emailsAdded,
            newRecruiters = Store.data.contacts.size - recruitersBefore,
            errors = errors.distinct(),
        )
        Store.update { d -> d.copy(runs = (listOf(log) + d.runs).take(60)) }
        return log
    }

    private fun sig(j: Job) = (j.title.lowercase().replace(Regex("\\W+"), "") + "|" +
        j.company.lowercase().replace(Regex("\\W+"), ""))

    fun rescore(j: Job, p: Profile, keywords: String, prefs: TitlePrefs = TitlePrefs.NONE): Job {
        val r = Matcher.score(j, p, keywords, prefs)
        val mails = if (j.description.isNotBlank()) Emails.extract(j.description).map { it.email } else j.emailsFound
        return j.copy(
            matchScore = r.score, matchedSkills = r.matched, missingSkills = r.missing, emailsFound = mails,
            reasons = r.reasons, cautions = r.cautions,
        )
    }

    fun rescoreAll(d: AppData): AppData {
        val kw = d.searches.associate { it.id to it.keywords }
        val prefs = TitlePrefs.of(d.jobs)
        return d.copy(jobs = d.jobs.map { rescore(it, d.profile, kw[it.searchId] ?: defaultRole(d), prefs) })
    }

    /** Fetch the public job page for one LinkedIn job; also captures the recruiter if shown. */
    suspend fun enrich(jobKey: String, errors: MutableList<String> = mutableListOf()): Boolean {
        val job = Store.data.jobs.firstOrNull { it.key == jobKey } ?: return false
        if (!job.key.startsWith("li:")) return true
        val det = try {
            LinkedInJobs.details(LinkedInJobs.idFromKey(job.key))
        } catch (e: Exception) {
            errors += "Couldn't read details for ${job.title}: ${e.message}"
            return false
        }
        val profile = Store.data.profile
        val kw = Store.data.searches.firstOrNull { it.id == job.searchId }?.keywords.orEmpty()
        val prefs = TitlePrefs.of(Store.data.jobs)
        Store.updateJob(job.key) { rescore(it.copy(description = det.description, criteria = det.criteria, detailsFetched = true), profile, kw, prefs) }
        det.poster?.let { p ->
            val fresh = Store.data.jobs.firstOrNull { it.key == jobKey } ?: job
            Store.addContact(newContact(p.name, p.headline, p.profileUrl, fresh, ContactKind.RECRUITER, source = "posting"))
        }
        return true
    }

    fun kindFor(headline: String): ContactKind =
        if (headline.contains(Regex("(?i)recruit|talent|hiring|\\bHR\\b|human resources|people ops")))
            ContactKind.RECRUITER else ContactKind.EMPLOYEE

    /** Builds a suggestion: ranked, with its evidence, a drafted note and referral ask. Nothing is sent. */
    fun newContact(
        name: String, headline: String, url: String, job: Job?,
        kind: ContactKind = kindFor(headline), degree: Int = 0, tag: String = "",
        source: String = "search", postSnippet: String = "", postUrl: String = "",
    ): Contact {
        val d = Store.data
        val r = Rank.score(
            headline, degree, kind, d.profile, job, tag,
            wroteHiringPost = postSnippet.isNotBlank(), namedOnPosting = source == "posting",
            openings = Why.openingsAt(job?.company.orEmpty(), d).size,
        )
        val tpl = pickTemplate(d.profile.noteTemplateB.isNotBlank(), d.contacts.count { it.template == "A" }, d.contacts.count { it.template == "B" })
        val base = Contact(
            id = Store.newId(), name = name, headline = headline, profileUrl = url.substringBefore("?"),
            company = job?.company.orEmpty(), jobKey = job?.key.orEmpty(), kind = kind,
            degree = degree, rank = r.score, why = r.why, template = tpl,
            source = source, postSnippet = postSnippet, postUrl = postUrl,
            // Existing connections skip the invite step and go straight to the referral ask.
            stage = if (degree == 1) Stage.CONNECTED else Stage.SUGGESTED,
            connectedAt = if (degree == 1) System.currentTimeMillis() else 0,
        )
        return base.copy(note = draftNote(d, base, job), ask = draftAsk(d, base, job))
    }

    private fun pickTemplate(hasB: Boolean, a: Int, b: Int) = if (hasB && b < a) "B" else "A"

    private fun fillFor(template: String, d: AppData, c: Contact, job: Job?): String = Templates.fill(
        template.replace("{opening}", Why.opening(c, job, d)),
        d.profile, c.name, Why.shortName(job?.company ?: c.company), roleOf(job), job?.url.orEmpty(), job?.matchedSkills.orEmpty(),
    )

    fun draftNote(d: AppData, c: Contact, job: Job?): String {
        val p = d.profile
        val tpl = if (c.template == "B" && p.noteTemplateB.isNotBlank()) p.noteTemplateB else p.noteTemplateA
        if (tpl.isBlank()) return ""
        return Templates.fit(fillFor(tpl, d, c, job), p.noteCharLimit)
    }

    fun draftAsk(d: AppData, c: Contact, job: Job?): String = fillFor(d.profile.askTemplate, d, c, job)

    /**
     * One-off upgrades when the app is updated. Nothing the user entered is removed:
     * templates are replaced only if they are still the old defaults, and drafts are
     * rewritten only if they are still exactly what the app generated.
     */
    fun migrate(d0: AppData): AppData {
        if (d0.schema >= 1) return d0
        var d = d0
        val p = d.profile
        val oldNote = p.noteTemplateA == Profile.OLD_NOTE
        val oldAsk = p.askTemplate == Profile.OLD_ASK
        if (oldNote || oldAsk) {
            val jobs = d.jobs.associateBy { it.key }
            val next = p.copy(
                noteTemplateA = if (oldNote) Profile.DEFAULT_NOTE else p.noteTemplateA,
                askTemplate = if (oldAsk) Profile.DEFAULT_ASK else p.askTemplate,
            )
            val before = d
            d = d.copy(profile = next)
            d = d.copy(contacts = d.contacts.map { c ->
                val job = jobs[c.jobKey]
                var out = c
                if (oldNote && c.stage == Stage.SUGGESTED && c.note == draftNote(before, c, job)) out = out.copy(note = draftNote(d, c, job))
                if (oldAsk && (c.stage == Stage.SUGGESTED || c.stage == Stage.CONNECTED) && c.ask == draftAsk(before, c, job)) out = out.copy(ask = draftAsk(d, c, job))
                out
            })
        }
        return rescoreAll(d).copy(schema = 1)
    }

    fun draftFollowUp(p: Profile, c: Contact, job: Job?): String = Templates.fill(
        "Hi {firstName}, following up on my note about the {role} role at {company}. " +
            "If a referral isn't possible, no problem at all. A pointer to the right person would help just as much. Thanks.",
        p, c.name, job?.company ?: c.company, roleOf(job), job?.url.orEmpty(),
    )

    /** "Senior Android Developer (Kotlin) - Remote" -> "Senior Android Developer" */
    fun roleOf(job: Job?): String = job?.title?.split(Regex("\\s[-|]\\s|[(,]"))?.first()?.trim().orEmpty()

    /** Creates email drafts for addresses found in job descriptions. Returns how many were added. */
    suspend fun queueEmails(jobs: List<Job>): Int {
        var n = 0
        for (j in jobs) {
            for (f in Emails.extract(j.description)) {
                if (addEmailLead(f.email, f.context, j.url, company = j.company, role = roleOf(j), jobKey = j.key)) n++
            }
        }
        return n
    }

    /** Adds a draft email if this address hasn't been queued/sent before. */
    suspend fun addEmailLead(
        email: String, context: String, sourceUrl: String,
        company: String = "", role: String = "", jobKey: String = "", name: String = "",
    ): Boolean {
        val addr = email.lowercase().trim()
        val d = Store.data
        if (d.emails.any { it.to.equals(addr, ignoreCase = true) }) return false
        val p = d.profile
        val c = company.ifBlank { Emails.companyFromEmail(addr) }
        val r = role.ifBlank { defaultRole(d) }
        val n = name.ifBlank { Emails.guessNameFromEmail(addr) }
        val hasB = p.emailBodyB.isNotBlank()
        val tpl = pickTemplate(hasB, d.emails.count { it.template == "A" }, d.emails.count { it.template == "B" })
        val subjectT = if (tpl == "B" && p.emailSubjectB.isNotBlank()) p.emailSubjectB else p.emailSubjectA
        val bodyT = if (tpl == "B") p.emailBodyB else p.emailBodyA
        val prefer = d.jobs.firstOrNull { it.key == jobKey }?.matchedSkills.orEmpty()
        Store.upsertEmail(
            EmailLead(
                id = Store.newId(), to = addr, name = n, company = c, role = r, jobKey = jobKey,
                sourceUrl = sourceUrl, context = context.take(600), template = tpl,
                subject = Templates.fill(subjectT, p, n, c, r, sourceUrl, prefer),
                body = Templates.fill(bodyT, p, n, c, r, sourceUrl, prefer),
                attachResume = Store.resumeFile() != null,
            )
        )
        return true
    }

    fun defaultRole(d: AppData): String =
        d.searches.firstOrNull { it.enabled }?.keywords
            ?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
            .orEmpty()

    /** Closes out asks that got no answer after one follow-up. */
    suspend fun housekeeping() {
        val now = System.currentTimeMillis()
        val wait = TimeUnit.DAYS.toMillis(Store.data.profile.followUpDays.toLong())
        Store.update { d ->
            if (d.contacts.none { it.stage == Stage.ASKED && it.followUps >= 1 && now - it.askedAt > wait }) d
            else d.copy(contacts = d.contacts.map {
                if (it.stage == Stage.ASKED && it.followUps >= 1 && now - it.askedAt > wait) it.copy(stage = Stage.NO_RESPONSE) else it
            })
        }
    }

    // ---------- Things shared into the app from LinkedIn / browser ----------

    suspend fun handleShared(text: String): String {
        val t = text.trim()
        val profileUrl = Regex("https?://[a-z.]*linkedin\\.com/in/[A-Za-z0-9_%\\-]+/?").find(t)?.value
        val jobId = Regex("linkedin\\.com/(?:comm/)?jobs/view/(?:[^/?\\s]*-)?(\\d{6,})").find(t)?.groupValues?.get(1)
            ?: Regex("currentJobId=(\\d{6,})").find(t)?.groupValues?.get(1)
        val anyUrl = Regex("https?://\\S+").find(t)?.value

        // 1. Emails directly in the shared text ("send your CV to ...")
        val direct = Emails.extract(t)
        if (direct.isNotEmpty()) {
            var n = 0
            direct.forEach { if (addEmailLead(it.email, it.context, anyUrl.orEmpty())) n++ }
            return "Found ${direct.size} email(s). $n new draft(s) are waiting in your inbox."
        }

        // 2. A job link: add the job and read its details
        if (jobId != null) {
            val key = "li:$jobId"
            if (Store.data.jobs.none { it.key == key }) {
                val det = runCatching { LinkedInJobs.details(jobId) }.getOrNull()
                val html = runCatching { Http.get(LinkedInJobs.jobUrl(jobId)) }.getOrNull()
                val title = html?.let { Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1) }
                    ?.substringBefore(" | ")?.trim().orEmpty()
                val (role, company) = splitTitle(title)
                val job = Job(
                    key = key, source = "LinkedIn (shared)", title = role.ifBlank { "LinkedIn job $jobId" },
                    company = company, url = LinkedInJobs.jobUrl(jobId),
                    description = det?.description.orEmpty(), criteria = det?.criteria.orEmpty(),
                    detailsFetched = det != null, status = JobStatus.SAVED,
                )
                Store.update { d -> d.copy(jobs = listOf(rescore(job, d.profile, defaultRole(d), TitlePrefs.of(d.jobs))) + d.jobs) }
                val saved = Store.data.jobs.first { it.key == key }
                det?.poster?.let { p -> Store.addContact(newContact(p.name, p.headline, p.profileUrl, saved, ContactKind.RECRUITER, source = "posting")) }
                if (det != null) queueEmails(listOf(saved))
                return "Job kept: ${job.title}"
            }
            Store.updateJob(key) { if (it.status == JobStatus.NEW || it.status == JobStatus.IGNORED) it.copy(status = JobStatus.SAVED) else it }
            return "That job is already tracked."
        }

        // 3. A profile link: add as a suggestion
        if (profileUrl != null) {
            val name = t.substringBefore("http").lines().firstOrNull { it.isNotBlank() }
                ?.replace(Regex("(?i)(check out|view|'s profile|on linkedin|profile)"), "")?.trim()
                .orEmpty()
            val slugName = profileUrl.trimEnd('/').substringAfterLast("/")
                .replace(Regex("-[0-9a-f]{5,}$"), "").split("-")
                .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            val c = newContact(name.ifBlank { slugName }, "", profileUrl, null, source = "shared")
            return if (Store.addContact(c)) "Added ${c.name}. Review the suggestion in your inbox." else "${c.name} is already tracked."
        }

        // 4. A post link: try to read the public post for emails
        if (anyUrl != null && anyUrl.contains("linkedin.com")) {
            val html = runCatching { Http.get(anyUrl) }.getOrNull()
            if (html != null) {
                val text2 = org.jsoup.Jsoup.parse(html).text()
                val f = Emails.extract(text2).filter { Emails.looksLikeHiringAsk(it.context) }
                var n = 0
                f.forEach { if (addEmailLead(it.email, it.context, anyUrl)) n++ }
                if (f.isNotEmpty()) return "Found ${f.size} email(s) in the post. $n new draft(s) added."
            }
            return "No email found in that link. Open it in the LinkedIn tab and tap Grab emails."
        }
        return "Nothing recognised. Share a LinkedIn profile, a job, or a post containing an email."
    }

    /** "Android Developer at Acme" / "Acme hiring Android Developer in Pune" */
    fun splitTitle(t: String): Pair<String, String> {
        Regex("^(.*?) hiring (.*?)( in .*)?$").find(t)?.let { return it.groupValues[2].trim() to it.groupValues[1].trim() }
        Regex("^(.*) at (.*)$").find(t)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        return t to ""
    }
}
