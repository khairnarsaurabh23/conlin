package com.jobpilot.data

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val name: String = "",
    val headline: String = "",
    val email: String = "",
    val phone: String = "",
    val linkedinUrl: String = "",
    /** Comma-separated skills, used for match scoring and in templates. */
    val skills: String = "",
    val yearsExperience: String = "",
    val about: String = "",
    /** File name of the resume copied into app storage (empty = none). */
    val resumeFileName: String = "",

    // Job sources
    val useLinkedIn: Boolean = true,
    val useJSearch: Boolean = false,
    val jsearchApiKey: String = "",

    // Email sending (optional SMTP; otherwise the Gmail app is used)
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val smtpUser: String = "",
    val smtpPassword: String = "",
    val dailyEmailCap: Int = 25,

    // Templates
    val referralTemplate: String = DEFAULT_REFERRAL,
    val referralCharLimit: Int = 300,
    val emailSubjectTemplate: String = DEFAULT_SUBJECT,
    val emailBodyTemplate: String = DEFAULT_BODY,
    val followUpDays: Int = 5,

    // Background checks
    val autoCheckEnabled: Boolean = true,
    val autoCheckHours: Int = 12,
    val detailFetchLimit: Int = 15,
) {
    fun skillList(): List<String> =
        skills.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    val smtpConfigured: Boolean get() = smtpUser.isNotBlank() && smtpPassword.isNotBlank()

    companion object {
        const val DEFAULT_REFERRAL =
            "Hi {firstName}, I'm {myName}, {myHeadline}. I'm applying for the {role} role at {company} " +
                "and my experience with {topSkills} is a strong fit. Would you be open to referring me? " +
                "Happy to share my resume. Thanks!"
        const val DEFAULT_SUBJECT = "Application for {role} – {myName}"
        const val DEFAULT_BODY =
            "Hi {firstName},\n\n" +
                "I came across your post about openings at {company} and would like to be considered for the {role} position.\n\n" +
                "I'm {myName}, {myHeadline}, with {years} of experience. My key skills include {skills}.\n\n" +
                "I've attached my resume for your review. I'd be glad to talk about how I can contribute to your team.\n\n" +
                "Thank you for your time,\n{myName}\n{myPhone}\n{myLinkedIn}"
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
        if (location.isNotBlank()) append(" · ").append(location)
    }
}

@Serializable
enum class JobStatus(val label: String) {
    NEW("New"), SAVED("Saved"), APPLIED("Applied"), REFERRAL_ASKED("Referral asked"),
    INTERVIEW("Interview"), REJECTED("Rejected"), IGNORED("Ignored")
}

@Serializable
data class Job(
    /** "<source>:<sourceId>" — unique key for de-duplication. */
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
)

@Serializable
enum class ContactKind(val label: String) {
    RECRUITER("Recruiter / hiring team"), EMPLOYEE("Employee (referral)"), OTHER("Other")
}

@Serializable
enum class ContactStatus(val label: String) {
    TO_CONTACT("To contact"), CONTACTED("Contacted"), REPLIED("Replied"),
    REFERRED("Referred"), NO_RESPONSE("No response"), DECLINED("Declined")
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
    val status: ContactStatus = ContactStatus.TO_CONTACT,
    val message: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val contactedAt: Long = 0,
    val notes: String = "",
)

@Serializable
enum class EmailStatus(val label: String) {
    DRAFT("Draft"), APPROVED("Approved"), SENT("Sent"), FAILED("Failed"), SKIPPED("Skipped")
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
    val attachResume: Boolean = true,
    val status: EmailStatus = EmailStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val sentAt: Long = 0,
    val via: String = "",
    val error: String = "",
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
data class AppData(
    val profile: Profile = Profile(),
    val searches: List<SearchPref> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val emails: List<EmailLead> = emptyList(),
    val runs: List<RunLog> = emptyList(),
)
