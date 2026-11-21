package com.jobpilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jobpilot.core.ApplyVia
import com.jobpilot.core.Readiness
import com.jobpilot.data.AppData
import com.jobpilot.data.Job
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Stage
import com.jobpilot.data.TailoredResume

@Composable
fun JobDetailScreen(vm: AppViewModel, data: AppData, job: Job, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val p = data.profile
    val people = data.contacts.filter { it.jobKey == job.key && it.stage != Stage.SKIPPED }.sortedByDescending { it.rank }
    var notes by remember(job.key) { mutableStateOf(job.notes) }
    var askTailor by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<TailoredResume?>(null) }
    val busyMsg by vm.busy.collectAsState()
    val busy = busyMsg != null

    Page(snack) {
        Header(job.company.ifBlank { "Job" }, onBack = { vm.openJob(null) }) {
            Quiet("Delete", { vm.deleteJob(job.key) })
        }
        busyMsg?.let { BusyBar(it) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Text(job.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                    ScoreText(job.matchScore); Muted("match")
                }
            }
            Muted(listOf(job.company, job.location).filter { it.isNotBlank() }.joinToString(", "), Modifier.padding(top = 2.dp))
            Muted(listOf(job.source, job.postedAt.take(10), job.criteria).filter { it.isNotBlank() }.joinToString(" · "))

            ButtonRow {
                Btn("Open posting", { vm.openUrl(job.url) }, primary = true)
                if (job.status == JobStatus.NEW) {
                    Btn("Keep", { vm.keepJob(job.key) })
                    Btn("Skip", { vm.skipJob(job.key); vm.openJob(null) })
                }
                if (!job.detailsFetched && (job.key.startsWith("li:") || job.url.isNotBlank())) Btn("Load full details", { vm.fetchDetails(job.key) }, enabled = !busy)
            }

            SectionTitle("Status")
            Seg(JobStatus.entries.filter { it != JobStatus.NEW || job.status == JobStatus.NEW }, job.status, { it.label }, Modifier.padding(start = 0.dp)) {
                vm.setJobStatus(job.key, it)
            }

            SectionTitle("Why this job", trailing = "${job.matchScore}%")
            job.reasons.forEach { Mark("+", it, ok) }
            job.cautions.forEach { Mark("-", it, warn) }
            if (job.missingSkills.isNotEmpty() && job.detailsFetched) Mark("-", "Doesn't mention " + job.missingSkills.joinToString(", "), MaterialTheme.colorScheme.onSurfaceVariant)
            if (job.reasons.isEmpty() && job.cautions.isEmpty()) Muted("Not scored yet.")

            val ready = Readiness.of(job, p)
            SectionTitle("Ready to apply?", trailing = ready.level.label)
            Muted(ready.note)
            ready.knockouts.forEach { Mark("!", it, MaterialTheme.colorScheme.error, maxLines = 2) }
            if (ready.shown.isNotEmpty()) Mark("+", "Your resume shows: " + ready.shown.take(6).joinToString(", "), ok, maxLines = 2)
            if (ready.unshown.isNotEmpty()) Mark("-", "You have, but your resume doesn't show: " + ready.unshown.joinToString(", "), warn, maxLines = 3)
            if (ready.gaps.isNotEmpty()) Mark("-", "Posting also asks for: " + ready.gaps.take(6).joinToString(", "), MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (job.appliedAt == 0L) ButtonRow {
                when (Readiness.via(job)) {
                    ApplyVia.FORM -> Btn("Apply (" + Readiness.formName(job) + ")", { vm.startApply(listOf(job.key)) }, primary = ready.level == Readiness.Level.READY)
                    ApplyVia.LINKEDIN -> Btn("Apply on LinkedIn", { vm.applyOnLinkedIn(job) }, primary = ready.level == Readiness.Level.READY)
                    ApplyVia.EMAIL -> Btn("Draft the email", { vm.queueEmailsFromJob(job) }, primary = ready.level == Readiness.Level.READY)
                }
                Quiet("Mark applied", { vm.markApplied(job.key) })
            } else Muted("Applied " + java.text.DateFormat.getDateInstance().format(java.util.Date(job.appliedAt)) + ".", Modifier.padding(top = 6.dp))

            SectionTitle("Resume for this job")
            val saved = job.tailored
            if (job.tailoredResume.isNotBlank()) {
                Text(
                    if (saved?.byAi == true) "Rewritten for this job by the AI from your resume. Attached to emails for this job."
                    else "A reordered version is saved and attached to emails for this job.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                saved?.changes?.take(5)?.forEach { Mark("+", it, ok) }
                saved?.gaps?.take(4)?.forEach { Mark("!", "Posting asks for: $it", warn) }
                ButtonRow {
                    Btn("Share PDF", { shareFile(ctx, vm.fileFor(job.tailoredResume), "application/pdf") }, primary = true)
                    Btn("Redo", { if (p.aiConfigured) askTailor = true else preview = vm.ruleTailor(job) }, enabled = !busy)
                    Quiet("Use my original", { vm.removeTailoredResume(job) })
                }
            } else {
                Muted(
                    if (p.aiConfigured) "The AI rewrites your resume around this posting: it reorders and rewords your real experience, and tells you what it changed and what the posting asks for that you don't show."
                    else "Without an AI key this only reorders your skills and writes a short summary. Add a key under Settings > AI writing for a real rewrite from your resume."
                )
                ButtonRow {
                    Btn(if (p.aiConfigured) "Tailor with AI" else "Tailor (basic)", { if (p.aiConfigured) askTailor = true else preview = vm.ruleTailor(job) }, enabled = job.description.isNotBlank() && !busy)
                }
                if (job.description.isBlank()) Muted("Load the full details first.")
                else if (p.aiConfigured && p.resumeText.isBlank()) Muted("Your resume text hasn't been read yet (Settings > About you). Until then the AI only has your profile fields.", color = warn)
            }

            val friends = com.jobpilot.core.JobRunner.connectionsAt(job.company, data)
            if (friends.isNotEmpty()) {
                SectionTitle("People you know there", trailing = "${friends.size}")
                friends.take(5).forEach { k -> Mark("+", k.name + if (k.position.isNotBlank()) ", ${k.position}" else "", ok, maxLines = 1) }
                if (friends.size > 5) Muted("And ${friends.size - 5} more.")
                Muted("From your LinkedIn connections. A referral from someone who knows you goes furthest.")
                ButtonRow { Btn("Ask them for a referral", { vm.askKnown(job) }, primary = true) }
            }

            SectionTitle("People", trailing = if (people.isEmpty()) "" else "${people.size}")
            if (people.isEmpty()) Muted("No one yet. Search opens in the LinkedIn tab, where Grab people adds everyone on the page as suggestions.")
            people.take(6).forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium)
                        if (c.why.isNotBlank()) Muted(c.why, maxLines = 1)
                    }
                    Tag(c.stage.label)
                }
            }
            if (people.size > 6) Muted("And ${people.size - 6} more in Tracker.")
            ButtonRow {
                Btn("Same role", { vm.findPeople(job) })
                if (p.college.isNotBlank()) Btn("Alumni", { vm.findPeople(job, "alumni") })
                if (p.pastCompanyList().isNotEmpty()) Btn("Ex-colleagues", { vm.findPeople(job, "colleague") })
                Btn("Recruiters", { vm.findPeople(job, "recruiter") })
            }

            SectionTitle("Recruiter emails")
            if (job.emailsFound.isNotEmpty()) {
                Text(job.emailsFound.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                ButtonRow { Btn("Draft emails", { vm.queueEmailsFromJob(job) }) }
            } else {
                Muted("No address in this posting.")
                ButtonRow { Btn("Look for hiring posts", { vm.findHiringPosts(job) }) }
            }

            SectionTitle("Notes")
            Field("Interview dates, who referred you", notes, lines = 2) { notes = it }
            if (notes != job.notes) ButtonRow { Btn("Save notes", { vm.setJobNotes(job.key, notes) }) }

            SectionTitle("Description")
            SelectionContainer {
                Text(
                    job.description.ifBlank { "Not loaded yet." }, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 40.dp),
                )
            }
        }
    }

    if (askTailor) {
        var extra by remember { mutableStateOf("") }
        Dialog(
            title = "Tailor with AI", onDismiss = { askTailor = false }, confirm = "Generate",
            onConfirm = { askTailor = false; vm.aiTailor(job, extra.trim()) { r -> if (r != null) preview = r } },
        ) {
            Column {
                Muted("Takes up to a minute. You review the result before a PDF is made.")
                Field("Anything to emphasise or leave out (optional)", extra, lines = 3) { extra = it }
            }
        }
    }

    preview?.let { r ->
        var summary by remember(r) { mutableStateOf(r.summary) }
        Dialog(
            title = "Review", onDismiss = { preview = null }, confirm = "Create PDF",
            onConfirm = { vm.createTailoredResume(job, r.copy(summary = summary.trim())); preview = null },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Summary", summary, lines = 4) { summary = it }
                if (r.changes.isNotEmpty()) {
                    Label("what changed", Modifier.padding(top = 8.dp, bottom = 2.dp))
                    r.changes.forEach { Mark("+", it, ok, maxLines = 5) }
                }
                if (r.gaps.isNotEmpty()) {
                    Label("the posting asks for, your resume doesn't show", Modifier.padding(top = 8.dp, bottom = 2.dp))
                    r.gaps.forEach { Mark("!", it, warn, maxLines = 4) }
                }
                val unsupported = remember(r) { com.jobpilot.core.FactCheck.unsupported(r, p) }
                if (unsupported.isNotEmpty()) {
                    Label("not found in your resume, check these", Modifier.padding(top = 8.dp, bottom = 2.dp), color = MaterialTheme.colorScheme.error)
                    unsupported.forEach { Mark("!", it, MaterialTheme.colorScheme.error, maxLines = 3) }
                }
                Label("skills", Modifier.padding(top = 8.dp, bottom = 2.dp))
                Text(r.skills.joinToString(", ").ifBlank { "None listed." }, style = MaterialTheme.typography.bodySmall)
                r.experience.filter { it.title.isNotBlank() }.forEach { role ->
                    Label(listOf(role.title, role.company).filter { it.isNotBlank() }.joinToString(", "), Modifier.padding(top = 8.dp, bottom = 2.dp))
                    role.bullets.forEach { Mark("-", it, MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5) }
                }
                if (r.byAi) Muted("Check every line against your real experience before sending it.", Modifier.padding(top = 10.dp), color = warn)
            }
        }
    }
}
