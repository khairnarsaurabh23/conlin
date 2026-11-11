package com.jobpilot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jobpilot.data.AppData
import com.jobpilot.data.Experience
import com.jobpilot.data.PostedWithin
import com.jobpilot.data.SearchPref
import com.jobpilot.data.Store
import com.jobpilot.data.WorkType

@Composable
fun SettingsScreen(vm: AppViewModel, data: AppData, snack: SnackbarHostState) {
    var p by remember { mutableStateOf(data.profile) }
    var editSearch by remember { mutableStateOf<SearchPref?>(null) }
    val pickResume = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importResume(it) }
    }
    // The resume file is saved separately, as soon as it is picked.
    val dirty = p.copy(resumeFileName = "") != data.profile.copy(resumeFileName = "")
    fun save() = vm.saveProfile(p.copy(resumeFileName = Store.data.profile.resumeFileName))

    Page(snack) {
        Header("Settings", onBack = { vm.showSettings(false) }) {
            Quiet("Save", { save() }, enabled = dirty, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {

            SectionTitle("Jobs you want", Modifier.padding(top = 0.dp))
            data.searches.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label(), style = MaterialTheme.typography.titleSmall)
                        Muted(
                            listOf(s.experience.label, s.workType.label, s.postedWithin.label, if (s.enabled) "" else "paused")
                                .filter { it.isNotBlank() && it != "Any" }.joinToString(", ")
                        )
                    }
                    Quiet(if (s.enabled) "Pause" else "Resume", { vm.saveSearch(s.copy(enabled = !s.enabled)) })
                    Quiet("Edit", { editSearch = s })
                }
            }
            ButtonRow { Btn("Add a job search", { editSearch = SearchPref(id = Store.newId(), keywords = "") }, primary = data.searches.isEmpty()) }

            SectionTitle("About you")
            Field("Full name", p.name) { p = p.copy(name = it) }
            Field("Headline, e.g. Android developer", p.headline) { p = p.copy(headline = it) }
            Field("Skills, comma separated", p.skills, lines = 3) { p = p.copy(skills = it) }
            Field("Years of experience", p.yearsExperience, keyboard = KeyboardType.Number) { p = p.copy(yearsExperience = it) }
            Field("Email", p.email, keyboard = KeyboardType.Email) { p = p.copy(email = it) }
            Field("Phone", p.phone, keyboard = KeyboardType.Phone) { p = p.copy(phone = it) }
            Field("Your LinkedIn profile link", p.linkedinUrl, keyboard = KeyboardType.Uri) { p = p.copy(linkedinUrl = it) }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (data.profile.resumeFileName.isBlank()) "No resume attached" else "Resume attached",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Btn(if (data.profile.resumeFileName.isBlank()) "Choose file" else "Replace", {
                    pickResume.launch(arrayOf("application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                })
            }

            SectionTitle("For ranking referrers and tailoring")
            Muted("People from your college or a company you worked at are suggested first. They are the most likely to help.")
            Field("College or university", p.college) { p = p.copy(college = it) }
            Field("Companies you have worked at, comma separated", p.pastCompanies) { p = p.copy(pastCompanies = it) }
            Field("Experience, as it should appear on a tailored resume", p.experienceText, lines = 5) { p = p.copy(experienceText = it) }
            Field("Education", p.educationText, lines = 2) { p = p.copy(educationText = it) }

            SectionTitle("Account safety")
            Muted("These caps apply to connection requests you confirm as sent. LinkedIn allows roughly 100 a week on established accounts and far fewer on new ones; staying well under is what keeps an account unrestricted.")
            Row {
                NumField("Per day", p.dailyInviteCap, Modifier.weight(1f).padding(end = 6.dp)) { p = p.copy(dailyInviteCap = it.coerceIn(1, 25)) }
                NumField("Per 7 days", p.weeklyInviteCap, Modifier.weight(1f).padding(start = 6.dp)) { p = p.copy(weeklyInviteCap = it.coerceIn(5, 100)) }
            }
            Row {
                NumField("Messages per day", p.dailyMessageCap, Modifier.weight(1f).padding(end = 6.dp)) { p = p.copy(dailyMessageCap = it.coerceIn(1, 50)) }
                NumField("Minutes between sends", p.minGapMinutes, Modifier.weight(1f).padding(start = 6.dp)) { p = p.copy(minGapMinutes = it.coerceIn(1, 60)) }
            }
            Toggle("Warm up gradually", p.rampUp, "A third of the caps in week one, two thirds in week two.") { p = p.copy(rampUp = it) }
            Toggle("Open profiles in the LinkedIn app", p.openInLinkedInApp, "Off: profiles open in the built-in browser, where limit notices are detected automatically.") { p = p.copy(openInLinkedInApp = it) }
            NumField("Flag invites unanswered after N days", p.staleInviteDays) { p = p.copy(staleInviteDays = it.coerceIn(7, 60)) }

            SectionTitle("Messages")
            Field("Connection note. Leave empty to send plain requests", p.noteTemplateA, lines = 3) { p = p.copy(noteTemplateA = it) }
            Field("Second version to compare (optional)", p.noteTemplateB, lines = 3) { p = p.copy(noteTemplateB = it) }
            NumField("Note length limit (200 on free accounts, 300 on Premium)", p.noteCharLimit) { p = p.copy(noteCharLimit = it.coerceIn(100, 300)) }
            Field("Referral message, sent after they accept", p.askTemplate, lines = 6) { p = p.copy(askTemplate = it) }
            NumField("Follow up after N days without a reply", p.followUpDays) { p = p.copy(followUpDays = it.coerceIn(2, 30)) }
            Muted("You can use {firstName} {company} {role} {jobUrl} {myName} {myHeadline} {myEmail} {myPhone} {myLinkedIn} {years} {skills} {topSkills}")

            SectionTitle("Recruiter emails")
            Field("Subject", p.emailSubjectA) { p = p.copy(emailSubjectA = it) }
            Field("Body", p.emailBodyA, lines = 8) { p = p.copy(emailBodyA = it) }
            Field("Second subject to compare (optional)", p.emailSubjectB) { p = p.copy(emailSubjectB = it) }
            Field("Second body to compare (optional)", p.emailBodyB, lines = 4) { p = p.copy(emailBodyB = it) }
            NumField("Most emails per day", p.dailyEmailCap) { p = p.copy(dailyEmailCap = it.coerceIn(1, 100)) }

            SectionTitle("Gmail")
            Muted(
                "Used for two things: sending approved emails in one go, and a read-only inbox check that spots accepted invites, " +
                    "replies, interview mails and bounces. In your Google account turn on 2-Step Verification, create an App Password at " +
                    "myaccount.google.com/apppasswords and paste it here. It stays on this phone and can be revoked at any time. " +
                    "Without it, each email opens in the Gmail app for you to send."
            )
            Field("Gmail address", p.smtpUser, keyboard = KeyboardType.Email) { p = p.copy(smtpUser = it.trim()) }
            Field("App Password", p.smtpPassword, secret = true) { p = p.copy(smtpPassword = it.replace(" ", "")) }
            Toggle("Check my inbox for replies", p.mailScanEnabled, "Read-only. Runs with each job check.") { p = p.copy(mailScanEnabled = it) }
            Row {
                Field("SMTP server", p.smtpHost, Modifier.weight(2f).padding(end = 6.dp)) { p = p.copy(smtpHost = it.trim()) }
                NumField("Port", p.smtpPort, Modifier.weight(1f).padding(start = 6.dp)) { p = p.copy(smtpPort = it) }
            }
            Field("IMAP server", p.imapHost) { p = p.copy(imapHost = it.trim()) }

            SectionTitle("Job sources")
            Toggle("LinkedIn public listings", p.useLinkedIn, "No sign-in involved.") { p = p.copy(useLinkedIn = it) }
            Toggle("JSearch", p.useJSearch, "Adds Indeed, Glassdoor, Naukri and company sites. Free key from rapidapi.com.") { p = p.copy(useJSearch = it) }
            if (p.useJSearch) Field("JSearch RapidAPI key", p.jsearchApiKey, secret = true) { p = p.copy(jsearchApiKey = it.trim()) }
            NumField("Read full details for the best N new jobs per check", p.detailFetchLimit) { p = p.copy(detailFetchLimit = it.coerceIn(0, 50)) }
            Toggle("Check in the background", p.autoCheckEnabled) { p = p.copy(autoCheckEnabled = it) }
            if (p.autoCheckEnabled) NumField("Every N hours", p.autoCheckHours) { p = p.copy(autoCheckHours = it.coerceIn(1, 72)) }

            ButtonRow(Modifier.padding(top = 14.dp, bottom = 40.dp)) { Btn("Save settings", { save() }, primary = true, enabled = dirty) }
        }
    }

    editSearch?.let { s ->
        SearchDialog(s, onDismiss = { editSearch = null }, onDelete = { vm.deleteSearch(s.id); editSearch = null }) { vm.saveSearch(it); editSearch = null }
    }
}

@Composable
private fun SearchDialog(initial: SearchPref, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (SearchPref) -> Unit) {
    var s by remember(initial.id) { mutableStateOf(initial) }
    val isNew = initial.keywords.isBlank()
    Dialog(
        title = if (isNew) "New job search" else "Edit search", onDismiss = onDismiss, confirm = "Save",
        onConfirm = { onSave(s.copy(keywords = s.keywords.trim(), location = s.location.trim())) },
        confirmEnabled = s.keywords.isNotBlank(),
        dismiss = if (isNew) "Cancel" else "Delete", onDismissButton = if (isNew) onDismiss else onDelete,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Field("Job title or keywords", s.keywords) { s = s.copy(keywords = it) }
            Field("Place, e.g. Pune, India", s.location) { s = s.copy(location = it) }
            Muted("Experience", Modifier.padding(top = 8.dp))
            Seg(Experience.entries, s.experience, { it.label }) { s = s.copy(experience = it) }
            Muted("Work type", Modifier.padding(top = 4.dp))
            Seg(WorkType.entries, s.workType, { it.label }) { s = s.copy(workType = it) }
            Muted("Posted within", Modifier.padding(top = 4.dp))
            Seg(PostedWithin.entries, s.postedWithin, { it.label }) { s = s.copy(postedWithin = it) }
            Field("Skip titles containing, e.g. senior, lead", s.excludeWords) { s = s.copy(excludeWords = it) }
            NumField("Most results per check", s.maxResults) { s = s.copy(maxResults = it.coerceIn(5, 200)) }
        }
    }
}
