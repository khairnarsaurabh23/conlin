package com.jobpilot.core

import com.jobpilot.data.Job
import com.jobpilot.data.Profile

/** Match scoring, email extraction and message templates. Pure Kotlin, no Android deps. */
object Matcher {
    data class Result(val score: Int, val matched: List<String>, val missing: List<String>)

    fun score(job: Job, profile: Profile, searchKeywords: String): Result {
        val skills = profile.skillList()
        val hay = (job.title + "\n" + job.description + "\n" + job.criteria).lowercase()
        val matched = skills.filter { containsTerm(hay, it) }
        val missing = skills.filterNot { containsTerm(hay, it) }

        // Title relevance: share of search keywords that appear in the title.
        val kw = searchKeywords.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length > 1 }
        val titleHits = kw.count { job.title.lowercase().contains(it) }
        val titleScore = if (kw.isEmpty()) 0.5 else titleHits.toDouble() / kw.size

        val skillScore = when {
            skills.isEmpty() -> 0.5
            job.description.isBlank() -> 0.5 // details not fetched yet
            else -> matched.size.toDouble() / skills.size
        }
        val score = (titleScore * 40 + skillScore * 60).toInt().coerceIn(0, 100)
        return Result(score, matched, missing)
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
