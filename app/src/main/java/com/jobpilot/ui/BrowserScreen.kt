@file:OptIn(ExperimentalMaterial3Api::class)

package com.jobpilot.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jobpilot.core.Emails
import com.jobpilot.data.AppData
import com.jobpilot.data.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/*
 * The in-app LinkedIn browser. You sign in and browse yourself; JobPilot only reads the page
 * you're looking at when you tap a button ("Grab people", "Grab emails"). It never clicks,
 * scrolls, connects or messages on its own, which keeps your account safe.
 */

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

private const val PEOPLE_JS = """
(function(){
 var out=[],seen={};
 var as=document.querySelectorAll('a[href*="/in/"]');
 for(var i=0;i<as.length;i++){
   var a=as[i]; var h=(a.href||'').split('?')[0].replace(/\/$/,'');
   if(!/linkedin\.com\/in\/[^\/]+$/.test(h)) continue;
   if(/\/in\/me$/.test(h)) continue;
   if(seen[h]) continue;
   var box=a.closest('li')||(a.parentElement&&a.parentElement.parentElement)||a;
   var lines=(box.innerText||'').split('\n').map(function(s){return s.trim();}).filter(function(s){return s.length>0;});
   var name=(a.innerText||'').split('\n')[0].trim();
   if(!name&&lines.length) name=lines[0];
   name=name.replace(/View .*?profile/i,'').replace(/[•·].*$/,'').replace(/\s+(1st|2nd|3rd\+?)$/,'').trim();
   if(!name||name.length>70||/^(linkedin member|see all|view|me)$/i.test(name)) continue;
   var head='';
   for(var k=0;k<lines.length;k++){
     var L=lines[k];
     if(L.indexOf(name)===0||/^([•·]|1st|2nd|3rd|Connect|Message|Follow|View|Pending)/i.test(L)||L.length<4) continue;
     head=L; break;
   }
   seen[h]=1; out.push({name:name,headline:head.slice(0,160),url:h});
 }
 return JSON.stringify(out);
})()
"""

private const val TEXT_JS =
    "(function(){return JSON.stringify({url:location.href.split('#')[0],title:document.title,text:(document.body.innerText||'').slice(0,300000)});})()"

private val json = Json { ignoreUnknownKeys = true }

/** evaluateJavascript returns the JS value JSON-encoded; our scripts return a JSON string. */
private fun decodeJsResult(raw: String?): String? =
    runCatching { json.parseToJsonElement(raw ?: return null).jsonPrimitive.content }.getOrNull()

@Composable
fun BrowserScreen(vm: AppViewModel, data: AppData, webView: WebView) {
    val pendingUrl by vm.browserUrl.collectAsState()
    val targetKey by vm.browserJob.collectAsState()
    val target = data.jobs.firstOrNull { it.key == targetKey }
    var title by remember { mutableStateOf(webView.title ?: "LinkedIn") }
    var canBack by remember { mutableStateOf(webView.canGoBack()) }
    var loading by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(webView.settings.userAgentString == DESKTOP_UA) }
    var menu by remember { mutableStateOf(false) }

    var people by remember { mutableStateOf<List<CapturedPerson>?>(null) }
    var emails by remember { mutableStateOf<Pair<String, List<Emails.Found>>?>(null) }
    var profileImport by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    LaunchedEffect(Unit) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https" // block app-intents (linkedin://, intent://)
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
            override fun onPageFinished(view: WebView, url: String?) {
                loading = false; title = view.title ?: url.orEmpty(); canBack = view.canGoBack()
            }
        }
        if (webView.url == null && pendingUrl == null) webView.loadUrl("https://www.linkedin.com/feed/")
    }
    LaunchedEffect(pendingUrl) {
        pendingUrl?.let { webView.loadUrl(it); vm.browserNavigated() }
    }
    BackHandler(enabled = canBack) { webView.goBack() }

    fun go(url: String) = webView.loadUrl(url)
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    val role = target?.title?.split(Regex("[-–(|,]"))?.first()?.trim()
        ?: data.searches.firstOrNull()?.keywords.orEmpty()

    Column(Modifier.fillMaxSize()) {
        // Header
        Surface(tonalElevation = 2.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (loading) "Loading…" else title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { webView.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (desktop) "Switch to mobile site" else "Switch to desktop site") },
                            onClick = {
                                menu = false; desktop = !desktop
                                webView.settings.userAgentString = if (desktop) DESKTOP_UA else null
                                webView.reload()
                            }
                        )
                        DropdownMenuItem(text = { Text("Open this page in LinkedIn app") }, onClick = {
                            menu = false; webView.url?.let { vm.openUrl(it) }
                        })
                        DropdownMenuItem(text = { Text("Import my profile from this page") }, onClick = {
                            menu = false
                            webView.evaluateJavascript(TEXT_JS) { raw ->
                                val s = decodeJsResult(raw) ?: return@evaluateJavascript
                                val o = json.parseToJsonElement(s).jsonObject
                                val url = o["url"]?.jsonPrimitive?.content.orEmpty()
                                val t = o["title"]?.jsonPrimitive?.content.orEmpty()
                                val name = t.substringBefore(" | ").replace(Regex("^\\(\\d+\\)\\s*"), "").trim()
                                val lines = o["text"]?.jsonPrimitive?.content.orEmpty().lines().map { it.trim() }.filter { it.length > 3 }
                                val idx = lines.indexOfFirst { it == name }
                                val headline = if (idx >= 0) lines.drop(idx + 1).firstOrNull { !it.matches(Regex("(?i)(he|she|they)/.*|.*connections?$|^[•·].*")) }.orEmpty() else ""
                                if (!url.contains("/in/")) vm.toast("Open your own profile page first (tap \"My profile\")")
                                else profileImport = Triple(name, headline.take(200), url.substringBefore("?"))
                            }
                        })
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(onClick = { go("https://www.linkedin.com/feed/") }, label = { Text("Feed") })
                    AssistChip(onClick = { go("https://www.linkedin.com/in/me/") }, label = { Text("My profile") })
                    AssistChip(onClick = { go("https://www.linkedin.com/messaging/") }, label = { Text("Messages") })
                    AssistChip(onClick = { go("https://www.linkedin.com/mynetwork/invitation-manager/") }, label = { Text("Invitations") })
                    if (target != null) {
                        AssistChip(onClick = {
                            go("https://www.linkedin.com/search/results/people/?keywords=${enc(target.company + " " + role)}")
                        }, label = { Text("People @ ${target.company.take(18)}") })
                        AssistChip(onClick = {
                            go("https://www.linkedin.com/search/results/people/?keywords=${enc(target.company + " recruiter")}")
                        }, label = { Text("Recruiters @ ${target.company.take(14)}") })
                    }
                    AssistChip(onClick = {
                        go("https://www.linkedin.com/search/results/content/?keywords=${enc("hiring $role email resume")}&sortBy=%22date_posted%22")
                    }, label = { Text("Hiring posts") })
                }
                if (target != null) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Saving to: ${target.title} @ ${target.company}", style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { vm.setBrowserJob(null) }) { Icon(Icons.Default.Close, "Clear job") }
                    }
                }
            }
        }

        AndroidView(
            factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        // Capture actions
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(modifier = Modifier.weight(1f), onClick = {
                    webView.evaluateJavascript(PEOPLE_JS) { raw ->
                        val s = decodeJsResult(raw)
                        val arr = s?.let { runCatching { json.parseToJsonElement(it) as JsonArray }.getOrNull() }
                        val list = arr.orEmpty().mapNotNull { el ->
                            val o = el as? JsonObject ?: return@mapNotNull null
                            CapturedPerson(
                                o["name"]?.jsonPrimitive?.content.orEmpty(),
                                o["headline"]?.jsonPrimitive?.content.orEmpty(),
                                o["url"]?.jsonPrimitive?.content.orEmpty(),
                            )
                        }.filter { p -> p.url.isNotBlank() && data.contacts.none { Store.sameProfile(it.profileUrl, p.url) } }
                        if (list.isEmpty()) vm.toast("No new people on this page. Open a people search first.")
                        else people = list
                    }
                }) { Text("Grab people") }
                FilledTonalButton(modifier = Modifier.weight(1f), onClick = {
                    webView.evaluateJavascript(TEXT_JS) { raw ->
                        val s = decodeJsResult(raw) ?: return@evaluateJavascript
                        val o = runCatching { json.parseToJsonElement(s).jsonObject }.getOrNull() ?: return@evaluateJavascript
                        val url = o["url"]?.jsonPrimitive?.content.orEmpty()
                        val found = Emails.extract(o["text"]?.jsonPrimitive?.content.orEmpty())
                            .filter { f -> data.emails.none { it.to.equals(f.email, true) } }
                        if (found.isEmpty()) vm.toast("No new emails visible. Tap \"…see more\" on posts, then try again.")
                        else emails = url to found
                    }
                }) { Text("Grab emails") }
            }
        }
    }

    // ---- dialogs ----
    people?.let { list ->
        val checked = remember(list) { mutableStateListOf<Boolean>().apply { repeat(list.size) { add(true) } } }
        AlertDialog(
            onDismissRequest = { people = null },
            title = { Text("Add ${checked.count { it }} people" + if (target != null) " for ${target.company}" else "") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    itemsIndexed(list) { i, p ->
                        Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                            Column {
                                Text(p.name, fontWeight = FontWeight.SemiBold)
                                if (p.headline.isNotBlank()) Text(p.headline, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.addCapturedPeople(list.filterIndexed { i, _ -> checked[i] }, targetKey); people = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { people = null }) { Text("Cancel") } }
        )
    }

    emails?.let { (url, list) ->
        val checked = remember(list) {
            mutableStateListOf<Boolean>().apply { list.forEach { add(Emails.looksLikeHiringAsk(it.context)) } }
        }
        AlertDialog(
            onDismissRequest = { emails = null },
            title = { Text("Emails on this page") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    itemsIndexed(list) { i, f ->
                        Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }, verticalAlignment = Alignment.Top) {
                            Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                            Column(Modifier.padding(top = 10.dp)) {
                                Text(f.email, fontWeight = FontWeight.SemiBold)
                                Text(f.context, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.addCapturedEmails(list.filterIndexed { i, _ -> checked[i] }, url, targetKey); emails = null
                }) { Text("Create drafts") }
            },
            dismissButton = { TextButton(onClick = { emails = null }) { Text("Cancel") } }
        )
    }

    profileImport?.let { (n0, h0, u0) ->
        var n by remember(u0) { mutableStateOf(n0) }
        var h by remember(u0) { mutableStateOf(h0) }
        AlertDialog(
            onDismissRequest = { profileImport = null },
            title = { Text("Import from LinkedIn") },
            text = {
                Column {
                    OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(h, { h = it }, label = { Text("Headline") })
                    Text(u0, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    Text("Add your skills in Settings — they drive match scores.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { Button(onClick = { vm.importProfileFromPage(n, h, u0); profileImport = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { profileImport = null }) { Text("Cancel") } }
        )
    }
}
