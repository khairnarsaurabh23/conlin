package com.jobpilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobpilot.core.Guard
import com.jobpilot.core.Insights
import com.jobpilot.core.MailScan
import com.jobpilot.core.TemplateStat
import com.jobpilot.data.AppData
import java.text.DateFormat
import java.util.Date

@Composable
fun InsightsScreen(vm: AppViewModel, data: AppData) {
    val ctx = LocalContext.current
    val now = remember(data) { System.currentTimeMillis() }
    val ins = remember(data) { Insights.of(data, now) }
    val usage = Guard.inviteUsage(data, now)
    val busy by vm.busy.collectAsState()
    val p = data.profile

    Column(Modifier.fillMaxSize()) {
        Header("Insights")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            SectionTitle("Jobs", Modifier.padding(top = 0.dp))
            Funnel(listOf("Found" to ins.jobsFound, "Kept" to ins.jobsKept, "Applied or asked" to ins.applied, "Interview" to ins.interviews))
            if (ins.rejected > 0) Muted("${ins.rejected} rejected", Modifier.padding(top = 4.dp))

            SectionTitle("Referrals")
            Funnel(listOf("Invited" to ins.invited, "Accepted" to ins.accepted, "Asked" to ins.asked, "Replied" to ins.replied, "Referred" to ins.referred))
            Muted(
                listOfNotNull(
                    ins.acceptanceRate?.let { "$it% of invites accepted" } ?: "Acceptance rate appears after 5 invites are a week old",
                    ins.askReplyRate?.let { "$it% of asks answered" },
                ).joinToString(". "), Modifier.padding(top = 6.dp)
            )

            SectionTitle("Recruiter emails")
            Funnel(listOf("Sent" to ins.emailsSent, "Replied" to ins.emailReplies, "Interview" to ins.emailInterviews))
            Muted(
                listOfNotNull(
                    ins.emailReplyRate?.let { "$it% reply rate" },
                    ins.medianReplyHours?.let { "typical reply after " + if (it < 48) "$it hours" else "${it / 24} days" },
                    if (ins.emailRejections > 0) "${ins.emailRejections} rejections" else null,
                    if (ins.bounced > 0) "${ins.bounced} bounced" else null,
                ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifBlank { "Reply tracking fills in once emails are sent." },
                Modifier.padding(top = 6.dp)
            )

            val templates = (ins.emailTemplates + ins.noteTemplates).filter { it.sent > 0 }
            if (templates.size > 1) {
                SectionTitle("Which wording works")
                templates.forEach { TemplateRow(it) }
                Muted("Add a second version of a template in Settings to compare them.", Modifier.padding(top = 4.dp))
            }

            if (ins.respondingCompanies.isNotEmpty()) {
                SectionTitle("Companies that answered")
                ins.respondingCompanies.forEach { (name, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Muted("$n")
                    }
                }
            }

            SectionTitle("Account safety")
            Meter("Connection requests, last 24 hours", usage.today, usage.limits.day)
            Gap(10)
            Meter("Last 7 days", usage.week, usage.limits.week)
            Muted(
                (Guard.pauseText(data, now) ?: usage.limits.note.ifBlank { "Limits are set in Settings. LinkedIn's own weekly ceiling is around 100 for established accounts." }),
                Modifier.padding(top = 6.dp)
            )

            SectionTitle("Gmail reply tracking")
            if (!p.smtpConfigured) {
                Muted("Add your Gmail address and an App Password in Settings. JobPilot then reads your inbox (read-only, on this phone) to spot accepted invites, replies, interview mails, rejections and bounces.")
                ButtonRow { Btn("Open Settings", { vm.showSettings(true) }) }
            } else {
                val s = data.mailScan
                Muted(
                    if (s.at == 0L && s.error.isBlank()) "Not checked yet."
                    else (if (s.at > 0) "Last check " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.at)) + ". " else "") + MailScan.summary(s)
                )
                ButtonRow { Btn("Check inbox now", { vm.scanMail() }, enabled = busy == null) }
            }

            SectionTitle("Report")
            Muted("Everything in one document: searches, top matches, people, emails and run history.")
            ButtonRow {
                Btn("Open report", { vm.buildReport() }, primary = true, enabled = busy == null)
                Btn("Export jobs as CSV", { vm.exportCsv { f -> shareFile(ctx, f, "text/csv") } })
            }
            Gap(32)
        }
    }
}

/** Plain funnel: label, count, and a bar proportional to the first step. */
@Composable
private fun Funnel(steps: List<Pair<String, Int>>) {
    val top = steps.maxOf { it.second }.coerceAtLeast(1)
    steps.forEach { (label, n) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(132.dp))
            Box(Modifier.weight(1f).height(8.dp)) {
                Box(
                    Modifier.fillMaxWidth((n.toFloat() / top).coerceIn(0.01f, 1f)).height(8.dp)
                        .background(if (n == 0) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary)
                )
            }
            Text("$n", fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.width(44.dp).padding(start = 10.dp))
        }
    }
}

@Composable
private fun TemplateRow(t: TemplateStat) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(t.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Muted("${t.positive} of ${t.sent}" + (t.rate?.let { "  ($it%)" } ?: ""))
    }
}
