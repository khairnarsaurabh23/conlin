package com.jobpilot.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jobpilot.MainActivity
import com.jobpilot.core.Emails
import com.jobpilot.core.JobRunner
import com.jobpilot.data.AppData
import com.jobpilot.data.Stage
import com.jobpilot.data.Store
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/*
 * The built-in LinkedIn browser. You sign in and browse yourself. JobPilot only reads the page
 * you are looking at: when you tap Grab people or Grab emails, and a light check for LinkedIn's
 * own limit and restriction notices so outreach can be paused. It never clicks, scrolls,
 * connects or messages on its own.
 */

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
   var txt=(box.innerText||'');
   var lines=txt.split('\n').map(function(s){return s.trim();}).filter(function(s){return s.length>0;});
   var name=(a.innerText||'').split('\n')[0].trim();
   if(!name&&lines.length) name=lines[0];
   name=name.replace(/View .*?profile/i,'').replace(/[•·].*$/,'').replace(/\s+(1st|2nd|3rd\+?)$/,'').trim();
   if(!name||name.length>70||/^(linkedin member|see all|view|me)$/i.test(name)) continue;
   var deg=0; var m=txt.match(/(^|[^a-z0-9])(1st|2nd|3rd)/i);
   if(m){ deg = m[2].charAt(0)=='1'?1:(m[2].charAt(0)=='2'?2:3); }
   var head='';
   for(var k=0;k<lines.length;k++){
     var L=lines[k];
     if(L.indexOf(name)===0||/^([•·]|1st|2nd|3rd|Connect|Message|Follow|View|Pending)/i.test(L)||L.length<4) continue;
     head=L; break;
   }
   seen[h]=1; out.push({name:name,headline:head.slice(0,160),url:h,degree:deg});
 }
 return JSON.stringify(out);
})()
"""

private const val TEXT_JS =
    "(function(){return JSON.stringify({url:location.href.split('#')[0],title:document.title,text:(document.body.innerText||'').slice(0,300000)});})()"

/** Looks for LinkedIn's own warnings on the visible page. Returns "", "limit", "restricted" or "challenge". */
private const val WATCH_JS = """
(function(){
 var t=(document.body&&document.body.innerText||'').slice(0,200000).toLowerCase();
 var u=location.href;
 if(t.indexOf('reached the weekly invitation limit')>=0) return 'limit';
 if(t.indexOf('account has been restricted')>=0||t.indexOf('account is temporarily restricted')>=0||t.indexOf('your account is restricted')>=0||t.indexOf('account access has been restricted')>=0) return 'restricted';
 if(u.indexOf('/checkpoint/challenge')>=0) return 'challenge';
 return '';
})()
"""

private val json = Json { ignoreUnknownKeys = true }

/** evaluateJavascript returns the JS value JSON-encoded; our scripts return a string. */
private fun decodeJsResult(raw: String?): String? =
    runCatching { json.parseToJsonElement(raw ?: return null).jsonPrimitive.content }.getOrNull()

@Composable
fun BrowserScreen(vm: AppViewModel, data: AppData, webView: WebView) {
    val pendingUrl by vm.browserUrl.collectAsState()
    val targetKey by vm.browserJob.collectAsState()
    val tag by vm.browserTag.collectAsState()
    val target = data.jobs.firstOrNull { it.key == targetKey }
    var title by remember { mutableStateOf(webView.title ?: "LinkedIn") }
    var canBack by remember { mutableStateOf(webView.canGoBack()) }
    var loading by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(webView.settings.userAgentString == MainActivity.DESKTOP_UA) }
    var menu by remember { mutableStateOf(false) }

    var people by remember { mutableStateOf<List<CapturedPerson>?>(null) }
    var emails by remember { mutableStateOf<Pair<String, List<Emails.Found>>?>(null) }
    var profileImport by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    LaunchedEffect(Unit) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https" // keep app links (linkedin://, intent://) inside the page
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
            override fun onPageFinished(view: WebView, url: String?) {
                loading = false; title = view.title ?: url.orEmpty(); canBack = view.canGoBack()
            }
        }
        if (webView.url == null && pendingUrl == null) webView.loadUrl("https://www.linkedin.com/login")
    }
    LaunchedEffect(pendingUrl) {
        pendingUrl?.let { webView.loadUrl(it); vm.browserNavigated() }
    }
    // Restriction watch: read-only check of the visible page every few seconds.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            webView.evaluateJavascript(WATCH_JS) { raw ->
                val kind = decodeJsResult(raw).orEmpty()
                if (kind.isNotEmpty()) vm.onRestrictionSignal(kind)
            }
        }
    }
    BackHandler(enabled = canBack) { webView.goBack() }

    fun go(url: String, newTag: String = "") { vm.setBrowserTag(newTag); webView.loadUrl(url) }
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    val role = target?.let { JobRunner.roleOf(it) } ?: data.searches.firstOrNull()?.keywords.orEmpty()
    val p = data.profile

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (loading) "Loading" else title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            Quiet("Reload", { webView.reload() })
            Quiet("More", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Sign-in page") }, onClick = { menu = false; go("https://www.linkedin.com/login") })
                DropdownMenuItem(
                    text = { Text(if (desktop) "Use the mobile site" else "Use the desktop site") },
                    onClick = {
                        menu = false; desktop = !desktop
                        webView.settings.userAgentString = if (desktop) MainActivity.DESKTOP_UA else MainActivity.mobileUa(webView)
                        webView.reload()
                    }
                )
                DropdownMenuItem(text = { Text("Open this page in the LinkedIn app") }, onClick = { menu = false; webView.url?.let { vm.openUrl(it) } })
                DropdownMenuItem(text = { Text("Import my profile from this page") }, onClick = {
                    menu = false
                    webView.evaluateJavascript(TEXT_JS) { raw ->
                        val s = decodeJsResult(raw) ?: return@evaluateJavascript
                        val o = runCatching { json.parseToJsonElement(s).jsonObject }.getOrNull() ?: return@evaluateJavascript
                        val url = o["url"]?.jsonPrimitive?.content.orEmpty()
                        val t = o["title"]?.jsonPrimitive?.content.orEmpty()
                        val name = t.substringBefore(" | ").replace(Regex("^\\(\\d+\\)\\s*"), "").trim()
                        val lines = o["text"]?.jsonPrimitive?.content.orEmpty().lines().map { it.trim() }.filter { it.length > 3 }
                        val idx = lines.indexOfFirst { it == name }
                        val headline = if (idx >= 0) lines.drop(idx + 1).firstOrNull { !it.matches(Regex("(?i)(he|she|they)/.*|.*connections?$|^[\\u2022\\u00b7].*")) }.orEmpty() else ""
                        if (!url.contains("/in/")) vm.toast("Open your own profile page first")
                        else profileImport = Triple(name, headline.take(200), url.substringBefore("?"))
                    }
                })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            Quiet("My profile", { go("https://www.linkedin.com/in/me/") })
            Quiet("Messages", { go("https://www.linkedin.com/messaging/") })
            Quiet("Sent invites", { go("https://www.linkedin.com/mynetwork/invitation-manager/sent/") })
            Quiet("Connections", { go("https://www.linkedin.com/mynetwork/invite-connect/connections/") })
            Quiet("Hiring posts", { go("https://www.linkedin.com/search/results/content/?keywords=${enc("hiring $role email resume")}&sortBy=%22date_posted%22") })
            if (target != null) {
                val base = "https://www.linkedin.com/search/results/people/?keywords="
                Quiet("Same role", { go(base + enc(target.company + " " + role)) })
                if (p.college.isNotBlank()) Quiet("Alumni", { go(base + enc(target.company + " " + p.college), "alumni") })
                p.pastCompanyList().firstOrNull()?.let { pc -> Quiet("Ex-colleagues", { go(base + enc(target.company + " " + pc), "colleague") }) }
                Quiet("Recruiters", { go(base + enc(target.company + " recruiter"), "recruiter") })
            }
        }
        if (target != null) {
            Row(Modifier.padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Muted("Saving to ${JobRunner.roleOf(target)} at ${target.company}", Modifier.weight(1f), maxLines = 1)
                Quiet("Clear", { vm.setBrowserJob(null); vm.setBrowserTag("") })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        AndroidView(
            factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn("Grab people", {
                webView.evaluateJavascript(PEOPLE_JS) { raw ->
                    val arr = decodeJsResult(raw)?.let { runCatching { json.parseToJsonElement(it) as JsonArray }.getOrNull() }
                    val all = arr.orEmpty().mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        CapturedPerson(
                            o["name"]?.jsonPrimitive?.content.orEmpty(), o["headline"]?.jsonPrimitive?.content.orEmpty(),
                            o["url"]?.jsonPrimitive?.content.orEmpty(), o["degree"]?.jsonPrimitive?.intOrNull ?: 0,
                        )
                    }.filter { it.url.isNotBlank() }
                    // New people, plus invited people who now show as 1st-degree (they accepted).
                    val useful = all.filter { cp ->
                        val known = data.contacts.firstOrNull { Store.sameProfile(it.profileUrl, cp.url) }
                        known == null || (cp.degree == 1 && (known.stage == Stage.INVITED || known.stage == Stage.APPROVED || known.stage == Stage.SUGGESTED))
                    }
                    if (useful.isEmpty()) vm.toast("No new people on this page. Open a people search first.") else people = useful
                }
            }, Modifier.weight(1f).padding(end = 6.dp))
            Btn("Grab emails", {
                webView.evaluateJavascript(TEXT_JS) { raw ->
                    val s = decodeJsResult(raw) ?: return@evaluateJavascript
                    val o = runCatching { json.parseToJsonElement(s).jsonObject }.getOrNull() ?: return@evaluateJavascript
                    val url = o["url"]?.jsonPrimitive?.content.orEmpty()
                    val found = Emails.extract(o["text"]?.jsonPrimitive?.content.orEmpty())
                        .filter { f -> data.emails.none { it.to.equals(f.email, true) } }
                    if (found.isEmpty()) vm.toast("No new emails visible. Expand the posts with \"see more\", then try again.")
                    else emails = url to found
                }
            }, Modifier.weight(1f).padding(start = 6.dp))
        }
    }

    // ---- dialogs ----
    people?.let { list ->
        val checked = remember(list) { mutableStateListOf<Boolean>().apply { repeat(list.size) { add(true) } } }
        Dialog(
            title = "Add ${checked.count { it }} as suggestions", onDismiss = { people = null }, confirm = "Add",
            onConfirm = { vm.addCapturedPeople(list.filterIndexed { i, _ -> checked[i] }, targetKey, tag); people = null },
        ) {
            Column {
                Muted(
                    (if (target != null) "For ${target.company}. " else "Not linked to a job. ") +
                        "Nothing is sent: each one goes to your Inbox for approval."
                )
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(list) { i, cp ->
                        Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                            Column {
                                Text(cp.name + if (cp.degree > 0) "  (${cp.degree}${listOf("st", "nd", "rd")[cp.degree - 1]})" else "", style = MaterialTheme.typography.bodyMedium)
                                if (cp.headline.isNotBlank()) Muted(cp.headline, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }

    emails?.let { (url, list) ->
        val checked = remember(list) {
            mutableStateListOf<Boolean>().apply { list.forEach { add(Emails.looksLikeHiringAsk(it.context)) } }
        }
        Dialog(
            title = "Emails on this page", onDismiss = { emails = null }, confirm = "Create drafts",
            onConfirm = { vm.addCapturedEmails(list.filterIndexed { i, _ -> checked[i] }, url, targetKey); emails = null },
        ) {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(list) { i, f ->
                    Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }, verticalAlignment = Alignment.Top) {
                        Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                        Column(Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                            Text(f.email, style = MaterialTheme.typography.bodyMedium)
                            Muted(f.context, maxLines = 4)
                        }
                    }
                }
            }
        }
    }

    profileImport?.let { (n0, h0, u0) ->
        var n by remember(u0) { mutableStateOf(n0) }
        var h by remember(u0) { mutableStateOf(h0) }
        Dialog(
            title = "Import from LinkedIn", onDismiss = { profileImport = null }, confirm = "Save",
            onConfirm = { vm.importProfileFromPage(n, h, u0); profileImport = null },
        ) {
            Column {
                Field("Name", n) { n = it }
                Field("Headline", h, lines = 2) { h = it }
                Muted(u0)
            }
        }
    }
}
