package app.baseline.labs

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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Extracted lab results, InBody scans and cached AI notes, in one DataStore file encrypted with AES-256-GCM
 * (same pattern as SecureStore). It has its own Keystore master key and keyset, so a reset of one store never
 * strands the other. The app excludes its files from backups.
 */
class LabStore private constructor(context: Context) {
    // Built lazily on DataStore's IO thread at first read, so opening a screen never blocks on the Keystore.
    private val aead: Aead by lazy {
        AeadConfig.register()
        fun build() = AndroidKeysetManager.Builder()
            .withSharedPref(context, "baseline_labs_keyset", "baseline_labs_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://baseline_labs_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        try {
            build()
        } catch (e: Exception) {
            // Keystore key lost (e.g. after a lock-screen reset): the data can't be read anyway, so start fresh.
            context.getSharedPreferences("baseline_labs_keyset_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            runCatching {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("baseline_labs_master_key")
            }
            File(context.filesDir, FILE).delete()
            build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val associated = "baseline-labs-v1".toByteArray()

    private val serializer = object : Serializer<LabsData> {
        override val defaultValue = LabsData()
        override suspend fun readFrom(input: InputStream): LabsData {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return defaultValue
            return try {
                json.decodeFromString(LabsData.serializer(), aead.decrypt(bytes, associated).decodeToString())
            } catch (e: SerializationException) {
                throw CorruptionException("Unreadable lab data", e)
            } catch (e: java.security.GeneralSecurityException) {
                throw CorruptionException("Lab data can't be decrypted", e)
            }
        }

        override suspend fun writeTo(t: LabsData, output: OutputStream) {
            output.write(aead.encrypt(json.encodeToString(LabsData.serializer(), t).toByteArray(), associated))
        }
    }

    private val store: DataStore<LabsData> = DataStoreFactory.create(
        serializer = serializer,
        corruptionHandler = ReplaceFileCorruptionHandler { LabsData() },
        produceFile = { File(context.filesDir, FILE) },
    )

    val data: Flow<LabsData> = store.data

    suspend fun update(transform: (LabsData) -> LabsData) {
        store.updateData(transform)
    }

    companion object {
        private const val FILE = "datastore/labs.enc"
        @Volatile private var instance: LabStore? = null

        /** One instance per process (DataStore requires a single owner per file). */
        fun get(context: Context): LabStore = instance ?: synchronized(this) {
            instance ?: LabStore(context.applicationContext).also { instance = it }
        }
    }
}
