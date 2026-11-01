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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.jobpilot.core.Mailer
import com.jobpilot.data.AppData
import com.jobpilot.data.EmailLead
import com.jobpilot.data.EmailStatus

private enum class EmailFilter(val label: String) { REVIEW("To review"), APPROVED("Approved"), SENT("Sent"), FAILED("Failed"), ALL("All") }

@Composable
fun EmailsScreen(vm: AppViewModel, data: AppData) {
    var filter by rememberSaveable { mutableStateOf(EmailFilter.REVIEW) }
    var editing by remember { mutableStateOf<EmailLead?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val busyMsg by vm.busy.collectAsState()
    val p = data.profile
    val drafts = data.emails.count { it.status == EmailStatus.DRAFT }
    val approved = data.emails.count { it.status == EmailStatus.APPROVED }

    val list = data.emails.filter { e ->
        when (filter) {
            EmailFilter.REVIEW -> e.status == EmailStatus.DRAFT
            EmailFilter.APPROVED -> e.status == EmailStatus.APPROVED
            EmailFilter.SENT -> e.status == EmailStatus.SENT
            EmailFilter.FAILED -> e.status == EmailStatus.FAILED || e.status == EmailStatus.SKIPPED
            EmailFilter.ALL -> true
        }
    }.sortedByDescending { it.createdAt }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Recruiter emails") })
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Emails found in job posts and \"send your resume to…\" posts. Review, approve, then send." +
                    if (p.resumeFileName.isBlank()) " ⚠ No resume attached yet (Settings)." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.approveAllDrafts() }, enabled = drafts > 0) { Text("Approve all ($drafts)") }
                if (p.smtpConfigured) {
                    Button(onClick = { vm.sendApprovedSmtp() }, enabled = approved > 0 && busyMsg == null) { Text("Send approved ($approved)") }
                } else {
                    Button(onClick = { vm.openNextApprovedInGmail() }, enabled = approved > 0) { Text("Open next in Gmail ($approved)") }
                }
            }
            Text(
                if (p.smtpConfigured) "Sends from ${p.smtpUser} · ${Mailer.sentToday()}/${p.dailyEmailCap} sent today"
                else "Sending via the Gmail app (you tap Send). Add a Gmail App Password in Settings to send all at once.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        ChipRow(EmailFilter.entries, filter, { it.label }) { filter = it }
        if (list.isEmpty()) {
            EmptyState(
                "Nothing here",
                "Emails are collected automatically from job descriptions. To find more, open Jobs → ⋮ → \"Find hiring posts\", then tap \"Grab emails\" in the LinkedIn tab."
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { e ->
                val open = e.id in expanded
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.to, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOf(e.name, e.company, e.role).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Tag(e.status.label, if (e.status == EmailStatus.SENT) Good else if (e.status == EmailStatus.FAILED) Low else MaterialTheme.colorScheme.primary)
                            IconButton(onClick = { vm.deleteEmail(e.id) }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                        if (e.context.isNotBlank()) {
                            Text(
                                "“${e.context}”", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (open) 20 else 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp).clickable {
                                    expanded = if (open) expanded - e.id else expanded + e.id
                                }
                            )
                        }
                        Text("Subject: ${e.subject}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        if (e.error.isNotBlank()) Text(e.error, color = Low, style = MaterialTheme.typography.bodySmall)
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { editing = e }) { Text("Review / edit") }
                            when (e.status) {
                                EmailStatus.DRAFT -> TextButton(onClick = { vm.setEmailStatus(e.id, EmailStatus.APPROVED) }) { Text("Approve") }
                                EmailStatus.APPROVED -> TextButton(onClick = { vm.setEmailStatus(e.id, EmailStatus.DRAFT) }) { Text("Unapprove") }
                                EmailStatus.FAILED -> TextButton(onClick = { vm.setEmailStatus(e.id, EmailStatus.APPROVED) }) { Text("Retry") }
                                else -> {}
                            }
                            if (e.status != EmailStatus.SENT) {
                                TextButton(onClick = { vm.openInGmail(e) }) { Text("Open in Gmail") }
                                TextButton(onClick = { vm.setEmailStatus(e.id, EmailStatus.SKIPPED) }) { Text("Skip") }
                            }
                            if (e.sourceUrl.isNotBlank()) TextButton(onClick = { vm.openUrl(e.sourceUrl) }) { Text("Source") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        var to by remember(e.id) { mutableStateOf(e.to) }
        var subject by remember(e.id) { mutableStateOf(e.subject) }
        var body by remember(e.id) { mutableStateOf(e.body) }
        var attach by remember(e.id) { mutableStateOf(e.attachResume) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Review email") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 8, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = attach, onCheckedChange = { attach = it }, enabled = p.resumeFileName.isNotBlank())
                        Text(if (p.resumeFileName.isNotBlank()) "Attach resume" else "No resume uploaded")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveEmail(e.copy(to = to.trim(), subject = subject, body = body, attachResume = attach, status = EmailStatus.APPROVED))
                    editing = null
                }) { Text("Save & approve") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.saveEmail(e.copy(to = to.trim(), subject = subject, body = body, attachResume = attach))
                    editing = null
                }) { Text("Save") }
            }
        )
    }
}
