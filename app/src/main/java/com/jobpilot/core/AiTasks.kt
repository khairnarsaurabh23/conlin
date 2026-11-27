package com.jobpilot.core

import com.jobpilot.data.AppData
import com.jobpilot.data.Contact
import com.jobpilot.data.EmailLead
import com.jobpilot.data.Job
import com.jobpilot.data.Profile
import com.jobpilot.data.TailoredResume
import com.jobpilot.net.Ai
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** The writing jobs handed to a language model: resume tailoring, emails and LinkedIn messages. */
object AiTasks {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private const val RULES =
        "You help one job seeker with their own applications. Use only facts found in the material you are given. " +
            "Never invent or inflate employers, job titles, dates, degrees, certifications, numbers, or skills. " +
            "Write plainly, the way a thoughtful person writes: no buzzwords, no exclamation marks, no emoji, no flattery. " +
            "Output exactly the format asked for and nothing else."

    @Serializable internal data class EmailOut(val subject: String = "", val body: String = "")
    @Serializable internal data class NoteOut(val note: String = "", val ask: String = "")

    private fun jsonPart(s: String): String {
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw IOException("The AI did not answer in the expected format. Try again.")
        return s.substring(a, b + 1)
    }

    /** Text of a .docx, read locally: it is a zip with the text in word/document.xml. */
    fun docxText(file: File): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: return ""
        val xml = zip.getInputStream(entry).bufferedReader().readText()
        xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>|<w:br/>"), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Plain text of the uploaded resume. A .docx is read on the phone; a PDF is read by the model. */
    suspend fun readResume(p: Profile, file: File): String {
        if (file.extension.equals("docx", true)) return docxText(file)
        if (!file.extension.equals("pdf", true)) throw IOException("Only PDF and .docx resumes can be read. Paste the text instead.")
        return Ai.complete(
            p, RULES,
            "Transcribe this resume to plain text. Keep every section, heading, date and bullet point, in the original order. " +
                "Do not summarise, correct or leave anything out. Output only the resume text.",
            pdf = file, maxTokens = 4000,
        )
    }

    /** What the model knows about the user: their resume text if available, otherwise the profile fields. */
    private fun background(p: Profile): String = buildString {
        appendLine("Name: ${p.name}")
        if (p.headline.isNotBlank()) appendLine("Headline: ${p.headline}")
        if (p.yearsExperience.isNotBlank()) appendLine("Years of experience: ${p.yearsExperience}")
        if (p.resumeText.isNotBlank()) {
            appendLine("RESUME:"); appendLine(p.resumeText.take(12000))
        } else {
            appendLine("Skills: ${p.skills}")
            if (p.experienceText.isNotBlank()) { appendLine("Experience:"); appendLine(p.experienceText) }
            if (p.educationText.isNotBlank() || p.college.isNotBlank()) appendLine("Education: ${p.educationText.ifBlank { p.college }}")
        }
    }

    private fun posting(job: Job?): String = if (job == null) "No specific posting." else buildString {
        appendLine("Title: ${job.title}")
        appendLine("Company: ${job.company}")
        if (job.location.isNotBlank()) appendLine("Location: ${job.location}")
        if (job.description.isNotBlank()) { appendLine("Description:"); appendLine(job.description.take(7000)) }
    }

    suspend fun tailor(d: AppData, job: Job, instructions: String): TailoredResume {
        val p = d.profile
        val prompt = buildString {
            appendLine("Rewrite this person's resume for the job posting below so that it fits on one page.")
            appendLine("Rules:")
            appendLine("- Keep every employer, job title, date, degree and number exactly as the resume states them.")
            appendLine("- You may reorder, cut, merge and reword. Put the experience most relevant to the posting first within each role, and cut what is irrelevant.")
            appendLine("- Reword bullets to use the posting's vocabulary only where the resume supports it. Start bullets with a verb. Keep each under 30 words.")
            appendLine("- At most 5 bullets for the most recent role, 3 for older ones, 2 per project, and 3 projects.")
            appendLine("- The summary is 2 or 3 sentences about this person's actual background as it relates to this job. Do not mention the company by name.")
            appendLine("- skills: only skills the resume shows, the ones this posting asks for first, at most 16.")
            appendLine("- changes: 3 to 6 short notes telling the person what you changed and why.")
            appendLine("- gaps: requirements in the posting that the resume does not show. Do not add them to the resume.")
            if (instructions.isNotBlank()) appendLine("The person also asks: $instructions")
            appendLine()
            appendLine("Answer with one JSON object, no code fence:")
            appendLine("""{"headline":"","summary":"","skills":[""],"experience":[{"title":"","company":"","dates":"","bullets":[""]}],"projects":[{"name":"","bullets":[""]}],"education":[""],"other":[""],"changes":[""],"gaps":[""]}""")
            appendLine()
            appendLine("JOB POSTING"); appendLine(posting(job))
            appendLine("PERSON"); append(background(p))
        }
        val out = Ai.complete(p, RULES, prompt, maxTokens = 4000)
        val tr = try {
            json.decodeFromString(TailoredResume.serializer(), jsonPart(out))
        } catch (e: IOException) { throw e } catch (e: Exception) {
            throw IOException("The AI did not answer in the expected format. Try again.")
        }
        if (tr.summary.isBlank() && tr.experience.isEmpty()) throw IOException("The AI returned an empty resume. Try again.")
        return tr.copy(byAi = true)
    }

    suspend fun email(d: AppData, lead: EmailLead, context: String): Pair<String, String> {
        val p = d.profile
        val job = d.jobs.firstOrNull { it.key == lead.jobKey }
        val prompt = buildString {
            appendLine("Write a short job application email from this person to ${lead.to}.")
            if (lead.name.isNotBlank()) appendLine("Recipient's name: ${lead.name}")
            appendLine("Role: ${lead.role.ifBlank { "not stated" }}. Company: ${lead.company.ifBlank { "not stated" }}.")
            if (lead.context.isNotBlank()) appendLine("Where the address was found: \"${lead.context}\"")
            if (context.isNotBlank()) appendLine("The person wants this included or taken into account: $context")
            appendLine("Rules:")
            appendLine("- 90 to 140 words in the body. Say which role, then two or three specific things from their background that fit this posting, then that the resume is attached.")
            appendLine("- If where the address was found asks for particular details (notice period, location, salary and so on), include only those the person has given; otherwise leave them out.")
            appendLine("- Greeting uses the recipient's first name if known, otherwise \"Hi,\". Sign off with the person's name" +
                listOf(p.phone, p.linkedinUrl).filter { it.isNotBlank() }.joinToString("") { ", then $it on its own line" } + ".")
            appendLine("- No placeholders or brackets. Plain text with line breaks.")
            appendLine("""Answer with one JSON object, no code fence: {"subject":"","body":""}""")
            appendLine()
            appendLine("JOB POSTING"); appendLine(posting(job))
            appendLine("PERSON"); append(background(p))
        }
        val o = try { json.decodeFromString(EmailOut.serializer(), jsonPart(Ai.complete(p, RULES, prompt, maxTokens = 1200))) }
        catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("The AI did not answer in the expected format. Try again.") }
        if (o.body.isBlank()) throw IOException("The AI returned an empty email. Try again.")
        return o.subject.ifBlank { lead.subject } to o.body.trim()
    }

    suspend fun note(d: AppData, c: Contact, context: String): Pair<String, String> {
        val p = d.profile
        val job = d.jobs.firstOrNull { it.key == c.jobKey }
        val limit = p.noteCharLimit
        val prompt = buildString {
            appendLine("Write two LinkedIn messages from this person to ${c.name}" + (if (c.headline.isNotBlank()) " (${c.headline})" else "") + ".")
            appendLine("Why this person is being contacted:")
            Why.lines(c, d).forEach { appendLine("- $it") }
            if (context.isNotBlank()) appendLine("The sender wants this included or taken into account: $context")
            appendLine("1. note: a connection request note of at most ${limit - 10} characters, counting spaces. It must say the concrete reason for writing (from the list above), one specific thing about the sender that is relevant, and end with a clear, small request. Do not ask for a referral yet.")
            appendLine("2. ask: the message to send after they accept, 60 to 100 words. Thank them, name the role, give two specific points of fit, and ask whether they would refer the sender or point to the right person. Offer to send the resume. Sign with the sender's first name.")
            appendLine("Use the recipient's first name. Use a short form of the job title and company name. No placeholders.")
            appendLine("""Answer with one JSON object, no code fence: {"note":"","ask":""}""")
            appendLine()
            appendLine("JOB POSTING"); appendLine(posting(job))
            appendLine("SENDER"); append(background(p))
        }
        val o = try { json.decodeFromString(NoteOut.serializer(), jsonPart(Ai.complete(p, RULES, prompt, maxTokens = 1000))) }
        catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("The AI did not answer in the expected format. Try again.") }
        if (o.note.isBlank() && o.ask.isBlank()) throw IOException("The AI returned nothing. Try again.")
        var note = o.note.trim()
        if (note.length > limit) note = Templates.dropMiddle(note, limit)
        if (note.length > limit) note = Templates.fit(note, limit)
        return note to o.ask.trim()
    }

    /** Answers for open questions on an application form, in the same order. Empty where it can't know. */
    suspend fun formAnswers(d: AppData, job: Job?, questions: List<String>): List<String> {
        val p = d.profile
        val prompt = buildString {
            appendLine("Answer these questions from a job application form, as this person, in the first person.")
            appendLine("Use only facts from the resume and saved answers below. If a question needs a fact that isn't there (a salary, a date, a personal detail), answer with an empty string.")
            appendLine("Keep answers under 120 words, or under 220 for a cover letter. Plain text, no greeting or sign-off except in a cover letter.")
            appendLine("""Answer with one JSON object, no code fence: {"answers":["", ...]} with exactly ${questions.size} strings in the same order.""")
            appendLine()
            questions.forEachIndexed { i, q -> appendLine("${i + 1}. $q") }
            appendLine()
            appendLine("JOB POSTING"); appendLine(posting(job))
            appendLine("PERSON"); append(background(p))
            val saved = p.answers.summary()
            if (saved.isNotBlank()) { appendLine("SAVED ANSWERS"); appendLine(saved) }
        }
        val out = Ai.complete(p, RULES, prompt, maxTokens = 2500)
        val arr = try {
            (json.parseToJsonElement(jsonPart(out)) as kotlinx.serialization.json.JsonObject)["answers"] as kotlinx.serialization.json.JsonArray
        } catch (e: Exception) { throw IOException("The AI did not answer in the expected format. Try again.") }
        return questions.indices.map { i -> (arr.getOrNull(i) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().trim() }
    }

    suspend fun test(p: Profile): String {
        Ai.complete(p, "Reply with the single word: ready", "Say ready.", maxTokens = 20)
        return "AI connection works."
    }

    // ---------------- Learn and posts ----------------

    @Serializable internal data class LessonOut(
        val title: String = "", val summary: String = "", val explanation: String = "",
        val howItWorks: List<String> = emptyList(), val tradeoffs: List<String> = emptyList(), val useWhen: List<String> = emptyList(),
        val quiz: List<com.jobpilot.data.QuizQ> = emptyList(), val diagram: com.jobpilot.data.Diagram = com.jobpilot.data.Diagram(),
    )

    /** Explains a topic in depth for a working engineer, with a 3-question quiz and a small architecture diagram. */
    suspend fun lesson(d: AppData, base: com.jobpilot.data.Lesson, article: String): com.jobpilot.data.Lesson {
        val p = d.profile
        val prompt = buildString {
            appendLine("Teach one topic to a software engineer with ${p.yearsExperience.ifBlank { "a few" }} years of experience who works with: ${p.skills.take(300)}.")
            if (article.isNotBlank()) {
                appendLine("Base everything on this article. Do not add claims it doesn't support.")
                appendLine("ARTICLE TITLE: ${base.sourceTitle}")
                appendLine("ARTICLE (${base.sourceSite}):"); appendLine(article.take(10000))
            } else {
                appendLine("TOPIC: ${base.title}")
                appendLine("Explain it accurately and concretely, with a realistic example system.")
            }
            appendLine(
                """
                Answer with JSON only:
                {"title": "short plain title",
                 "summary": "2 sentences: what it is and why it matters",
                 "explanation": "the core idea in depth, 150-250 words, plain language, one concrete example",
                 "howItWorks": ["4-6 steps or parts, one sentence each"],
                 "tradeoffs": ["3-4 costs or limits"],
                 "useWhen": ["2-3 situations where you'd reach for it, or avoid it"],
                 "quiz": [{"q": "question that checks understanding, not trivia", "options": ["4 options"], "answer": 0, "why": "one sentence"}],
                 "diagram": {"title": "short", "nodes": [{"id": "a", "label": "Client"}], "edges": [{"from": "a", "to": "b", "label": "request"}]}}
                Exactly 3 quiz questions; vary which option is correct. Diagram: 3 to 7 nodes, labels of 1-3 words, edges in the order data flows.
                """.trimIndent()
            )
        }
        val out = json.decodeFromString<LessonOut>(jsonPart(Ai.complete(p, RULES_LEARN, prompt, maxTokens = 3000)))
        return base.copy(
            title = out.title.ifBlank { base.title }, summary = out.summary.ifBlank { base.summary }, explanation = out.explanation,
            howItWorks = out.howItWorks, tradeoffs = out.tradeoffs, useWhen = out.useWhen,
            quiz = out.quiz.filter { it.options.size >= 2 && it.answer in it.options.indices }.take(3),
            diagram = out.diagram.copy(nodes = out.diagram.nodes.take(8)), byAi = true,
        )
    }

    private const val RULES_LEARN =
        "You explain software architecture and engineering news precisely. If you are not sure of a fact, leave it out. " +
            "Plain words, no hype, no emoji. Output exactly the format asked for and nothing else."

    val POST_FORMATS = listOf(
        "story" to "A short story: what I learned today and where it fits",
        "lessons" to "3 things I learned, each with one line of why",
        "mistake" to "A common mistake people make, and what to do instead",
        "diagram" to "A post that walks through the attached diagram",
        "carousel" to "A short intro for a 5-7 slide carousel, plus the slides",
    )

    @Serializable internal data class PostOut(val text: String = "", val slides: List<com.jobpilot.data.Slide> = emptyList())

    /** A LinkedIn post in the user's voice, built around their own angle. */
    suspend fun post(d: AppData, l: com.jobpilot.data.Lesson, format: String, tone: String = ""): Pair<String, List<com.jobpilot.data.Slide>> {
        val p = d.profile
        val desc = POST_FORMATS.firstOrNull { it.first == format }?.second ?: POST_FORMATS.first().second
        val prompt = buildString {
            appendLine("Write a LinkedIn post for ${p.name}, ${p.headline.ifBlank { "a software engineer" }}, about something they learned.")
            appendLine("FORMAT: $desc")
            appendLine("TOPIC: ${l.title}")
            if (l.summary.isNotBlank()) appendLine("SUMMARY: ${l.summary}")
            if (l.explanation.isNotBlank()) appendLine("EXPLANATION: ${l.explanation.take(2500)}")
            if (l.howItWorks.isNotEmpty()) appendLine("HOW IT WORKS: " + l.howItWorks.joinToString(" | "))
            if (l.tradeoffs.isNotEmpty()) appendLine("TRADE-OFFS: " + l.tradeoffs.joinToString(" | "))
            if (l.sourceUrl.isNotBlank()) appendLine("SOURCE: ${l.sourceSite}, \"${l.sourceTitle}\"")
            if (l.angle.isNotBlank()) appendLine("THEIR OWN TAKE (build the post around this, in their words where possible): ${l.angle}")
            if (tone.isNotBlank()) appendLine("EXTRA INSTRUCTIONS: $tone")
            appendLine(
                """
                Rules:
                - First person, like an engineer telling colleagues. Short paragraphs, varied sentence length, contractions.
                - The first line makes a specific point in under 120 characters. No question hooks, no "Here's why".
                - Never claim they built, shipped or did something unless their own take says so.
                - No emoji, no long dashes, at most 3 hashtags at the very end, no "game-changer", "delve", "unlock", "leverage", "journey", "landscape", "excited to share".
                - 120-220 words. End with one honest question or observation, not a call to like or follow.
                - If there's a source, credit it in one plain line ("Read via ...").
                Answer with JSON only: {"text": "the post", "slides": [{"title": "...", "body": "1-3 short lines"}]}
                Slides only for the carousel format (5-7 slides: a cover, the idea, how it works, trade-offs, takeaway); otherwise an empty list.
                """.trimIndent()
            )
        }
        val out = json.decodeFromString<PostOut>(jsonPart(Ai.complete(p, RULES_LEARN, prompt, maxTokens = 2000)))
        return out.text.trim() to out.slides.take(8)
    }

    /** No AI key: a plain starting point the user rewrites. */
    fun postTemplate(l: com.jobpilot.data.Lesson, format: String): String = buildString {
        appendLine(if (format == "mistake") "A mistake I see a lot with ${l.title.lowercase()}:" else "Today I spent some time on ${l.title.lowercase()}.")
        appendLine()
        if (l.summary.isNotBlank()) { appendLine(l.summary.take(300)); appendLine() }
        if (l.howItWorks.isNotEmpty()) { l.howItWorks.take(3).forEach { appendLine("- $it") }; appendLine() }
        appendLine(l.angle.ifBlank { "Where it clicked for me: (write one line from your own work)" })
        if (l.sourceUrl.isNotBlank()) { appendLine(); appendLine("Read via ${l.sourceSite}") }
    }.trim()
}
