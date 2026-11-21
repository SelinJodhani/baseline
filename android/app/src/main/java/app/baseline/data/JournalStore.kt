package app.baseline.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

/** The one-tap morning answer to "How do you feel?". */
@Serializable
enum class Feel(val label: String) { Drained("Drained"), Ok("OK"), Great("Great") }

/** A stretch of life where targets pause. Sick and travel days are also left out of personal baselines. */
@Serializable
enum class LifeMode(val label: String, val excludedFromBaseline: Boolean) {
    Rest("Rest", false),
    Sick("Sick", true),
    Travel("Travel", true),
}

/**
 * What the user told us about one local date. [mode] is stamped once a mode span is settled, so history
 * survives turning a mode off. [tags] is reserved for context tags (alcohol, late meal…) in a later phase.
 */
@Serializable
data class JournalEntry(
    val feel: Feel? = null,
    val mode: LifeMode? = null,
    val tags: List<String> = emptyList(),
)

/** A mode turned on at [start] (ISO local date). It lasts until turned off, or [Journal.MODE_MAX_DAYS] days. */
@Serializable
data class ModeSpan(val mode: LifeMode, val start: String) {
    val startDate: LocalDate get() = LocalDate.parse(start)
    val lastDate: LocalDate get() = startDate.plusDays(Journal.MODE_MAX_DAYS - 1L)
    /** 1-based day number of this span on [d]. */
    fun dayOf(d: LocalDate): Int = (d.toEpochDay() - startDate.toEpochDay()).toInt() + 1
}

/** Everything the user logs by hand, keyed by local date (ISO yyyy-MM-dd). Pure data: safe to use in tests. */
@Serializable
data class Journal(
    val entries: Map<String, JournalEntry> = emptyMap(),
    /** The most recent mode span; it may have expired already (see [activeMode]). */
    val mode: ModeSpan? = null,
) {
    fun entry(d: LocalDate): JournalEntry? = entries[d.toString()]
    fun feelOn(d: LocalDate): Feel? = entry(d)?.feel

    /** The mode in force on [today], or null when none is set or it has run its course. */
    fun activeMode(today: LocalDate): ModeSpan? =
        mode?.takeIf { !today.isBefore(it.startDate) && !today.isAfter(it.lastDate) }

    /** The mode that applied on [d]: stamped history first, then the current span. */
    fun modeOn(d: LocalDate): LifeMode? =
        entry(d)?.mode ?: mode?.takeIf { !d.isBefore(it.startDate) && !d.isAfter(it.lastDate) }?.mode

    /** Dates whose readings shouldn't shape the personal normal (sick and travel days), up to [through]. */
    fun excludedDates(through: LocalDate): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        entries.forEach { (k, e) -> if (e.mode?.excludedFromBaseline == true) runCatching { LocalDate.parse(k) }.getOrNull()?.let(out::add) }
        mode?.takeIf { it.mode.excludedFromBaseline }?.let { span ->
            var d = span.startDate
            val end = minOf(span.lastDate, through)
            while (!d.isAfter(end)) { out += d; d = d.plusDays(1) }
        }
        return out
    }

    fun withFeel(d: LocalDate, feel: Feel?): Journal = edit(d) { it.copy(feel = feel) }

    /**
     * Turn a mode on (from [today]) or off (null). The previous span is first written into the per-day entries
     * so its days stay excluded later. Turning a mode off on the day it started counts as a mis-tap: nothing is
     * stamped. Turning it off on a later day stamps up to yesterday (today is a fresh start).
     */
    fun withMode(newMode: LifeMode?, today: LocalDate): Journal {
        var j = this
        mode?.let { span ->
            val switchingSameDay = span.startDate == today
            if (!switchingSameDay) {
                val end = minOf(span.lastDate, today.minusDays(1))
                var d = span.startDate
                while (!d.isAfter(end)) { j = j.edit(d) { e -> e.copy(mode = e.mode ?: span.mode) }; d = d.plusDays(1) }
            }
        }
        return j.copy(mode = newMode?.let { ModeSpan(it, today.toString()) })
    }

    private fun edit(d: LocalDate, f: (JournalEntry) -> JournalEntry): Journal {
        val k = d.toString()
        val e = f(entries[k] ?: JournalEntry())
        val next = if (e == JournalEntry()) entries - k else entries + (k to e)
        return copy(entries = next)
    }

    companion object {
        const val MODE_MAX_DAYS = 7
    }
}

/**
 * The journal (feelings, modes, later tags) in its own DataStore file, encrypted with AES-256-GCM under a
 * separate Android Keystore key. Same pattern as [SecureStore]; excluded from backups by the app's rules.
 */
class JournalStore(context: Context) {
    private val aead: Aead = run {
        AeadConfig.register()
        fun build() = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET, PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        try {
            build()
        } catch (e: Exception) {
            // Keystore key lost: start a fresh journal instead of crashing every launch.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            runCatching {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
            }
            File(context.filesDir, FILE).delete()
            build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val associated = "baseline-journal-v1".toByteArray()

    private val serializer = object : Serializer<Journal> {
        override val defaultValue = Journal()
        override suspend fun readFrom(input: InputStream): Journal {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return defaultValue
            return try {
                json.decodeFromString(Journal.serializer(), aead.decrypt(bytes, associated).decodeToString())
            } catch (e: SerializationException) {
                throw CorruptionException("Unreadable journal", e)
            } catch (e: IllegalArgumentException) {
                throw CorruptionException("Unreadable journal", e)
            } catch (e: java.security.GeneralSecurityException) {
                throw CorruptionException("Journal can't be decrypted", e)
            }
        }

        override suspend fun writeTo(t: Journal, output: OutputStream) {
            output.write(aead.encrypt(json.encodeToString(Journal.serializer(), t).toByteArray(), associated))
        }
    }

    private val store: DataStore<Journal> = DataStoreFactory.create(
        serializer = serializer,
        corruptionHandler = ReplaceFileCorruptionHandler { Journal() },
        produceFile = { File(context.filesDir, FILE) },
    )

    val journal: Flow<Journal> = store.data

    suspend fun update(transform: (Journal) -> Journal) {
        store.updateData(transform)
    }

    suspend fun clear() = update { Journal() }

    private companion object {
        const val FILE = "datastore/journal.enc"
        const val PREFS = "baseline_journal_keyset_prefs"
        const val KEYSET = "baseline_journal_keyset"
        const val MASTER_KEY_ALIAS = "baseline_journal_master_key"
        const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"
    }
}
