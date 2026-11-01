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

/**
 * Single source of truth. Everything is kept in one JSON file in app-private storage.
 * The UI and the background worker share this object (same process).
 */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
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
            file = File(context.filesDir, "jobpilot.json")
            if (file.exists()) {
                runCatching { _state.value = json.decodeFromString(AppData.serializer(), file.readText()) }
                    .onFailure { file.copyTo(File(context.filesDir, "jobpilot.corrupt.json"), overwrite = true) }
            }
            loaded = true
        }
    }

    val data: AppData get() = _state.value

    suspend fun update(transform: (AppData) -> AppData) {
        mutex.withLock {
            val next = transform(_state.value)
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

    fun newId(): String = UUID.randomUUID().toString().take(12)

    // ---- convenience mutators ----

    suspend fun setProfile(p: Profile) = update { it.copy(profile = p) }

    suspend fun upsertSearch(s: SearchPref) = update { d ->
        val others = d.searches.filterNot { it.id == s.id }
        val idx = d.searches.indexOfFirst { it.id == s.id }
        val list = if (idx >= 0) d.searches.toMutableList().also { it[idx] = s } else others + s
        d.copy(searches = list)
    }

    suspend fun deleteSearch(id: String) = update { d -> d.copy(searches = d.searches.filterNot { it.id == id }) }

    suspend fun updateJob(key: String, f: (Job) -> Job) = update { d ->
        d.copy(jobs = d.jobs.map { if (it.key == key) f(it) else it })
    }

    suspend fun deleteJob(key: String) = update { d -> d.copy(jobs = d.jobs.filterNot { it.key == key }) }

    suspend fun upsertContact(c: Contact) = update { d ->
        val existing = d.contacts.indexOfFirst { it.id == c.id || sameProfile(it.profileUrl, c.profileUrl) }
        if (existing >= 0) d.copy(contacts = d.contacts.toMutableList().also { it[existing] = c.copy(id = it[existing].id) })
        else d.copy(contacts = d.contacts + c)
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

    fun sameProfile(a: String, b: String): Boolean = normalizeProfile(a) == normalizeProfile(b)

    fun normalizeProfile(url: String): String =
        url.substringBefore("?").trimEnd('/').lowercase()
            .replace("://in.linkedin.com", "://www.linkedin.com")
            .replace("://linkedin.com", "://www.linkedin.com")
            .replace("http://", "https://")
}
