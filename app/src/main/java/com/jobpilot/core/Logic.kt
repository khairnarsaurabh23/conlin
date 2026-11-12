package com.jobpilot.core

import com.jobpilot.data.ActionKind
import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.ContactKind
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Profile
import com.jobpilot.data.ReplyKind
import com.jobpilot.data.Stage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HOUR = 3_600_000L
private const val DAY = 86_400_000L

/**
 * Outreach budgets. LinkedIn restricts accounts that send too many invitations or behave like
 * a bot, so every send goes through here first. All windows are rolling, like LinkedIn's own.
 */
object Guard {
    data class Limits(val day: Int, val week: Int, val note: String = "")
    data class Check(val ok: Boolean, val reason: String = "")
    data class Usage(val today: Int, val week: Int, val limits: Limits)

    fun count(d: AppData, kind: ActionKind, window: Long, now: Long): Int =
        d.actions.count { it.kind == kind && now - it.at < window }

    /** (accepted, resolved): invites that were accepted, out of those old enough to judge. */
    fun acceptance(d: AppData, now: Long): Pair<Int, Int> {
        val sent = d.contacts.filter { it.invitedAt > 0 }
        val resolved = sent.filter { it.connectedAt > 0 || now - it.invitedAt > 7 * DAY }
        return resolved.count { it.connectedAt > 0 } to resolved.size
    }

    fun acceptanceRate(d: AppData, now: Long): Int? {
        val (a, r) = acceptance(d, now)
        return if (r < 5) null else a * 100 / r
    }

    fun inviteLimits(d: AppData, now: Long): Limits {
        val p = d.profile
        var day = p.dailyInviteCap
        var week = p.weeklyInviteCap
        var note = ""
        if (p.rampUp) {
            val first = d.actions.filter { it.kind == ActionKind.INVITE }.minOfOrNull { it.at }
            val weeks = if (first == null) 0 else ((now - first) / (7 * DAY)).toInt()
            if (weeks < 2) {
                val num = weeks + 1 // 1/3 in the first week, 2/3 in the second
                day = maxOf(3, day * num / 3)
                week = maxOf(10, week * num / 3)
                note = "Warm-up week ${weeks + 1} of 2"
            }
        }
        val (acc, resolved) = acceptance(d, now)
        if (resolved >= 15 && acc * 100 / resolved < 25) {
            day = maxOf(3, day / 2); week = maxOf(10, week / 2)
            note = "Few invites are being accepted, so limits are halved"
        }
        return Limits(day, week, note)
    }

    fun inviteUsage(d: AppData, now: Long) = Usage(
        count(d, ActionKind.INVITE, DAY, now), count(d, ActionKind.INVITE, 7 * DAY, now), inviteLimits(d, now)
    )

    fun pauseText(d: AppData, now: Long): String? {
        val s = d.safety
        if (s.pausedUntil <= now) return null
        val until = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(s.pausedUntil))
        val what = if (s.invitesOnly) "Connection requests paused" else "Outreach paused"
        return "$what until $until" + if (s.pauseReason.isNotBlank()) ". ${s.pauseReason}" else ""
    }

    private fun gap(d: AppData, now: Long): Check? {
        val last = d.actions.filter { it.kind != ActionKind.EMAIL }.maxOfOrNull { it.at } ?: return null
        val waitMs = d.profile.minGapMinutes * 60_000L - (now - last)
        return if (waitMs > 0) Check(false, "Wait ${(waitMs / 60_000) + 1} min before the next one") else null
    }

    fun canInvite(d: AppData, now: Long): Check {
        pauseText(d, now)?.let { return Check(false, it) }
        val u = inviteUsage(d, now)
        if (u.today >= u.limits.day) return Check(false, "Daily limit reached (${u.today} of ${u.limits.day})")
        if (u.week >= u.limits.week) return Check(false, "Weekly limit reached (${u.week} of ${u.limits.week})")
        return gap(d, now) ?: Check(true)
    }

    fun canMessage(d: AppData, now: Long): Check {
        if (!d.safety.invitesOnly) pauseText(d, now)?.let { return Check(false, it) }
        val today = count(d, ActionKind.MESSAGE, DAY, now)
        if (today >= d.profile.dailyMessageCap) return Check(false, "Daily message limit reached ($today of ${d.profile.dailyMessageCap})")
        return gap(d, now) ?: Check(true)
    }

    fun emailsLeftToday(d: AppData, now: Long): Int =
        (d.profile.dailyEmailCap - count(d, ActionKind.EMAIL, DAY, now)).coerceAtLeast(0)
}

/** Who to ask first: people with a real reason to answer rank highest. */
object Rank {
    data class Result(val score: Int, val why: String)

    fun score(
        headline: String, degree: Int, kind: ContactKind, p: Profile, job: Job?, tag: String = "",
        wroteHiringPost: Boolean = false, namedOnPosting: Boolean = false, openings: Int = 0,
    ): Result {
        val h = headline.lowercase()
        var s = 0
        val why = mutableListOf<String>()
        if (wroteHiringPost) { s += 35; why += "Posted about hiring" }
        if (namedOnPosting) { s += 30; why += "Hiring contact" }
        if (degree == 1) { s += 40; why += "Already connected" }
        if (p.college.isNotBlank() && (tag == "alumni" || h.contains(p.college.lowercase()))) { s += 30; why += "Same college" }
        val ex = p.pastCompanyList().firstOrNull { it.length > 2 && h.contains(it.lowercase()) }
        if (ex != null || tag == "colleague") { s += 30; why += "Worked at ${ex ?: "a company you did"}" }
        if (degree == 2) { s += 15; why += "2nd-degree connection" }
        if (kind == ContactKind.RECRUITER && !namedOnPosting) { s += 20; why += "Recruiter" }
        if (Regex("(?i)\\bhiring\\b").containsMatchIn(headline)) { s += 10; why += "Headline says hiring" }
        if (job != null) {
            val words = job.title.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length > 3 }
            if (words.any { h.contains(it) }) { s += 15; why += "Similar role" }
            if (job.company.length > 2 && h.contains(job.company.lowercase())) s += 10
        }
        s += (openings * 4).coerceAtMost(12)
        return Result(s, why.take(2).joinToString(" \u00b7 "))
    }
}

/**
 * The evidence behind a suggestion: did this person post about hiring, are they named on a
 * posting, does their company have openings. Shown on the card and used to open the message.
 */
object Why {
    private val HIRING = Regex(
        "(?i)(we(['\u2019]| a)re hiring|is hiring|now hiring|hiring for|hiring an?\\b|looking for an?\\b|looking to hire|" +
            "open (role|position)s?|job opening|opening for|openings? (for|at|in)|join (our|my) team|vacanc|" +
            "send (me |us )?(your |ur )?(cv|resume)|share (your |ur )?(cv|resume)|drop (your |ur )?(cv|resume)|dm me|referr)"
    )

    fun looksLikeHiring(text: String): Boolean = HIRING.containsMatchIn(text)

    /** The sentence or two starting at the hiring phrase, without the post's header clutter. */
    fun snippet(postText: String, max: Int = 220): String {
        val t = postText.replace(Regex("\\s+"), " ").trim()
        val m = HIRING.find(t) ?: return ""
        // Back up to the start of the sentence the phrase sits in (at most ~100 characters).
        var from = m.range.first
        val floor = (from - 100).coerceAtLeast(0)
        var i = from - 1
        while (i > floor) {
            val ch = t[i]
            if ((ch == '.' || ch == '!' || ch == '?' || ch == '\u2022' || ch == '|') && t[i + 1] == ' ') break
            i--
        }
        from = if (i > floor) i + 2 else from
        var to = (from + max).coerceAtMost(t.length)
        if (to < t.length) to = t.lastIndexOf(' ', to).let { if (it > m.range.last) it else to }
        return t.substring(from, to).trim() + (if (to < t.length) "..." else "")
    }

    /** "Acme Technologies Pvt. Ltd." -> "Acme Technologies", for use in sentences. */
    fun shortName(company: String): String =
        company.replace(Regex("(?i)[,\\s]+(pvt\\.?|private|ltd\\.?|limited|inc\\.?|llc|llp|corp\\.?|co\\.)(?=[,\\s.]|$)"), "").trim().trimEnd(',', '.')
            .ifBlank { company }

    /** Scores at or above this count as a real match when describing a company's openings. */
    private const val GOOD = 40

    private fun norm(company: String) = company.lowercase()
        .replace(Regex("\\b(pvt|private|ltd|limited|inc|llc|llp|corp|corporation|technologies|technology|solutions|india)\\b\\.?"), "")
        .replace(Regex("[^a-z0-9]+"), "")

    /** Open jobs at the same company, best match first. */
    fun openingsAt(company: String, d: AppData): List<Job> {
        val n = norm(company)
        if (n.length < 2) return emptyList()
        return d.jobs.filter { it.status != JobStatus.IGNORED && it.status != JobStatus.REJECTED && norm(it.company) == n }
            .sortedByDescending { it.matchScore }
    }

    /** "Python Developer – Web development + Agentic AI (Remote)" -> "Python Developer". */
    fun shortRole(title: String?): String {
        val t = title.orEmpty().split(Regex("\\s[-\u2013\u2014|:/+]\\s|[(,\\[]")).first().trim()
        if (t.length <= 42) return t
        val cut = t.take(42)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 15 } ?: 42).trim()
    }

    private fun role(job: Job?) = shortRole(job?.title)

    /** Lines for the card, strongest evidence first. */
    fun lines(c: Contact, d: AppData): List<String> {
        val job = d.jobs.firstOrNull { it.key == c.jobKey }
        val full = job?.company ?: c.company
        val company = shortName(full)
        val out = mutableListOf<String>()
        if (c.postSnippet.isNotBlank()) out += "Posted about hiring: \"${c.postSnippet}\""
        if (c.source == "posting" && job != null) {
            val days = Matcher.daysSince(job.postedAt, System.currentTimeMillis())
            out += "Named as the hiring contact on the ${role(job)} posting" + when (days) {
                null -> ""; 0 -> ", posted today"; else -> ", posted $days day${if (days == 1) "" else "s"} ago"
            }
        }
        if (Regex("(?i)\\bhiring\\b").containsMatchIn(c.headline)) out += "Their headline says they are hiring"
        val open = openingsAt(full, d)
        val good = open.filter { it.matchScore >= GOOD || it.key == c.jobKey }
        if (good.isNotEmpty()) {
            val list = good.take(3).joinToString(", ") { "${role(it)} (${it.matchScore}%)" }
            out += if (good.size == 1) "$company has an opening that matches you: $list"
            else "$company has ${good.size} openings that match you: $list"
        } else if (open.isNotEmpty()) out += "$company has ${open.size} opening(s) in your searches, none a close match"
        val signal = out.isNotEmpty()
        if (c.degree == 1) out += "You are already connected"
        if (c.why.isNotBlank()) c.why.split(" \u00b7 ").filter { it !in setOf("Posted about hiring", "Hiring contact", "Already connected", "Headline says hiring") }
            .takeIf { it.isNotEmpty() }?.let { out += it.joinToString(", ") }
        if (!signal) out += if (company.isNotBlank()) "No hiring signal found. Suggested from your search of people at $company."
        else "No hiring signal found, and no company or job is linked yet."
        return out
    }

    /** True when there is concrete evidence (a post, a posting, or openings) behind the suggestion. */
    fun hasSignal(c: Contact, d: AppData): Boolean =
        c.postSnippet.isNotBlank() || c.source == "posting" || openingsAt(d.jobs.firstOrNull { it.key == c.jobKey }?.company ?: c.company, d).isNotEmpty()

    /**
     * Opening clause for messages, e.g. "I saw your post about hiring for Android Developer at Acme".
     * No full stop. [short] gives a tighter wording for when the note would not fit.
     */
    fun opening(c: Contact, job: Job?, d: AppData, short: Boolean = false): String {
        val full = job?.company ?: c.company
        val company = shortName(full)
        val r = role(job)
        val at = if (company.isNotBlank()) " at $company" else ""
        val good = openingsAt(full, d).count { it.matchScore >= GOOD || it.key == c.jobKey }
        return when {
            c.postSnippet.isNotBlank() && short -> "I saw your hiring post" + if (r.isNotBlank()) " for $r" else at
            c.postSnippet.isNotBlank() -> "I saw your post about hiring" + (if (r.isNotBlank()) " for $r" else "") + at
            c.source == "posting" && job != null -> if (short) "I saw you're the hiring contact for $r" else "I saw you're the hiring contact for the $r opening$at"
            job != null && short -> "I'm applying for $r$at"
            job != null && good > 1 -> "I noticed $company has $good openings in my field, including $r"
            job != null -> "I noticed $company has an opening for $r"
            company.isNotBlank() -> "I'm interested in opportunities at $company"
            else -> "I came across your profile"
        }
    }
}

/** Rule-based resume tailoring: lead with what the job asks for. No text is invented. */
object Tailor {
    data class Result(val summary: String, val skills: List<String>, val missingKeywords: List<String>)

    private val DICT = listOf(
        "Kotlin", "Java", "Android", "Jetpack Compose", "Swift", "iOS", "Flutter", "Dart", "React Native", "React",
        "Angular", "Vue", "Next.js", "JavaScript", "TypeScript", "HTML", "CSS", "Node.js", "Express", "Python", "Django",
        "Flask", "FastAPI", "Go", "Rust", "C++", "C#", ".NET", "PHP", "Laravel", "Ruby", "Rails", "Spring Boot", "Spring",
        "Hibernate", "SQL", "MySQL", "PostgreSQL", "MongoDB", "Redis", "SQLite", "Room", "Firebase", "GraphQL", "REST",
        "gRPC", "Kafka", "RabbitMQ", "Docker", "Kubernetes", "AWS", "Azure", "GCP", "Terraform", "Jenkins", "CI/CD",
        "Git", "Linux", "Microservices", "MVVM", "MVI", "Clean Architecture", "Coroutines", "RxJava", "Retrofit", "Dagger",
        "Hilt", "Koin", "JUnit", "Espresso", "Selenium", "Cypress", "Jest", "Unit testing", "TDD", "Agile", "Scrum", "Jira",
        "Figma", "Machine Learning", "Deep Learning", "TensorFlow", "PyTorch", "NLP", "LLM", "Pandas", "NumPy", "Spark",
        "Hadoop", "Airflow", "ETL", "Power BI", "Tableau", "Excel", "Data Analysis", "Statistics", "A/B testing",
        "SEO", "Google Analytics", "Salesforce", "SAP", "Product Management", "Stakeholder management", "Communication",
        "Leadership", "System Design", "Data Structures", "Algorithms", "OOP", "Security", "OAuth", "Play Store",
        "App Store", "Performance", "Accessibility", "WebSocket", "Bluetooth", "NDK", "Gradle", "Maven",
    )

    fun build(p: Profile, job: Job): Result {
        val hay = (job.title + "\n" + job.description + "\n" + job.criteria).lowercase()
        val mine = p.skillList()
        val matched = mine.filter { Matcher.containsTerm(hay, it) }
        val ordered = matched + mine.filterNot { it in matched }
        val mineLower = mine.map { it.lowercase() }.toSet()
        val missing = DICT.filter { it.lowercase() !in mineLower && Matcher.containsTerm(hay, it) }.take(12)

        val years = p.yearsExperience.trim().let { if (it.isNotEmpty() && it.all(Char::isDigit)) "$it years" else it }
        val lead = p.headline.trim().trimEnd('.').ifBlank { "Professional" }
        val summary = buildString {
            append(lead)
            if (years.isNotBlank()) append(" with $years of experience")
            append(". ")
            val focus = (if (matched.isNotEmpty()) matched else mine).take(5)
            if (focus.isNotEmpty()) append("Hands-on with ${Templates.joinNatural(focus)}. ")
            val role = job.title.split(Regex("[-(|,]")).first().trim()
            if (role.isNotBlank()) {
                append("Looking to contribute as ${article(role)} $role")
                if (job.company.isNotBlank()) append(" at ${job.company}")
                append(".")
            }
        }.trim()
        return Result(summary, ordered, missing)
    }

    private fun article(word: String) = if (word.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
}

/** Classifies incoming mail found by the read-only inbox scan. */
object MailRules {
    private val AUTO = listOf("out of office", "automatic reply", "auto-reply", "autoreply", "auto reply", "on leave", "on vacation")
    private val REJECT = listOf(
        "unfortunately", "regret to inform", "not moving forward", "not be moving forward", "other candidates",
        "another candidate", "not selected", "not been selected", "position has been filled", "not be proceeding",
        "won't be proceeding", "will not be proceeding", "not shortlisted", "decided not to",
    )
    private val INTERVIEW = listOf(
        "interview", "schedule a call", "quick call", "your availability", "available for a call", "next round",
        "assessment", "coding test", "assignment", "shortlisted", "short-listed", "screening", "discuss further",
        "meeting invite", "calendar invite",
    )

    fun classify(subject: String, body: String): ReplyKind {
        val s = subject.lowercase()
        val all = s + "\n" + body.lowercase().take(4000)
        return when {
            AUTO.any { s.contains(it) } -> ReplyKind.AUTO
            REJECT.any { all.contains(it) } -> ReplyKind.REJECTION
            INTERVIEW.any { all.contains(it) } -> ReplyKind.INTERVIEW
            else -> ReplyKind.REPLY
        }
    }

    fun isAcceptance(subject: String): Boolean {
        val s = subject.lowercase()
        return s.contains("accepted your invitation") || s.contains("is now a connection") || s.contains("are now connected")
    }

    fun isLinkedInMessage(subject: String): Boolean {
        val s = subject.lowercase()
        return s.contains("messaged you") || s.contains("sent you a message") || s.contains("new message from") ||
            s.contains("replied to your message") || s.contains("sent you a new message")
    }

    fun isBounce(from: String, subject: String): Boolean {
        val f = from.lowercase(); val s = subject.lowercase()
        return f.contains("mailer-daemon") || f.contains("postmaster") ||
            s.contains("delivery status notification") || s.contains("undeliverable") || s.contains("delivery failure")
    }

    /** True when the person's name appears in the text (full name, or first name as a whole word). */
    fun mentions(text: String, name: String): Boolean {
        val t = text.lowercase()
        val n = name.lowercase().trim()
        if (n.length < 3) return false
        if (t.contains(n)) return true
        val first = n.split(" ").first()
        return first.length >= 4 && Regex("(?<![a-z])" + Regex.escape(first) + "(?![a-z])").containsMatchIn(t)
    }
}

data class TemplateStat(val label: String, val sent: Int, val positive: Int) {
    val rate: Int? get() = if (sent == 0) null else positive * 100 / sent
}

data class Insights(
    val jobsFound: Int, val jobsKept: Int, val applied: Int, val interviews: Int, val rejected: Int,
    val suggested: Int, val invited: Int, val accepted: Int, val acceptanceRate: Int?,
    val asked: Int, val replied: Int, val referred: Int, val askReplyRate: Int?,
    val emailsSent: Int, val emailReplies: Int, val emailInterviews: Int, val emailRejections: Int,
    val bounced: Int, val emailReplyRate: Int?, val medianReplyHours: Int?,
    val emailTemplates: List<TemplateStat>, val noteTemplates: List<TemplateStat>,
    val respondingCompanies: List<Pair<String, Int>>,
) {
    companion object {
        fun of(d: AppData, now: Long): Insights {
            val jobs = d.jobs
            val kept = jobs.count { it.status != JobStatus.NEW && it.status != JobStatus.IGNORED }
            val appliedSet = setOf(JobStatus.APPLIED, JobStatus.REFERRAL_ASKED, JobStatus.INTERVIEW, JobStatus.REJECTED)

            val invited = d.contacts.filter { it.invitedAt > 0 }
            val accepted = invited.count { it.connectedAt > 0 }
            val asked = d.contacts.filter { it.askedAt > 0 }
            val replied = asked.count { it.stage == Stage.REPLIED || it.stage == Stage.REFERRED || it.repliedAt > 0 }

            val sent = d.emails.filter { it.sentAt > 0 && it.status != EmailStatus.FAILED }
            val real = setOf(ReplyKind.REPLY, ReplyKind.INTERVIEW, ReplyKind.REJECTION)
            val answered = sent.filter { it.reply in real }
            val hours = answered.filter { it.repliedAt > it.sentAt }.map { ((it.repliedAt - it.sentAt) / HOUR).toInt() }.sorted()

            val emailT = sent.groupBy { it.template }.toSortedMap().map { (t, l) ->
                TemplateStat("Email $t", l.size, l.count { it.reply == ReplyKind.REPLY || it.reply == ReplyKind.INTERVIEW })
            }
            val noteT = invited.groupBy { it.template }.toSortedMap().map { (t, l) ->
                TemplateStat("Note $t", l.size, l.count { it.connectedAt > 0 })
            }
            val companies = (answered.map { it.company } +
                d.contacts.filter { it.repliedAt > 0 || it.stage == Stage.REFERRED }.map { it.company })
                .filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.take(6).map { it.key to it.value }

            return Insights(
                jobsFound = jobs.size, jobsKept = kept, applied = jobs.count { it.status in appliedSet },
                interviews = jobs.count { it.status == JobStatus.INTERVIEW }, rejected = jobs.count { it.status == JobStatus.REJECTED },
                suggested = d.contacts.size, invited = invited.size, accepted = accepted,
                acceptanceRate = Guard.acceptanceRate(d, now),
                asked = asked.size, replied = replied, referred = d.contacts.count { it.stage == Stage.REFERRED },
                askReplyRate = if (asked.isEmpty()) null else replied * 100 / asked.size,
                emailsSent = sent.size, emailReplies = answered.size,
                emailInterviews = sent.count { it.reply == ReplyKind.INTERVIEW },
                emailRejections = sent.count { it.reply == ReplyKind.REJECTION },
                bounced = d.emails.count { it.status == EmailStatus.BOUNCED },
                emailReplyRate = if (sent.isEmpty()) null else answered.size * 100 / sent.size,
                medianReplyHours = if (hours.isEmpty()) null else hours[hours.size / 2],
                emailTemplates = emailT, noteTemplates = noteT, respondingCompanies = companies,
            )
        }
    }
}
