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

@Serializable
data class AppSettings(
    val serverUrl: String = "",
    val token: String = "",
    val demo: Boolean = false,
    val onboarded: Boolean = false,
    /** Personal sleep need in minutes (default 8 h; adults need 7–9 h). */
    val sleepNeedMin: Int = 480,
    /** Daily step target. Mortality benefit plateaus around 7–10k steps/day for adults. */
    val stepGoal: Int = 8000,
    /** Weekly moderate-to-vigorous minutes target (WHO: 150–300). */
    val weeklyActiveGoal: Int = 150,
    val theme: String = "system",
    val dynamicColor: Boolean = true,
    /** Optional AI daily brief (Google Gemini, the user's own API key). Off unless turned on. */
    val aiEnabled: Boolean = false,
    val geminiKey: String = "",
    val geminiModel: String = "gemini-3.5-flash-lite",
    val lastUpdateCheck: Long = 0,
    val skippedVersion: String = "",
    /** Calm mode: Today shows words instead of numbers (story pages keep their numbers). */
    val calmMode: Boolean = false,
    /** Keep last night's sleep details off Today until noon, so the morning isn't judged by a number. */
    val holdSleepScoreUntilAfternoon: Boolean = false,
)

/**
 * Settings (including the bridge token) in one DataStore file encrypted with AES-256-GCM; the key lives in
 * the Android Keystore, so the file is useless off this device. Excluded from backups.
 */
class SecureStore(context: Context) {
    private val aead: Aead = run {
        AeadConfig.register()
        fun build() = AndroidKeysetManager.Builder()
            .withSharedPref(context, "baseline_keyset", "baseline_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://baseline_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        try {
            build()
        } catch (e: Exception) {
            // Keystore key lost (e.g. after a lock-screen reset): start fresh instead of crashing every launch.
            context.getSharedPreferences("baseline_keyset_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            runCatching {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("baseline_master_key")
            }
            File(context.filesDir, "datastore/settings.enc").delete()
            build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val associated = "baseline-settings-v1".toByteArray()

    private val serializer = object : Serializer<AppSettings> {
        override val defaultValue = AppSettings()
        override suspend fun readFrom(input: InputStream): AppSettings {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return defaultValue
            return try {
                json.decodeFromString(AppSettings.serializer(), aead.decrypt(bytes, associated).decodeToString())
            } catch (e: SerializationException) {
                throw CorruptionException("Unreadable settings", e)
            } catch (e: java.security.GeneralSecurityException) {
                throw CorruptionException("Settings can't be decrypted", e)
            }
        }

        override suspend fun writeTo(t: AppSettings, output: OutputStream) {
            output.write(aead.encrypt(json.encodeToString(AppSettings.serializer(), t).toByteArray(), associated))
        }
    }

    private val store: DataStore<AppSettings> = DataStoreFactory.create(
        serializer = serializer,
        corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
        produceFile = { File(context.filesDir, "datastore/settings.enc") },
    )

    val settings: Flow<AppSettings> = store.data

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }
}

/** Whether glanceable surfaces (Today, widgets) should show numbers, or calm words only. */
fun AppSettings.showNumbers(): Boolean = !calmMode

/** Whether last night's sleep details should stay hidden right now ([hour] = local hour of day, 0–23). */
fun AppSettings.holdSleepDetail(hour: Int): Boolean = holdSleepScoreUntilAfternoon && hour < 12
