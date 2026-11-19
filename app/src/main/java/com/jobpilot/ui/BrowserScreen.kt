package com.jobpilot.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.jobpilot.core.FormRules
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import com.jobpilot.MainActivity
import com.jobpilot.core.Emails
import com.jobpilot.core.JobRunner
import com.jobpilot.core.Why
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
 var HIRE=/hiring|looking for a|looking to hire|open role|open position|opening|join our team|join my team|vacanc|send (me |us )?(your |ur )?(cv|resume)|share (your |ur )?(cv|resume)|referr/i;
 var onPosts=/\/search\/results\/content|\/feed|\/posts\/|recent-activity/.test(location.href);
 function clean(u){return (u||'').split('?')[0].replace(/\/$/,'');}
 var as=document.querySelectorAll('a[href*="/in/"]');
 for(var i=0;i<as.length;i++){
   var a=as[i]; var h=clean(a.href);
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
   var post='',purl='';
   if(onPosts){
     var pb=a.closest('[data-urn*="activity"],[data-activity-urn],[data-id*="activity"],.feed-shared-update-v2,article');
     if(!pb){ var el=a.parentElement; for(var d=0;d<10&&el;d++){ var tl=(el.innerText||'').length; if(tl>6000) break; if(tl>=250){pb=el;break;} el=el.parentElement; } }
     if(pb){
       var first=pb.querySelector('a[href*="/in/"]');
       var pt=(pb.innerText||'');
       if(first&&clean(first.href)==h&&pt.length<8000&&HIRE.test(pt)){
         post=pt.replace(/\s+/g,' ').slice(0,1500);
         var urn=(pb.getAttribute('data-urn')||pb.getAttribute('data-activity-urn')||pb.getAttribute('data-id')||'').match(/urn:li:activity:\d+/);
         purl=urn?('https://www.linkedin.com/feed/update/'+urn[0]+'/'):location.href.split('#')[0];
       }
     }
   }
   seen[h]=1; out.push({name:name,headline:head.slice(0,160),url:h,degree:deg,post:post,postUrl:purl});
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

/** Shared by the form scripts: a field's label text, and setting a value so React-style forms notice. */
private const val JS_HELPERS = """
function jpLab(el){
  var t='';
  try{ if(el.id){ var l=document.querySelector('label[for="'+CSS.escape(el.id)+'"]'); if(l) t+=l.innerText+' '; } }catch(e){}
  var p=el.closest('label'); if(p) t+=p.innerText+' ';
  var by=el.getAttribute('aria-labelledby'); if(by){ by.split(' ').forEach(function(id){ var x=document.getElementById(id); if(x) t+=x.innerText+' '; }); }
  t+=(el.getAttribute('aria-label')||'')+' '+(el.placeholder||'');
  if(t.trim().length<3){ var c=el.parentElement; for(var i=0;i<4&&c;i++){ var x=(c.innerText||'').trim(); if(x.length>0&&x.length<300){ t+=' '+x; break; } c=c.parentElement; } }
  if(t.trim().length<3) t+=' '+(el.name||'')+' '+(el.id||'');
  return t.replace(/\s+/g,' ').trim();
}
function jpSet(el,v){
  var proto = el.tagName=='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  var d=Object.getOwnPropertyDescriptor(proto,'value'); d.set.call(el,v);
  el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); el.dispatchEvent(new Event('blur',{bubbles:true}));
}
"""

/** Fills text fields, dropdowns and Yes/No radio groups from the saved answers. Never submits. */
private const val FILL_JS = """
(function(A){
 var filled=0, missing=[];
 var els=document.querySelectorAll('input,textarea,select');
 for(var i=0;i<els.length;i++){
   var el=els[i]; var type=(el.type||'').toLowerCase();
   if(el.disabled||el.readOnly) continue;
   if(['hidden','submit','button','file','checkbox','radio','password','search','image','reset'].indexOf(type)>=0) continue;
   if(el.tagName!='SELECT' && el.value && el.value.trim()) continue;
   if(el.offsetParent===null) continue;
   var L=jpLab(el).toLowerCase(); var v=null;
   for(var r=0;r<A.rules.length;r++){ try{ if(new RegExp(A.rules[r][0],'i').test(L)){ v=A.rules[r][1]; break; } }catch(e){} }
   if(v===null){ if(el.required||/\*/.test(L)) missing.push(L.slice(0,60)); continue; }
   if(el.tagName=='SELECT'){
     if(el.selectedIndex>0) continue;
     var o=el.options, pick=-1, vl=v.toLowerCase();
     for(var k=0;k<o.length;k++){ var ot=(o[k].text||'').toLowerCase().trim(); if(ot && (ot==vl || ot.indexOf(vl)==0 || (vl.indexOf(ot)==0 && ot.length>1))){ pick=k; break; } }
     if(pick<0){ missing.push(L.slice(0,60)); continue; }
     el.selectedIndex=pick; el.dispatchEvent(new Event('change',{bubbles:true})); filled++;
   } else { jpSet(el,v); filled++; }
 }
 var groups={}, rs=document.querySelectorAll('input[type=radio]');
 for(var i=0;i<rs.length;i++){ var n=rs[i].name; if(n){ (groups[n]=groups[n]||[]).push(rs[i]); } }
 for(var n in groups){
   var g=groups[n]; if(g.some(function(x){return x.checked;})) continue;
   var fs=g[0].closest('fieldset')||(g[0].parentElement&&g[0].parentElement.parentElement&&g[0].parentElement.parentElement.parentElement);
   var Q=((fs&&fs.innerText)||'').toLowerCase().slice(0,500); var v=null;
   for(var r=0;r<A.yesno.length;r++){ try{ if(new RegExp(A.yesno[r][0],'i').test(Q)){ v=A.yesno[r][1].toLowerCase(); break; } }catch(e){} }
   if(!v) continue;
   for(var k=0;k<g.length;k++){ var t=(jpLab(g[k])+' '+(g[k].value||'')).toLowerCase(); if(new RegExp('(^|[^a-z])'+v+'([^a-z]|$)').test(t)){ g[k].click(); filled++; break; } }
 }
 var files=document.querySelectorAll('input[type=file]').length;
 return JSON.stringify({filled:filled, missing:missing.slice(0,6), files:files});
})
"""

/** Collects empty open questions (textareas, or text fields phrased as a question) for the AI. */
private const val QUESTIONS_JS = """
(function(){
 var out=[]; window.__jpQ=[];
 var els=document.querySelectorAll('input,textarea');
 for(var i=0;i<els.length;i++){
   var el=els[i]; var type=(el.type||'').toLowerCase();
   if(el.disabled||el.readOnly) continue;
   if(el.tagName=='INPUT' && type!='text' && type!='') continue;
   if(el.value && el.value.trim()) continue;
   if(el.offsetParent===null) continue;
   var L=jpLab(el);
   if(el.tagName!='TEXTAREA' && L.indexOf('?')<0 && L.length<40) continue;
   window.__jpQ.push(el); out.push(L.slice(0,300));
 }
 return JSON.stringify(out.slice(0,12));
})()
"""

private const val ANSWER_JS = """
(function(A){ var q=window.__jpQ||[], n=0; for(var i=0;i<A.length&&i<q.length;i++){ if(A[i] && !q[i].value){ jpSet(q[i],A[i]); n++; } } return ''+n; })
"""

/** True on a typical "thanks for applying" page. */
private const val APPLIED_JS = """
(function(){ var t=(document.body&&document.body.innerText||'').slice(0,20000).toLowerCase();
 return /thank(s| you)[^.]{0,40}(for applying|for your application)|application (has been |was )?(received|submitted|sent)|we('ve| have) received your application|successfully (applied|submitted)|your application is (in|complete)/.test(t)?'1':''; })()
"""

private fun fillArgs(p: com.jobpilot.data.Profile): String = buildJsonObject {
    putJsonArray("rules") { FormRules.text(p).forEach { (r, v) -> addJsonArray { add(r); add(v) } } }
    putJsonArray("yesno") { FormRules.yesNo(p).forEach { (r, v) -> addJsonArray { add(r); add(v) } } }
}.toString()

private val json = Json { ignoreUnknownKeys = true }

/** evaluateJavascript returns the JS value JSON-encoded; our scripts return a string. */
private fun decodeJsResult(raw: String?): String? =
    runCatching { json.parseToJsonElement(raw ?: return null).jsonPrimitive.content }.getOrNull()

@Composable
fun BrowserScreen(vm: AppViewModel, data: AppData, webView: WebView, pickFile: ((Uri?) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val queue by vm.applyQueue.collectAsState()
    val qIndex by vm.applyIndex.collectAsState()
    val applying = queue.getOrNull(qIndex)?.let { k -> data.jobs.firstOrNull { it.key == k } }
    var pageLoads by remember { mutableStateOf(0) }
    var fileCb by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
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
    var pageUrl by remember { mutableStateOf(webView.url.orEmpty()) }
    var saveJob by remember { mutableStateOf<List<String>?>(null) } // url, title, company, text
    val onLinkedIn = pageUrl.isBlank() || pageUrl.contains("linkedin.com")

    LaunchedEffect(Unit) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https" // keep app links (linkedin://, intent://) inside the page
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
            override fun onPageFinished(view: WebView, url: String?) {
                loading = false; title = view.title ?: url.orEmpty(); canBack = view.canGoBack(); pageUrl = url.orEmpty()
                pageLoads++
            }
        }
        // Resume upload on web forms: offer the resume (tailored for this job if there is one).
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return true
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
            // While applying, notice the "thanks for applying" page.
            val key = vm.applyKey ?: vm.browserJob.value?.takeIf { k -> Store.data.jobs.any { it.key == k && it.applyOpenedAt > 0 } }
            if (key != null && Store.data.jobs.any { it.key == key && it.appliedAt == 0L }) {
                webView.evaluateJavascript(APPLIED_JS) { raw -> if (decodeJsResult(raw) == "1") vm.markApplied(key, auto = true) }
            }
        }
    }

    fun runFill(quiet: Boolean) {
        webView.evaluateJavascript(JS_HELPERS + FILL_JS + "(" + fillArgs(Store.data.profile) + ")") { raw ->
            val o = decodeJsResult(raw)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return@evaluateJavascript
            val filled = o["filled"]?.jsonPrimitive?.intOrNull ?: 0
            val missing = (o["missing"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val files = o["files"]?.jsonPrimitive?.intOrNull ?: 0
            if (quiet && filled == 0) return@evaluateJavascript
            vm.toast(buildString {
                append(if (filled == 0) "Nothing to fill on this page" else "Filled $filled field(s)")
                if (missing.isNotEmpty()) append(". Still needed: " + missing.take(3).joinToString(", ") { it.take(30) })
                if (files > 0) append(". Tap the resume upload to attach yours")
                append(". Check it, then tap Submit.")
            })
        }
    }

    fun runAiAnswers() {
        webView.evaluateJavascript(JS_HELPERS + QUESTIONS_JS) { raw ->
            val qs = decodeJsResult(raw)?.let { runCatching { json.parseToJsonElement(it) as JsonArray }.getOrNull() }.orEmpty()
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            if (qs.isEmpty()) { vm.toast("No open questions left on this page"); return@evaluateJavascript }
            vm.aiFormAnswers(qs) { answers ->
                if (answers == null) return@aiFormAnswers
                val arg = buildJsonArray { answers.forEach { add(it) } }.toString()
                webView.evaluateJavascript(JS_HELPERS + ANSWER_JS + "(" + arg + ")") { r ->
                    val n = decodeJsResult(r)?.toIntOrNull() ?: 0
                    val skipped = answers.count { it.isBlank() }
                    vm.toast("Drafted $n answer(s)" + (if (skipped > 0) ", left $skipped for you" else "") + ". Read them before submitting.")
                }
            }
        }
    }

    // While applying, fill each page as it loads.
    LaunchedEffect(pageLoads, applying?.key) {
        if (applying != null && pageLoads > 0) { delay(1800); runFill(quiet = true) }
    }
    BackHandler(enabled = canBack) { webView.goBack() }

    fun go(url: String, newTag: String = "") { vm.setBrowserTag(newTag); webView.loadUrl(url) }
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    val role = target?.let { JobRunner.roleOf(it) } ?: data.searches.firstOrNull()?.keywords.orEmpty()
    val p = data.profile

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (loading) "loading..." else title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f),
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
            Quiet("Wellfound", { go("https://wellfound.com/jobs") })
            Quiet("YC jobs", { go("https://www.ycombinator.com/jobs") })
            Quiet("HN jobs", { go("https://news.ycombinator.com/jobs") })
            Quiet("Hiring posts", { go("https://www.linkedin.com/search/results/content/?keywords=${enc("hiring $role email resume")}&sortBy=%22date_posted%22") })
            if (target != null) {
                val base = "https://www.linkedin.com/search/results/people/?keywords="
                Quiet("Same role", { go(base + enc(target.company + " " + role)) })
                if (p.college.isNotBlank()) Quiet("Alumni", { go(base + enc(target.company + " " + p.college), "alumni") })
                p.pastCompanyList().firstOrNull()?.let { pc -> Quiet("Ex-colleagues", { go(base + enc(target.company + " " + pc), "colleague") }) }
                Quiet("Recruiters", { go(base + enc(target.company + " recruiter"), "recruiter") })
            }
        }
        if (applying != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Label("Applying ${qIndex + 1} of ${queue.size}" + if (applying.tailoredResume.isNotBlank()) " · tailored resume ready" else "")
                Text("${JobRunner.roleOf(applying)} at ${applying.company}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Btn("Fill form", { runFill(quiet = false) }, primary = true)
                    if (data.profile.aiConfigured) Btn("AI answers", { runAiAnswers() })
                    Btn(if (qIndex + 1 < queue.size) "Applied, next" else "Applied, done", { vm.applyNext(markApplied = true) })
                    Quiet("Skip", { vm.applyNext(markApplied = false) })
                    Quiet("Stop", { vm.stopApply() })
                }
            }
        } else if (target != null) {
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
            if (!onLinkedIn) Btn("Save this job", {
                webView.evaluateJavascript(TEXT_JS) { raw ->
                    val str = decodeJsResult(raw) ?: return@evaluateJavascript
                    val o = runCatching { json.parseToJsonElement(str).jsonObject }.getOrNull() ?: return@evaluateJavascript
                    val (role, company) = JobRunner.titleFromPage(o["title"]?.jsonPrimitive?.content.orEmpty())
                    saveJob = listOf(o["url"]?.jsonPrimitive?.content.orEmpty(), role, company, o["text"]?.jsonPrimitive?.content.orEmpty())
                }
            }, Modifier.weight(1f).padding(end = 6.dp), primary = true) else Btn("Grab people", {
                webView.evaluateJavascript(PEOPLE_JS) { raw ->
                    val arr = decodeJsResult(raw)?.let { runCatching { json.parseToJsonElement(it) as JsonArray }.getOrNull() }
                    val all = arr.orEmpty().mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        CapturedPerson(
                            o["name"]?.jsonPrimitive?.content.orEmpty(), o["headline"]?.jsonPrimitive?.content.orEmpty(),
                            o["url"]?.jsonPrimitive?.content.orEmpty(), o["degree"]?.jsonPrimitive?.intOrNull ?: 0,
                            o["post"]?.jsonPrimitive?.content.orEmpty(), o["postUrl"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    }.filter { it.url.isNotBlank() }
                    // New people, plus invited people who now show as 1st-degree (they accepted).
                    val useful = all.filter { cp ->
                        val known = data.contacts.firstOrNull { Store.sameProfile(it.profileUrl, cp.url) }
                        known == null || (cp.degree == 1 && (known.stage == Stage.INVITED || known.stage == Stage.APPROVED || known.stage == Stage.SUGGESTED))
                    }
                    // People who wrote a hiring post come first.
                    if (useful.isEmpty()) vm.toast("No new people on this page. Open a people search or hiring posts first.")
                    else people = useful.sortedByDescending { it.post.isNotBlank() }
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
                                val snip = remember(cp.post) { Why.snippet(cp.post, 140) }
                                if (snip.isNotBlank()) Muted("Hiring post: $snip", maxLines = 3, color = MaterialTheme.colorScheme.primary)
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

    fileCb?.let { cb ->
        val key = applying?.key ?: targetKey
        val resume = remember(key) { Store.resumeFor(key.orEmpty()) }
        fun answer(v: Array<Uri>?) { cb.onReceiveValue(v); fileCb = null }
        Dialog(
            title = "Attach a file", onDismiss = { answer(null) },
            confirm = if (resume != null) "Attach my resume" else "Choose a file",
            onConfirm = {
                if (resume != null) answer(arrayOf(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", resume)))
                else { fileCb = null; pickFile { uri -> cb.onReceiveValue(uri?.let { arrayOf(it) }) } }
            },
            dismiss = if (resume != null) "Choose another file" else "Cancel",
            onDismissButton = {
                if (resume != null) { fileCb = null; pickFile { uri -> cb.onReceiveValue(uri?.let { arrayOf(it) }) } } else answer(null)
            },
        ) {
            Muted(
                when {
                    resume == null -> "No resume is attached in the app yet."
                    resume.name.startsWith("resume_") -> "Attach the resume tailored for this job."
                    else -> "Attach your resume (${resume.extension.uppercase()})."
                }
            )
        }
    }

    saveJob?.let { v ->
        var t by remember(v) { mutableStateOf(v[1]) }
        var c by remember(v) { mutableStateOf(v[2]) }
        var l by remember(v) { mutableStateOf("") }
        Dialog(
            title = "Save this job", onDismiss = { saveJob = null }, confirm = "Save", confirmEnabled = t.isNotBlank(),
            onConfirm = { vm.saveWebJob(v[0], t, c, l, v[3]); saveJob = null },
        ) {
            Column {
                Muted("From ${com.jobpilot.net.Feeds.siteName(v[0])}. The page text is kept for matching, tailoring and emails.")
                Field("Job title", t) { t = it }
                Field("Company", c) { c = it }
                Field("Location, e.g. Remote or Bengaluru", l) { l = it }
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
