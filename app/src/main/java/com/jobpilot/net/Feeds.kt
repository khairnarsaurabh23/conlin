package com.jobpilot.net

import com.jobpilot.core.Matcher
import com.jobpilot.data.Job
import com.jobpilot.data.SearchPref
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.Instant

/**
 * Job feeds that are published for anyone to read:
 *  - Hacker News "Who is hiring?" (monthly thread, mostly startups), through the HN Algolia API
 *  - Remotive (remote jobs). Their terms ask for attribution and at most a few calls a day.
 *  - Arbeitnow (Europe, many English-speaking roles with visa sponsorship)
 *  - Company careers pages hosted on Ashby, Lever or Greenhouse, through their public job-board APIs
 * Wellfound and YC's own job board have no open feed; those are saved from the Browse tab instead.
 */
object Feeds {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(s: String): JsonElement? = runCatching { json.parseToJsonElement(s) }.getOrNull()
    private fun JsonObject.s(k: String): String {
        val e = this[k] ?: return ""
        if (e is JsonNull) return ""
        return (e as? JsonPrimitive)?.content.orEmpty()
    }
    private fun JsonObject.o(k: String) = this[k] as? JsonObject
    private fun JsonObject.a(k: String) = this[k] as? JsonArray
    private fun JsonArray?.objects(): List<JsonObject> = this.orEmpty().mapNotNull { it as? JsonObject }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun html(s: String) = if (s.isBlank()) "" else LinkedInJobs.htmlToText(s)
    private fun cap(s: String) = s.split(Regex("[-_ ]+")).filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    // ---------------- keyword matching for feeds that can't search server-side ----------------

    private val GENERIC = setOf(
        "developer", "engineer", "engineering", "software", "senior", "junior", "sr", "jr", "lead", "staff", "intern",
        "remote", "the", "and", "for", "with", "of", "in", "at", "role", "job", "jobs",
    )

    fun tokens(keywords: String) = keywords.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length > 1 }
    fun specific(keywords: String) = tokens(keywords).filter { it !in GENERIC }

    /**
     * True when a posting fits the search. Specific words ("android", "python") must all appear
     * somewhere; when [titleRequired] at least one search word must also be in the title.
     */
    fun matches(keywords: String, title: String, body: String, titleRequired: Boolean = true): Boolean {
        val all = tokens(keywords)
        if (all.isEmpty()) return true
        val t = title.lowercase()
        val hay = (title + "\n" + body.take(4000)).lowercase()
        val spec = all.filter { it !in GENERIC }
        if (spec.isEmpty()) return all.any { Matcher.containsTerm(t, it) }
        if (!spec.all { Matcher.containsTerm(hay, it) }) return false
        return !titleRequired || all.any { Matcher.containsTerm(t, it) }
    }

    // ---------------- Remotive ----------------

    suspend fun remotive(p: SearchPref, onError: (String) -> Unit): List<Job> {
        val body = try {
            Http.get("https://remotive.com/api/remote-jobs?search=${enc(specific(p.keywords).joinToString(" ").ifBlank { p.keywords })}&limit=100")
        } catch (e: Exception) {
            onError("Remotive: ${e.message}"); return emptyList()
        }
        val root = parse(body) as? JsonObject ?: return emptyList()
        return root.a("jobs").objects().mapNotNull { o ->
            val id = o.s("id").ifBlank { return@mapNotNull null }
            val title = o.s("title")
            val desc = html(o.s("description"))
            if (!matches(p.keywords, title, desc)) return@mapNotNull null
            val region = o.s("candidate_required_location")
            Job(
                key = "rm:$id", source = "Remotive", title = title, company = o.s("company_name"),
                location = "Remote" + if (region.isNotBlank()) " ($region)" else "",
                url = o.s("url"), applyUrl = o.s("url"), postedAt = o.s("publication_date"), description = desc,
                criteria = listOf(o.s("job_type").replace('_', ' '), o.s("salary")).filter { it.isNotBlank() }.joinToString(" · "),
                searchId = p.id, detailsFetched = true,
            )
        }.take(p.maxResults)
    }

    // ---------------- Arbeitnow (fetched once per check, matched against each search) ----------------

    suspend fun arbeitnow(onError: (String) -> Unit, pages: Int = 3): List<Job> {
        val out = mutableListOf<Job>()
        for (n in 1..pages) {
            val body = try {
                Http.get("https://www.arbeitnow.com/api/job-board-api?page=$n")
            } catch (e: Exception) {
                onError("Arbeitnow: ${e.message}"); break
            }
            val root = parse(body) as? JsonObject ?: break
            root.a("data").objects().forEach { o ->
                val slug = o.s("slug").ifBlank { return@forEach }
                val remote = o.s("remote") == "true"
                val loc = o.s("location")
                val created = o.s("created_at").toLongOrNull()
                out += Job(
                    key = "an:$slug", source = "Arbeitnow", title = o.s("title"), company = o.s("company_name"),
                    location = listOf(loc, if (remote) "Remote" else "").filter { it.isNotBlank() }.joinToString(", ") +
                        if (loc.isNotBlank() && !loc.contains("germany", true)) ", Germany" else "",
                    url = o.s("url"), applyUrl = o.s("url"),
                    postedAt = created?.let { Instant.ofEpochSecond(it).toString() }.orEmpty(),
                    description = html(o.s("description")),
                    criteria = (o.a("job_types").orEmpty() + o.a("tags").orEmpty())
                        .mapNotNull { (it as? JsonPrimitive)?.content }.take(4).joinToString(" · "),
                    detailsFetched = true,
                )
            }
            val next = (root.o("links")?.get("next") as? JsonPrimitive)?.content
            if (next.isNullOrBlank() || next == "null") break
            delay(800)
        }
        return out
    }

    // ---------------- Hacker News: Ask HN: Who is hiring? ----------------

    @Volatile private var hnStory: Pair<String, Long>? = null

    private suspend fun hnStoryId(onError: (String) -> Unit): String? {
        hnStory?.let { (id, at) -> if (System.currentTimeMillis() - at < 6 * 3_600_000L) return id }
        val body = try {
            Http.get("https://hn.algolia.com/api/v1/search_by_date?tags=story,author_whoishiring&hitsPerPage=6")
        } catch (e: Exception) {
            onError("Hacker News: ${e.message}"); return null
        }
        val root = parse(body) as? JsonObject ?: return null
        val id = root.a("hits").objects().firstOrNull { it.s("title").startsWith("Ask HN: Who is hiring", ignoreCase = true) }?.s("objectID")
        if (id != null) hnStory = id to System.currentTimeMillis()
        return id
    }

    private val ROLE = Regex("(?i)engineer|developer|scientist|designer|manager|architect|analyst|devops|\\bsre\\b|founding|intern|head of|programmer|researcher|specialist")
    private val PLACE = Regex("(?i)remote|onsite|on-site|in-office|hybrid|visa|relocat|anywhere|worldwide|\\b(usa?|uk|eu|europe|canada|india|germany|berlin|london|nyc|new york|san francisco|sf|bay area|seattle|boston|austin|toronto|amsterdam|paris|singapore|bangalore|bengaluru)\\b")

    /** Splits the first line of a post, "Acme | Senior Engineer | Remote (US) | $150k", into its parts. */
    fun hnHeader(firstLine: String, keywords: String): Triple<String, String, String> {
        val parts = firstLine.split("|", " \u2022 ", " - ").map { it.trim() }.filter { it.isNotBlank() }
        val company = parts.firstOrNull().orEmpty().replace(Regex("\\(?https?://\\S+\\)?"), "").replace(Regex("\\s*\\(.*?\\)\\s*$"), "").trim().take(60)
        val rest = parts.drop(1)
        val title = rest.firstOrNull { ROLE.containsMatchIn(it) && it.length < 120 && !it.contains("http") }
            ?: rest.firstOrNull { part -> tokens(keywords).any { part.lowercase().contains(it) } && part.length < 120 }
            ?: "${cap(keywords)} (see post)"
        val loc = rest.filter { it != title && PLACE.containsMatchIn(it) && it.length < 80 && !it.contains("http") && !it.contains("$") }
            .take(3).joinToString(", ")
        return Triple(company, title, loc)
    }

    suspend fun hackerNews(p: SearchPref, onError: (String) -> Unit): List<Job> {
        val sid = hnStoryId(onError) ?: return emptyList()
        val q = specific(p.keywords).joinToString(" ").ifBlank { p.keywords }
        val body = try {
            Http.get("https://hn.algolia.com/api/v1/search?tags=comment,story_$sid&query=${enc(q)}&hitsPerPage=100")
        } catch (e: Exception) {
            onError("Hacker News: ${e.message}"); return emptyList()
        }
        val root = parse(body) as? JsonObject ?: return emptyList()
        return root.a("hits").objects().mapNotNull { o ->
            if (o.s("parent_id") != sid) return@mapNotNull null // only top-level posts, not replies
            val id = o.s("objectID").ifBlank { return@mapNotNull null }
            val text = html(o.s("comment_text"))
            if (text.length < 60) return@mapNotNull null
            val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            val (company, title, loc) = hnHeader(first, p.keywords)
            if (!matches(p.keywords, title, text, titleRequired = false)) return@mapNotNull null
            Job(
                key = "hn:$id", source = "Hacker News", title = title.take(120), company = company, location = loc,
                url = "https://news.ycombinator.com/item?id=$id", postedAt = o.s("created_at"),
                description = text.take(8000), searchId = p.id, detailsFetched = true,
            )
        }.take(p.maxResults)
    }

    // ---------------- Startup careers pages (Ashby, Lever, Greenhouse) ----------------

    data class Board(val kind: String, val token: String)

    /** Accepts careers links or "ashby:name", "lever:name", "greenhouse:name", one per line or comma separated. */
    fun parseBoards(s: String): List<Board> = s.split(Regex("[,\\s]+")).mapNotNull { raw ->
        val t = raw.trim().trimEnd('/')
        fun after(marker: String) = t.substringAfter(marker).substringBefore("/").substringBefore("?").substringBefore("#")
        when {
            t.isBlank() -> null
            t.startsWith("ashby:", true) -> Board("ashby", t.substringAfter(":"))
            t.startsWith("lever:", true) -> Board("lever", t.substringAfter(":"))
            t.startsWith("greenhouse:", true) -> Board("greenhouse", t.substringAfter(":"))
            t.contains("ashbyhq.com/") -> Board("ashby", after("ashbyhq.com/"))
            t.contains("lever.co/") -> Board("lever", after("lever.co/"))
            t.contains("greenhouse.io/") && t.contains("for=") -> Board("greenhouse", t.substringAfter("for=").substringBefore("&"))
            t.contains("greenhouse.io/") -> Board("greenhouse", after("greenhouse.io/"))
            else -> null
        }
    }.filter { it.token.isNotBlank() && it.token != "embed" }.distinct()

    suspend fun board(b: Board, onError: (String) -> Unit): List<Job> {
        val name = cap(b.token)
        val src = "$name careers"
        return try {
            when (b.kind) {
                "ashby" -> {
                    val root = parse(Http.get("https://api.ashbyhq.com/posting-api/job-board/${enc(b.token)}")) as? JsonObject ?: return emptyList()
                    root.a("jobs").objects().filter { it.s("isListed") != "false" }.mapNotNull { o ->
                        val id = o.s("id").ifBlank { return@mapNotNull null }
                        val remote = o.s("isRemote") == "true" || o.s("workplaceType").equals("remote", true)
                        val locs = (listOf(o.s("location")) + o.a("secondaryLocations").objects().map { it.s("location") })
                            .filter { it.isNotBlank() }.distinct()
                        val loc = locs.take(4).joinToString(", ").let { if (remote && !it.contains("remote", true)) "$it, Remote".trim(',', ' ') else it }
                        Job(
                            key = "ab:${b.token}:$id", source = src, title = o.s("title").trim(), company = name, location = loc,
                            url = o.s("jobUrl"), applyUrl = o.s("applyUrl"), postedAt = o.s("publishedAt"),
                            description = o.s("descriptionPlain").ifBlank { html(o.s("descriptionHtml")) },
                            criteria = listOf(o.s("employmentType"), o.s("department")).filter { it.isNotBlank() }.joinToString(" · "),
                            detailsFetched = true,
                        )
                    }
                }
                "lever" -> {
                    val arr = parse(Http.get("https://api.lever.co/v0/postings/${enc(b.token)}?mode=json")) as? JsonArray ?: return emptyList()
                    arr.objects().mapNotNull { o ->
                        val id = o.s("id").ifBlank { return@mapNotNull null }
                        val cat = o.o("categories")
                        val all = cat?.a("allLocations").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                        val remote = o.s("workplaceType").equals("remote", true)
                        val loc = (all.ifEmpty { listOf(cat?.s("location").orEmpty()) }.filter { it.isNotBlank() } + if (remote) listOf("Remote") else emptyList())
                            .distinct().take(4).joinToString(", ")
                        val lists = o.a("lists").objects().joinToString("\n\n") { it.s("text") + "\n" + html(it.s("content")) }
                        Job(
                            key = "lv:${b.token}:$id", source = src, title = o.s("text").trim(), company = name, location = loc,
                            url = o.s("hostedUrl"), applyUrl = o.s("applyUrl"),
                            postedAt = o.s("createdAt").toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
                            description = listOf(o.s("descriptionPlain"), lists, o.s("additionalPlain")).filter { it.isNotBlank() }.joinToString("\n\n"),
                            criteria = listOf(cat?.s("commitment").orEmpty(), cat?.s("team").orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                            detailsFetched = true,
                        )
                    }
                }
                "greenhouse" -> {
                    val root = parse(Http.get("https://boards-api.greenhouse.io/v1/boards/${enc(b.token)}/jobs?content=true")) as? JsonObject ?: return emptyList()
                    root.a("jobs").objects().mapNotNull { o ->
                        val id = o.s("id").ifBlank { return@mapNotNull null }
                        // Greenhouse returns the description HTML escaped once more.
                        val content = Jsoup.parse(o.s("content")).text()
                        Job(
                            key = "gh:${b.token}:$id", source = src, title = o.s("title").trim(), company = name,
                            location = o.o("location")?.s("name").orEmpty(),
                            url = o.s("absolute_url"), applyUrl = o.s("absolute_url"), postedAt = o.s("updated_at"),
                            description = html(content), detailsFetched = true,
                        )
                    }
                }
                else -> emptyList()
            }
        } catch (e: HttpException) {
            onError(if (e.code == 404) "$name: no ${b.kind} careers page with that name" else "$name careers page failed (${e.code})")
            emptyList()
        } catch (e: Exception) {
            onError("$name careers page: ${e.message}"); emptyList()
        }
    }

    /** Readable site name for jobs saved from a web page. */
    fun siteName(url: String): String {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.").lowercase()
        return when {
            host.endsWith("wellfound.com") || host.endsWith("angel.co") -> "Wellfound"
            host == "news.ycombinator.com" -> "Hacker News"
            host.endsWith("workatastartup.com") -> "Work at a Startup"
            host.endsWith("ycombinator.com") -> "Y Combinator"
            host.endsWith("instahyre.com") -> "Instahyre"
            host.endsWith("cutshort.io") -> "Cutshort"
            host.endsWith("naukri.com") -> "Naukri"
            host.endsWith("indeed.com") || host.contains("indeed.") -> "Indeed"
            host.isBlank() -> "Web"
            else -> host
        }
    }
}
