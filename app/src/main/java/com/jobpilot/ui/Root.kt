package com.jobpilot.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobpilot.data.AppData
import com.jobpilot.data.EmailStatus
import com.jobpilot.data.JobStatus
import com.jobpilot.data.Stage
import kotlinx.coroutines.launch

@Composable
fun AppRoot(vm: AppViewModel, webView: WebView) {
    val data by vm.data.collectAsState()
    val tab by vm.tab.collectAsState()
    val openJob by vm.openJob.collectAsState()
    val showSettings by vm.showSettings.collectAsState()
    val report by vm.reportFile.collectAsState()
    val busy by vm.busy.collectAsState()
    val snack = remember { SnackbarHostState() }

    // A new message replaces whatever is showing; clearing the state lets the same text show twice.
    LaunchedEffect(Unit) {
        vm.toast.collect { msg ->
            if (msg != null) {
                vm.toastShown()
                snack.currentSnackbarData?.dismiss()
                launch { snack.showSnackbar(msg) }
            }
        }
    }

    // Full-screen pages on top of the tabs
    val reportFile = report
    val job = data.jobs.firstOrNull { it.key == openJob }
    when {
        reportFile != null -> {
            BackHandler { vm.closeReport() }
            ReportScreen(vm, reportFile, snack); return
        }
        showSettings -> {
            BackHandler { vm.showSettings(false) }
            SettingsScreen(vm, data, snack); return
        }
        job != null -> {
            BackHandler { vm.openJob(null) }
            JobDetailScreen(vm, data, job, snack); return
        }
    }

    BackHandler(enabled = tab != Tab.INBOX && tab != Tab.LINKEDIN) { vm.setTab(Tab.INBOX) }

    Page(snack, bottomBar = { TabBar(tab, waiting(data)) { vm.setTab(it) } }) {
        busy?.let { BusyBar(it) }
        Crossfade(targetState = tab, animationSpec = tween(140), label = "tab", modifier = Modifier.weight(1f)) { t ->
            when (t) {
                Tab.INBOX -> InboxScreen(vm, data, busy != null)
                Tab.TRACKER -> TrackerScreen(vm, data)
                Tab.LINKEDIN -> BrowserScreen(vm, data, webView)
                Tab.INSIGHTS -> InsightsScreen(vm, data)
            }
        }
    }
}

/** Number of things waiting for a decision or a send. */
private fun waiting(d: AppData): Int =
    d.contacts.count { it.stage == Stage.SUGGESTED || it.stage == Stage.CONNECTED || it.stage == Stage.APPROVED || it.stage == Stage.ASK_APPROVED } +
        d.emails.count { it.status == EmailStatus.DRAFT || it.status == EmailStatus.APPROVED } +
        d.jobs.count { it.status == JobStatus.NEW }

@Composable
private fun TabBar(selected: Tab, inboxCount: Int, onSelect: (Tab) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(50.dp)) {
            Tab.entries.forEach { t ->
                val sel = t == selected
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t.title.lowercase(), fontFamily = Mono, fontSize = 12.5.sp,
                                fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                                color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (t == Tab.INBOX && inboxCount > 0) {
                                Text(
                                    "(" + (if (inboxCount > 99) "99+" else "$inboxCount") + ")", fontFamily = Mono, fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BusyBar(text: String) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        Label(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}
