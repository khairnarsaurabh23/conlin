package com.jobpilot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobpilot.core.Guard
import com.jobpilot.core.JobRunner
import com.jobpilot.core.Why
import com.jobpilot.data.ContactKind
import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.EmailLead
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Stage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** One row of the inbox. Keys are stable so rows animate out when handled. */
private sealed class Card(val key: String) {
    object Safety : Card("safety")
    object Setup : Card("setup")
    class Head(val title: String, val count: Int) : Card("head-$title")
    class SendPerson(val c: Contact, val job: Job?) : Card("send-${c.id}")
    class SendEmails(val count: Int) : Card("send-emails")
    class SendEmail(val e: EmailLead) : Card("sendmail-${e.id}")
    class FollowUp(val c: Contact, val job: Job?) : Card("follow-${c.id}")
    class Ask(val c: Contact, val job: Job?) : Card("ask-${c.id}")
    class Connect(val c: Contact, val job: Job?) : Card("connect-${c.id}")
    class Email(val e: EmailLead) : Card("email-${e.id}")
    class Stale(val count: Int) : Card("stale")
    class FindPeople(val job: Job) : Card("people-${job.key}")
    class NewJob(val job: Job) : Card("job-${job.key}")
    class More(val text: String, val id: String) : Card("more-$id")
    object Empty : Card("empty")
}

private const val MAX_PEOPLE = 8
private const val MAX_JOBS = 8

private fun buildCards(d: AppData, now: Long): List<Card> {
    val p = d.profile
    val jobs = d.jobs.associateBy { it.key }
    val out = mutableListOf<Card>(Card.Safety)
    if (p.name.isBlank() || p.skillList().isEmpty() || d.searches.isEmpty()) out += Card.Setup

    // Ready to send: everything already approved
    val sendPeople = d.contacts.filter { it.stage == Stage.ASK_APPROVED || it.stage == Stage.APPROVED }
        .sortedWith(compareByDescending<Contact> { it.stage == Stage.ASK_APPROVED }.thenByDescending { it.rank })
    val sendMails = d.emails.filter { it.status == EmailStatus.APPROVED }
    if (sendPeople.isNotEmpty() || sendMails.isNotEmpty()) {
        out += Card.Head("Ready to send", sendPeople.size + sendMails.size)
        sendPeople.forEach { out += Card.SendPerson(it, jobs[it.jobKey]) }
        if (sendMails.isNotEmpty()) {
            if (p.smtpConfigured) out += Card.SendEmails(sendMails.size) else sendMails.forEach { out += Card.SendEmail(it) }
        }
    }

    // Needs a decision
    val review = mutableListOf<Card>()
    val followMs = TimeUnit.DAYS.toMillis(p.followUpDays.toLong())
    d.contacts.filter { it.stage == Stage.ASKED && it.followUps == 0 && now - it.askedAt > followMs }
        .forEach { review += Card.FollowUp(it, jobs[it.jobKey]) }
    d.contacts.filter { it.stage == Stage.CONNECTED }.sortedByDescending { it.rank }
        .forEach { review += Card.Ask(it, jobs[it.jobKey]) }
    d.emails.filter { it.status == EmailStatus.DRAFT }.sortedByDescending { it.createdAt }.forEach { review += Card.Email(it) }
    val suggested = d.contacts.filter { it.stage == Stage.SUGGESTED }.sortedByDescending { it.rank }
    suggested.take(MAX_PEOPLE).forEach { review += Card.Connect(it, jobs[it.jobKey]) }
    if (suggested.size > MAX_PEOPLE) review += Card.More("${suggested.size - MAX_PEOPLE} more people, lower ranked, appear as you clear these.", "people")
    val staleMs = TimeUnit.DAYS.toMillis(p.staleInviteDays.toLong())
    val stale = d.contacts.count { it.stage == Stage.INVITED && now - it.invitedAt > staleMs }
    if (stale > 0) review += Card.Stale(stale)
    d.jobs.filter { j -> j.status == JobStatus.SAVED && !j.peoplePrompted && d.contacts.none { it.jobKey == j.key } }
        .sortedByDescending { it.matchScore }.take(3).forEach { review += Card.FindPeople(it) }
    val allNew = d.jobs.filter { it.status == JobStatus.NEW }
    val fresh = allNew.filter { it.matchScore >= p.minMatch }
        .sortedWith(compareByDescending<Job> { it.matchScore }.thenByDescending { it.foundAt })
    fresh.take(MAX_JOBS).forEach { review += Card.NewJob(it) }
    val hidden = allNew.size - fresh.size
    val queued = (fresh.size - MAX_JOBS).coerceAtLeast(0)
    if (queued > 0 || hidden > 0) review += Card.More(
        listOfNotNull(
            if (queued > 0) "$queued more new jobs queued" else null,
            if (hidden > 0) "$hidden under ${p.minMatch}% kept out of the inbox" else null,
        ).joinToString(", "), if (hidden > 0) "jobs" else "queue"
    )

    if (review.isNotEmpty()) {
        out += Card.Head("For your approval", review.count { it !is Card.More })
        out += review
    } else if (sendPeople.isEmpty() && sendMails.isEmpty()) out += Card.Empty
    return out
}

@Composable
fun InboxScreen(vm: AppViewModel, data: AppData, busy: Boolean) {
    // Budgets depend on the clock (spacing between sends), so refresh twice a minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val cards = remember(data, now) { buildCards(data, now) }
    var editPerson by remember { mutableStateOf<Contact?>(null) }
    var editEmail by remember { mutableStateOf<EmailLead?>(null) }
    var warning by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header("Inbox") {
            Quiet(if (busy) "Checking" else "Check now", { vm.runSearch() }, enabled = !busy && data.searches.isNotEmpty())
            IconButton(onClick = { vm.showSettings(true) }) { Icon(Icons.Default.Settings, "Settings") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            items(cards, key = { it.key }) { card ->
                Column(Modifier.animateItem().padding(bottom = if (card is Card.Head) 0.dp else 10.dp)) {
                    when (card) {
                        Card.Safety -> SafetyStrip(vm, data, now) { warning = true }
                        Card.Setup -> SetupCard(vm, data)
                        is Card.Head -> SectionTitle(card.title, trailing = "${card.count}")
                        is Card.SendPerson -> SendPersonCard(vm, data, card.c, card.job, now)
                        is Card.SendEmails -> Panel {
                            Label("emails, approved")
                            Text("${card.count} ready to go", style = MaterialTheme.typography.titleSmall)
                            Muted("Sent from ${data.profile.smtpUser}, a few seconds apart. ${Guard.emailsLeftToday(data, now)} left today.")
                            ButtonRow { Btn("Send now", { vm.sendApprovedSmtp() }, primary = true, enabled = !busy) }
                        }
                        is Card.SendEmail -> SendEmailCard(vm, card.e) { editEmail = card.e }
                        is Card.FollowUp -> PersonCard(
                            vm, data, card.c, card.job, "follow-up", "No reply in ${data.profile.followUpDays} days. Send one short follow-up?",
                            JobRunner.draftFollowUp(data.profile, card.c, card.job),
                        ) {
                            Btn("Approve follow-up", { vm.approveFollowUp(card.c) }, primary = true)
                            Quiet("No response", { vm.setStage(card.c.id, Stage.NO_RESPONSE) })
                            Quiet("They replied", { vm.setStage(card.c.id, Stage.REPLIED) })
                        }
                        is Card.Ask -> PersonCard(vm, data, card.c, card.job, "referral ask", "Connected. Ask for a referral?", card.c.ask) {
                            Btn("Approve", { vm.approveAsk(card.c.id) }, primary = true)
                            Quiet("Edit", { editPerson = card.c })
                            Quiet("Skip", { vm.skipContact(card.c.id) })
                        }
                        is Card.Connect -> PersonCard(
                            vm, data, card.c, card.job, "connection request", "Send a connection request?",
                            card.c.note.ifBlank { "No note. A plain request will be sent." },
                        ) {
                            Btn("Approve", { vm.approveInvite(card.c.id) }, primary = true)
                            Quiet("Edit", { editPerson = card.c })
                            Quiet("Skip", { vm.skipContact(card.c.id) })
                        }
                        is Card.Email -> EmailCard(card.e, onApprove = { vm.setEmailStatus(card.e.id, EmailStatus.APPROVED) },
                            onEdit = { editEmail = card.e }, onSkip = { vm.setEmailStatus(card.e.id, EmailStatus.SKIPPED) })
                        is Card.Stale -> Panel {
                            Text("${card.count} invite(s) unanswered for over ${data.profile.staleInviteDays} days", style = MaterialTheme.typography.titleSmall)
                            Muted("Withdrawing old invites keeps your acceptance rate healthy. Withdraw them in LinkedIn, then close them here.")
                            ButtonRow {
                                Btn("Open sent invitations", { vm.openUrl("https://www.linkedin.com/mynetwork/invitation-manager/sent/") })
                                Quiet("Close them here", { vm.closeStaleInvites() })
                            }
                        }
                        is Card.FindPeople -> Panel {
                            Label("kept job")
                            Text("${card.job.title} at ${card.job.company}", style = MaterialTheme.typography.titleSmall)
                            Muted("Find people there who could refer you.")
                            ButtonRow {
                                Btn("Find people", { vm.findPeople(card.job) }, primary = true)
                                if (data.profile.college.isNotBlank()) Btn("Alumni", { vm.findPeople(card.job, "alumni") })
                                Quiet("Not now", { vm.dismissPeoplePrompt(card.job.key) })
                            }
                        }
                        is Card.NewJob -> JobCard(card.job, onOpen = { vm.openJob(card.job.key) },
                            onKeep = { vm.keepJob(card.job.key) }, onSkip = { vm.skipJob(card.job.key) })
                        is Card.More -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Muted(card.text, Modifier.weight(1f))
                            if (card.id == "jobs") Quiet("Skip those", { vm.skipWeakJobs(data.profile.minMatch) })
                        }
                        Card.Empty -> EmptyInbox(data)
                    }
                }
            }
        }
    }

    editPerson?.let { c -> PersonEditor(vm, data, c) { editPerson = null } }
    editEmail?.let { e -> EmailEditor(vm, data, e) { editEmail = null } }
    if (warning) WarningDialog(vm) { warning = false }
}

@Composable
private fun SafetyStrip(vm: AppViewModel, data: AppData, now: Long, onReport: () -> Unit) {
    val usage = Guard.inviteUsage(data, now)
    val paused = Guard.pauseText(data, now)
    Panel {
        if (paused != null) {
            Text(paused, style = MaterialTheme.typography.bodyMedium, color = warn)
            ButtonRow { Quiet("Resume now", { vm.resumeOutreach() }) }
            Gap(4)
        }
        Meter("Connection requests, last 24 hours", usage.today, usage.limits.day)
        Gap(10)
        Meter("Last 7 days", usage.week, usage.limits.week)
        val notes = buildList {
            if (usage.limits.note.isNotBlank()) add(usage.limits.note)
            Guard.acceptanceRate(data, now)?.let { add("$it% of your invites are accepted") }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted(notes.joinToString(". "), Modifier.weight(1f))
            Quiet("Report a warning", onReport)
        }
    }
}

@Composable
private fun SetupCard(vm: AppViewModel, data: AppData) {
    val p = data.profile
    val steps = listOf(
        "Add the jobs you want" to data.searches.isNotEmpty(),
        "Add your name and skills" to (p.name.isNotBlank() && p.skillList().isNotEmpty()),
        "Attach your resume" to p.resumeFileName.isNotBlank(),
        "Connect Gmail for sending and reply tracking (optional)" to p.smtpConfigured,
    )
    Panel(onClick = { vm.showSettings(true) }) {
        Label("setup")
        Gap(4)
        steps.forEach { (label, done) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(if (done) "[x]" else "[ ]", fontFamily = Mono, fontSize = 13.sp, color = if (done) ok else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun EmptyInbox(data: AppData) {
    val last = data.runs.firstOrNull()
    EmptyNote(
        "Nothing waiting for you",
        buildString {
            if (data.searches.isEmpty()) append("Add a job search in Settings to get started.")
            else {
                if (last != null) append("Last check: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last.at)) + ". ")
                if (data.profile.autoCheckEnabled) append("JobPilot checks again every ${data.profile.autoCheckHours} hours.")
                last?.errors?.firstOrNull()?.let { append("\n\n$it") }
            }
        }
    )
}

fun forLine(c: Contact, job: Job?): String = when {
    job != null -> "For ${JobRunner.roleOf(job)} at ${job.company}"
    c.company.isNotBlank() -> c.company
    else -> "Not linked to a job yet"
}

/** Shared layout for approval cards about a person: who, the evidence for contacting them, the draft. */
@Composable
private fun PersonCard(
    vm: AppViewModel, data: AppData, c: Contact, job: Job?, kind: String, question: String, draft: String,
    actions: @Composable () -> Unit,
) {
    val why = remember(c, data.jobs) { Why.lines(c, data) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(kind, Modifier.weight(1f))
            if (c.degree in 1..3) Tag(listOf("1st", "2nd", "3rd")[c.degree - 1])
            if (c.kind == ContactKind.RECRUITER) Box(Modifier.padding(start = 6.dp)) { Tag("recruiter", MaterialTheme.colorScheme.primary) }
        }
        Text(c.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        if (c.headline.isNotBlank()) Muted(c.headline, maxLines = 2)
        Muted(forLine(c, job), Modifier.padding(top = 2.dp))

        Label("why", Modifier.padding(top = 10.dp, bottom = 2.dp))
        why.forEach { line ->
            if (line.startsWith("No hiring signal")) Mark("!", line, warn) else Mark("+", line, ok, maxLines = 4)
        }
        if (c.postUrl.isNotBlank() || job != null) Row {
            if (c.postUrl.isNotBlank()) Quiet("View post", { vm.openUrl(c.postUrl) })
            if (job != null) Quiet("View job", { vm.openJob(job.key) })
            Quiet("Profile", { vm.openUrl(c.profileUrl) })
        }

        Text(question, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        if (draft.isNotBlank()) Quote(draft, maxLines = 6)
        ButtonRow { actions() }
    }
}

@Composable
private fun SendPersonCard(vm: AppViewModel, data: AppData, c: Contact, job: Job?, now: Long) {
    val invite = c.stage == Stage.APPROVED
    val check = if (invite) Guard.canInvite(data, now) else Guard.canMessage(data, now)
    val text = if (invite) c.note else c.ask
    Panel {
        Label(if (invite) "connection request" else if (c.followUps > 0) "follow-up message" else "referral message")
        Text(c.name, style = MaterialTheme.typography.titleSmall)
        Muted(forLine(c, job))
        if (text.isNotBlank()) Quote(text, Modifier.padding(top = 8.dp), maxLines = 3)
        if (c.openedAt > 0) {
            Text("Did it go through?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            ButtonRow {
                Btn("Yes, sent", { vm.confirmSent(c, true) }, primary = true)
                Btn("No", { vm.confirmSent(c, false) })
                Quiet("LinkedIn showed a limit", { vm.confirmSent(c, false); vm.onRestrictionSignal("limit", fromBrowser = false) })
            }
        } else {
            ButtonRow {
                Btn(if (text.isBlank()) "Open profile" else "Copy and open profile", { vm.openForSend(c) }, primary = true, enabled = check.ok)
                Quiet("Move back", { vm.unapprove(c.id) })
            }
            if (!check.ok) Muted(check.reason, Modifier.padding(top = 4.dp), color = warn)
        }
    }
}

@Composable
private fun SendEmailCard(vm: AppViewModel, e: EmailLead, onEdit: () -> Unit) {
    Panel {
        Label("email, approved")
        Text(e.to, style = MaterialTheme.typography.titleSmall)
        Muted(e.subject, maxLines = 1)
        if (e.openedAt > 0) {
            Text("Did you send it?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            ButtonRow {
                Btn("Yes, sent", { vm.confirmEmailSent(e, true) }, primary = true)
                Btn("No", { vm.confirmEmailSent(e, false) })
            }
        } else ButtonRow {
            Btn("Open in Gmail", { vm.openInGmail(e) }, primary = true)
            Quiet("Edit", onEdit)
            Quiet("Move back", { vm.setEmailStatus(e.id, EmailStatus.DRAFT) })
        }
    }
}

@Composable
private fun EmailCard(e: EmailLead, onApprove: () -> Unit, onEdit: () -> Unit, onSkip: () -> Unit) {
    Panel {
        Label("recruiter email")
        Text(e.to, style = MaterialTheme.typography.titleSmall)
        Muted(listOf(e.role, e.company).filter { it.isNotBlank() }.joinToString(" at "))
        if (e.context.isNotBlank()) {
            Label("found in", Modifier.padding(top = 8.dp, bottom = 2.dp))
            Quote(e.context, maxLines = 3)
        }
        Text("Send your resume with this subject?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
        Muted(e.subject, maxLines = 1)
        ButtonRow {
            Btn("Approve", onApprove, primary = true)
            Quiet("Read and edit", onEdit)
            Quiet("Skip", onSkip)
        }
    }
}

@Composable
private fun JobCard(job: Job, onOpen: () -> Unit, onKeep: () -> Unit, onSkip: () -> Unit) {
    Panel(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("new job", Modifier.weight(1f))
            ScoreText(job.matchScore)
        }
        Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Muted(listOf(job.company, job.location).filter { it.isNotBlank() }.joinToString(", "), maxLines = 1)
        if (job.reasons.isNotEmpty() || job.cautions.isNotEmpty()) {
            Gap(6)
            job.reasons.take(4).forEach { Mark("+", it, ok, maxLines = 2) }
            job.cautions.take(3).forEach { Mark("-", it, warn, maxLines = 2) }
        }
        ButtonRow {
            Btn("Keep", onKeep, primary = true)
            Quiet("Skip", onSkip)
            Quiet("Details", onOpen)
        }
    }
}

// ---------------- dialogs (also used by the Tracker) ----------------

@Composable
fun PersonEditor(vm: AppViewModel, data: AppData, c: Contact, onClose: () -> Unit) {
    var note by remember(c.id) { mutableStateOf(c.note) }
    var ask by remember(c.id) { mutableStateOf(c.ask) }
    val limit = data.profile.noteCharLimit
    Dialog(
        title = c.name, onDismiss = onClose, confirm = "Save",
        onConfirm = { vm.saveContactText(c.id, note.trim(), ask.trim()); onClose() },
        dismiss = "Redraft", onDismissButton = { vm.redraft(c); onClose() },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Field("Connection note (leave empty for a plain request)", note, lines = 3) { note = it }
            Muted("${note.length} of $limit characters", color = if (note.length > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Gap()
            Field("Referral message, sent after they accept", ask, lines = 5) { ask = it }
        }
    }
}

@Composable
fun EmailEditor(vm: AppViewModel, data: AppData, e: EmailLead, onClose: () -> Unit) {
    var to by remember(e.id) { mutableStateOf(e.to) }
    var subject by remember(e.id) { mutableStateOf(e.subject) }
    var body by remember(e.id) { mutableStateOf(e.body) }
    var attach by remember(e.id) { mutableStateOf(e.attachResume) }
    val hasResume = data.profile.resumeFileName.isNotBlank() || data.jobs.any { it.key == e.jobKey && it.tailoredResume.isNotBlank() }
    fun edited() = e.copy(to = to.trim(), subject = subject, body = body, attachResume = attach)
    Dialog(
        title = "Email", onDismiss = onClose,
        confirm = if (e.status == EmailStatus.DRAFT) "Save and approve" else "Save",
        onConfirm = {
            vm.saveEmail(if (e.status == EmailStatus.DRAFT) edited().copy(status = EmailStatus.APPROVED) else edited()); onClose()
        },
        confirmEnabled = to.contains("@"),
        dismiss = if (e.status == EmailStatus.DRAFT) "Save draft" else "Cancel",
        onDismissButton = { if (e.status == EmailStatus.DRAFT) vm.saveEmail(edited()); onClose() },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Field("To", to) { to = it }
            Field("Subject", subject) { subject = it }
            Field("Message", body, lines = 8) { body = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = attach && hasResume, onCheckedChange = { attach = it }, enabled = hasResume)
                Text(if (hasResume) "Attach resume" else "No resume uploaded yet", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun WarningDialog(vm: AppViewModel, onClose: () -> Unit) {
    Dialog(title = "What did LinkedIn show?", onDismiss = onClose, confirm = "Close", onConfirm = onClose, dismiss = "") {
        Column {
            Muted("Pausing straight away is the safest response. Carrying on after a warning is what turns it into a restriction.")
            Gap()
            Btn("Weekly invitation limit reached", { vm.onRestrictionSignal("limit", false); onClose() }, Modifier.fillMaxWidth())
            Gap(6)
            Btn("A security check or CAPTCHA", { vm.onRestrictionSignal("challenge", false); onClose() }, Modifier.fillMaxWidth())
            Gap(6)
            Btn("Account restricted", { vm.onRestrictionSignal("restricted", false); onClose() }, Modifier.fillMaxWidth())
        }
    }
}
