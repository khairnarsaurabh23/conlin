package com.jobpilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.jobpilot.data.Answers
import com.jobpilot.data.QA

/** The answers used to fill application forms. Everything here is editable and optional. */
@Composable
fun AnswersScreen(vm: AppViewModel, data: AppData, snack: SnackbarHostState) {
    var a by remember { mutableStateOf(data.profile.answers) }
    var name by remember { mutableStateOf(data.profile.name) }
    var email by remember { mutableStateOf(data.profile.email) }
    var phone by remember { mutableStateOf(data.profile.phone) }
    var linkedin by remember { mutableStateOf(data.profile.linkedinUrl) }
    var years by remember { mutableStateOf(data.profile.yearsExperience) }
    val p = data.profile
    val dirty = a != p.answers || name != p.name || email != p.email || phone != p.phone || linkedin != p.linkedinUrl || years != p.yearsExperience
    fun save() = vm.saveAnswers(a, name, email, phone, linkedin, years)

    Page(snack) {
        Header("Application answers", onBack = { vm.showAnswers(false) }) {
            Quiet("Save", { save() }, enabled = dirty, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Muted("Used to fill application forms. Anything left empty is left for you to fill on the form.")

            SectionTitle("Contact")
            Field("Full name", name) { name = it }
            Field("Email", email, keyboard = KeyboardType.Email) { email = it }
            Field("Phone", phone, keyboard = KeyboardType.Phone) { phone = it }
            Field("LinkedIn profile link", linkedin, keyboard = KeyboardType.Uri) { linkedin = it }
            Field("GitHub", a.github, keyboard = KeyboardType.Uri) { a = a.copy(github = it) }
            Field("Portfolio or website", a.portfolio, keyboard = KeyboardType.Uri) { a = a.copy(portfolio = it) }
            Field("Current city", a.city) { a = a.copy(city = it) }

            SectionTitle("Work")
            Field("Current company", a.currentCompany) { a = a.copy(currentCompany = it) }
            Field("Current job title", a.currentTitle) { a = a.copy(currentTitle = it) }
            Field("Years of experience", years, keyboard = KeyboardType.Number) { years = it }
            Field("Notice period, e.g. 30 days", a.noticePeriod) { a = a.copy(noticePeriod = it) }
            Field("Can start, e.g. Immediately or 1 Jan", a.startDate) { a = a.copy(startDate = it) }
            Field("Current salary (CTC)", a.currentCtc) { a = a.copy(currentCtc = it) }
            Field("Expected salary", a.expectedCtc) { a = a.copy(expectedCtc = it) }

            SectionTitle("Eligibility")
            Muted("Start with Yes or No where it fits, so Yes/No questions can be answered.")
            Field("Authorized to work, e.g. Yes, in India", a.workAuthorization) { a = a.copy(workAuthorization = it) }
            Field("Need visa sponsorship, e.g. Yes, for roles abroad", a.needsSponsorship) { a = a.copy(needsSponsorship = it) }
            Field("Willing to relocate, e.g. Yes", a.willingToRelocate) { a = a.copy(willingToRelocate = it) }
            Field("How did you hear about us", a.howHeard) { a = a.copy(howHeard = it) }
            Field("Gender (optional, leave empty to skip)", a.gender) { a = a.copy(gender = it) }

            SectionTitle("Questions you get often", trailing = "${a.custom.size}")
            Muted("Add a question as it appears on forms, or part of it, and your answer. It is matched against form labels.")
            a.custom.forEachIndexed { i, qa ->
                Panel(Modifier.padding(top = 8.dp)) {
                    Field("Question", qa.q) { v -> a = a.copy(custom = a.custom.toMutableList().also { it[i] = qa.copy(q = v) }) }
                    Field("Answer", qa.a, lines = 2) { v -> a = a.copy(custom = a.custom.toMutableList().also { it[i] = qa.copy(a = v) }) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Quiet("Remove", { a = a.copy(custom = a.custom.toMutableList().also { it.removeAt(i) }) })
                    }
                }
            }
            ButtonRow { Btn("Add a question", { a = a.copy(custom = a.custom + QA()) }) }

            ButtonRow(Modifier.padding(top = 14.dp, bottom = 40.dp)) { Btn("Save answers", { save() }, primary = true, enabled = dirty) }
            if (a == Answers()) Text("", style = MaterialTheme.typography.bodySmall)
        }
    }
}
