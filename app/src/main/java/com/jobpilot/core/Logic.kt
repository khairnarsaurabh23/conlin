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
import com.jobpilot.data.TailoredResume
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

    /** [session]: you're sending a batch yourself, so the spacing rule between single sends doesn't apply. */
    fun canInvite(d: AppData, now: Long, session: Boolean = false): Check {
        pauseText(d, now)?.let { return Check(false, it) }
        val u = inviteUsage(d, now)
        if (u.today >= u.limits.day) return Check(false, "Daily limit reached (${u.today} of ${u.limits.day})")
        if (u.week >= u.limits.week) return Check(false, "Weekly limit reached (${u.week} of ${u.limits.week})")
        return (if (session) null else gap(d, now)) ?: Check(true)
    }

    fun invitesLeft(d: AppData, now: Long): Int {
        val u = inviteUsage(d, now)
        return minOf(u.limits.day - u.today, u.limits.week - u.week).coerceAtLeast(0)
    }

    /** Direct messages to non-connections left in the rolling month. On Premium, answered ones come back. */
    fun directLeft(d: AppData, now: Long): Int {
        val byId = d.contacts.associateBy { it.id }
        val used = d.actions.filter { it.kind == ActionKind.DIRECT && now - it.at < 30 * DAY }.count { a ->
            val c = byId[a.ref]
            !(d.profile.premium && c != null && c.repliedAt > 0 && c.repliedAt - a.at < 90 * DAY)
        }
        return (d.profile.directPerMonth - used).coerceAtLeast(0)
    }

    fun canDirect(d: AppData, now: Long, session: Boolean = false): Check {
        if (directLeft(d, now) <= 0) return Check(false, "No direct messages left this month")
        return canMessage(d, now, session)
    }

    fun canMessage(d: AppData, now: Long, session: Boolean = false): Check {
        if (!d.safety.invitesOnly) pauseText(d, now)?.let { return Check(false, it) }
        val today = count(d, ActionKind.MESSAGE, DAY, now) + count(d, ActionKind.DIRECT, DAY, now)
        if (today >= d.profile.dailyMessageCap) return Check(false, "Daily message limit reached ($today of ${d.profile.dailyMessageCap})")
        return (if (session) null else gap(d, now)) ?: Check(true)
    }

    fun emailsLeftToday(d: AppData, now: Long): Int =
        (d.profile.dailyEmailCap - count(d, ActionKind.EMAIL, DAY, now)).coerceAtLeast(0)
}

/** Who to ask first: people with a real reason to answer rank highest. */
object Rank {
    data class Result(val score: Int, val why: String)

    fun score(
        headline: String, degree: Int, kind: ContactKind, p: Profile, job: Job?, tag: String = "",
        wroteHiringPost: Boolean = false, namedOnPosting: Boolean = false, openings: Int = 0, known: Boolean = false,
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
        if (known) { s += 45; why += "You know them" }
        if (kind == ContactKind.RECRUITER && !namedOnPosting) { s += 20; why += "Recruiter" }
        if (kind == ContactKind.MANAGER) { s += 18; why += "Hiring manager" }
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

    fun norm(company: String) = company.lowercase()
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
        if (c.known) out += "You already know them" + if (c.headline.isNotBlank()) " (${c.headline})" else ""
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
        if (c.why.isNotBlank()) c.why.split(" \u00b7 ").filter { it !in setOf("Posted about hiring", "Hiring contact", "Already connected", "Headline says hiring", "You know them") }
            .takeIf { it.isNotEmpty() }?.let { out += it.joinToString(", ") }
        if (!signal) out += if (company.isNotBlank()) "No hiring signal found. Suggested from your search of people at $company."
        else "No hiring signal found, and no company or job is linked yet."
        return out
    }

    /** True when there is concrete evidence (a post, a posting, or openings) behind the suggestion. */
    fun hasSignal(c: Contact, d: AppData): Boolean =
        c.known || c.postSnippet.isNotBlank() || c.source == "posting" || openingsAt(d.jobs.firstOrNull { it.key == c.jobKey }?.company ?: c.company, d).isNotEmpty()

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
            c.known && job != null -> if (short) "I saw $company is hiring for $r" else "Hope you're well. I saw $company is hiring for $r"
            c.known -> "Hope you're well. I'm looking at roles at $company"
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

    val DICT = listOf(
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


/**
 * Sorts a job into the groups shown as filters: remote (open to you), remote somewhere else,
 * abroad, home country, visa sponsorship and startup. Works from the location and the text.
 */
object Classify {
    private val INDIA = listOf(
        "india", "bengaluru", "bangalore", "pune", "mumbai", "hyderabad", "chennai", "delhi", "gurgaon", "gurugram",
        "noida", "kolkata", "ahmedabad", "kochi", "jaipur", "indore", "chandigarh", "coimbatore", "trivandrum",
        "thiruvananthapuram", "nagpur", "mohali", "vadodara", "bhubaneswar", "thane", "nashik", "surat", "mysore", "mysuru",
    )
    private val OPEN = listOf("worldwide", "anywhere", "global", "international", "all countries", "any location", "apac", "asia")
    private val REGION = Regex(
        "(?i)\\b(us|usa|u\\.s\\.?|united states|north america|americas|canada|uk|united kingdom|eu|europe|european|emea|latam|" +
            "germany|france|spain|netherlands|poland|portugal|ireland|australia|brazil|mexico|est|pst|cet|time ?zones?)\\b"
    )
    private val REMOTE = Regex("(?i)\\b(remote|anywhere|work from home|wfh|distributed)\\b")
    private val NO_VISA = Regex(
        "(?i)((no|not|unable to|cannot|can't|won't|will not|do not|don't|does not)\\s+(currently\\s+)?(offer\\s+|provide\\s+)?(visa\\s+)?sponsor" +
            "|without (visa )?sponsorship|must (already )?(have|hold) (the )?(right|authori[sz]ation) to work|must be (legally )?authori[sz]ed)"
    )
    private val VISA = Regex("(?i)visa sponsorship|sponsor(ship)? (your |a |work )?visa|visa support|relocation (support|package|assistance|bonus)|relocate you|help(s)? (you )?relocate|\\bvisa\\b")
    private val STARTUP_TEXT = Regex("(?i)\\b(seed|series [a-c]|yc [swfx]\\d\\d|y combinator|early[- ]stage|founding (engineer|team)|startup)\\b")
    private val STARTUP_SOURCES = listOf("hacker news", " careers", "wellfound", "y combinator", "work at a startup")

    data class Result(val tags: List<String>, val reasons: List<String>, val cautions: List<String>)

    fun homeWords(home: String): List<String> =
        if (home.isBlank() || home.equals("india", true)) INDIA else listOf(home.trim().lowercase())

    fun of(job: Job, home: String): Result {
        val loc = job.location.lowercase()
        val hw = homeWords(home)
        val homeName = home.ifBlank { "India" }
        val text = (job.title + "\n" + job.location + "\n" + job.criteria + "\n" + job.description.take(6000)).lowercase()
        val tags = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val cautions = mutableListOf<String>()

        val remote = REMOTE.containsMatchIn(loc)
        val inHome = hw.any { loc.contains(it) } || (hw === INDIA && Regex(",\\s*in$").containsMatchIn(loc.trim()))
        if (remote) {
            val openWord = OPEN.firstOrNull { loc.contains(it) }
            when {
                inHome -> { tags += "remote"; reasons += "Remote, open to $homeName" }
                openWord != null -> { tags += "remote"; reasons += "Remote, open worldwide" }
                REGION.containsMatchIn(job.location) -> {
                    tags += "remote-elsewhere"
                    val inParens = Regex("(?i)remote\\s*\\(([^)]*)\\)").findAll(job.location).map { it.groupValues[1].trim() }.toList()
                    val region = if (inParens.isNotEmpty()) inParens.joinToString(", ")
                    else job.location.replace(Regex("(?i)\\bremote\\b,?\\s*"), "").trim(' ', ',').ifBlank { job.location }
                    cautions += "Remote, but only for $region"
                }
                else -> { tags += "remote"; reasons += "Remote" }
            }
        }
        if (inHome) tags += "home"
        // A real place that is not home: strip the remote/arrangement words and see what is left.
        val place = loc.replace(Regex("remote|hybrid|on-?site|in-office|anywhere|worldwide|full[- ]?time|\\(|\\)|[^a-z ]"), " ").trim()
        if (!inHome && place.length >= 2 && "remote" !in tags) tags += "abroad"

        val away = "abroad" in tags || "remote-elsewhere" in tags
        if (away || job.source == "Hacker News" || job.source == "Arbeitnow") {
            if (NO_VISA.containsMatchIn(text)) { if (away) cautions += "Says no visa sponsorship" }
            else if (VISA.containsMatchIn(text)) { tags += "visa"; reasons += "Mentions visa sponsorship or relocation" }
        }
        if (STARTUP_SOURCES.any { job.source.lowercase().contains(it) } || STARTUP_TEXT.containsMatchIn(text)) tags += "startup"
        return Result(tags.distinct(), reasons, cautions)
    }
}


/** How a kept job gets applied to. */
enum class ApplyVia(val label: String) { EMAIL("By email"), FORM("Company form"), LINKEDIN("On LinkedIn") }

/**
 * Is the resume ready for this job? Compares what the posting asks for with what the resume
 * (or the tailored version for this job) actually shows.
 */
object Readiness {
    enum class Level(val label: String) { READY("Ready to apply"), TAILOR("Tailor first"), WEAK("Weak fit") }

    data class Result(
        val level: Level,
        /** Asked for, and the resume shows it. */
        val shown: List<String>,
        /** Asked for, listed in your skills, but the resume doesn't show it: tailoring fixes this. */
        val unshown: List<String>,
        /** Asked for, and not in your profile at all. */
        val gaps: List<String>,
        val note: String = "",
        /** Hard blockers in the posting, e.g. no sponsorship when you need it. */
        val knockouts: List<String> = emptyList(),
    )

    fun via(job: Job): ApplyVia = when {
        job.emailsFound.any { Emails.isRecruitingAddress(it) } -> ApplyVia.EMAIL
        job.source.startsWith("LinkedIn") -> ApplyVia.LINKEDIN
        else -> ApplyVia.FORM
    }

    /** "Greenhouse form", "Lever form", ... from the apply link. */
    fun formName(job: Job): String {
        val u = (job.applyUrl.ifBlank { job.url }).lowercase()
        return when {
            "greenhouse" in u -> "Greenhouse form"
            "lever.co" in u -> "Lever form"
            "ashbyhq" in u -> "Ashby form"
            "myworkdayjobs" in u || "workday" in u -> "Workday form"
            "smartrecruiters" in u -> "SmartRecruiters form"
            "bamboohr" in u -> "BambooHR form"
            "workable" in u -> "Workable form"
            "teamtailor" in u -> "Teamtailor form"
            "breezy.hr" in u -> "Breezy form"
            "wellfound" in u -> "Wellfound"
            "ycombinator" in u || "workatastartup" in u -> "YC Work at a Startup"
            "news.ycombinator" in u -> "See the HN post"
            else -> "Company form"
        }
    }

    fun of(job: Job, p: Profile): Result {
        val posting = (job.title + "\n" + job.description + "\n" + job.criteria).lowercase()
        val skills = p.skillList()
        val skillSet = skills.map { it.lowercase() }.toSet()
        val terms = (Tailor.DICT + skills).distinctBy { it.lowercase() }.filter { Matcher.containsTerm(posting, it) }
        val t = job.tailored
        val resume = when {
            t != null -> (listOf(t.headline, t.summary) + t.skills + t.experience.flatMap { it.bullets + it.title } + t.projects.flatMap { it.bullets + it.name }).joinToString("\n")
            p.resumeText.isNotBlank() -> p.resumeText
            else -> skills.joinToString(", ") + "\n" + p.experienceText
        }.lowercase()
        val shown = terms.filter { Matcher.containsTerm(resume, it) }
        val rest = terms.filterNot { it in shown }
        val unshown = rest.filter { it.lowercase() in skillSet }
        val gaps = rest.filterNot { it.lowercase() in skillSet }
        val coverage = if (terms.isEmpty()) 100 else shown.size * 100 / terms.size
        val level = when {
            job.matchScore < p.minMatch -> Level.WEAK
            job.description.isBlank() -> Level.TAILOR
            t != null -> Level.READY
            unshown.isNotEmpty() -> Level.TAILOR
            terms.size >= 3 && coverage < 60 -> Level.TAILOR
            else -> Level.READY
        }
        val note = when {
            level == Level.WEAK -> "Match is under your ${p.minMatch}% minimum."
            job.description.isBlank() -> "Load the full posting to check your resume against it."
            t != null -> "Using the resume tailored for this job."
            p.resumeText.isBlank() -> "Checked against your skills list. Read your resume in Settings for a real check."
            level == Level.TAILOR && unshown.isNotEmpty() -> "Your resume doesn't show some skills you have that this posting asks for."
            level == Level.TAILOR -> "Your resume shows only $coverage% of what this posting asks for."
            else -> "Your resume shows $coverage% of what this posting asks for."
        }
        return Result(level, shown, unshown, gaps, note, Knockout.of(job, p))
    }
}

/** Builds the rules the form filler uses, from the saved answers. */
object FormRules {
    /** Escapes a saved question so it can be matched literally (also valid as a JavaScript pattern). */
    fun esc(s: String) = Regex("[.*+?^\${}()|\\[\\]\\\\/]").replace(s.trim().lowercase()) { "\\" + it.value }

    private fun yesNo(a: String): String? = when {
        a.trim().lowercase().startsWith("yes") -> "Yes"
        a.trim().lowercase().startsWith("no") -> "No"
        else -> null
    }

    /** Ordered (regex, value) pairs; the first match wins. Custom questions come first. */
    fun text(p: Profile): List<Pair<String, String>> {
        val a = p.answers
        val parts = p.name.trim().split(Regex("\\s+"))
        val first = parts.firstOrNull().orEmpty()
        val last = if (parts.size > 1) parts.last() else ""
        val email = p.email.ifBlank { p.smtpUser }
        val out = mutableListOf<Pair<String, String>>()
        a.custom.filter { it.q.isNotBlank() && it.a.isNotBlank() }.forEach { out += esc(it.q) to it.a }
        out += "first.?name|given.?name|fname" to first
        out += "last.?name|surname|family.?name|lname" to last
        out += "^(?!.*(company|employer|school|university|college|reference|referr|manager|emergency)).*(full.?name|your name|\\bname\\b)" to p.name
        out += "e-?mail" to email
        out += "phone|mobile|contact.?number|whatsapp" to p.phone
        out += "linkedin" to p.linkedinUrl
        out += "github" to a.github
        out += "portfolio|personal.?(site|website)|website|blog|other.?link" to a.portfolio
        out += "notice" to a.noticePeriod
        out += "expected.{0,15}(ctc|salary|compensation|pay)|salary.?expectation|desired.{0,10}(salary|compensation)" to a.expectedCtc
        out += "current.{0,15}(ctc|salary|compensation)" to a.currentCtc
        out += "(current|present|most recent).{0,10}(company|employer|organi[sz]ation)" to a.currentCompany
        out += "(current|present|most recent).{0,10}(title|role|designation|position)" to a.currentTitle
        out += "(years|yrs).{0,25}experience|experience.{0,15}(years|yrs)|total experience" to p.yearsExperience
        out += "start date|earliest start|available to start|when can you (start|join)|joining date" to a.startDate
        out += "how did you (hear|find|learn|come)|where did you (hear|find)" to a.howHeard
        out += "sponsor" to a.needsSponsorship
        out += "relocat" to a.willingToRelocate
        out += "(authori[sz]ed|authori[sz]ation|eligible|legally).{0,40}work|work.{0,15}(permit|authori[sz]ation)" to a.workAuthorization
        out += "gender" to a.gender
        out += "(current )?(location|city)|where are you (based|located)|based in|current address" to a.city
        out += "college|university|school" to p.college
        return out.filter { it.second.isNotBlank() }
    }

    /** Yes/No radio groups: question pattern -> "Yes" or "No". */
    fun yesNo(p: Profile): List<Pair<String, String>> {
        val a = p.answers
        return listOfNotNull(
            yesNo(a.needsSponsorship)?.let { "sponsor" to it },
            yesNo(a.willingToRelocate)?.let { "relocat" to it },
            yesNo(a.workAuthorization)?.let { "(authori[sz]ed|eligible|legally).{0,40}work" to it },
        ) + p.answers.custom.mapNotNull { qa -> yesNo(qa.a)?.let { esc(qa.q) to it } }
    }
}


/** Hard blockers in a posting: things that get an application rejected regardless of fit. */
object Knockout {
    private val DEGREE = Regex("(?i)(master'?s|\\bm\\.?s\\.?\\b|m\\.?tech|\\bmba\\b|ph\\.?d|doctorate)[^.\\n]{0,40}(required|mandatory|must)|(require[sd]?|must have)[^.\\n]{0,30}(master'?s|ph\\.?d|doctorate)")
    private val HAS_DEGREE = Regex("(?i)master|m\\.?tech|\\bm\\.?s\\b|\\bmba\\b|ph\\.?d|doctorate|post ?graduate")
    private val AUTH = Regex("(?i)must (already )?(be|have)[^.\\n]{0,25}(authori[sz]ed|eligible|right) to work in (the )?([a-z .]{2,30})")
    private val RESIDE = Regex("(?i)(must|need to|required to) (be based|reside|live|be located) in (the )?([a-z ,]{2,40})")
    private val CLEARANCE = Regex("(?i)(security|secret|government) clearance[^.\\n]{0,30}(required|must|needed)|must (hold|have|obtain)[^.\\n]{0,20}clearance")

    fun of(job: Job, p: Profile): List<String> {
        if (job.description.isBlank()) return emptyList()
        val text = job.description
        val home = Classify.homeWords(p.homeCountry)
        val out = mutableListOf<String>()
        val mine = p.yearsExperience.trim().toIntOrNull()
        val need = Matcher.requiredYears(text)
        if (mine != null && need != null && need > mine + 1) out += "Asks for $need+ years; you have $mine"
        if (DEGREE.containsMatchIn(text) && !HAS_DEGREE.containsMatchIn(p.resumeText + " " + p.educationText + " " + p.college)) out += "Requires a Master's or PhD"
        AUTH.find(text)?.let { m ->
            val where = m.groupValues[5].trim().trimEnd('.', ',')
            if (home.none { where.lowercase().contains(it) }) out += "Must already be authorized to work in ${where.replaceFirstChar(Char::uppercase)}"
        }
        RESIDE.find(text)?.let { m ->
            val where = m.groupValues[4].trim().trimEnd('.', ',')
            if (home.none { where.lowercase().contains(it) } && !where.contains("remote", true)) out += "Must live in ${where.replaceFirstChar(Char::uppercase)}"
        }
        if (CLEARANCE.containsMatchIn(text)) out += "Needs a security clearance"
        if (p.answers.needsSponsorship.trim().lowercase().startsWith("yes") && "abroad" in job.tags &&
            job.cautions.any { it.startsWith("Says no visa") }) out += "No visa sponsorship, and you need it"
        return out.distinct()
    }
}

/** Is a posting still open? Phrases from career-ops' liveness checks (MIT). */
object Liveness {
    private val CLOSED = listOf(
        Regex("(?i)job (is )?no longer available"), Regex("(?i)job.{0,20}no longer open"),
        Regex("(?i)this job has expired|job posting has expired"), Regex("(?i)no longer accepting applications"),
        Regex("(?i)this (position|role|job) (is )?no longer"), Regex("(?i)this (?:job|role|position)(?: listing)? is closed\\b(?!-)"),
        Regex("(?i)position has (already )?been filled"), Regex("(?i)applications?\\s+(?:(?:have|are|is)\\s+)?closed(?!\\s*\\?)"),
        Regex("(?i)the job you(?:'re| are) (?:looking|searching) for (?:may |might )?(?:have |has )?(?:closed|expired|been removed)"),
        Regex("(?i)(job|posting|page) not found"),
    )

    fun closed(pageText: String): Boolean = CLOSED.any { it.containsMatchIn(pageText.take(30000)) }
}

/** The paced queue: when the next approved LinkedIn send should be offered. */
object Pacer {
    data class Next(val contact: Contact?, val waitMs: Long, val reason: String)

    fun inHours(p: Profile, now: Long): Boolean {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        return if (p.paceStartHour <= p.paceEndHour) h in p.paceStartHour until p.paceEndHour else h >= p.paceStartHour || h < p.paceEndHour
    }

    fun queueSize(d: AppData) = queue(d).size

    fun queue(d: AppData): List<Contact> = d.contacts
        .filter { it.stage == Stage.ASK_APPROVED || it.stage == Stage.APPROVED }
        .sortedWith(compareByDescending<Contact> { it.stage == Stage.ASK_APPROVED }.thenByDescending { it.rank })

    fun next(d: AppData, now: Long): Next {
        val p = d.profile
        val q = queue(d)
        if (q.isEmpty()) return Next(null, 0, "Queue is empty")
        if (!inHours(p, now)) return Next(null, 0, "Outside ${p.paceStartHour}:00 to ${p.paceEndHour}:00")
        if (q.any { it.openedAt > 0 && now - it.openedAt < 30 * 60_000L }) return Next(null, 0, "Finish the one that's open first")
        val gap = p.paceMinutes * 60_000L
        val lastSend = d.actions.filter { it.kind != ActionKind.EMAIL }.maxOfOrNull { it.at } ?: 0L
        val wait = maxOf(lastSend + gap, d.pace.lastNotifiedAt + gap) - now
        if (wait > 0) return Next(null, wait, "Next in ${wait / 60_000 + 1} min")
        val c = q.firstOrNull { c ->
            when {
                c.stage == Stage.APPROVED -> Guard.canInvite(d, now).ok
                c.direct && c.degree != 1 && c.connectedAt == 0L -> Guard.canDirect(d, now).ok
                else -> Guard.canMessage(d, now).ok
            }
        } ?: return Next(null, 0, Guard.canInvite(d, now).reason.ifBlank { "Limits reached for now" })
        return Next(c, 0, "Ready")
    }

    /** "10:00" -> (10, 0); falls back to 10:00. */
    fun sessionClock(p: Profile): Pair<Int, Int> {
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(p.sessionTime.trim())
        val h = m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..23 } ?: 10
        val min = m?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return h to min
    }

    /** Today's reminder time, in millis. */
    fun sessionAt(p: Profile, now: Long): Long {
        val (h, m) = sessionClock(p)
        return java.util.Calendar.getInstance().apply {
            timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun day(now: Long): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))

    /** Time for today's one reminder: past the set time, not reminded yet today, and something to send. */
    fun reminderDue(d: AppData, now: Long): Boolean =
        d.profile.paceEnabled && queue(d).isNotEmpty() && now >= sessionAt(d.profile, now) && d.pace.lastReminderDay != day(now)

    /** How many of the queue fit in today's limits. */
    fun sendableNow(d: AppData, now: Long): Int {
        val q = queue(d)
        val invites = q.count { it.stage == Stage.APPROVED }
        val msgs = q.size - invites
        val invLeft = if (Guard.canInvite(d, now, true).ok) minOf(invites, Guard.invitesLeft(d, now)) else 0
        val msgLeft = if (Guard.canMessage(d, now, true).ok) msgs else 0
        return invLeft + msgLeft
    }

    fun status(d: AppData, now: Long): String {
        val p = d.profile
        val count = queue(d).size
        if (count == 0) return "Nothing waiting. Approved requests collect here until you send them."
        val ready = sendableNow(d, now)
        val head = "$count waiting" + if (ready < count) ", $ready fit today's limits" else ""
        return head + if (p.paceEnabled) ". Reminder at ${p.sessionTime} daily; send any time." else ". Send any time."
    }

    fun emailDue(d: AppData, now: Long): Boolean {
        val p = d.profile
        return p.emailDrip && p.smtpConfigured && inHours(p, now) &&
            d.emails.any { it.status == EmailStatus.APPROVED } &&
            now - d.pace.lastEmailAt >= p.emailDripMinutes * 60_000L &&
            Guard.emailsLeftToday(d, now) > 0
    }
}

/** Claims in a tailored resume that the original resume doesn't support (career-ops' verify-cv-facts idea). */
object FactCheck {
    private val NUM = Regex("\\d[\\d,]*(\\.\\d+)?\\s*(%|\\+|x\\b|k\\b|m\\b|lpa|lakh|cr\\b|crore|million|billion)?", RegexOption.IGNORE_CASE)

    private fun digits(s: String) = s.filter { it.isDigit() || it == '.' }.trimEnd('.')

    fun unsupported(r: TailoredResume, p: Profile): List<String> {
        val source = (p.resumeText + "\n" + p.experienceText + "\n" + p.educationText + "\n" + p.skills + "\n" + p.yearsExperience + "\n" + p.headline).lowercase()
        if (source.isBlank()) return emptyList()
        val sourceNums = NUM.findAll(source).map { digits(it.value) }.filter { it.isNotEmpty() }.toSet()
        val out = mutableListOf<String>()
        val texts = listOf(r.summary, r.headline) + r.experience.flatMap { it.bullets } + r.projects.flatMap { it.bullets } + r.other
        for (t in texts) for (m in NUM.findAll(t)) {
            val n = digits(m.value)
            if (n.length >= 1 && n !in sourceNums && n.toDoubleOrNull()?.let { it > 1 } == true) out += "\"${m.value.trim()}\" in: ${t.take(80)}"
        }
        r.experience.forEach { e ->
            if (e.company.isNotBlank() && !source.contains(e.company.lowercase().take(12))) out += "Employer \"${e.company}\" isn't in your resume"
            if (e.title.isNotBlank() && !source.contains(e.title.lowercase().split(" ").filter { it.length > 3 }.firstOrNull() ?: e.title.lowercase())) out += "Title \"${e.title}\" isn't in your resume"
        }
        r.skills.filter { sk -> !source.contains(sk.lowercase()) }.take(6).forEach { out += "Skill \"$it\" isn't in your resume or skills" }
        return out.distinct().take(12)
    }
}
