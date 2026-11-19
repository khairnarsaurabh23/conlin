package com.jobpilot.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jobpilot.data.Job

/** The groups jobs can be filtered by. Tags are set by Classify when a job is scored. */
enum class JobCat(val label: String) {
    ALL("All"), REMOTE("Remote"), ABROAD("Abroad"), VISA("Visa"), STARTUP("Startups"), HOME("Home");

    fun test(j: Job): Boolean = when (this) {
        ALL -> true
        REMOTE -> "remote" in j.tags
        ABROAD -> "abroad" in j.tags || "remote-elsewhere" in j.tags
        VISA -> "visa" in j.tags
        STARTUP -> "startup" in j.tags
        HOME -> "home" in j.tags
    }

    fun title(home: String) = if (this == HOME) home.ifBlank { "India" } else label
}

fun tagLabel(tag: String, home: String): String = when (tag) {
    "remote" -> "Remote"
    "remote-elsewhere" -> "Remote, other region"
    "abroad" -> "Abroad"
    "home" -> home.ifBlank { "India" }
    "visa" -> "Visa"
    "startup" -> "Startup"
    else -> tag
}

/** Category pills with counts; categories with nothing in them are hidden. */
@Composable
fun CatFilter(jobs: List<Job>, selected: JobCat, home: String, modifier: Modifier = Modifier, onSelect: (JobCat) -> Unit) {
    val shown = JobCat.entries.filter { it == JobCat.ALL || it == selected || jobs.any(it::test) }
    if (shown.size <= 1) return
    Seg(shown, selected, { c -> c.title(home) + if (c == JobCat.ALL) "" else " " + jobs.count(c::test) }, modifier, onSelect)
}

@Composable
fun JobTags(job: Job, home: String, modifier: Modifier = Modifier) {
    if (job.tags.isEmpty()) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        job.tags.forEach { t ->
            Tag(
                tagLabel(t, home),
                when (t) {
                    "remote", "visa" -> ok
                    "remote-elsewhere" -> warn
                    "startup" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
