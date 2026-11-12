package com.jobpilot.core

import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Profile

/** What the user tends to keep and skip, learned from their own decisions. */
data class TitlePrefs(val liked: Set<String>, val disliked: Set<String>) {
    companion object {
        val NONE = TitlePrefs(emptySet(), emptySet())
        private val KEPT = setOf(JobStatus.SAVED, JobStatus.APPLIED, JobStatus.REFERRAL_ASKED, JobStatus.INTERVIEW)

        fun tokens(title: String): Set<String> =
            title.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length > 3 }.toSet()

        fun of(jobs: List<Job>): TitlePrefs {
            val kept = HashMap<String, Int>(); val skipped = HashMap<String, Int>()
            for (j in jobs) {
                val into = when (j.status) { in KEPT -> kept; JobStatus.IGNORED -> skipped; else -> continue }
                tokens(j.title).forEach { into[it] = (into[it] ?: 0) + 1 }
            }
            val liked = kept.filter { (t, n) -> n >= 2 && n >= 2 * (skipped[t] ?: 0) }.keys
            val disliked = skipped.filter { (t, n) -> n >= 3 && n >= 3 * (kept[t] ?: 0) }.keys
            return TitlePrefs(liked, disliked)
        }
    }
}

/** Match scoring, email extraction and message templates. Pure Kotlin, no Android deps. */
object Matcher {
    data class Result(
        val score: Int, val matched: List<String>, val missing: List<String>,
        val reasons: List<String> = emptyList(), val cautions: List<String> = emptyList(),
    )

    private val YEARS = Regex("(\\d{1,2})\\s*(?:\\+|plus)?\\s*(?:-|to|\u2013)?\\s*(?:\\d{1,2})?\\s*\\+?\\s*(?:years?|yrs?)", RegexOption.IGNORE_CASE)
    private val SENIOR = Regex("(?i)\\b(senior|sr\\.?|lead|principal|staff|architect|manager|head|director|vp)\\b")
    private val JUNIOR = Regex("(?i)\\b(intern|internship|trainee|fresher|graduate|apprentice)\\b")

    /** Smallest "N years" figure the description asks for, if it states one. */
    fun requiredYears(description: String): Int? =
        YEARS.findAll(description).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 0..20 }.minOrNull()

    fun daysSince(postedAt: String, now: Long): Int? = runCatching {
        val d = java.time.LocalDate.parse(postedAt.take(10))
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        java.time.temporal.ChronoUnit.DAYS.between(d, today).toInt().coerceAtLeast(0)
    }.getOrNull()

    fun score(
        job: Job, profile: Profile, searchKeywords: String,
        prefs: TitlePrefs = TitlePrefs.NONE, now: Long = System.currentTimeMillis(),
    ): Result {
        val skills = profile.skillList()
        val hay = (job.title + "\n" + job.description + "\n" + job.criteria).lowercase()
        val matched = skills.filter { containsTerm(hay, it) }
        val missing = skills.filterNot { containsTerm(hay, it) }
        val reasons = mutableListOf<String>()
        val cautions = mutableListOf<String>()
        val hasDetails = job.description.isNotBlank()

        // Title relevance: share of search keywords that appear in the title.
        val kw = searchKeywords.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length > 1 }
        val titleHits = kw.count { job.title.lowercase().contains(it) }
        val titleScore = if (kw.isEmpty()) 0.5 else titleHits.toDouble() / kw.size
        if (kw.isNotEmpty() && titleHits == kw.size) reasons += "Title matches \"${searchKeywords.trim()}\""
        else if (kw.isNotEmpty() && titleHits == 0) cautions += "Title doesn't mention \"${searchKeywords.trim()}\""

        val skillScore = when {
            skills.isEmpty() || !hasDetails -> 0.5
            else -> matched.size.toDouble() / skills.size
        }
        if (hasDetails && skills.isNotEmpty()) {
            if (matched.isNotEmpty()) reasons += "Mentions ${matched.size} of your ${skills.size} skills: ${matched.take(4).joinToString(", ")}"
            else cautions += "Mentions none of your skills"
        }
        var s = titleScore * 35 + skillScore * 45

        // Experience asked for vs. what the user has
        val mine = profile.yearsExperience.trim().toIntOrNull()
        val need = if (hasDetails) requiredYears(job.description) else null
        when {
            need == null || mine == null -> s += 5
            need <= mine -> { s += 10; reasons += "Asks for $need+ years, you have $mine" }
            need == mine + 1 -> s += 4
            else -> { s -= 12; cautions += "Asks for $need+ years, you have $mine" }
        }
        if (mine != null) {
            if (mine < 4 && SENIOR.containsMatchIn(job.title)) { s -= 10; cautions += "Senior-level title" }
            if (mine >= 2 && JUNIOR.containsMatchIn(job.title)) { s -= 10; cautions += "Entry-level title" }
        }

        // Fresh postings get more replies
        val days = daysSince(job.postedAt, now)
        when {
            days == null -> s += 2
            days <= 3 -> { s += 6; reasons += if (days == 0) "Posted today" else "Posted $days day${if (days == 1) "" else "s"} ago" }
            days <= 10 -> s += 3
            days > 30 -> { s -= 5; cautions += "Posted over a month ago" }
        }

        // What the user kept and skipped before
        val tokens = TitlePrefs.tokens(job.title)
        if (tokens.any { it in prefs.liked }) { s += 6; reasons += "Similar to jobs you kept" }
        if (tokens.any { it in prefs.disliked }) { s -= 8; cautions += "Similar to jobs you skipped" }

        if (hasDetails && Emails.extract(job.description).isNotEmpty()) { s += 3; reasons += "Recruiter email in the posting" }
        if (!hasDetails) cautions += "Scored on the title only; open it to load the full description"

        return Result(s.toInt().coerceIn(0, 100), matched, missing, reasons, cautions)
    }

    fun containsTerm(hay: String, term: String): Boolean {
        val t = term.lowercase().trim()
        if (t.isEmpty()) return false
        // Word-ish boundary match that tolerates symbols like c++, c#, node.js
        val pattern = "(?<![a-z0-9])" + Regex.escape(t) + "(?![a-z0-9])"
        return Regex(pattern).containsMatchIn(hay)
    }
}

object Emails {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val BAD_SUFFIX = listOf(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp")
    private val BAD_PARTS = listOf("example.com", "domain.com", "email.com", "noreply", "no-reply", "sentry", "linkedin.com")

    data class Found(val email: String, val context: String)

    fun extract(text: String): List<Found> {
        val out = LinkedHashMap<String, Found>()
        for (m in EMAIL.findAll(text)) {
            val e = m.value.trimEnd('.').lowercase()
            if (BAD_SUFFIX.any { e.endsWith(it) } || BAD_PARTS.any { e.contains(it) }) continue
            if (out.containsKey(e)) continue
            val from = (m.range.first - 280).coerceAtLeast(0)
            val to = (m.range.last + 120).coerceAtMost(text.length - 1)
            val ctx = text.substring(from, to + 1).replace(Regex("\\s+"), " ").trim()
            out[e] = Found(e, ctx)
        }
        return out.values.toList()
    }

    /** Light heuristic: does the surrounding text actually ask people to send a CV/resume? */
    fun looksLikeHiringAsk(context: String): Boolean {
        val c = context.lowercase()
        return listOf("resume", "cv", "hiring", "send", "share", "apply", "opening", "position", "role", "interested", "drop")
            .any { c.contains(it) }
    }

    fun guessNameFromEmail(email: String): String {
        val local = email.substringBefore("@")
        if (local.length < 3 || listOf("hr", "careers", "jobs", "recruit", "talent", "hiring", "info").any { local.contains(it) }) return ""
        return local.split('.', '_', '-').filter { it.isNotEmpty() && it.all(Char::isLetter) }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    fun companyFromEmail(email: String): String {
        val domain = email.substringAfter("@").substringBeforeLast(".")
        val generic = listOf("gmail", "yahoo", "outlook", "hotmail", "icloud", "proton", "rediffmail", "live", "aol", "ymail")
        if (generic.any { domain.startsWith(it) }) return ""
        return domain.substringAfterLast(".").replaceFirstChar(Char::uppercase)
    }
}

object Templates {
    fun fill(
        template: String,
        p: Profile,
        recipientName: String = "",
        company: String = "",
        role: String = "",
        jobUrl: String = "",
        preferSkills: List<String> = emptyList(),
    ): String {
        val first = recipientName.trim().split(" ").firstOrNull().orEmpty()
        // Skills the job actually mentions come first.
        val skills = (preferSkills + p.skillList()).distinctBy { it.lowercase() }
        val values = mapOf(
            "firstName" to first.ifBlank { "there" },
            "name" to recipientName.ifBlank { "there" },
            "company" to company.ifBlank { "your company" },
            "role" to role.ifBlank { "open" },
            "jobUrl" to jobUrl,
            "myName" to p.name,
            "myHeadline" to p.headline.ifBlank { "a professional" },
            "myEmail" to p.email,
            "myPhone" to p.phone,
            "myLinkedIn" to p.linkedinUrl,
            "years" to p.yearsExperience.ifBlank { "relevant" }.let { if (it.all(Char::isDigit)) "$it years" else it },
            "skills" to skills.take(6).joinToString(", "),
            "topSkills" to joinNatural(skills.take(3)).ifBlank { "this area" },
        )
        val placeholderOnly = Regex("^\\s*(\\{\\w+\\}\\s*)+$")
        val out = template.lines().mapNotNull { line ->
            var filled = line
            values.forEach { (k, v) -> filled = filled.replace("{$k}", v) }
            // Drop signature lines like "{myPhone}" when that field is empty
            if (placeholderOnly.matches(line) && filled.isBlank()) null else filled
        }.joinToString("\n")
        // Clean up artefacts from empty optional fields
        return out.replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun joinNatural(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** Shortens to the limit at a sentence/word boundary. */
    fun fit(text: String, limit: Int): String {
        if (limit <= 0 || text.length <= limit) return text
        val cut = text.take(limit)
        val sentence = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (sentence > limit * 0.6) return cut.substring(0, sentence + 1)
        val space = cut.lastIndexOf(' ')
        return (if (space > 0) cut.substring(0, space) else cut).trimEnd(',', ' ') + "..."
    }
}
