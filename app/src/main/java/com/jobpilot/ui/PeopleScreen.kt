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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.jobpilot.data.Contact
import com.jobpilot.data.ContactStatus
import com.jobpilot.data.JobStatus
import java.util.concurrent.TimeUnit

private enum class PeopleFilter(val label: String) { TO_DO("To contact"), FOLLOW("Follow-up due"), WAITING("Contacted"), WON("Replied / referred"), ALL("All") }

@Composable
fun PeopleScreen(vm: AppViewModel, data: AppData) {
    var filter by rememberSaveable { mutableStateOf(PeopleFilter.TO_DO) }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var linking by remember { mutableStateOf<Contact?>(null) }
    val now = System.currentTimeMillis()
    val followMs = TimeUnit.DAYS.toMillis(data.profile.followUpDays.toLong())
    fun due(c: Contact) = c.status == ContactStatus.CONTACTED && c.contactedAt > 0 && now - c.contactedAt > followMs
    val jobs = data.jobs.associateBy { it.key }

    val list = data.contacts.filter { c ->
        when (filter) {
            PeopleFilter.TO_DO -> c.status == ContactStatus.TO_CONTACT
            PeopleFilter.FOLLOW -> due(c)
            PeopleFilter.WAITING -> c.status == ContactStatus.CONTACTED
            PeopleFilter.WON -> c.status == ContactStatus.REPLIED || c.status == ContactStatus.REFERRED
            PeopleFilter.ALL -> true
        }
    }.sortedByDescending { jobs[it.jobKey]?.matchScore ?: 0 }

    val todayCount = data.contacts.count { it.contactedAt > 0 && now - it.contactedAt < TimeUnit.DAYS.toMillis(1) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Referral outreach") })
        Text(
            "Contacted today: $todayCount. Tap \"Copy & open\": the note is copied and the profile opens in LinkedIn — " +
                "tap Connect → Add a note → paste → Send.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        ChipRow(PeopleFilter.entries, filter, { it.label }) { filter = it }
        if (list.isEmpty()) {
            EmptyState(
                "No one here yet",
                "Open a job → \"Find referrers\" → sign in to LinkedIn → \"Grab people\". Recruiters shown on job pages are added automatically. You can also share a profile from the LinkedIn app to JobPilot."
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { c ->
                val job = jobs[c.jobKey]
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = FontWeight.SemiBold)
                                if (c.headline.isNotBlank()) Text(c.headline, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { editing = c }) { Icon(Icons.Default.Edit, "Edit note") }
                            IconButton(onClick = { vm.deleteContact(c.id) }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            Tag(c.kind.label)
                            Tag(c.status.label, if (c.status == ContactStatus.REFERRED) Good else MaterialTheme.colorScheme.primary)
                            if (due(c)) Tag("follow up", Mid)
                        }
                        Text(
                            if (job != null) "For: ${job.title} @ ${job.company}" else "Not linked to a job" + if (c.company.isNotBlank()) " (${c.company})" else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable { linking = c }
                        )
                        if (c.message.isNotBlank()) {
                            Text(
                                c.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (due(c)) {
                                Button(onClick = { vm.contactPerson(c, followUp = true) }) { Text("Copy follow-up & open") }
                            } else {
                                Button(onClick = { vm.contactPerson(c) }) { Text(if (c.status == ContactStatus.TO_CONTACT) "Copy & open" else "Open again") }
                            }
                            if (job == null) OutlinedButton(onClick = { linking = c }) { Text("Link job") }
                        }
                        if (c.status != ContactStatus.TO_CONTACT) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(ContactStatus.REPLIED, ContactStatus.REFERRED, ContactStatus.NO_RESPONSE, ContactStatus.DECLINED).forEach { s ->
                                    FilterChip(selected = c.status == s, onClick = {
                                        vm.setContactStatus(c.id, s)
                                        if (s == ContactStatus.REFERRED && c.jobKey.isNotBlank()) vm.setJobStatus(c.jobKey, JobStatus.REFERRAL_ASKED)
                                    }, label = { Text(s.label) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { c ->
        var text by remember(c.id) { mutableStateOf(c.message) }
        val limit = data.profile.referralCharLimit
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Note to ${c.name}") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 5, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${text.length}/$limit", style = MaterialTheme.typography.labelSmall,
                            color = if (text.length > limit) Low else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.regenerateMessage(c); editing = null }) {
                            Icon(Icons.Default.Refresh, null); Text("Regenerate")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setContactMessage(c.id, text); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }

    linking?.let { c ->
        val options = data.jobs.filter { it.status != JobStatus.IGNORED }.sortedByDescending { it.matchScore }.take(60)
        AlertDialog(
            onDismissRequest = { linking = null },
            title = { Text("Which job is ${c.name} for?") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options, key = { it.key }) { j ->
                        Text(
                            "${j.title} @ ${j.company}",
                            modifier = Modifier.fillMaxWidth().clickable { vm.linkContactToJob(c, j); linking = null }.padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { linking = null }) { Text("Close") } }
        )
    }
}
