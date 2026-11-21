package com.jobpilot.core

import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.ContactKind
import com.jobpilot.data.Connection
import com.jobpilot.data.EmailLead
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Profile
import com.jobpilot.data.RunLog
import com.jobpilot.data.Stage
import com.jobpilot.data.Store
import com.jobpilot.net.Http
import com.jobpilot.net.Feeds
import com.jobpilot.net.JSearch
import com.jobpilot.net.LinkedInJobs
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val DAY = 86_400_000L

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
            if (profile.useHackerNews) {
                onProgress("Searching Hacker News: ${s.label()}")
                found += Feeds.hackerNews(s) { errors += it }
            }
        }
        val now = System.currentTimeMillis()
        // Remotive asks for no more than a few calls a day.
        if (profile.useRemotive && now - (Store.data.sourceRuns["remotive"] ?: 0L) > 6 * 3_600_000L) {
            for (s in searches) {
                onProgress("Searching Remotive: ${s.keywords}")
                found += Feeds.remotive(s) { errors += it }
            }
            Store.update { it.copy(sourceRuns = it.sourceRuns + ("remotive" to now)) }
        }
        // Feeds without search: fetch once, then match each saved search.
        val shared = mutableListOf<Job>()
        if (profile.useArbeitnow) {
            onProgress("Reading Arbeitnow (Europe)")
            shared += Feeds.arbeitnow({ errors += it })
        }
        for (b in Feeds.parseBoards(profile.startupBoards)) {
            onProgress("Reading ${b.token} careers page")
            shared += Feeds.board(b) { errors += it }
        }
        for (j in shared) {
            val s = searches.firstOrNull { Feeds.matches(it.keywords, j.title, j.description) } ?: continue
            found += j.copy(searchId = s.id)
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
        val bySig = existing.associateBy { sig(it) }
        val sigs = bySig.keys.toHashSet()
        val newJobs = mutableListOf<Job>()
        val seenAgain = HashMap<String, Boolean>() // existing key -> listed again under a new id (repost)
        val now0 = System.currentTimeMillis()
        for (j in filtered) {
            if (j.key in keys) { seenAgain.putIfAbsent(j.key, false); continue }
            val old = bySig[sig(j)]
            if (old != null) {
                // Same title at the same company under a new listing, weeks later: a repost.
                if (now0 - old.foundAt > 21 * DAY && now0 - old.lastSeenAt > 7 * DAY) seenAgain[old.key] = true
                else seenAgain.putIfAbsent(old.key, false)
                continue
            }
            if (sig(j) in sigs) continue
            keys += j.key; sigs += sig(j)
            newJobs += j
        }
        if (seenAgain.isNotEmpty()) Store.update { d ->
            d.copy(jobs = d.jobs.map { e ->
                when (seenAgain[e.key]) {
                    true -> e.copy(repostCount = e.repostCount + 1, lastSeenAt = now0)
                    false -> e.copy(lastSeenAt = now0)
                    null -> e
                }
            })
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

        // 3b. Kept jobs: check the postings are still open, a few per check.
        val stale = Store.data.jobs.filter {
            (it.status == JobStatus.SAVED || it.status == JobStatus.REFERRAL_ASKED) && it.appliedAt == 0L && !it.closed &&
                System.currentTimeMillis() - it.checkedAt > 2 * DAY
        }.sortedByDescending { it.matchScore }.take(12)
        var closedNow = 0
        for (j in stale) {
            onProgress("Checking ${j.title} is still open")
            if (checkOpen(j.key) == false) closedNow++
            delay(800)
        }
        if (closedNow > 0) errors += "$closedNow kept job(s) are no longer open"

        // 4. Turn emails in descriptions into drafts for approval
        val keysNew = scoredNew.map { it.key }.toSet()
        val emailsAdded = queueEmails(Store.data.jobs.filter { it.key in keysNew })
        housekeeping()

        // Optional: email applications that need no review go out straight away.
        if (profile.autoSendEmails && profile.smtpConfigured) {
            val jobs = Store.data.jobs.associateBy { it.key }
            val auto = Store.data.emails.filter { e ->
                val j = jobs[e.jobKey]
                e.status == EmailStatus.DRAFT && j != null && Emails.isRecruitingAddress(e.to) &&
                    j.matchScore >= profile.autoSendMin && Readiness.of(j, profile).level == Readiness.Level.READY
            }.map { it.id }.toSet()
            if (auto.isNotEmpty()) {
                Store.update { d -> d.copy(emails = d.emails.map { if (it.id in auto) it.copy(status = EmailStatus.APPROVED) else it }) }
                onProgress("Sending ${auto.size} email application(s)")
                Mailer.sendApproved(onProgress).let { if (!it.startsWith("Sent")) errors += it }
            }
        }

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
        val c0 = Classify.of(j, p.homeCountry)
        val ko = Knockout.of(j.copy(tags = c0.tags, cautions = c0.cautions), p)
        val extra = buildList {
            ko.forEach { add("Knock-out: $it") }
            if (j.repostCount > 0) add("Listed again ${j.repostCount} time(s) since it was first seen; may be hard to fill or a ghost posting")
            if (j.closed) add("This posting looks closed")
        }
        val c = c0.copy(cautions = c0.cautions + extra, tags = c0.tags + if (j.closed) listOf("closed") else emptyList())
        var score = r.score
        if ("remote-elsewhere" in c.tags) score -= 8
        if (c.cautions.any { it.startsWith("Says no visa") }) score -= 6
        score -= 10 * ko.size
        val mails = if (j.description.isNotBlank()) Emails.extract(j.description).map { it.email } else j.emailsFound
        return j.copy(
            matchScore = score.coerceIn(0, 100), matchedSkills = r.matched, missingSkills = r.missing, emailsFound = mails,
            reasons = c.reasons + r.reasons, cautions = c.cautions + r.cautions, tags = c.tags,
        )
    }

    /** A job saved from a web page the user was looking at (Wellfound, YC and others). */
    suspend fun saveWebJob(url: String, title: String, company: String, location: String, text: String): String {
        val clean = url.substringBefore("#")
        val key = "web:" + clean.substringBefore("?").trimEnd('/')
        if (Store.data.jobs.any { it.key == key }) {
            Store.updateJob(key) { if (it.status == JobStatus.NEW || it.status == JobStatus.IGNORED) it.copy(status = JobStatus.SAVED) else it }
            return "Already tracked. Marked as kept."
        }
        val job = Job(
            key = key, source = Feeds.siteName(clean), title = title.trim().ifBlank { "Saved job" }, company = company.trim(),
            location = location.trim(), url = clean, applyUrl = clean, description = text.take(10000),
            detailsFetched = text.isNotBlank(), status = JobStatus.SAVED,
        )
        Store.update { d -> d.copy(jobs = listOf(rescore(job, d.profile, defaultRole(d), TitlePrefs.of(d.jobs))) + d.jobs) }
        if (text.isNotBlank()) queueEmails(listOf(Store.data.jobs.first { it.key == key }))
        return "Saved: ${job.title}" + if (job.company.isNotBlank()) " at ${job.company}" else ""
    }

    /** "Senior Engineer at Acme | Wellfound" -> (title, company). */
    fun titleFromPage(pageTitle: String): Pair<String, String> {
        val t = pageTitle.split(" | ", " \u2022 ", " \u00b7 ", " \u2014 ").first().trim()
        return splitTitle(t)
    }

    fun rescoreAll(d: AppData): AppData {
        val kw = d.searches.associate { it.id to it.keywords }
        val prefs = TitlePrefs.of(d.jobs)
        return d.copy(jobs = d.jobs.map { rescore(it, d.profile, kw[it.searchId] ?: defaultRole(d), prefs) })
    }

    /** Fetch the public job page for one LinkedIn job; also captures the recruiter if shown. */
    /**
     * Fetches the posting and records whether it is still open. Returns false when closed,
     * true when open, null when it couldn't be read. Fills in a missing description on the way.
     */
    suspend fun checkOpen(jobKey: String): Boolean? {
        val job = Store.data.jobs.firstOrNull { it.key == jobKey } ?: return null
        val now = System.currentTimeMillis()
        val url = if (job.key.startsWith("li:")) "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/${LinkedInJobs.idFromKey(job.key)}" else job.url
        if (url.isBlank()) return null
        val text = try {
            org.jsoup.Jsoup.parse(Http.get(url)).text()
        } catch (e: com.jobpilot.net.HttpException) {
            if (e.code == 404 || e.code == 410) "job not found" else return null
        } catch (e: Exception) {
            return null
        }
        val closed = Liveness.closed(text)
        val p = Store.data.profile
        val kw = Store.data.searches.firstOrNull { it.id == job.searchId }?.keywords ?: defaultRole(Store.data)
        val prefs = TitlePrefs.of(Store.data.jobs)
        Store.updateJob(jobKey) {
            val desc = if (it.description.isBlank() && !closed && text.length > 400) text.take(10000) else it.description
            rescore(it.copy(closed = closed, checkedAt = now, description = desc, detailsFetched = it.detailsFetched || desc.isNotBlank()), p, kw, prefs)
        }
        return !closed
    }

    suspend fun enrich(jobKey: String, errors: MutableList<String> = mutableListOf()): Boolean {
        val job = Store.data.jobs.firstOrNull { it.key == jobKey } ?: return false
        if (!job.key.startsWith("li:")) {
            // Other sources: read the posting page itself when the feed had no description.
            if (job.description.isBlank()) checkOpen(jobKey) ?: run { errors += "Couldn't read ${job.title}"; return false }
            return true
        }
        val det = try {
            LinkedInJobs.details(LinkedInJobs.idFromKey(job.key))
        } catch (e: Exception) {
            errors += "Couldn't read details for ${job.title}: ${e.message}"
            return false
        }
        val profile = Store.data.profile
        val kw = Store.data.searches.firstOrNull { it.id == job.searchId }?.keywords.orEmpty()
        val prefs = TitlePrefs.of(Store.data.jobs)
        Store.updateJob(job.key) { rescore(it.copy(description = det.description, criteria = det.criteria, detailsFetched = true, closed = det.closed, checkedAt = System.currentTimeMillis()), profile, kw, prefs) }
        det.poster?.let { p ->
            val fresh = Store.data.jobs.firstOrNull { it.key == jobKey } ?: job
            Store.addContact(newContact(p.name, p.headline, p.profileUrl, fresh, ContactKind.RECRUITER, source = "posting"))
        }
        return true
    }

    fun kindFor(headline: String): ContactKind = when {
        headline.contains(Regex("(?i)recruit|talent|\\bHR\\b|human resources|people ops|sourcer")) -> ContactKind.RECRUITER
        headline.contains(Regex("(?i)engineering manager|\\bmanager\\b|head of|director|\\bvp\\b|vice president|\\bcto\\b|founder|team lead|tech lead")) -> ContactKind.MANAGER
        headline.contains("hiring", true) -> ContactKind.RECRUITER
        else -> ContactKind.EMPLOYEE
    }

    /** Builds a suggestion: ranked, with its evidence, a drafted note and referral ask. Nothing is sent. */
    fun newContact(
        name: String, headline: String, url: String, job: Job?,
        kind: ContactKind = kindFor(headline), degree: Int = 0, tag: String = "",
        source: String = "search", postSnippet: String = "", postUrl: String = "", known: Boolean = false,
    ): Contact {
        val d = Store.data
        val r = Rank.score(
            headline, degree, kind, d.profile, job, tag,
            wroteHiringPost = postSnippet.isNotBlank(), namedOnPosting = source == "posting",
            openings = Why.openingsAt(job?.company.orEmpty(), d).size, known = known,
        )
        val tpl = pickTemplate(d.profile.noteTemplateB.isNotBlank(), d.contacts.count { it.template == "A" }, d.contacts.count { it.template == "B" })
        val base = Contact(
            id = Store.newId(), name = name, headline = headline, profileUrl = url.substringBefore("?"),
            company = job?.company.orEmpty(), jobKey = job?.key.orEmpty(), kind = kind,
            degree = degree, rank = r.score, why = r.why, template = tpl,
            source = source, postSnippet = postSnippet, postUrl = postUrl, known = known,
            // Existing connections skip the invite step and go straight to the referral ask.
            stage = if (degree == 1) Stage.CONNECTED else Stage.SUGGESTED,
            connectedAt = if (degree == 1) System.currentTimeMillis() else 0,
        )
        return base.copy(note = draftNote(d, base, job), ask = draftAsk(d, base, job))
    }

    private fun pickTemplate(hasB: Boolean, a: Int, b: Int) = if (hasB && b < a) "B" else "A"

    private fun fillFor(template: String, d: AppData, c: Contact, job: Job?, short: Boolean = false): String = Templates.fill(
        template.replace("{opening}", Why.opening(c, job, d, short)),
        d.profile, c.name, Why.shortName(job?.company ?: c.company), roleOf(job), job?.url.orEmpty(), job?.matchedSkills.orEmpty(),
    )

    /**
     * The connection note must fit LinkedIn's limit without losing its point. Rather than cutting
     * the end off, try a tighter opening and then drop middle sentences, so the reason for writing
     * and the ask both survive.
     */
    fun draftNote(d: AppData, c: Contact, job: Job?): String {
        val p = d.profile
        val chosen = if (c.template == "B" && p.noteTemplateB.isNotBlank()) p.noteTemplateB else p.noteTemplateA
        // While the note is left at its default, frame it for who is being written to.
        val tpl = if (chosen != Profile.DEFAULT_NOTE) chosen else when (c.kind) {
            ContactKind.RECRUITER -> Profile.NOTE_RECRUITER
            ContactKind.MANAGER -> Profile.NOTE_MANAGER
            else -> chosen
        }
        if (tpl.isBlank()) return ""
        for (short in listOf(false, true)) {
            val text = fillFor(tpl, d, c, job, short)
            if (text.length <= p.noteCharLimit) return text
            val trimmed = Templates.dropMiddle(text, p.noteCharLimit)
            if (trimmed.length <= p.noteCharLimit) return trimmed
        }
        return Templates.fit(fillFor(tpl, d, c, job, true), p.noteCharLimit)
    }

    fun draftAsk(d: AppData, c: Contact, job: Job?): String {
        val chosen = d.profile.askTemplate
        val tpl = if (chosen != Profile.DEFAULT_ASK) chosen else when {
            c.known -> Profile.ASK_KNOWN
            c.kind == ContactKind.RECRUITER -> Profile.ASK_RECRUITER
            c.kind == ContactKind.MANAGER -> Profile.ASK_MANAGER
            else -> chosen
        }
        return fillFor(tpl, d, c, job)
    }

    // ---------------- LinkedIn connections export ----------------

    /** Reads LinkedIn's Connections.csv. The file starts with a "Notes:" preamble before the header row. */
    fun parseConnections(csv: String): List<Connection> {
        fun split(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var q = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> q = !q
                    ch == ',' && !q -> { out += sb.toString().trim(); sb.clear() }
                    else -> sb.append(ch)
                }
                i++
            }
            out += sb.toString().trim()
            return out
        }
        val lines = csv.replace("\r", "").lines()
        val h = lines.indexOfFirst { it.contains("First Name", true) && it.contains("Company", true) }
        if (h < 0) return emptyList()
        val head = split(lines[h]).map { it.lowercase() }
        fun col(name: String) = head.indexOfFirst { it == name }
        val fn = col("first name"); val ln = col("last name"); val url = col("url"); val em = col("email address")
        val co = col("company"); val pos = col("position"); val on = col("connected on")
        return lines.drop(h + 1).filter { it.isNotBlank() }.mapNotNull { l ->
            val f = split(l)
            fun g(i: Int) = if (i in f.indices) f[i] else ""
            val name = (g(fn) + " " + g(ln)).trim()
            if (name.isBlank()) null else Connection(name, g(url), g(co), g(pos), g(on), g(em))
        }
    }

    fun connectionsAt(company: String, d: AppData): List<Connection> {
        val n = Why.norm(company)
        if (n.length < 2) return emptyList()
        return d.connections.filter { Why.norm(it.company) == n }
    }

    /** Adds people you already know at the job's company, straight to the referral ask. */
    suspend fun addKnown(job: Job, people: List<Connection>): Int {
        var n = 0
        for (k in people) {
            if (k.url.isBlank()) continue
            val existing = Store.data.contacts.firstOrNull { Store.sameProfile(it.profileUrl, k.url) }
            if (existing != null) {
                Store.updateContact(existing.id) {
                    it.copy(known = true, jobKey = it.jobKey.ifBlank { job.key }, company = it.company.ifBlank { job.company })
                }
                continue
            }
            if (Store.addContact(newContact(k.name, k.position, k.url, job, degree = 1, source = "connections", known = true))) n++
        }
        return n
    }

    // ---------------- Follow-ups on applications ----------------

    /** A follow-up (or interview thank-you) email to the address the application went to. */
    suspend fun followUpEmail(job: Job, thanks: Boolean): EmailLead? {
        val sent = Store.data.emails.filter { it.jobKey == job.key && it.sentAt > 0 }.maxByOrNull { it.sentAt } ?: return null
        val p = Store.data.profile
        val first = sent.name.trim().split(" ").firstOrNull().orEmpty().ifBlank { "there" }
        val role = roleOf(job).ifBlank { sent.role }
        val body = if (thanks) {
            "Hi $first,\n\nThank you for the conversation about the $role role. I enjoyed learning about the team and the work, " +
                "and I'm even more interested in joining. If anything else would help, I'm happy to send it.\n\nThanks,\n${p.name}"
        } else {
            "Hi $first,\n\nI applied for the $role role at ${Why.shortName(job.company)} a little while ago and wanted to check in. " +
                "I'm still very interested, and happy to share anything else that would help. My resume is attached again for convenience.\n\nThanks,\n${p.name}"
        }
        val lead = EmailLead(
            id = Store.newId(), to = sent.to, name = sent.name, company = sent.company, role = role, jobKey = job.key,
            subject = (if (thanks) "Thank you - " else "Re: ") + sent.subject.removePrefix("Re: "), body = body,
            attachResume = !thanks && sent.attachResume, template = "F",
        )
        Store.upsertEmail(lead)
        Store.updateJob(job.key) { if (thanks) it.copy(thankedAt = System.currentTimeMillis()) else it.copy(followedUpAt = System.currentTimeMillis()) }
        return lead
    }


    /**
     * One-off upgrades when the app is updated. Nothing the user entered is removed:
     * templates are replaced only if they are still an old default.
     */
    fun migrate(d0: AppData): AppData {
        var d = d0
        if (d.schema < 1) {
            val p = d.profile
            val oldNote = p.noteTemplateA == Profile.OLD_NOTE
            val oldAsk = p.askTemplate == Profile.OLD_ASK
            if (oldNote || oldAsk) {
                val jobs = d.jobs.associateBy { it.key }
                val before = d
                d = d.copy(profile = p.copy(
                    noteTemplateA = if (oldNote) Profile.DEFAULT_NOTE else p.noteTemplateA,
                    askTemplate = if (oldAsk) Profile.DEFAULT_ASK else p.askTemplate,
                ))
                val after = d
                d = d.copy(contacts = d.contacts.map { c ->
                    val job = jobs[c.jobKey]
                    var out = c
                    if (oldNote && c.stage == Stage.SUGGESTED && c.note == draftNote(before, c, job)) out = out.copy(note = draftNote(after, c, job))
                    if (oldAsk && (c.stage == Stage.SUGGESTED || c.stage == Stage.CONNECTED) && c.ask == draftAsk(before, c, job)) out = out.copy(ask = draftAsk(after, c, job))
                    out
                })
            }
            d = rescoreAll(d).copy(schema = 1)
        }
        if (d.schema < 2) {
            // 2.1 notes could lose their second sentence to the length limit. Redraft suggestions
            // that are still waiting for approval, unless the template was customised.
            val p = d.profile
            if (p.noteTemplateA == Profile.V21_NOTE || p.noteTemplateA == Profile.DEFAULT_NOTE) {
                d = d.copy(profile = p.copy(noteTemplateA = Profile.DEFAULT_NOTE))
                val jobs = d.jobs.associateBy { it.key }
                val now = d
                d = d.copy(contacts = d.contacts.map { c ->
                    if (c.stage == Stage.SUGGESTED && !c.edited) c.copy(note = draftNote(now, c, jobs[c.jobKey])) else c
                })
            }
            // Addresses such as accommodations or privacy mailboxes are not for applications.
            d = d.copy(emails = d.emails.map { e ->
                if (e.sentAt == 0L && e.status != EmailStatus.SKIPPED && !Emails.isRecruitingAddress(e.to))
                    e.copy(status = EmailStatus.SKIPPED, error = "Skipped: this looks like a non-recruiting mailbox, not an address for applications.")
                else e
            })
            d = d.copy(schema = 2)
        }
        if (d.schema < 3) {
            // 2.3 moved to a light, warm look; dark stays available in Settings.
            d = d.copy(profile = d.profile.copy(theme = "light"), schema = 3)
        }
        if (d.schema < 4) {
            // Dark is the default again; light stays available in Settings.
            d = d.copy(profile = d.profile.copy(theme = "dark"), schema = 4)
        }
        if (d.schema < 5) {
            // 2.4 sorts jobs into remote / abroad / visa / startup: tag what is already stored.
            d = rescoreAll(d).copy(schema = 5)
        }
        if (d.schema < 6) {
            // 2.6 confirms sends by reading the page in Browse, so profiles open there by default.
            d = d.copy(profile = d.profile.copy(openInLinkedInApp = false), schema = 6)
        }
        return d
    }

    /** A first message to someone you aren't connected to: the reason, the fit and the ask in one. */
    fun draftDirect(d: AppData, c: Contact, job: Job?): String = fillFor(
        "Hi {firstName}, {opening}. I have {experience} with {topSkills} and I'm applying for the role. " +
            "Would you be open to referring me, or pointing me to the right person? I can send my resume. Thanks,\n{myName}",
        d, c, job,
    )

    fun draftFollowUp(p: Profile, c: Contact, job: Job?): String = Templates.fill(
        "Hi {firstName}, following up on my note about the {role} role at {company}. " +
            "If a referral isn't possible, no problem at all. A pointer to the right person would help just as much. Thanks.",
        p, c.name, job?.company ?: c.company, roleOf(job), job?.url.orEmpty(),
    )

    /** "Senior Android Developer (Kotlin) - Remote" -> "Senior Android Developer" */
    fun roleOf(job: Job?): String = Why.shortRole(job?.title)

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
            return "No email found in that link. Open it in the Browse tab and tap Grab emails."
        }

        // 5. Any other job link (Wellfound, YC, a careers page): save it as a job.
        if (anyUrl != null) {
            val html = runCatching { Http.get(anyUrl) }.getOrNull()
            val doc = html?.let { org.jsoup.Jsoup.parse(it) }
            val pageTitle = doc?.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null } ?: doc?.title().orEmpty()
            val sharedTitle = t.substringBefore("http").lines().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            val (role, company) = titleFromPage(pageTitle.ifBlank { sharedTitle })
            val body = doc?.body()?.text().orEmpty()
            return saveWebJob(anyUrl, role.ifBlank { sharedTitle }, company, "", body)
        }
        return "Nothing recognised. Share a job link, a LinkedIn profile, or a post containing an email."
    }

    /** "Android Developer at Acme" / "Acme hiring Android Developer in Pune" */
    fun splitTitle(t: String): Pair<String, String> {
        Regex("^(.*?) hiring (.*?)( in .*)?$").find(t)?.let { return it.groupValues[2].trim() to it.groupValues[1].trim() }
        Regex("^(.*) at (.*)$").find(t)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        return t to ""
    }
}
