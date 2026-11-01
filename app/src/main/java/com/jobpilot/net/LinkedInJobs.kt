package com.jobpilot.net

import com.jobpilot.data.Experience
import com.jobpilot.data.Job
import com.jobpilot.data.SearchPref
import com.jobpilot.data.WorkType
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.random.Random

/** Recruiter / hiring-team member shown on a public job page. */
data class Poster(val name: String, val headline: String, val profileUrl: String)

data class JobDetails(
    val description: String,
    val criteria: String,
    val poster: Poster?,
)

/**
 * Reads LinkedIn's PUBLIC (logged-out) job listings — the same pages anyone sees at
 * linkedin.com/jobs without signing in. No account or cookies are used, so your
 * LinkedIn account is never involved. Requests are spaced out to stay polite.
 */
object LinkedInJobs {
    private const val BASE = "https://www.linkedin.com/jobs-guest/jobs/api"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun searchUrl(p: SearchPref, start: Int): String = buildString {
        append("$BASE/seeMoreJobPostings/search?keywords=").append(enc(p.keywords))
        if (p.location.isNotBlank()) append("&location=").append(enc(p.location))
        append("&f_TPR=").append(p.postedWithin.linkedinCode)
        if (p.experience != Experience.ANY) append("&f_E=").append(p.experience.linkedinCode)
        if (p.workType != WorkType.ANY) append("&f_WT=").append(p.workType.linkedinCode)
        append("&sortBy=DD")
        append("&start=").append(start)
    }

    /** Public URL for a job id that opens in the LinkedIn app or browser. */
    fun jobUrl(id: String) = "https://www.linkedin.com/jobs/view/$id/"

    suspend fun search(p: SearchPref, onError: (String) -> Unit = {}): List<Job> {
        val out = LinkedHashMap<String, Job>()
        var start = 0
        while (out.size < p.maxResults && start < 1000) {
            val html = try {
                Http.get(searchUrl(p, start))
            } catch (e: HttpException) {
                if (e.code == 429) onError("LinkedIn rate-limited the search \"${p.keywords}\"; will retry next run.")
                else if (e.code != 400) onError("LinkedIn search failed (${e.code}) for \"${p.keywords}\".")
                break
            } catch (e: Exception) {
                onError("LinkedIn search error: ${e.message}")
                break
            }
            val cards = parseSearch(html, p.id)
            if (cards.isEmpty()) break
            cards.forEach { out.putIfAbsent(it.key, it) }
            start += cards.size.coerceAtLeast(10)
            delay(1200L + Random.nextLong(800))
        }
        return out.values.take(p.maxResults)
    }

    fun parseSearch(html: String, searchId: String): List<Job> {
        val doc = Jsoup.parse(html)
        return doc.select("li").mapNotNull { li -> parseCard(li, searchId) }
    }

    private fun parseCard(li: Element, searchId: String): Job? {
        val urn = li.selectFirst("[data-entity-urn]")?.attr("data-entity-urn").orEmpty()
        val link = li.selectFirst("a.base-card__full-link, a[href*='/jobs/view/']")?.attr("href").orEmpty()
        val id = urn.substringAfterLast(":").takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
            ?: Regex("(\\d{6,})").findAll(link.substringBefore("?")).lastOrNull()?.value
            ?: return null
        val title = li.selectFirst("h3.base-search-card__title, .base-search-card__title, h3")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val company = li.selectFirst("h4.base-search-card__subtitle, .base-search-card__subtitle, h4")?.text()?.trim().orEmpty()
        val location = li.selectFirst(".job-search-card__location")?.text()?.trim().orEmpty()
        val posted = li.selectFirst("time")?.attr("datetime").orEmpty()
        return Job(
            key = "li:$id",
            source = "LinkedIn",
            title = title,
            company = company,
            location = location,
            url = jobUrl(id),
            postedAt = posted,
            searchId = searchId,
        )
    }

    fun idFromKey(key: String): String = key.removePrefix("li:")

    suspend fun details(jobId: String): JobDetails {
        val html = Http.get("$BASE/jobPosting/$jobId")
        return parseDetails(html)
    }

    fun parseDetails(html: String): JobDetails {
        val doc = Jsoup.parse(html)
        val descEl = doc.selectFirst(".show-more-less-html__markup, .description__text")
        val description = descEl?.let { htmlToText(it.html()) }.orEmpty()

        val criteria = doc.select("li.description__job-criteria-item").joinToString(" · ") { li ->
            val k = li.selectFirst("h3")?.text()?.trim().orEmpty()
            val v = li.selectFirst("span")?.text()?.trim().orEmpty()
            if (k.isNotEmpty()) "$k: $v" else v
        }

        val box = doc.selectFirst(".message-the-recruiter, .hirer-card, [class*=message-the-recruiter]")
        val poster = box?.let { b ->
            val a = b.selectFirst("a[href*='/in/']") ?: return@let null
            val url = a.attr("href").substringBefore("?")
            val name = (b.selectFirst(".base-main-card__title, h3")?.text() ?: a.text()).trim()
            val headline = b.selectFirst(".base-main-card__subtitle, h4")?.text()?.trim().orEmpty()
            if (name.isBlank() || url.isBlank()) null else Poster(name, headline, url)
        }
        return JobDetails(description, criteria, poster)
    }

    fun htmlToText(html: String): String {
        val withBreaks = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|li|div|h\\d)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ")
        return Jsoup.parse(withBreaks).wholeText()
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
