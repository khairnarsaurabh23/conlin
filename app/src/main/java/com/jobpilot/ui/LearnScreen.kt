package com.jobpilot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobpilot.core.AiTasks
import com.jobpilot.core.Learn
import com.jobpilot.data.AppData
import com.jobpilot.data.Lesson
import com.jobpilot.data.LessonStatus
import com.jobpilot.data.QuizQ
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LearnScreen(vm: AppViewModel, data: AppData, busy: Boolean) {
    val now = remember(data) { System.currentTimeMillis() }
    val p = data.profile
    val today = Learn.today(data, now)
    val reviews = Learn.reviewsDue(data, now).filter { it.id != today?.id }
    val ready = data.lessons.filter { it.status == LessonStatus.POST_READY }.sortedByDescending { it.createdAt }
    val learned = data.lessons.filter { it.status == LessonStatus.LEARNED && it.id != today?.id }.sortedByDescending { it.learnedAt }
    val posted = data.lessons.filter { it.status == LessonStatus.POSTED }.sortedByDescending { it.postedAt }

    Column(Modifier.fillMaxSize()) {
        Header("Learn") { Quiet("Settings", { vm.showSettings(true) }) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            val total = data.lessons.count { it.learnedAt > 0 }
            Muted(
                (if (p.learnEnabled) "A new topic every day at ${p.learnTime}. " else "") +
                    (if (total > 0) "$total learned, ${posted.size} posted. " else "") +
                    Learn.postSlot(data, now)
            )
            Gap(10)

            if (today == null || today.status == LessonStatus.SKIPPED) {
                Panel {
                    Label("today")
                    Text("No topic yet today", style = MaterialTheme.typography.titleMedium)
                    Muted("Concepts are picked from what your saved jobs ask for; news comes from engineering blogs and Hacker News.")
                    ButtonRow {
                        Btn("Get today's topic", { vm.learnToday() }, primary = true, enabled = !busy)
                        Quiet("A concept", { vm.learnToday(news = false) }, enabled = !busy)
                        Quiet("Some news", { vm.learnToday(news = true) }, enabled = !busy)
                    }
                    if (!p.aiConfigured) Muted("Add an AI key in Settings for in-depth explanations, quizzes, diagrams and post drafts.", Modifier.padding(top = 6.dp), color = warn)
                }
            } else {
                LessonCard(vm, data, today, busy, open = true)
            }

            if (reviews.isNotEmpty()) {
                SectionTitle("Review", trailing = "${reviews.size}")
                Muted("A quick quiz on things you learned, spaced out so they stick.")
                reviews.take(3).forEach { l ->
                    Gap(8)
                    Panel {
                        Label("review · ${dayText(l.learnedAt)}")
                        Text(l.title, style = MaterialTheme.typography.titleSmall)
                        Quiz(l.quiz, "Done") { score -> vm.reviewDone(l.id, score) }
                    }
                }
            }

            if (ready.isNotEmpty()) {
                SectionTitle("Ready to post", trailing = "${ready.size}")
                ready.forEach { l -> Gap(8); PostCard(vm, data, l, busy) }
            }

            if (learned.isNotEmpty()) {
                SectionTitle("Learned, not posted", trailing = "${learned.size}")
                learned.take(6).forEach { l ->
                    Gap(8)
                    var open by remember(l.id) { mutableStateOf(false) }
                    Panel(onClick = { open = !open }) {
                        Label(dayText(l.learnedAt))
                        Text(l.title, style = MaterialTheme.typography.titleSmall)
                        if (open) LessonCard(vm, data, l, busy, open = true, nested = true)
                    }
                }
            }

            if (posted.isNotEmpty()) {
                SectionTitle("Posted", trailing = "${posted.size}")
                bestFormat(posted)?.let { Muted(it); Gap(4) }
                posted.take(10).forEach { l -> Gap(8); PostedCard(vm, l) }
            }
        }
    }
}

private fun dayText(t: Long) = if (t <= 0) "" else SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))

private fun bestFormat(posted: List<Lesson>): String? {
    val withStats = posted.filter { it.views > 0 }
    if (withStats.size < 3) return "Add views to a few posts and this shows which formats work best for you."
    val by = withStats.groupBy { it.format.ifBlank { "story" } }.mapValues { (_, v) -> v.sumOf { it.views } / v.size }
    val best = by.maxByOrNull { it.value } ?: return null
    val name = AiTasks.POST_FORMATS.firstOrNull { it.first == best.key }?.first ?: best.key
    return "Your ${formatLabel(name)} posts do best, about ${best.value} views each."
}

private fun formatLabel(f: String) = when (f) {
    "story" -> "story"; "lessons" -> "3 lessons"; "mistake" -> "common mistake"; "diagram" -> "diagram"; "carousel" -> "carousel"; else -> f
}

@Composable
private fun LessonCard(vm: AppViewModel, data: AppData, l: Lesson, busy: Boolean, open: Boolean, nested: Boolean = false) {
    val body: @Composable () -> Unit = {
        if (!nested) {
            Label(if (l.kind == "news") "today · ${l.sourceSite.ifBlank { "news" }}" else "today · concept")
            Text(l.title, style = MaterialTheme.typography.titleMedium)
            if (l.why.isNotBlank()) Muted(l.why)
        }
        if (l.summary.isNotBlank()) Text(l.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        if (open && l.explanation.isNotBlank()) {
            Text(l.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (open && l.howItWorks.isNotEmpty()) {
            Label("how it works", Modifier.padding(top = 10.dp, bottom = 2.dp))
            l.howItWorks.forEachIndexed { i, s -> Mark("${i + 1}.", s, MaterialTheme.colorScheme.primary, maxLines = 6) }
        }
        if (open && l.tradeoffs.isNotEmpty()) {
            Label("trade-offs", Modifier.padding(top = 10.dp, bottom = 2.dp))
            l.tradeoffs.forEach { Mark("-", it, MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5) }
        }
        if (open && l.useWhen.isNotEmpty()) {
            Label("use it when", Modifier.padding(top = 10.dp, bottom = 2.dp))
            l.useWhen.forEach { Mark("+", it, ok, maxLines = 5) }
        }
        if (open && !l.diagram.isEmpty) {
            val bmp = remember(l.id, l.diagram) { vm.diagramBitmap(l).asImageBitmap() }
            Image(bmp, "Diagram", Modifier.padding(top = 10.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (l.sourceUrl.isNotBlank()) Quiet("Read the article", { vm.openInBrowse(l.sourceUrl) })
            if (!l.byAi && data.profile.aiConfigured) Quiet("Explain in depth", { vm.explainAgain(l) }, enabled = !busy)
        }

        when (l.status) {
            LessonStatus.NEW, LessonStatus.SKIPPED -> {
                if (l.quiz.isNotEmpty()) {
                    Label("check yourself", Modifier.padding(top = 10.dp, bottom = 2.dp))
                    Quiz(l.quiz, "Mark learned") { score -> vm.markLearned(l.id, score) }
                } else {
                    ButtonRow { Btn("Mark learned", { vm.markLearned(l.id, -1) }, primary = true) }
                }
                ButtonRow {
                    Quiet("Another topic", { vm.learnToday(another = true) }, enabled = !busy)
                    Quiet("Skip today", { vm.skipLesson(l.id) })
                }
            }
            LessonStatus.LEARNED -> PostComposer(vm, data, l, busy)
            else -> {}
        }
    }
    if (nested) Column { body() } else Panel { body() }
}

@Composable
private fun Quiz(quiz: List<QuizQ>, doneLabel: String, onDone: (Int) -> Unit) {
    val picked = remember(quiz) { mutableStateMapOf<Int, Int>() }
    quiz.forEachIndexed { qi, q ->
        Text("${qi + 1}. ${q.q}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        val choice = picked[qi]
        q.options.forEachIndexed { oi, o ->
            val cs = MaterialTheme.colorScheme
            val color = when {
                choice == null -> cs.outlineVariant
                oi == q.answer -> ok
                oi == choice -> cs.error
                else -> cs.outlineVariant
            }
            Text(
                o, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, color, RoundedCornerShape(10.dp))
                    .clickable(enabled = choice == null) { picked[qi] = oi }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
        if (choice != null && q.why.isNotBlank()) Muted((if (choice == q.answer) "Right. " else "Not quite. ") + q.why, Modifier.padding(top = 2.dp))
    }
    if (picked.size == quiz.size) {
        val score = quiz.indices.count { picked[it] == quiz[it].answer }
        Gap(8)
        Text("$score of ${quiz.size} right", style = MaterialTheme.typography.titleSmall)
        if (score * 3 < quiz.size * 2) Muted("Worth another read. You can still mark it.")
        ButtonRow { Btn(doneLabel, { onDone(score) }, primary = true) }
    }
}

@Composable
private fun FormatChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text, style = MaterialTheme.typography.bodyMedium,
        color = when { !enabled -> cs.outlineVariant; selected -> cs.onPrimary; else -> cs.onSurfaceVariant },
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) cs.primary else Color.Transparent)
            .border(1.dp, if (selected) cs.primary else cs.outlineVariant, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun PostComposer(vm: AppViewModel, data: AppData, l: Lesson, busy: Boolean) {
    var angle by remember(l.id) { mutableStateOf(l.angle) }
    var format by remember(l.id) { mutableStateOf(l.format.ifBlank { if (!l.diagram.isEmpty) "diagram" else "story" }) }
    Label("turn it into a post", Modifier.padding(top = 12.dp, bottom = 2.dp))
    Muted("One line from you makes it sound like you. Where have you seen this at work, or where would you use it?")
    Field("Your take", angle, lines = 3) { angle = it }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AiTasks.POST_FORMATS.forEach { (key, _) ->
            FormatChip(formatLabel(key), format == key, enabled = key != "diagram" || !l.diagram.isEmpty) { format = key }
        }
    }
    Muted(AiTasks.POST_FORMATS.firstOrNull { it.first == format }?.second.orEmpty())
    ButtonRow {
        Btn("Draft post", { vm.draftPost(l.id, angle, format) }, primary = true, enabled = !busy)
    }
    if (angle.isBlank()) Muted("Leaving your take empty works, but the post will be more generic.", color = warn)
}

@Composable
private fun PostCard(vm: AppViewModel, data: AppData, l: Lesson, busy: Boolean) {
    var text by remember(l.id, l.postText) { mutableStateOf(l.postText) }
    var shared by remember(l.id) { mutableStateOf(false) }
    var url by remember(l.id) { mutableStateOf("") }
    var tone by remember(l.id) { mutableStateOf("") }
    var redo by remember(l.id) { mutableStateOf(false) }
    val issues = remember(text) { Learn.humanCheck(text) }
    Panel {
        Label("${formatLabel(l.format.ifBlank { "story" })} post · ${dayText(l.createdAt)}")
        Text(l.title, style = MaterialTheme.typography.titleSmall)
        Field("Post", text, lines = 8) { text = it }
        Muted("${text.length} characters")
        issues.forEach { Mark("!", it, warn, maxLines = 2) }
        if (l.format == "diagram" && !l.diagram.isEmpty) {
            val bmp = remember(l.id, l.diagram) { vm.diagramBitmap(l).asImageBitmap() }
            Image(bmp, "Diagram", Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
        }
        if (l.format == "carousel" && l.slides.isNotEmpty()) {
            Label("${l.slides.size} slides" + if (!l.diagram.isEmpty) " + diagram" else "", Modifier.padding(top = 8.dp))
            l.slides.forEachIndexed { i, s -> Mark("${i + 1}.", s.title, MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
        ButtonRow {
            Btn("Share to LinkedIn", { vm.savePost(l.id, text); vm.sharePost(l.copy(postText = text)); shared = true }, primary = true, enabled = text.isNotBlank())
            Quiet("Save", { vm.savePost(l.id, text) })
            Quiet("Redraft", { redo = !redo })
        }
        if (redo) {
            Field("What to change (optional), e.g. shorter, more personal", tone) { tone = it }
            ButtonRow { Btn("Write it again", { vm.draftPost(l.id, l.angle, l.format, tone); redo = false }, enabled = !busy) }
        }
        if (shared) {
            Gap(6)
            Text("Posted it?", style = MaterialTheme.typography.bodyMedium)
            Field("Post link (optional)", url) { url = it }
            ButtonRow { Btn("Mark posted", { vm.markPosted(l.id, url) }, primary = true) }
        }
    }
}

@Composable
private fun PostedCard(vm: AppViewModel, l: Lesson) {
    var edit by remember(l.id) { mutableStateOf(false) }
    var views by remember(l.id) { mutableIntStateOf(l.views) }
    var reactions by remember(l.id) { mutableIntStateOf(l.reactions) }
    Panel {
        Label("${formatLabel(l.format.ifBlank { "story" })} · ${dayText(l.postedAt)}")
        Text(l.title, style = MaterialTheme.typography.titleSmall)
        Muted(if (l.views > 0 || l.reactions > 0) "${l.views} views · ${l.reactions} reactions" else "No stats yet")
        if (edit) {
            Row {
                NumField("Views", views, Modifier.weight(1f).padding(end = 6.dp)) { views = it.coerceAtLeast(0) }
                NumField("Reactions", reactions, Modifier.weight(1f).padding(start = 6.dp)) { reactions = it.coerceAtLeast(0) }
            }
            ButtonRow { Btn("Save", { vm.setPostStats(l.id, views, reactions); edit = false }, primary = true) }
        } else {
            ButtonRow {
                Quiet("Add stats", { edit = true })
                if (l.postUrl.isNotBlank()) Quiet("Open post", { vm.openInBrowse(l.postUrl) })
                else Quiet("My posts", { vm.openInBrowse("https://www.linkedin.com/in/me/recent-activity/all/") })
            }
        }
    }
}
