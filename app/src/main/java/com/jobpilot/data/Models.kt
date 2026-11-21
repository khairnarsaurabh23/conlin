package com.jobpilot.data

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val name: String = "",
    val headline: String = "",
    val email: String = "",
    val phone: String = "",
    val linkedinUrl: String = "",
    /** Comma-separated skills, used for match scoring, tailoring and templates. */
    val skills: String = "",
    val yearsExperience: String = "",
    val college: String = "",
    /** Comma-separated previous employers, used to rank ex-colleagues first. */
    val pastCompanies: String = "",
    /** Free text used when building a tailored resume PDF. */
    val experienceText: String = "",
    val educationText: String = "",
    /** File name of the resume copied into app storage (empty = none). */
    val resumeFileName: String = "",

    // Job sources
    val useLinkedIn: Boolean = true,
    val useJSearch: Boolean = false,
    val jsearchApiKey: String = "",
    /** Monthly Hacker News "Who is hiring?" thread: mostly startups, many remote or with visa support. */
    val useHackerNews: Boolean = true,
    /** Remotive: remote-only jobs. */
    val useRemotive: Boolean = true,
    /** Arbeitnow: jobs in Europe, many in English and with visa sponsorship. */
    val useArbeitnow: Boolean = true,
    /** Careers pages on Ashby, Lever or Greenhouse (links or "ashby:name"), one per line. */
    val startupBoards: String = "",
    /** Used to tell remote-for-you from remote-elsewhere, and home from abroad. */
    val homeCountry: String = "India",

    // Mail (Gmail app password is used for both sending and the read-only reply scan)
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val imapHost: String = "imap.gmail.com",
    val smtpUser: String = "",
    val smtpPassword: String = "",
    val dailyEmailCap: Int = 20,
    val mailScanEnabled: Boolean = true,

    // Templates. "B" variants are optional; when filled the app alternates and compares reply rates.
    val noteTemplateA: String = DEFAULT_NOTE,
    val noteTemplateB: String = "",
    val noteCharLimit: Int = 200,
    val askTemplate: String = DEFAULT_ASK,
    val emailSubjectA: String = DEFAULT_SUBJECT,
    val emailBodyA: String = DEFAULT_BODY,
    val emailSubjectB: String = "",
    val emailBodyB: String = "",
    val followUpDays: Int = 5,
    val staleInviteDays: Int = 14,

    // Account safety
    val dailyInviteCap: Int = 15,
    val weeklyInviteCap: Int = 60,
    val dailyMessageCap: Int = 20,
    val minGapMinutes: Int = 3,
    val rampUp: Boolean = true,
    /** true = profiles open in the LinkedIn app, false = in the built-in browser. */
    val openInLinkedInApp: Boolean = false,
    /** Messages you can send to people you aren't connected to, per month (InMail or free ones). */
    val directPerMonth: Int = 3,
    /** Premium: InMail credits come back when the person replies within 90 days. */
    val premium: Boolean = false,

    // Send session: approved LinkedIn sends wait in a queue; one reminder a day at [sessionTime].
    val paceEnabled: Boolean = true,
    val sessionTime: String = "10:00",
    val paceMinutes: Int = 20,
    val paceStartHour: Int = 10,
    val paceEndHour: Int = 19,
    /** Approved emails go out by themselves, one every [emailDripMinutes], within the same hours. */
    val emailDrip: Boolean = true,
    val emailDripMinutes: Int = 30,
    /** Remind to follow up on an application after this many days without a reply. */
    val appFollowDays: Int = 7,

    // Background checks
    val autoCheckEnabled: Boolean = true,
    val autoCheckHours: Int = 12,
    val detailFetchLimit: Int = 15,
    /** New jobs scoring below this are kept out of the inbox (still listed in the Tracker). */
    val minMatch: Int = 40,
    /** Answers used to fill application forms. Edited in the app. */
    val answers: Answers = Answers(),
    /** Send email applications with no review when the match is at least [autoSendMin] and the resume is ready. */
    val autoSendEmails: Boolean = false,
    val autoSendMin: Int = 75,
    /** "dark", "light" or "system". */
    val theme: String = "dark",

    // AI writing (optional). The key stays on the phone; requests go straight to the provider.
    /** "anthropic" or "openai" (any OpenAI-compatible endpoint). */
    val aiProvider: String = "anthropic",
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    val aiKey: String = "",
    /** Plain text of the uploaded resume: read by the AI from a PDF, extracted from a .docx, or pasted. */
    val resumeText: String = "",
) {
    val aiConfigured: Boolean get() = aiKey.isNotBlank()

    fun skillList(): List<String> = splitList(skills)
    fun pastCompanyList(): List<String> = splitList(pastCompanies)

    val smtpConfigured: Boolean get() = smtpUser.isNotBlank() && smtpPassword.isNotBlank()

    companion object {
        fun splitList(s: String): List<String> =
            s.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }

        // {opening} is filled with the concrete reason for writing: their hiring post, the posting
        // they are named on, or the openings at their company.
        const val DEFAULT_NOTE =
            "Hi {firstName}, {opening}. I have {experience} with {topSkills}. Would you be open to a quick chat about it?"

        /** Default shipped in 2.1. */
        const val V21_NOTE =
            "Hi {firstName}, {opening}. I work with {topSkills} and would like to connect."
        const val DEFAULT_ASK =
            "Hi {firstName}, thanks for connecting. {opening}. I'm applying, and I have {years} of experience with " +
                "{topSkills}. If it looks like a reasonable fit, would you be open " +
                "to referring me or pointing me to the right person? I can send my resume. Thanks either way.\n{myName}"

        // Framing by who you're writing to, used while the templates above are left at their defaults.
        const val NOTE_RECRUITER =
            "Hi {firstName}, {opening}. I have {experience} with {topSkills}. Could I share my resume with you for this role?"
        const val NOTE_MANAGER =
            "Hi {firstName}, {opening}. I have {experience} with {topSkills} and would value a quick word about what the team is building."
        const val ASK_RECRUITER =
            "Hi {firstName}, thanks for connecting. {opening}. I have {experience} with {topSkills}. Could you pass my profile to the hiring team, " +
                "or tell me the best way to apply? I can send my resume. Thanks.\n{myName}"
        const val ASK_MANAGER =
            "Hi {firstName}, thanks for connecting. {opening}. I've worked on {topSkills} for {experience}, which looks close to what your team needs. " +
                "If you think I'd fit, a referral or a word with the recruiter would mean a lot. Happy to send my resume.\n{myName}"
        const val ASK_KNOWN =
            "Hi {firstName}, {opening}. I'm applying, and I have {experience} with {topSkills}. Would you be comfortable referring me, " +
                "or pointing me to the right person? I can send my resume and the job link. Thanks a lot.\n{myName}"

        /** Defaults shipped in 2.0, recognised so untouched templates can be upgraded. */
        const val OLD_NOTE =
            "Hi {firstName}, I came across the {role} opening at {company} and your profile. " +
                "I work with {topSkills} and would like to connect."
        const val OLD_ASK =
            "Hi {firstName}, thanks for connecting. I'm applying for the {role} role at {company}. " +
                "I have {years} of experience with {topSkills}. If it looks like a reasonable fit, would you be open " +
                "to referring me or pointing me to the right person? I can send my resume. Thanks either way.\n{myName}"
        const val DEFAULT_SUBJECT = "Application for {role} - {myName}"
        const val DEFAULT_BODY =
            "Hi {firstName},\n\n" +
                "I saw your post about openings at {company} and would like to be considered for the {role} position.\n\n" +
                "I'm {myName}, {myHeadline}, with {years} of experience. My main skills are {skills}.\n\n" +
                "My resume is attached. I'd be glad to talk whenever suits you.\n\n" +
                "Thanks,\n{myName}\n{myPhone}\n{myLinkedIn}"
    }
}

@Serializable
enum class Experience(val label: String, val linkedinCode: String) {
    ANY("Any", ""),
    INTERNSHIP("Internship", "1"),
    ENTRY("Entry level", "2"),
    ASSOCIATE("Associate", "3"),
    MID_SENIOR("Mid-Senior", "4"),
    DIRECTOR("Director", "5"),
}

@Serializable
enum class WorkType(val label: String, val linkedinCode: String) {
    ANY("Any", ""), ONSITE("On-site", "1"), REMOTE("Remote", "2"), HYBRID("Hybrid", "3")
}

@Serializable
enum class PostedWithin(val label: String, val linkedinCode: String, val jsearchCode: String) {
    DAY("24 hours", "r86400", "today"),
    WEEK("Past week", "r604800", "week"),
    MONTH("Past month", "r2592000", "month"),
}

/** What kind of jobs the user wants. Each one is searched separately. */
@Serializable
data class SearchPref(
    val id: String,
    val keywords: String,
    val location: String = "",
    val experience: Experience = Experience.ANY,
    val workType: WorkType = WorkType.ANY,
    val postedWithin: PostedWithin = PostedWithin.WEEK,
    /** Comma-separated words; jobs whose title contains any of these are dropped. */
    val excludeWords: String = "",
    val maxResults: Int = 50,
    val enabled: Boolean = true,
) {
    fun label(): String = buildString {
        append(keywords)
        if (location.isNotBlank()) append(", ").append(location)
    }
}

@Serializable
enum class JobStatus(val label: String) {
    NEW("New"), SAVED("Kept"), APPLIED("Applied"), REFERRAL_ASKED("Referral asked"),
    INTERVIEW("Interview"), REJECTED("Rejected"), IGNORED("Skipped")
}

@Serializable
data class Job(
    /** "<source>:<sourceId>" - unique key for de-duplication. */
    val key: String,
    val source: String,
    val title: String,
    val company: String,
    val location: String = "",
    val url: String = "",
    val applyUrl: String = "",
    val postedAt: String = "",
    val description: String = "",
    val criteria: String = "",
    val searchId: String = "",
    val foundAt: Long = System.currentTimeMillis(),
    val detailsFetched: Boolean = false,
    val matchScore: Int = 0,
    val matchedSkills: List<String> = emptyList(),
    val missingSkills: List<String> = emptyList(),
    val emailsFound: List<String> = emptyList(),
    val status: JobStatus = JobStatus.NEW,
    val notes: String = "",
    /** The "find people" prompt for this job was handled or dismissed. */
    val peoplePrompted: Boolean = false,
    val tailoredSummary: String = "",
    /** File name of the tailored resume PDF in app storage (empty = none). */
    val tailoredResume: String = "",
    /** Why this job was suggested, and what to be careful about. Filled by scoring. */
    val reasons: List<String> = emptyList(),
    val cautions: List<String> = emptyList(),
    /** The tailored resume content behind [tailoredResume], when one was generated. */
    val tailored: TailoredResume? = null,
    /** Categories: remote, remote-elsewhere, abroad, home, visa, startup. */
    val tags: List<String> = emptyList(),
    val appliedAt: Long = 0,
    /** The application page was opened; waiting for the user to say whether they applied. */
    val applyOpenedAt: Long = 0,
    /** The posting was found closed ("no longer accepting applications", or gone). */
    val closed: Boolean = false,
    /** Last time the posting was checked to still be open. */
    val checkedAt: Long = 0,
    /** Times the same role was listed again later (a possible ghost posting). */
    val repostCount: Int = 0,
    val lastSeenAt: Long = 0,
    /** Set when the job moves to Interview, for the thank-you reminder. */
    val interviewAt: Long = 0,
    val followedUpAt: Long = 0,
    val thankedAt: Long = 0,
)

@Serializable
data class QA(val q: String = "", val a: String = "")

/** Standard answers for application forms. Everything is optional and editable in the app. */
@Serializable
data class Answers(
    val city: String = "",
    val currentCompany: String = "",
    val currentTitle: String = "",
    val noticePeriod: String = "",
    val currentCtc: String = "",
    val expectedCtc: String = "",
    val workAuthorization: String = "",
    /** "Yes" or "No" (or a sentence starting with one). */
    val needsSponsorship: String = "",
    val willingToRelocate: String = "",
    val startDate: String = "",
    val github: String = "",
    val portfolio: String = "",
    val howHeard: String = "LinkedIn",
    val gender: String = "",
    /** Questions that come up often, with your answer. Matched against form labels. */
    val custom: List<QA> = emptyList(),
) {
    fun summary(): String = listOf(
        "City" to city, "Current company" to currentCompany, "Current title" to currentTitle,
        "Notice period" to noticePeriod, "Current salary" to currentCtc, "Expected salary" to expectedCtc,
        "Work authorization" to workAuthorization, "Needs visa sponsorship" to needsSponsorship,
        "Willing to relocate" to willingToRelocate, "Can start" to startDate, "GitHub" to github, "Portfolio" to portfolio,
    ).filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second}" } +
        custom.filter { it.q.isNotBlank() }.joinToString("") { "\nQ: ${it.q}\nA: ${it.a}" }

    val isEmpty: Boolean get() = listOf(city, noticePeriod, expectedCtc, workAuthorization).all { it.isBlank() }
}

@Serializable
data class ResumeRole(
    val title: String = "", val company: String = "", val dates: String = "",
    val bullets: List<String> = emptyList(),
)

@Serializable
data class ResumeProject(val name: String = "", val bullets: List<String> = emptyList())

/** A resume rewritten for one job. Facts come from the user's own resume; only emphasis and wording change. */
@Serializable
data class TailoredResume(
    val headline: String = "",
    val summary: String = "",
    val skills: List<String> = emptyList(),
    val experience: List<ResumeRole> = emptyList(),
    val projects: List<ResumeProject> = emptyList(),
    val education: List<String> = emptyList(),
    val other: List<String> = emptyList(),
    /** What was changed compared with the original, for the user to review. */
    val changes: List<String> = emptyList(),
    /** Things the posting asks for that the resume does not show. */
    val gaps: List<String> = emptyList(),
    val byAi: Boolean = false,
)

@Serializable
enum class ContactKind(val label: String) {
    RECRUITER("Recruiter"), EMPLOYEE("Peer"), OTHER("Other"), MANAGER("Hiring manager")
}

/**
 * Outreach pipeline for one person. Nothing moves past SUGGESTED or CONNECTED without approval:
 * SUGGESTED -> APPROVED -> INVITED -> CONNECTED -> ASK_APPROVED -> ASKED -> REPLIED / REFERRED
 */
@Serializable
enum class Stage(val label: String) {
    SUGGESTED("Suggested"), APPROVED("Ready to send"), INVITED("Invited"), CONNECTED("Connected"),
    ASK_APPROVED("Message ready to send"), ASKED("Asked"), REPLIED("Replied"), REFERRED("Referred"),
    NO_RESPONSE("No response"), DECLINED("Declined"), SKIPPED("Skipped")
}

@Serializable
data class Contact(
    val id: String,
    val name: String,
    val headline: String = "",
    val profileUrl: String,
    val company: String = "",
    val jobKey: String = "",
    val kind: ContactKind = ContactKind.EMPLOYEE,
    /** LinkedIn connection degree: 1, 2, 3; 0 = unknown. */
    val degree: Int = 0,
    val rank: Int = 0,
    /** Short reason this person ranks where they do, e.g. "Same college". */
    val why: String = "",
    val stage: Stage = Stage.SUGGESTED,
    /** Connection-request note (may be empty: plain invite). */
    val note: String = "",
    /** Referral message sent once connected. */
    val ask: String = "",
    val template: String = "A",
    val addedAt: Long = System.currentTimeMillis(),
    /** Profile was opened for sending; waiting for the user to confirm it went through. */
    val openedAt: Long = 0,
    val invitedAt: Long = 0,
    val connectedAt: Long = 0,
    val askedAt: Long = 0,
    val repliedAt: Long = 0,
    val followUps: Int = 0,
    val notes: String = "",
    /** Where the suggestion came from: "posting" (named on a job page), "post" (wrote a hiring post), "search", "shared". */
    val source: String = "",
    /** Excerpt of their hiring post, when they wrote one. */
    val postSnippet: String = "",
    val postUrl: String = "",
    /** The user changed the note or message by hand; app upgrades must leave it alone. */
    val edited: Boolean = false,
    /** Messaged without connecting first (InMail or Open Profile). */
    val direct: Boolean = false,
    /** Someone you already know (from your LinkedIn connections export). */
    val known: Boolean = false,
)

@Serializable
enum class EmailStatus(val label: String) {
    DRAFT("Draft"), APPROVED("Approved"), SENT("Sent"), FAILED("Failed"), BOUNCED("Bounced"), SKIPPED("Skipped")
}

@Serializable
enum class ReplyKind(val label: String) {
    NONE("No reply"), REPLY("Replied"), INTERVIEW("Interview"), REJECTION("Rejection"), AUTO("Auto-reply")
}

@Serializable
data class EmailLead(
    val id: String,
    val to: String,
    val name: String = "",
    val company: String = "",
    val role: String = "",
    val jobKey: String = "",
    val sourceUrl: String = "",
    /** Snippet of the post / description the email came from. */
    val context: String = "",
    val subject: String = "",
    val body: String = "",
    val template: String = "A",
    val attachResume: Boolean = true,
    val status: EmailStatus = EmailStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val openedAt: Long = 0,
    val sentAt: Long = 0,
    val via: String = "",
    val error: String = "",
    val reply: ReplyKind = ReplyKind.NONE,
    val repliedAt: Long = 0,
    val replySubject: String = "",
)

@Serializable
enum class ActionKind { INVITE, MESSAGE, EMAIL, DIRECT }

/** One outreach action that counts against a budget. */
@Serializable
data class OutreachAction(val at: Long, val kind: ActionKind, val ref: String = "")

@Serializable
data class Safety(
    val pausedUntil: Long = 0,
    val pauseReason: String = "",
    /** true = only connection requests are paused; messages to existing connections still allowed. */
    val invitesOnly: Boolean = false,
)

@Serializable
data class RunLog(
    val at: Long,
    val searches: Int,
    val jobsSeen: Int,
    val newJobs: Int,
    val newEmails: Int,
    val newRecruiters: Int,
    val errors: List<String> = emptyList(),
)

@Serializable
data class MailScanLog(
    val at: Long = 0,
    val scanned: Int = 0,
    val accepted: Int = 0,
    val replies: Int = 0,
    val interviews: Int = 0,
    val rejections: Int = 0,
    val bounces: Int = 0,
    val error: String = "",
)

@Serializable
data class AppData(
    val profile: Profile = Profile(),
    val searches: List<SearchPref> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val emails: List<EmailLead> = emptyList(),
    val runs: List<RunLog> = emptyList(),
    val actions: List<OutreachAction> = emptyList(),
    val safety: Safety = Safety(),
    val mailScan: MailScanLog = MailScanLog(),
    /** Data version, bumped by one-off upgrades. */
    val schema: Int = 0,
    /** When each rate-limited source was last fetched (epoch ms). */
    val sourceRuns: Map<String, Long> = emptyMap(),
    /** Imported from LinkedIn's Connections.csv: people you already know. */
    val connections: List<Connection> = emptyList(),
    val connectionsImportedAt: Long = 0,
    val pace: PaceState = PaceState(),
)

@Serializable
data class Connection(
    val name: String,
    val url: String = "",
    val company: String = "",
    val position: String = "",
    val connectedOn: String = "",
    val email: String = "",
)

@Serializable
data class PaceState(
    val lastNotifiedAt: Long = 0,
    val lastNotifiedId: String = "",
    val lastEmailAt: Long = 0,
    /** yyyyMMdd of the last daily send-session reminder. */
    val lastReminderDay: String = "",
)
