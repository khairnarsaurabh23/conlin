package com.jobpilot.core

import com.jobpilot.data.AppData
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Lesson
import com.jobpilot.data.LessonStatus
import com.jobpilot.data.Store
import com.jobpilot.net.Http
import com.jobpilot.net.LinkedInJobs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.Calendar

/** One topic a day to learn, and LinkedIn posts drafted from what you learned. */
object Learn {
    private const val DAY = 86_400_000L

    data class Concept(val title: String, val tags: List<String>)

    /** System design and architecture topics, worked through over time. */
    val CONCEPTS = listOf(
        Concept("Consistent hashing and how it spreads load across nodes", listOf("distributed", "redis", "cache", "sharding")),
        Concept("Database sharding: choosing a shard key and resharding", listOf("sql", "postgresql", "mysql", "mongodb", "scale")),
        Concept("Read replicas, replication lag and read-your-writes", listOf("sql", "postgresql", "mysql", "database")),
        Concept("CAP and PACELC in real systems", listOf("distributed", "database")),
        Concept("The outbox pattern for reliable events", listOf("kafka", "microservices", "rabbitmq", "events")),
        Concept("Sagas: transactions across microservices", listOf("microservices", "payments", "spring boot")),
        Concept("CQRS and when separate read models pay off", listOf("microservices", "architecture")),
        Concept("Event sourcing: storing changes instead of state", listOf("kafka", "architecture")),
        Concept("Kafka partitions, consumer groups and ordering", listOf("kafka", "events", "streaming")),
        Concept("Exactly-once delivery and idempotency keys", listOf("kafka", "payments", "rest", "api")),
        Concept("Rate limiting: token bucket, leaky bucket, sliding window", listOf("api", "redis", "rest", "gateway")),
        Concept("Caching strategies: cache-aside, write-through, write-behind", listOf("redis", "cache", "performance")),
        Concept("Cache stampedes and how to prevent them", listOf("redis", "cache", "performance")),
        Concept("Circuit breakers, retries with backoff and jitter", listOf("microservices", "resilience", "spring boot")),
        Concept("Bulkheads and timeouts: stopping one slow dependency taking everything down", listOf("microservices", "resilience")),
        Concept("Load balancing algorithms and health checks", listOf("aws", "kubernetes", "nginx")),
        Concept("API gateway vs service mesh", listOf("kubernetes", "microservices", "gateway")),
        Concept("Kubernetes deployments: rolling, blue-green and canary", listOf("kubernetes", "docker", "ci/cd")),
        Concept("Horizontal pod autoscaling and what to scale on", listOf("kubernetes", "aws")),
        Concept("Database indexes: B-trees, composite indexes and when they hurt", listOf("sql", "postgresql", "mysql")),
        Concept("Transaction isolation levels and the anomalies they allow", listOf("sql", "postgresql", "mysql", "payments")),
        Concept("Optimistic vs pessimistic locking", listOf("sql", "spring boot", "hibernate")),
        Concept("Distributed locks and why they are hard", listOf("redis", "distributed")),
        Concept("Leader election and consensus with Raft", listOf("distributed", "kubernetes")),
        Concept("Message queues vs event streams", listOf("kafka", "rabbitmq", "sqs")),
        Concept("Backpressure in streaming systems", listOf("kafka", "streaming", "coroutines", "rxjava")),
        Concept("Designing a URL shortener", listOf("system design")),
        Concept("Designing a notification system", listOf("system design", "android", "firebase")),
        Concept("Designing a payment system: ledgers and double-entry", listOf("payments", "fintech", "upi")),
        Concept("Designing a news feed: fan-out on write vs read", listOf("system design", "redis")),
        Concept("Designing a chat system: delivery, ordering and presence", listOf("websocket", "system design")),
        Concept("Search: inverted indexes and relevance", listOf("elasticsearch", "search")),
        Concept("Vector databases and retrieval-augmented generation", listOf("ai", "llm", "python")),
        Concept("Serving LLMs: batching, KV cache and latency", listOf("ai", "llm", "gpu")),
        Concept("Observability: logs, metrics, traces and SLOs", listOf("monitoring", "kubernetes", "aws")),
        Concept("Zero-downtime database migrations", listOf("sql", "postgresql", "ci/cd")),
        Concept("Multi-region architecture and failover", listOf("aws", "azure", "gcp")),
        Concept("CDNs and edge caching", listOf("performance", "cloudflare", "web")),
        Concept("OAuth 2.0 and OIDC flows explained", listOf("security", "api", "rest")),
        Concept("Offline-first mobile apps and sync conflicts", listOf("android", "kotlin", "room", "ios")),
        Concept("Clean Architecture and MVVM on Android", listOf("android", "kotlin", "mvvm", "clean architecture")),
        Concept("Pagination: offset vs cursor", listOf("api", "rest", "graphql", "sql")),
        Concept("Bloom filters and other probabilistic structures", listOf("redis", "performance", "distributed")),
        Concept("Write-ahead logs and how databases survive crashes", listOf("database", "postgresql", "kafka")),
    )

    val DEFAULT_FEEDS = listOf(
        "https://blog.bytebytego.com/feed",
        "https://netflixtechblog.com/feed",
        "https://blog.cloudflare.com/rss/",
        "https://aws.amazon.com/blogs/architecture/feed/",
        "https://martinfowler.com/feed.atom",
        "https://blog.pragmaticengineer.com/rss/",
        "https://dropbox.tech/feed",
        "https://slack.engineering/feed/",
        "https://stripe.com/blog/feed.rss",
        "https://feed.infoq.com/",
        "https://engineering.fb.com/feed/",
        "https://github.blog/engineering/feed/",
    )

    private val TECH = Regex(
        "(?i)architect|scal|distributed|database|postgres|mysql|kafka|redis|kubernetes|k8s|latency|outage|postmortem|incident|" +
            "microservice|cache|queue|stream|consensus|replica|shard|index|storage|throughput|llm|inference|vector|gpu|compiler|" +
            "kotlin|android|rust|go\\b|java\\b|api|protocol|network|cdn|edge|security|auth|migration|observability|tracing|design"
    )

    data class Candidate(val title: String, val url: String, val site: String, val text: String, val score: Int)

    /** Skills that keep appearing in the jobs you're looking at, with counts. */
    fun jobTerms(d: AppData): List<Pair<String, Int>> {
        val jobs = d.jobs.filter { it.status != JobStatus.IGNORED }.takeLast(150)
        return Tailor.DICT.map { t ->
            val r = Regex("(?i)(?<![a-z])" + Regex.escape(t) + "(?![a-z])")
            t to jobs.count { r.containsMatchIn(it.title + " " + it.description) }
        }.filter { it.second >= 2 }.sortedByDescending { it.second }.take(15)
    }

    private fun interests(d: AppData): List<String> =
        (jobTerms(d).map { it.first } + d.profile.learnInterests.split(",") + d.profile.skills.split(","))
            .map { it.trim().lowercase() }.filter { it.length > 1 }.distinct()

    fun feeds(d: AppData): List<String> =
        d.profile.learnFeeds.lines().map { it.trim() }.filter { it.startsWith("http") }.ifEmpty { DEFAULT_FEEDS }

    private fun seen(d: AppData) = d.lessons.map { it.sourceUrl }.filter { it.isNotBlank() }.toSet()

    /** Recent articles from the feeds and Hacker News, best match first. */
    suspend fun candidates(d: AppData): List<Candidate> = coroutineScope {
        val want = interests(d)
        val since = System.currentTimeMillis() - 10 * DAY
        val seen = seen(d)
        fun score(title: String, text: String): Int {
            val t = (title + " " + text.take(1500)).lowercase()
            var s = if (TECH.containsMatchIn(t)) 10 else 0
            want.forEach { w -> if (t.contains(w)) s += if (title.lowercase().contains(w)) 6 else 2 }
            return s
        }
        val fromFeeds = feeds(d).map { url ->
            async {
                runCatching {
                    val doc = Jsoup.parse(Http.get(url), "", Parser.xmlParser())
                    val site = doc.selectFirst("channel > title, feed > title")?.text()?.trim().orEmpty().ifBlank { url.substringAfter("://").substringBefore("/") }
                    doc.select("item, entry").take(15).mapNotNull { e ->
                        val title = e.selectFirst("title")?.text()?.trim().orEmpty()
                        val link = e.selectFirst("link")?.let { l -> l.attr("href").ifBlank { l.text() } }?.trim().orEmpty()
                        if (title.isBlank() || link.isBlank() || link in seen) return@mapNotNull null
                        val date = e.selectFirst("pubDate, published, updated")?.text().orEmpty()
                        val at = parseDate(date)
                        if (at in 1 until since) return@mapNotNull null
                        val body = LinkedInJobs.htmlToText(
                            e.selectFirst("content|encoded, encoded, content, description, summary")?.text().orEmpty()
                        )
                        Candidate(title, link, site, body, score(title, body))
                    }
                }.getOrDefault(emptyList())
            }
        }
        val fromHn = async {
            runCatching {
                val root = kotlinx.serialization.json.Json.parseToJsonElement(
                    Http.get("https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=40")
                ).jsonObject
                (root["hits"] as? JsonArray).orEmpty().mapNotNull { h ->
                    val o = h as? JsonObject ?: return@mapNotNull null
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                    val url = s("url")
                    val title = s("title")
                    if (url.isBlank() || title.isBlank() || url in seen) return@mapNotNull null
                    val pts = s("points").toIntOrNull() ?: 0
                    val sc = score(title, "")
                    if (sc < 10) null else Candidate(title, url, "Hacker News", "", sc + minOf(pts / 100, 6))
                }
            }.getOrDefault(emptyList())
        }
        (fromFeeds.awaitAll().flatten() + fromHn.await()).sortedByDescending { it.score }
    }

    private fun parseDate(s: String): Long {
        if (s.isBlank()) return 0
        val formats = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd")
        for (f in formats) runCatching { return java.text.SimpleDateFormat(f, java.util.Locale.US).parse(s.trim())!!.time }
        return 0
    }

    /** The next concept: matches your jobs first, never one you've already done. */
    fun nextConcept(d: AppData, skip: Set<String> = emptySet()): Pair<Concept, String>? {
        val done = d.lessons.map { it.title.lowercase() }.toSet() + skip.map { it.lowercase() }
        val terms = jobTerms(d)
        val want = interests(d)
        return CONCEPTS.filter { it.title.lowercase() !in done }.map { c ->
            val hits = terms.filter { (t, _) -> c.tags.any { it.equals(t, true) } }
            val score = hits.sumOf { it.second } * 3 + c.tags.count { it in want }
            Triple(c, score, hits.firstOrNull())
        }.maxByOrNull { it.second }?.let { (c, _, hit) ->
            c to if (hit != null) "${hit.first} is in ${hit.second} of your saved jobs" else "Common in system design interviews"
        }
    }

    /** Alternates: concept days and news days, so you get both. */
    fun wantNews(d: AppData): Boolean {
        val last = d.lessons.filter { it.status != LessonStatus.SKIPPED }.maxByOrNull { it.createdAt } ?: return false
        return last.kind == "concept"
    }

    /** Builds a lesson. With an AI key it explains the topic in depth with a quiz and a diagram. */
    suspend fun make(d: AppData, news: Boolean, skipTitles: Set<String> = emptySet()): Lesson {
        val now = System.currentTimeMillis()
        if (news) {
            val c = candidates(d).firstOrNull { it.title !in skipTitles }
            if (c != null) {
                val text = c.text.takeIf { it.length > 1500 } ?: runCatching {
                    LinkedInJobs.htmlToText(Http.get(c.url)).take(12000)
                }.getOrDefault(c.text)
                val base = Lesson(
                    id = Store.newId(), createdAt = now, kind = "news", title = c.title, sourceTitle = c.title,
                    sourceUrl = c.url, sourceSite = c.site, summary = text.take(600),
                    why = "From ${c.site}" + interests(d).firstOrNull { c.title.lowercase().contains(it) }?.let { ", about $it" }.orEmpty(),
                )
                return if (d.profile.aiConfigured) runCatching { AiTasks.lesson(d, base, text) }.getOrDefault(base) else base
            }
        }
        val (concept, why) = nextConcept(d, skipTitles) ?: (CONCEPTS.random() to "")
        val base = Lesson(id = Store.newId(), createdAt = now, kind = "concept", title = concept.title, why = why)
        return if (d.profile.aiConfigured) runCatching { AiTasks.lesson(d, base, "") }.getOrDefault(base) else base
    }

    fun today(d: AppData, now: Long): Lesson? {
        val start = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return d.lessons.lastOrNull { it.createdAt >= start && it.status != LessonStatus.SKIPPED }
    }

    fun learnAt(d: AppData, now: Long): Long {
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(d.profile.learnTime.trim())
        val h = m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..23 } ?: 9
        val min = m?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun due(d: AppData, now: Long): Boolean =
        d.profile.learnEnabled && now >= learnAt(d, now) && d.pace.lastLearnDay != Pacer.day(now)

    /** Spaced review: 3, 7 and 30 days after learning. */
    private val REVIEW = listOf(3L, 7L, 30L)

    fun nextReview(step: Int, from: Long): Long = REVIEW.getOrNull(step)?.let { from + it * DAY } ?: 0L

    fun reviewsDue(d: AppData, now: Long) = d.lessons.filter { it.nextReviewAt in 1..now && it.quiz.isNotEmpty() }

    /** Whether a post fits this week's pace. */
    fun postSlot(d: AppData, now: Long): String {
        val per = d.profile.postsPerWeek.coerceIn(1, 7)
        val last = d.lessons.maxOfOrNull { it.postedAt } ?: 0L
        val gap = 7 * DAY / per
        val wait = last + gap - now
        val week = d.lessons.count { it.postedAt > now - 7 * DAY }
        return when {
            week >= per -> "You've posted $week this week; your target is $per. Save the rest for next week."
            last == 0L || wait <= 0 -> "Good day to post one."
            else -> "Next post in about ${(wait + DAY - 1) / DAY} day(s), to keep $per a week."
        }
    }

    // ---------------- Keeping posts human ----------------

    private val CLICHES = listOf(
        "game-changer", "game changer", "delve", "in today's fast-paced", "fast-paced world", "unlock", "unleash", "leverage",
        "elevate", "revolutionize", "revolutionise", "cutting-edge", "seamless", "embark", "journey", "tapestry", "landscape",
        "dive deep", "deep dive", "let that sink in", "here's the thing", "buckle up", "excited to share", "thrilled to",
        "i'm humbled", "synergy", "paradigm", "robust", "harness", "navigate the", "in conclusion", "furthermore", "moreover",
        "it's important to note", "the world of", "ever-evolving", "testament to", "crucial", "pivotal",
    )

    /** Things that make a post read as machine-written. */
    fun humanCheck(text: String): List<String> {
        val t = text.lowercase()
        val out = mutableListOf<String>()
        CLICHES.filter { t.contains(it) }.take(5).forEach { out += "Sounds generic: \"$it\"" }
        val emoji = text.count { Character.getType(it) == Character.SURROGATE.toInt() } / 2
        if (emoji > 3) out += "$emoji emoji; one or two at most"
        val tags = Regex("#\\w+").findAll(text).count()
        if (tags > 3) out += "$tags hashtags; three or fewer"
        val dashes = text.count { it == '—' }
        if (dashes > 2) out += "$dashes long dashes; people rarely type these"
        if (text.length > 2200) out += "${text.length} characters; under 1,300 reads better"
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        if (firstLine.length > 140) out += "The first line is long; only about 140 characters show before \"see more\""
        if (!Regex("(?i)\\b(i|i'm|i've|my|me|we)\\b").containsMatchIn(text)) out += "No \"I\" anywhere; add what you did or think"
        return out
    }
}
