package com.jobpilot.ui

import android.content.Context
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun ReportScreen(vm: AppViewModel, file: File, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    Page(snack) {
        Header("Report", onBack = { vm.closeReport() }) {
            Quiet("CSV", { vm.exportCsv { shareFile(ctx, it, "text/csv") } })
            Quiet("Share", { shareFile(ctx, file, "text/html") })
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { c ->
                WebView(c).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            vm.openUrl(request.url.toString()); return true
                        }
                    }
                    loadDataWithBaseURL(null, file.readText(), "text/html", "UTF-8", null)
                }
            }
        )
    }
}

fun shareFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share ${file.name}"))
}
