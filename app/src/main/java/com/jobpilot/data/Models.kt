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
    val openInLinkedInApp: Boolean = true,

    // Background checks
    val autoCheckEnabled: Boolean = true,
    val autoCheckHours: Int = 12,
    val detailFetchLimit: Int = 15,
    /** New jobs scoring below this are kept out of the inbox (still listed in the Tracker). */
    val minMatch: Int = 40,
    /** "dark", "light" or "system". */
    val theme: String = "dark",
) {
    fun skillList(): List<String> = splitList(skills)
    fun pastCompanyList(): List<String> = splitList(pastCompanies)

    val smtpConfigured: Boolean get() = smtpUser.isNotBlank() && smtpPassword.isNotBlank()

    companion object {
        fun splitList(s: String): List<String> =
            s.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }

        // {opening} is filled with the concrete reason for writing: their hiring post, the posting
        // they are named on, or the openings at their company.
        const val DEFAULT_NOTE =
            "Hi {firstName}, {opening}. I work with {topSkills} and would like to connect."
        const val DEFAULT_ASK =
            "Hi {firstName}, thanks for connecting. {opening}. I'm applying, and I have {years} of experience with " +
                "{topSkills}. If it looks like a reasonable fit, would you be open " +
                "to referring me or pointing me to the right person? I can send my resume. Thanks either way.\n{myName}"

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
)

@Serializable
enum class ContactKind(val label: String) {
    RECRUITER("Recruiter"), EMPLOYEE("Employee"), OTHER("Other")
}

/**
 * Outreach pipeline for one person. Nothing moves past SUGGESTED or CONNECTED without approval:
 * SUGGESTED -> APPROVED -> INVITED -> CONNECTED -> ASK_APPROVED -> ASKED -> REPLIED / REFERRED
 */
@Serializable
enum class Stage(val label: String) {
    SUGGESTED("Suggested"), APPROVED("Invite approved"), INVITED("Invited"), CONNECTED("Connected"),
    ASK_APPROVED("Ask approved"), ASKED("Asked"), REPLIED("Replied"), REFERRED("Referred"),
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
enum class ActionKind { INVITE, MESSAGE, EMAIL }

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
)
