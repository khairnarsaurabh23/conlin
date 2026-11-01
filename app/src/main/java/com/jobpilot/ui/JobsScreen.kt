@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.jobpilot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobpilot.data.AppData
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus

private enum class JobFilter(val label: String) {
    ACTIVE("Active"), NEW("New"), STRONG("70%+"), SAVED("Saved"), APPLIED("Applied / asked"), ALL("All"), IGNORED("Ignored")
}

private enum class JobSort(val label: String) { MATCH("Best match"), NEWEST("Newest") }

@Composable
fun JobsScreen(vm: AppViewModel, data: AppData) {
    var filter by rememberSaveable { mutableStateOf(JobFilter.ACTIVE) }
    var sort by rememberSaveable { mutableStateOf(JobSort.MATCH) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchFilter by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    val list = data.jobs.asSequence()
        .filter { j ->
            when (filter) {
                JobFilter.ACTIVE -> j.status != JobStatus.IGNORED && j.status != JobStatus.REJECTED
                JobFilter.NEW -> j.status == JobStatus.NEW
                JobFilter.STRONG -> j.matchScore >= 70 && j.status != JobStatus.IGNORED
                JobFilter.SAVED -> j.status == JobStatus.SAVED
                JobFilter.APPLIED -> j.status == JobStatus.APPLIED || j.status == JobStatus.REFERRAL_ASKED || j.status == JobStatus.INTERVIEW
                JobFilter.ALL -> true
                JobFilter.IGNORED -> j.status == JobStatus.IGNORED || j.status == JobStatus.REJECTED
            }
        }
        .filter { searchFilter.isBlank() || it.searchId == searchFilter }
        .filter { query.isBlank() || (it.title + " " + it.company + " " + it.location).contains(query, ignoreCase = true) }
        .let { s -> if (sort == JobSort.MATCH) s.sortedByDescending { it.matchScore } else s.sortedByDescending { it.foundAt } }
        .toList()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Jobs (${list.size})") },
            actions = {
                TextButton(onClick = { sort = if (sort == JobSort.MATCH) JobSort.NEWEST else JobSort.MATCH }) { Text(sort.label) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Find hiring posts with emails") }, onClick = { menu = false; vm.findHiringPosts(null) })
                    DropdownMenuItem(text = { Text("Recalculate match scores") }, onClick = { menu = false; vm.rescoreAll() })
                    DropdownMenuItem(text = { Text("Delete ignored / rejected") }, onClick = { menu = false; vm.clearIgnored() })
                }
            }
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Filter by title, company, location") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        ChipRow(JobFilter.entries, filter, { it.label }) { filter = it }
        if (data.searches.size > 1) {
            val opts = listOf("") + data.searches.map { it.id }
            ChipRow(opts, searchFilter, { id -> if (id.isEmpty()) "All searches" else data.searches.first { it.id == id }.label() }) { searchFilter = it }
        }
        if (list.isEmpty()) {
            EmptyState("No jobs here", if (data.searches.isEmpty()) "Add a job search in Settings, then tap \"Check LinkedIn\" on Home." else "Try another filter or run a search from Home.")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.key }) { j -> JobRow(j) { vm.openJob(j.key) } }
        }
    }
}

@Composable
private fun JobRow(j: Job, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(j.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(j.company, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    listOf(j.location, j.postedAt.take(10), j.source).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(j.status.label)
                    if (j.emailsFound.isNotEmpty()) Tag("✉ ${j.emailsFound.size}", Good)
                    if (!j.detailsFetched) Tag("summary only", MaterialTheme.colorScheme.outline)
                }
            }
            ScorePill(j.matchScore)
        }
    }
}

@Composable
fun JobDetailScreen(vm: AppViewModel, data: AppData, job: Job, snack: SnackbarHostState) {
    val people = data.contacts.filter { it.jobKey == job.key }
    var notes by remember(job.key) { mutableStateOf(job.notes) }
    val busyMsg by vm.busy.collectAsState()
    val busy = busyMsg != null

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(job.company.ifBlank { "Job" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.openJob(null) }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.deleteJob(job.key) }) { Icon(Icons.Default.Delete, "Delete") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                ScorePill(job.matchScore)
            }
            Text("${job.company} · ${job.location}", style = MaterialTheme.typography.bodyMedium)
            Text(
                listOf(job.source, job.postedAt.take(10), job.criteria).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.openUrl(job.url) }) { Text("Open / Apply") }
                OutlinedButton(onClick = { vm.findReferrers(job) }) { Text("Find referrers") }
                if (!job.detailsFetched && job.key.startsWith("li:")) {
                    OutlinedButton(onClick = { vm.fetchDetails(job.key) }, enabled = !busy) { Text("Load full details") }
                }
                OutlinedButton(onClick = { vm.findHiringPosts(job) }) { Text("Hiring posts") }
            }

            SectionTitle("Status")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                JobStatus.entries.forEach { s ->
                    FilterChip(selected = job.status == s, onClick = { vm.setJobStatus(job.key, s) }, label = { Text(s.label) })
                }
            }

            if (job.matchedSkills.isNotEmpty() || job.missingSkills.isNotEmpty()) {
                SectionTitle("Skill match")
                if (job.matchedSkills.isNotEmpty()) Text("✓ " + job.matchedSkills.joinToString(", "), color = Good)
                if (job.missingSkills.isNotEmpty() && job.detailsFetched) Text("✗ Not mentioned: " + job.missingSkills.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (job.emailsFound.isNotEmpty()) {
                SectionTitle("Emails in this posting")
                Text(job.emailsFound.joinToString("\n"))
                TextButton(onClick = { vm.queueEmailsFromJob(job) }) { Text("Add to email queue") }
            }

            SectionTitle("People for this job (${people.size})")
            if (people.isEmpty()) {
                Text("None yet. Tap \"Find referrers\", sign in, then \"Grab people\".", style = MaterialTheme.typography.bodySmall)
            }
            people.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        Text("${c.kind.label} · ${c.status.label}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.contactPerson(c) }) { Text("Reach out") }
                }
            }

            SectionTitle("Notes")
            OutlinedTextField(
                value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                placeholder = { Text("Interview dates, who referred you…") }
            )
            if (notes != job.notes) TextButton(onClick = { vm.setJobNotes(job.key, notes) }) { Text("Save notes") }

            SectionTitle("Description")
            SelectionContainer {
                Text(
                    job.description.ifBlank { "Not loaded yet." },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
            }
        }
    }
}
