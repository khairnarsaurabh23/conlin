package com.jobpilot.core

import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.ContactKind
import com.jobpilot.data.EmailLead
import com.jobpilot.data.Job
import com.jobpilot.data.Profile
import com.jobpilot.data.RunLog
import com.jobpilot.data.Store
import com.jobpilot.net.Http
import com.jobpilot.net.LinkedInJobs
import com.jobpilot.net.JSearch
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Runs the saved searches, stores new jobs, enriches them and queues outreach. */
object JobRunner {

    @Volatile var running = false
        private set

    suspend fun runAll(onProgress: (String) -> Unit = {}): RunLog {
        if (running) return RunLog(System.currentTimeMillis(), 0, 0, 0, 0, 0, listOf("A search is already running"))
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
                onProgress("LinkedIn: ${s.label()}")
                found += LinkedInJobs.search(s) { errors += it }
            }
            if (profile.useJSearch && profile.jsearchApiKey.isNotBlank()) {
                onProgress("JSearch: ${s.label()}")
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
        val scoredNew = newJobs.map { rescore(it, profile, kwById[it.searchId].orEmpty()) }
        Store.update { d -> d.copy(jobs = scoredNew + d.jobs) }

        // 3. Fetch full descriptions for the best new LinkedIn jobs
        val toEnrich = scoredNew.filter { !it.detailsFetched && it.key.startsWith("li:") }
            .sortedByDescending { it.matchScore }
            .take(profile.detailFetchLimit)
        var i = 0
        for (j in toEnrich) {
            i++
            onProgress("Reading job $i/${toEnrich.size}: ${j.title}")
            enrich(j.key, errors)
            delay(1500L + Random.nextLong(1000))
        }

        // 4. Turn emails in descriptions + recruiters into outreach items
        val keysNew = scoredNew.map { it.key }.toSet()
        val (emailsAdded, recruitersAdded) = queueOutreach(Store.data.jobs.filter { it.key in keysNew })

        val log = RunLog(
            at = System.currentTimeMillis(),
            searches = searches.size,
            jobsSeen = filtered.size,
            newJobs = scoredNew.size,
            newEmails = emailsAdded,
            newRecruiters = recruitersAdded,
            errors = errors.distinct(),
        )
        Store.update { d -> d.copy(runs = (listOf(log) + d.runs).take(60)) }
        onProgress("Done: ${log.newJobs} new jobs")
        return log
    }

    private fun sig(j: Job) = (j.title.lowercase().replace(Regex("\\W+"), "") + "|" +
        j.company.lowercase().replace(Regex("\\W+"), ""))

    fun rescore(j: Job, p: Profile, keywords: String): Job {
        val r = Matcher.score(j, p, keywords)
        val mails = if (j.description.isNotBlank()) Emails.extract(j.description).map { it.email } else j.emailsFound
        return j.copy(matchScore = r.score, matchedSkills = r.matched, missingSkills = r.missing, emailsFound = mails)
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
        Store.updateJob(job.key) { rescore(it.copy(description = det.description, criteria = det.criteria, detailsFetched = true), profile, kw) }
        det.poster?.let { p ->
            if (Store.data.contacts.none { Store.sameProfile(it.profileUrl, p.profileUrl) }) {
                Store.upsertContact(
                    Contact(
                        id = Store.newId(), name = p.name, headline = p.headline, profileUrl = p.profileUrl,
                        company = job.company, jobKey = job.key, kind = ContactKind.RECRUITER,
                        message = referralMessage(profile, p.name, job.company, job.title, job.url, recruiter = true),
                    )
                )
            }
        }
        return true
    }

    /** Creates email drafts for addresses found in job descriptions. Returns (emails, recruiters) counts. */
    suspend fun queueOutreach(jobs: List<Job>): Pair<Int, Int> {
        var emails = 0
        for (j in jobs) {
            for (f in Emails.extract(j.description)) {
                if (addEmailLead(f.email, f.context, j.url, company = j.company, role = j.title, jobKey = j.key)) emails++
            }
        }
        val recruiters = Store.data.contacts.count { c -> c.kind == ContactKind.RECRUITER && jobs.any { it.key == c.jobKey } }
        return emails to recruiters
    }

    /** Adds a draft email if this address hasn't been queued/sent before. */
    suspend fun addEmailLead(
        email: String, context: String, sourceUrl: String,
        company: String = "", role: String = "", jobKey: String = "", name: String = "",
    ): Boolean {
        val addr = email.lowercase().trim()
        if (Store.data.emails.any { it.to.equals(addr, ignoreCase = true) }) return false
        val p = Store.data.profile
        val c = company.ifBlank { Emails.companyFromEmail(addr) }
        val r = role.ifBlank { defaultRole(Store.data) }
        val n = name.ifBlank { Emails.guessNameFromEmail(addr) }
        Store.upsertEmail(
            EmailLead(
                id = Store.newId(), to = addr, name = n, company = c, role = r, jobKey = jobKey,
                sourceUrl = sourceUrl, context = context.take(600),
                subject = Templates.fill(p.emailSubjectTemplate, p, n, c, r, sourceUrl),
                body = Templates.fill(p.emailBodyTemplate, p, n, c, r, sourceUrl),
                attachResume = Store.resumeFile() != null,
            )
        )
        return true
    }

    fun defaultRole(d: AppData): String =
        d.searches.firstOrNull { it.enabled }?.keywords
            ?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
            .orEmpty()

    fun referralMessage(p: Profile, name: String, company: String, role: String, jobUrl: String, recruiter: Boolean = false): String {
        val tpl = if (recruiter) p.referralTemplate.replace("Would you be open to referring me?", "Could I share my profile for this role?")
        else p.referralTemplate
        return Templates.fit(Templates.fill(tpl, p, name, company, role, jobUrl), p.referralCharLimit)
    }

    // ---------- Things shared into the app from LinkedIn / browser ----------

    data class ShareResult(val summary: String)

    suspend fun handleShared(text: String): ShareResult {
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
            return ShareResult("Found ${direct.size} email(s); $n new draft(s) added to Emails.")
        }

        // 2. A job link → add the job and read its details
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
                    detailsFetched = det != null, status = com.jobpilot.data.JobStatus.SAVED,
                )
                Store.update { d -> d.copy(jobs = listOf(rescore(job, d.profile, defaultRole(d))) + d.jobs) }
                if (det != null) queueOutreach(listOf(job))
                return ShareResult("Job saved: ${job.title}")
            }
            Store.updateJob(key) { it.copy(status = com.jobpilot.data.JobStatus.SAVED) }
            return ShareResult("Job already tracked — marked as Saved.")
        }

        // 3. A profile link → add as a referral contact
        if (profileUrl != null) {
            val name = t.substringBefore("http").lines().firstOrNull { it.isNotBlank() }
                ?.replace(Regex("(?i)(check out|view|'s profile|on linkedin|profile)"), "")?.trim()
                .orEmpty()
            val slugName = profileUrl.trimEnd('/').substringAfterLast("/")
                .replace(Regex("-[0-9a-f]{5,}$"), "").split("-")
                .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            val c = Contact(id = Store.newId(), name = name.ifBlank { slugName }, profileUrl = profileUrl)
            Store.upsertContact(c)
            return ShareResult("Added ${c.name} to People. Link them to a job to generate a referral note.")
        }

        // 4. A post link → try to read the public post for emails
        if (anyUrl != null && anyUrl.contains("linkedin.com")) {
            val html = runCatching { Http.get(anyUrl) }.getOrNull()
            if (html != null) {
                val text2 = org.jsoup.Jsoup.parse(html).text()
                val f = Emails.extract(text2).filter { Emails.looksLikeHiringAsk(it.context) }
                var n = 0
                f.forEach { if (addEmailLead(it.email, it.context, anyUrl)) n++ }
                if (f.isNotEmpty()) return ShareResult("Found ${f.size} email(s) in the post; $n new draft(s) added.")
            }
            return ShareResult("No email found in that link. Open it in the in-app Browser and tap \"Grab emails\".")
        }
        return ShareResult("Nothing recognised. Share a LinkedIn profile, job, or a post containing an email.")
    }

    /** "Android Developer at Acme | LinkedIn" / "Acme hiring Android Developer in Pune" */
    fun splitTitle(t: String): Pair<String, String> {
        Regex("^(.*?) hiring (.*?)( in .*)?$").find(t)?.let { return it.groupValues[2].trim() to it.groupValues[1].trim() }
        Regex("^(.*) at (.*)$").find(t)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        return t to ""
    }
}
