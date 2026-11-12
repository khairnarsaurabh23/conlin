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

    suspend fun test(p: Profile): String {
        Ai.complete(p, "Reply with the single word: ready", "Say ready.", maxTokens = 20)
        return "AI connection works."
    }
}
