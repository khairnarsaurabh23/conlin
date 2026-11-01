@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.jobpilot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jobpilot.data.AppData
import com.jobpilot.data.Experience
import com.jobpilot.data.PostedWithin
import com.jobpilot.data.Profile
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
    val dirty = p != data.profile.copy(resumeFileName = p.resumeFileName)

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { vm.showSettings(false) }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { vm.saveProfile(p.copy(resumeFileName = Store.data.profile.resumeFileName)) }, enabled = dirty) { Text("SAVE") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            SectionTitle("What jobs do you want?")
            data.searches.forEach { s ->
                Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(s.label(), fontWeight = FontWeight.SemiBold)
                            Text(
                                "${s.experience.label} · ${s.workType.label} · ${s.postedWithin.label} · max ${s.maxResults}" +
                                    if (s.excludeWords.isNotBlank()) " · excl. ${s.excludeWords}" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = { vm.saveSearch(s.copy(enabled = it)) })
                        IconButton(onClick = { editSearch = s }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { vm.deleteSearch(s.id) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            OutlinedButton(onClick = { editSearch = SearchPref(id = Store.newId(), keywords = "") }) {
                Icon(Icons.Default.Add, null); Text("Add a job search")
            }

            SectionTitle("About you")
            Field("Full name", p.name) { p = p.copy(name = it) }
            Field("Headline (e.g. Android developer with 3 yrs in Kotlin)", p.headline) { p = p.copy(headline = it) }
            Field("Skills, comma separated (used for match %)", p.skills, lines = 3) { p = p.copy(skills = it) }
            Field("Years of experience", p.yearsExperience, keyboard = KeyboardType.Number) { p = p.copy(yearsExperience = it) }
            Field("Email", p.email, keyboard = KeyboardType.Email) { p = p.copy(email = it) }
            Field("Phone", p.phone, keyboard = KeyboardType.Phone) { p = p.copy(phone = it) }
            Field("Your LinkedIn profile URL", p.linkedinUrl, keyboard = KeyboardType.Uri) { p = p.copy(linkedinUrl = it) }
            Text(
                "Tip: in the LinkedIn tab open \"My profile\" → ⋮ → Import my profile to fill name & headline.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (data.profile.resumeFileName.isBlank()) "No resume attached" else "Resume: ${data.profile.resumeFileName}",
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    pickResume.launch(arrayOf("application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                }) { Text(if (data.profile.resumeFileName.isBlank()) "Choose resume" else "Replace") }
            }

            SectionTitle("Job sources")
            Toggle("LinkedIn public job listings (no login needed)", p.useLinkedIn) { p = p.copy(useLinkedIn = it) }
            Toggle("JSearch API (adds Indeed, Glassdoor, Naukri…)", p.useJSearch) { p = p.copy(useJSearch = it) }
            if (p.useJSearch) {
                Field("JSearch RapidAPI key", p.jsearchApiKey, secret = true) { p = p.copy(jsearchApiKey = it) }
                Text("Free key at rapidapi.com → search \"JSearch\" → Subscribe (Basic).", style = MaterialTheme.typography.bodySmall)
            }
            NumField("Read full details for top N new jobs per run", p.detailFetchLimit) { p = p.copy(detailFetchLimit = it.coerceIn(0, 50)) }

            SectionTitle("Automatic checks")
            Toggle("Check for new jobs in the background", p.autoCheckEnabled) { p = p.copy(autoCheckEnabled = it) }
            if (p.autoCheckEnabled) NumField("Every N hours", p.autoCheckHours) { p = p.copy(autoCheckHours = it.coerceIn(1, 72)) }

            SectionTitle("Referral messages")
            Field("Connection note template", p.referralTemplate, lines = 4) { p = p.copy(referralTemplate = it) }
            NumField("Max note length (LinkedIn's limit for your account)", p.referralCharLimit) { p = p.copy(referralCharLimit = it.coerceIn(100, 2000)) }
            NumField("Follow up after N days", p.followUpDays) { p = p.copy(followUpDays = it.coerceIn(1, 60)) }
            Placeholders()

            SectionTitle("Recruiter emails")
            Field("Subject template", p.emailSubjectTemplate) { p = p.copy(emailSubjectTemplate = it) }
            Field("Body template", p.emailBodyTemplate, lines = 8) { p = p.copy(emailBodyTemplate = it) }
            Text(
                "Optional — to send all approved emails at once from the app. For Gmail: turn on 2-Step Verification, " +
                    "then create an App Password at myaccount.google.com/apppasswords and paste it here. " +
                    "Without this, each email opens in the Gmail app for you to send.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Field("Gmail address (SMTP user)", p.smtpUser, keyboard = KeyboardType.Email) { p = p.copy(smtpUser = it.trim()) }
            Field("App Password", p.smtpPassword, secret = true) { p = p.copy(smtpPassword = it.replace(" ", "")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(2f)) { Field("SMTP host", p.smtpHost) { p = p.copy(smtpHost = it.trim()) } }
                Column(Modifier.weight(1f)) { NumField("Port", p.smtpPort) { p = p.copy(smtpPort = it) } }
            }
            NumField("Max emails per day", p.dailyEmailCap) { p = p.copy(dailyEmailCap = it.coerceIn(1, 200)) }

            Button(
                onClick = { vm.saveProfile(p.copy(resumeFileName = Store.data.profile.resumeFileName)) },
                enabled = dirty, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
            ) { Text("Save settings") }
        }
    }

    editSearch?.let { s -> SearchDialog(s, onDismiss = { editSearch = null }) { vm.saveSearch(it); editSearch = null } }
}

@Composable
private fun Field(
    label: String, value: String, lines: Int = 1, secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = lines == 1, minLines = lines,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

@Composable
private fun NumField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Placeholders() {
    Text(
        "Placeholders: {firstName} {name} {company} {role} {jobUrl} {myName} {myHeadline} {myEmail} {myPhone} {myLinkedIn} {years} {skills} {topSkills}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SearchDialog(initial: SearchPref, onDismiss: () -> Unit, onSave: (SearchPref) -> Unit) {
    var s by remember(initial.id) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.keywords.isBlank()) "New job search" else "Edit search") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Job title / keywords (e.g. Android Developer)", s.keywords) { s = s.copy(keywords = it) }
                Field("Location (e.g. Pune, India or Remote)", s.location) { s = s.copy(location = it) }
                Text("Experience", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Experience.entries.forEach { e -> FilterChip(s.experience == e, { s = s.copy(experience = e) }, { Text(e.label) }) }
                }
                Text("Work type", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkType.entries.forEach { w -> FilterChip(s.workType == w, { s = s.copy(workType = w) }, { Text(w.label) }) }
                }
                Text("Posted within", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PostedWithin.entries.forEach { w -> FilterChip(s.postedWithin == w, { s = s.copy(postedWithin = w) }, { Text(w.label) }) }
                }
                Field("Skip titles containing (comma separated, e.g. senior, lead)", s.excludeWords) { s = s.copy(excludeWords = it) }
                NumField("Max results per run", s.maxResults) { s = s.copy(maxResults = it.coerceIn(5, 200)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(s.copy(keywords = s.keywords.trim(), location = s.location.trim())) }, enabled = s.keywords.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
