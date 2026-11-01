package com.jobpilot.net

import com.jobpilot.data.Experience
import com.jobpilot.data.Job
import com.jobpilot.data.SearchPref
import com.jobpilot.data.WorkType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

/**
 * JSearch (RapidAPI) aggregates Google for Jobs: LinkedIn, Indeed, Glassdoor, Naukri, company sites…
 * Free key: https://rapidapi.com/letscrape-6bRBa3QguO5/api/jsearch
 */
object JSearch {
    private const val HOST = "jsearch.p.rapidapi.com"
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(k: String): String {
        val e: JsonElement = this[k] ?: return ""
        if (e is JsonNull) return ""
        return (e as? JsonPrimitive)?.content.orEmpty()
    }

    suspend fun search(p: SearchPref, apiKey: String, onError: (String) -> Unit = {}): List<Job> {
        if (apiKey.isBlank()) return emptyList()
        val query = if (p.location.isBlank()) p.keywords else "${p.keywords} in ${p.location}"
        val pages = ((p.maxResults + 9) / 10).coerceIn(1, 5)
        val url = buildString {
            append("https://$HOST/search?query=").append(URLEncoder.encode(query, "UTF-8"))
            append("&page=1&num_pages=").append(pages)
            append("&date_posted=").append(p.postedWithin.jsearchCode)
            if (p.workType == WorkType.REMOTE) append("&remote_jobs_only=true")
            when (p.experience) {
                Experience.INTERNSHIP, Experience.ENTRY -> append("&job_requirements=under_3_years_experience,no_experience")
                Experience.MID_SENIOR, Experience.DIRECTOR -> append("&job_requirements=more_than_3_years_experience")
                else -> {}
            }
        }
        val body = try {
            Http.get(url, mapOf("X-RapidAPI-Key" to apiKey, "X-RapidAPI-Host" to HOST))
        } catch (e: HttpException) {
            onError(
                when (e.code) {
                    401, 403 -> "JSearch rejected the API key — check it in Settings."
                    429 -> "JSearch monthly quota reached."
                    else -> "JSearch failed (${e.code})."
                }
            ); return emptyList()
        } catch (e: Exception) {
            onError("JSearch error: ${e.message}"); return emptyList()
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val data = (root["data"] as? JsonArray) ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("job_id").ifBlank { return@mapNotNull null }
            val city = o.str("job_city")
            val country = o.str("job_country")
            val loc = o.str("job_location").ifBlank { listOf(city, country).filter { it.isNotBlank() }.joinToString(", ") }
            val remote = o.str("job_is_remote") == "true"
            val publisher = o.str("job_publisher")
            val apply = o.str("job_apply_link")
            Job(
                key = "js:$id",
                source = if (publisher.isNotBlank()) "JSearch · $publisher" else "JSearch",
                title = o.str("job_title"),
                company = o.str("employer_name"),
                location = if (remote) "$loc (Remote)".trim() else loc,
                url = apply,
                applyUrl = apply,
                postedAt = o.str("job_posted_at_datetime_utc"),
                description = o.str("job_description"),
                criteria = listOf(o.str("job_employment_type"))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                searchId = p.id,
                detailsFetched = true,
            )
        }.take(p.maxResults)
    }
}
