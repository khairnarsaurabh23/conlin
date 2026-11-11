package com.jobpilot.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Single source of truth. Everything is kept in one JSON file in app-private storage.
 * The UI and the background worker share this object (same process).
 */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AppData())
    val state: StateFlow<AppData> = _state.asStateFlow()

    private lateinit var file: File
    lateinit var filesDir: File
        private set
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            filesDir = context.filesDir
            file = File(context.filesDir, "jobpilot2.json")
            if (file.exists()) {
                runCatching { _state.value = json.decodeFromString(AppData.serializer(), file.readText()) }
                    .onFailure { file.copyTo(File(context.filesDir, "jobpilot2.corrupt.json"), overwrite = true) }
            }
            loaded = true
        }
    }

    val data: AppData get() = _state.value

    suspend fun update(transform: (AppData) -> AppData) {
        mutex.withLock {
            val next = transform(_state.value)
            if (next === _state.value) return
            _state.value = next
            withContext(Dispatchers.IO) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(AppData.serializer(), next))
                if (!tmp.renameTo(file)) {
                    file.delete(); tmp.renameTo(file)
                }
            }
        }
    }

    fun resumeFile(): File? {
        val n = data.profile.resumeFileName
        if (n.isBlank()) return null
        return File(filesDir, n).takeIf { it.exists() }
    }

    /** Tailored resume for the job if one was generated, otherwise the uploaded resume. */
    fun resumeFor(jobKey: String): File? {
        val t = data.jobs.firstOrNull { it.key == jobKey }?.tailoredResume.orEmpty()
        if (t.isNotBlank()) File(filesDir, t).takeIf { it.exists() }?.let { return it }
        return resumeFile()
    }

    fun newId(): String = UUID.randomUUID().toString().take(12)

    // ---- convenience mutators ----

    suspend fun setProfile(p: Profile) = update { it.copy(profile = p) }

    suspend fun upsertSearch(s: SearchPref) = update { d ->
        val idx = d.searches.indexOfFirst { it.id == s.id }
        val list = if (idx >= 0) d.searches.toMutableList().also { it[idx] = s } else d.searches + s
        d.copy(searches = list)
    }

    suspend fun deleteSearch(id: String) = update { d -> d.copy(searches = d.searches.filterNot { it.id == id }) }

    suspend fun updateJob(key: String, f: (Job) -> Job) = update { d ->
        d.copy(jobs = d.jobs.map { if (it.key == key) f(it) else it })
    }

    suspend fun deleteJob(key: String) = update { d -> d.copy(jobs = d.jobs.filterNot { it.key == key }) }

    /** Adds the contact unless the same profile is already tracked. Returns true if added. */
    suspend fun addContact(c: Contact): Boolean {
        var added = false
        update { d ->
            if (d.contacts.any { sameProfile(it.profileUrl, c.profileUrl) }) d
            else { added = true; d.copy(contacts = d.contacts + c) }
        }
        return added
    }

    suspend fun updateContact(id: String, f: (Contact) -> Contact) = update { d ->
        d.copy(contacts = d.contacts.map { if (it.id == id) f(it) else it })
    }

    suspend fun deleteContact(id: String) = update { d -> d.copy(contacts = d.contacts.filterNot { it.id == id }) }

    suspend fun upsertEmail(e: EmailLead) = update { d ->
        val idx = d.emails.indexOfFirst { it.id == e.id }
        if (idx >= 0) d.copy(emails = d.emails.toMutableList().also { it[idx] = e }) else d.copy(emails = d.emails + e)
    }

    suspend fun updateEmail(id: String, f: (EmailLead) -> EmailLead) = update { d ->
        d.copy(emails = d.emails.map { if (it.id == id) f(it) else it })
    }

    suspend fun deleteEmail(id: String) = update { d -> d.copy(emails = d.emails.filterNot { it.id == id }) }

    /** Records an action against the outreach budgets; keeps 90 days of history. */
    suspend fun recordAction(kind: ActionKind, ref: String = "") = update { d ->
        val now = System.currentTimeMillis()
        val keep = d.actions.filter { now - it.at < TimeUnit.DAYS.toMillis(90) }
        d.copy(actions = keep + OutreachAction(now, kind, ref))
    }

    /** Pauses outreach. A pause is never shortened, and a full pause is never narrowed to invites only. */
    suspend fun pause(hours: Int, reason: String, invitesOnly: Boolean) = update { d ->
        val now = System.currentTimeMillis()
        val until = now + TimeUnit.HOURS.toMillis(hours.toLong())
        val active = d.safety.pausedUntil > now
        val only = if (active) d.safety.invitesOnly && invitesOnly else invitesOnly
        d.copy(safety = Safety(maxOf(until, d.safety.pausedUntil), reason, only))
    }

    suspend fun resume() = update { d -> d.copy(safety = Safety()) }

    fun sameProfile(a: String, b: String): Boolean = normalizeProfile(a) == normalizeProfile(b)

    fun normalizeProfile(url: String): String =
        url.substringBefore("?").trimEnd('/').lowercase()
            .replace(Regex("://[a-z]{2,3}\\.linkedin\\.com"), "://www.linkedin.com")
            .replace("://linkedin.com", "://www.linkedin.com")
            .replace("http://", "https://")
}
