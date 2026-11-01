@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.jobpilot.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobpilot.data.ContactStatus
import com.jobpilot.data.EmailStatus

@Composable
fun AppRoot(vm: AppViewModel, webView: WebView) {
    val data by vm.data.collectAsState()
    val tab by vm.tab.collectAsState()
    val openJob by vm.openJob.collectAsState()
    val showSettings by vm.showSettings.collectAsState()
    val report by vm.reportFile.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let { snack.showSnackbar(it); vm.toastShown() }
    }

    // Overlays (full-screen pages on top of the tabs)
    when {
        report != null -> {
            BackHandler { vm.closeReport() }
            ReportScreen(vm, report!!, snack); return
        }
        showSettings -> {
            BackHandler { vm.showSettings(false) }
            SettingsScreen(vm, data, snack); return
        }
        openJob != null -> {
            val job = data.jobs.firstOrNull { it.key == openJob }
            if (job != null) {
                BackHandler { vm.openJob(null) }
                JobDetailScreen(vm, data, job, snack); return
            }
        }
    }

    val toContact = data.contacts.count { it.status == ContactStatus.TO_CONTACT }
    val drafts = data.emails.count { it.status == EmailStatus.DRAFT || it.status == EmailStatus.APPROVED }
    val newJobs = data.jobs.count { it.status == com.jobpilot.data.JobStatus.NEW }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val badge = when (t) {
                        Tab.JOBS -> newJobs; Tab.PEOPLE -> toContact; Tab.EMAILS -> drafts; else -> 0
                    }
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { vm.setTab(t) },
                        label = { Text(t.title, fontSize = 11.sp) },
                        icon = {
                            BadgedBox(badge = { if (badge > 0) Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
                                Icon(tabIcon(t), contentDescription = t.title)
                            }
                        }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            busy?.let { BusyBar(it) }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(vm, data, busy != null)
                    Tab.JOBS -> JobsScreen(vm, data)
                    Tab.PEOPLE -> PeopleScreen(vm, data)
                    Tab.EMAILS -> EmailsScreen(vm, data)
                    Tab.BROWSER -> BrowserScreen(vm, data, webView)
                }
            }
        }
    }
}

private fun tabIcon(t: Tab): ImageVector = when (t) {
    Tab.HOME -> Icons.Default.Home
    Tab.JOBS -> Icons.Default.List
    Tab.PEOPLE -> Icons.Default.Person
    Tab.EMAILS -> Icons.Default.Email
    Tab.BROWSER -> Icons.Default.Search
}

@Composable
fun BusyBar(text: String) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            text, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
fun ScorePill(score: Int) {
    Surface(color = scoreColor(score).copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
        Text(
            "$score%", color = scoreColor(score), fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
        Text(text, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HSpace(w: Int = 8) = Spacer(Modifier.width(w.dp))
