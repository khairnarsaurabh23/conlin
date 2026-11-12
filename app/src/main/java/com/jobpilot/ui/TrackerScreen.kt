package com.jobpilot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobpilot.core.Why
import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.EmailLead
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.ReplyKind
import com.jobpilot.data.Stage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Section(val label: String) { JOBS("Jobs"), PEOPLE("People"), EMAILS("Emails") }

private val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())
private fun day(ms: Long) = dayFmt.format(Date(ms))

@Composable
fun TrackerScreen(vm: AppViewModel, data: AppData) {
    var section by rememberSaveable { mutableStateOf(Section.JOBS) }
    Column(Modifier.fillMaxSize()) {
        Header("Tracker")
        Seg(Section.entries, section, {
            when (it) {
                Section.JOBS -> "Jobs ${data.jobs.count { j -> j.status != JobStatus.IGNORED }}"
                Section.PEOPLE -> "People ${data.contacts.count { c -> c.stage != Stage.SKIPPED }}"
                Section.EMAILS -> "Emails ${data.emails.size}"
            }
        }) { section = it }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (section) {
            Section.JOBS -> JobsList(vm, data)
            Section.PEOPLE -> PeopleList(vm, data)
            Section.EMAILS -> EmailsList(vm, data)
        }
    }
}

// ---------------- Jobs ----------------

private enum class JobFilter(val label: String) {
    KEPT("Kept"), NEW("New"), APPLIED("Applied"), INTERVIEW("Interview"), ALL("All"), SKIPPED("Skipped")
}

@Composable
private fun JobsList(vm: AppViewModel, data: AppData) {
    var filter by rememberSaveable { mutableStateOf(JobFilter.KEPT) }
    var query by rememberSaveable { mutableStateOf("") }
    var newest by rememberSaveable { mutableStateOf(false) }

    val list = data.jobs.asSequence().filter { j ->
        when (filter) {
            JobFilter.KEPT -> j.status == JobStatus.SAVED
            JobFilter.NEW -> j.status == JobStatus.NEW
            JobFilter.APPLIED -> j.status == JobStatus.APPLIED || j.status == JobStatus.REFERRAL_ASKED
            JobFilter.INTERVIEW -> j.status == JobStatus.INTERVIEW
            JobFilter.ALL -> j.status != JobStatus.IGNORED
            JobFilter.SKIPPED -> j.status == JobStatus.IGNORED || j.status == JobStatus.REJECTED
        }
    }.filter { query.isBlank() || (it.title + " " + it.company + " " + it.location).contains(query, ignoreCase = true) }
        .let { s -> if (newest) s.sortedByDescending { it.foundAt } else s.sortedByDescending { it.matchScore } }
        .toList()

    Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Seg(JobFilter.entries, filter, { it.label }, Modifier.weight(1f)) { filter = it }
        Quiet(if (newest) "Newest" else "Best match", { newest = !newest })
    }
    Field("Search title, company or place", query, Modifier.padding(horizontal = 16.dp)) { query = it }
    if (list.isEmpty()) EmptyNote("No jobs here", "New jobs arrive in your Inbox. The ones you keep are listed here.")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.key }) { j ->
            Column(Modifier.animateItem().clickable { vm.openJob(j.key) }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(j.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Muted(listOf(j.company, j.location).filter { it.isNotBlank() }.joinToString(", "), maxLines = 1)
                        Label(
                            listOf(j.status.label.lowercase(), "found ${day(j.foundAt)}", if (j.emailsFound.isNotEmpty()) "has email" else "",
                                if (j.tailoredResume.isNotBlank()) "resume tailored" else "").filter { it.isNotBlank() }.joinToString(" | "),
                            Modifier.padding(top = 2.dp),
                        )
                    }
                    ScoreText(j.matchScore)
                }
                HorizontalDivider( color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (filter == JobFilter.SKIPPED && list.isNotEmpty()) item {
            Row(Modifier.padding(horizontal = 12.dp)) { Quiet("Delete all skipped and rejected", { vm.clearSkipped() }) }
        }
    }
}

// ---------------- People ----------------

private enum class PeopleFilter(val label: String) {
    ACTIVE("In progress"), WAITING("Invited"), ASKED("Asked"), WON("Replied"), ALL("All"), CLOSED("Closed")
}

@Composable
private fun PeopleList(vm: AppViewModel, data: AppData) {
    var filter by rememberSaveable { mutableStateOf(PeopleFilter.ACTIVE) }
    var open by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var linking by remember { mutableStateOf<Contact?>(null) }
    val jobs = data.jobs.associateBy { it.key }
    val closed = setOf(Stage.SKIPPED, Stage.NO_RESPONSE, Stage.DECLINED)

    val list = data.contacts.filter { c ->
        when (filter) {
            PeopleFilter.ACTIVE -> c.stage !in closed
            PeopleFilter.WAITING -> c.stage == Stage.INVITED
            PeopleFilter.ASKED -> c.stage == Stage.ASKED
            PeopleFilter.WON -> c.stage == Stage.REPLIED || c.stage == Stage.REFERRED
            PeopleFilter.ALL -> true
            PeopleFilter.CLOSED -> c.stage in closed
        }
    }.sortedWith(compareBy<Contact> { it.stage.ordinal }.thenByDescending { it.rank })

    Seg(PeopleFilter.entries, filter, { it.label }) { filter = it }
    if (list.isEmpty()) EmptyNote(
        "No one here yet",
        "Open a kept job and tap Find people, or share a profile from the LinkedIn app to JobPilot. Suggestions come to your Inbox for approval."
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.id }) { c ->
            val job = jobs[c.jobKey]
            val isOpen = open == c.id
            Column(Modifier.animateItem().clickable { open = if (isOpen) null else c.id }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Muted(forLine(c, job), maxLines = 1)
                        }
                        Tag(c.stage.label, if (c.stage == Stage.REFERRED || c.stage == Stage.REPLIED) ok else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val whenText = when {
                        c.repliedAt > 0 -> "replied ${day(c.repliedAt)}"
                        c.askedAt > 0 -> "asked ${day(c.askedAt)}"
                        c.connectedAt > 0 -> "connected ${day(c.connectedAt)}"
                        c.invitedAt > 0 -> "invited ${day(c.invitedAt)}"
                        else -> "added ${day(c.addedAt)}"
                    }
                    Muted(listOf(c.kind.label, whenText, c.why).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1)
                    AnimatedVisibility(isOpen) {
                        Column {
                            if (c.headline.isNotBlank()) Muted(c.headline, Modifier.padding(top = 6.dp))
                            Label("why", Modifier.padding(top = 8.dp, bottom = 2.dp))
                            Why.lines(c, data).forEach { line ->
                                if (line.startsWith("No hiring signal")) Mark("!", line, warn) else Mark("+", line, ok, maxLines = 4)
                            }
                            ButtonRow {
                                when (c.stage) {
                                    Stage.SUGGESTED -> Btn("Approve invite", { vm.approveInvite(c.id) }, primary = true)
                                    Stage.INVITED -> Btn("They accepted", { vm.setStage(c.id, Stage.CONNECTED) }, primary = true)
                                    Stage.CONNECTED -> Btn("Approve referral ask", { vm.approveAsk(c.id) }, primary = true)
                                    Stage.ASKED -> {
                                        Btn("They replied", { vm.setStage(c.id, Stage.REPLIED) }, primary = true)
                                        Btn("Declined", { vm.setStage(c.id, Stage.DECLINED) })
                                    }
                                    Stage.REPLIED -> Btn("They referred me", { vm.setStage(c.id, Stage.REFERRED) }, primary = true)
                                    Stage.SKIPPED, Stage.NO_RESPONSE, Stage.DECLINED -> Btn("Reopen", { vm.setStage(c.id, if (c.connectedAt > 0) Stage.CONNECTED else Stage.SUGGESTED) })
                                    else -> {}
                                }
                                if (c.stage == Stage.SUGGESTED || c.stage == Stage.INVITED) Quiet("Already connected", { vm.setStage(c.id, Stage.CONNECTED) })
                                Quiet("Edit messages", { editing = c })
                                Quiet(if (job == null) "Link a job" else "Change job", { linking = c })
                                if (c.postUrl.isNotBlank()) Quiet("Post", { vm.openUrl(c.postUrl) })
                                Quiet("Profile", { vm.openUrl(c.profileUrl) })
                                Quiet("Remove", { vm.deleteContact(c.id) })
                            }
                        }
                    }
                }
                HorizontalDivider( color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    editing?.let { c -> PersonEditor(vm, data, c) { editing = null } }
    linking?.let { c ->
        val options = data.jobs.filter { it.status != JobStatus.IGNORED }.sortedByDescending { it.matchScore }.take(60)
        Dialog(title = "Which job is this for?", onDismiss = { linking = null }, confirm = "Close", onConfirm = { linking = null }, dismiss = "") {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options, key = { it.key }) { j: Job ->
                    Text(
                        "${j.title}, ${j.company}", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { vm.linkContactToJob(c, j); linking = null }.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

// ---------------- Emails ----------------

private enum class EmailFilter(val label: String) { ALL("All"), WAITING("Not sent"), SENT("Sent"), REPLIED("Replied"), PROBLEM("Failed") }

@Composable
private fun EmailsList(vm: AppViewModel, data: AppData) {
    var filter by rememberSaveable { mutableStateOf(EmailFilter.ALL) }
    var open by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EmailLead?>(null) }
    val problems = setOf(EmailStatus.FAILED, EmailStatus.BOUNCED)

    val list = data.emails.filter { e ->
        when (filter) {
            EmailFilter.ALL -> true
            EmailFilter.WAITING -> e.status == EmailStatus.DRAFT || e.status == EmailStatus.APPROVED
            EmailFilter.SENT -> e.status == EmailStatus.SENT
            EmailFilter.REPLIED -> e.reply != ReplyKind.NONE
            EmailFilter.PROBLEM -> e.status in problems
        }
    }.sortedByDescending { maxOf(it.repliedAt, it.sentAt, it.createdAt) }

    Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Seg(EmailFilter.entries, filter, { it.label }, Modifier.weight(1f)) { filter = it }
        if (data.emails.any { it.status == EmailStatus.DRAFT }) Quiet("Approve all drafts", { vm.approveAllDrafts() })
    }
    if (list.isEmpty()) EmptyNote(
        "No emails here",
        "Addresses are collected from job descriptions and from hiring posts you open in the LinkedIn tab. Each becomes a draft in your Inbox."
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.id }) { e ->
            val isOpen = open == e.id
            Column(Modifier.animateItem().clickable { open = if (isOpen) null else e.id }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(e.to, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(listOf(e.role, e.company).filter { it.isNotBlank() }.joinToString(" at "), maxLines = 1)
                        }
                        val label = if (e.reply != ReplyKind.NONE) e.reply.label else e.status.label
                        Tag(
                            label,
                            when {
                                e.reply == ReplyKind.INTERVIEW || e.reply == ReplyKind.REPLY -> ok
                                e.status in problems -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Muted(
                        listOf(
                            if (e.sentAt > 0) "sent ${day(e.sentAt)} via ${e.via}" else "drafted ${day(e.createdAt)}",
                            if (e.repliedAt > 0) "reply ${day(e.repliedAt)}" else "", "template ${e.template}",
                        ).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1,
                    )
                    AnimatedVisibility(isOpen) {
                        Column {
                            if (e.replySubject.isNotBlank()) Muted("Reply: ${e.replySubject}", Modifier.padding(top = 6.dp))
                            if (e.error.isNotBlank()) Muted(e.error, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error)
                            Muted("Subject: ${e.subject}", Modifier.padding(top = 6.dp))
                            ButtonRow {
                                when (e.status) {
                                    EmailStatus.DRAFT -> Btn("Approve", { vm.setEmailStatus(e.id, EmailStatus.APPROVED) }, primary = true)
                                    EmailStatus.APPROVED -> Btn("Move back to draft", { vm.setEmailStatus(e.id, EmailStatus.DRAFT) })
                                    EmailStatus.FAILED, EmailStatus.SKIPPED -> Btn("Try again", { vm.setEmailStatus(e.id, EmailStatus.APPROVED) })
                                    else -> {}
                                }
                                if (e.status != EmailStatus.SENT && e.status != EmailStatus.BOUNCED) Quiet("Read and edit", { editing = e })
                                if (e.sourceUrl.isNotBlank()) Quiet("Source", { vm.openUrl(e.sourceUrl) })
                                Quiet("Delete", { vm.deleteEmail(e.id) })
                            }
                        }
                    }
                }
                HorizontalDivider( color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    editing?.let { e -> EmailEditor(vm, data, e) { editing = null } }
}
