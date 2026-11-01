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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobpilot.data.AppData
import com.jobpilot.data.ContactStatus
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.JobStatus
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(vm: AppViewModel, data: AppData, busy: Boolean) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val day = TimeUnit.DAYS.toMillis(1)
    val p = data.profile
    val followUps = data.contacts.count {
        it.status == ContactStatus.CONTACTED && it.contactedAt > 0 && now - it.contactedAt > p.followUpDays * day
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("JobPilot") },
            actions = { IconButton(onClick = { vm.showSettings(true) }) { Icon(Icons.Default.Settings, "Settings") } }
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                if (p.name.isBlank()) "Welcome" else "Hi ${p.name.split(" ").first()}",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold
            )

            // Setup checklist
            val steps = listOf(
                "Add your name, headline and skills" to (p.name.isNotBlank() && p.skillList().isNotEmpty()),
                "Attach your resume" to (p.resumeFileName.isNotBlank()),
                "Tell me what jobs you want" to data.searches.isNotEmpty(),
                "Sign in to LinkedIn in the LinkedIn tab (for finding people)" to false,
            )
            if (steps.take(3).any { !it.second }) {
                Card(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clickable { vm.showSettings(true) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Finish setup", fontWeight = FontWeight.SemiBold)
                        steps.forEach { (label, done) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                Icon(
                                    if (done) Icons.Default.CheckCircle else Icons.Default.Info, null,
                                    tint = if (done) Good else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                HSpace()
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text("Tap to open Settings", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            Button(
                onClick = { vm.runSearch() }, enabled = !busy && data.searches.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null); HSpace(6)
                Text("Check LinkedIn for new jobs now")
            }
            data.runs.firstOrNull()?.let { r ->
                Text(
                    "Last check: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.at)) +
                        " · ${r.newJobs} new" + if (p.autoCheckEnabled) " · auto every ${p.autoCheckHours}h" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                r.errors.take(2).forEach {
                    Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = Low)
                }
            }

            SectionTitle("Overview")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${data.jobs.size}", "jobs tracked") { vm.setTab(Tab.JOBS) }
                Stat("${data.jobs.count { now - it.foundAt < day }}", "new today") { vm.setTab(Tab.JOBS) }
                Stat("${data.jobs.count { it.matchScore >= 70 && it.status != JobStatus.IGNORED }}", "strong matches") { vm.setTab(Tab.JOBS) }
                Stat("${data.jobs.count { it.status == JobStatus.APPLIED }}", "applied") { vm.setTab(Tab.JOBS) }
                Stat("${data.contacts.count { it.status == ContactStatus.TO_CONTACT }}", "people to contact") { vm.setTab(Tab.PEOPLE) }
                Stat("${data.contacts.count { it.status == ContactStatus.REPLIED || it.status == ContactStatus.REFERRED }}", "replies / referrals") { vm.setTab(Tab.PEOPLE) }
                Stat("${data.emails.count { it.status == EmailStatus.DRAFT || it.status == EmailStatus.APPROVED }}", "emails to review") { vm.setTab(Tab.EMAILS) }
                Stat("${data.emails.count { it.status == EmailStatus.SENT }}", "emails sent") { vm.setTab(Tab.EMAILS) }
                Stat("$followUps", "follow-ups due") { vm.setTab(Tab.PEOPLE) }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.buildReport() }, modifier = Modifier.weight(1f)) { Text("Detailed report") }
                OutlinedButton(onClick = {
                    vm.exportCsv { f -> shareFile(ctx, f, "text/csv") }
                }, modifier = Modifier.weight(1f)) { Text("Export CSV") }
            }

            val top = data.jobs.filter { it.status == JobStatus.NEW }.sortedByDescending { it.matchScore }.take(5)
            if (top.isNotEmpty()) {
                SectionTitle("Best new matches")
                top.forEach { j ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { vm.openJob(j.key) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(j.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${j.company} · ${j.location}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ScorePill(j.matchScore)
                        }
                    }
                }
            }

            SectionTitle("Tips")
            Text(
                "• Share any LinkedIn profile, job or \"send your CV to…\" post to JobPilot from the LinkedIn app's Share menu.\n" +
                    "• On a job, tap \"Find referrers\" then \"Grab people\" in the LinkedIn tab.\n" +
                    "• Keep outreach personal and modest — around 10–20 invites a day keeps your account in good standing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun Stat(n: String, label: String, onClick: () -> Unit) {
    Card(Modifier.width(104.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(10.dp)) {
            Text(n, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
