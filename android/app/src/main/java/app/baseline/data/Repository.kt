package app.baseline.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

enum class Source { None, Live, Cached, Demo }

data class DataState(
    val summary: Summary? = null,
    val source: Source = Source.None,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** When this device last got a fresh copy from the bridge (epoch seconds). */
    val fetchedAt: Long? = null,
    /** The bridge is pulling from the Zepp cloud right now. */
    val syncing: Boolean = false,
    /** Outcome of the last on-demand sync, worded for people (null = nothing to say). */
    val syncNote: String? = null,
    /** When the last on-demand sync finished (epoch seconds). */
    val syncedAt: Long? = null,
)

class BridgeException(message: String) : IOException(message)

/** Fetches the summary from Baseline Bridge, keeps an offline copy in app-private storage, or serves demo data. */
class Repository(private val context: Context, private val store: SecureStore) {
    private val _state = MutableStateFlow(DataState())
    val state: StateFlow<DataState> = _state.asStateFlow()
    private val mutex = Mutex()

    private val cacheFile get() = File(context.filesDir, "cache/summary.json")
    private val metaFile get() = File(context.filesDir, "cache/summary.meta")

    suspend fun loadInitial() {
        val settings = store.settings.first()
        if (settings.demo) {
            _state.value = DataState(summary = DemoData.generate(), source = Source.Demo, fetchedAt = nowSec())
            return
        }
        val cached = withContext(Dispatchers.IO) { readCache() }
        if (cached != null) _state.value = DataState(summary = cached.first, source = Source.Cached, fetchedAt = cached.second)
    }

    /** Pull a fresh copy. Returns null on success, or a human-readable error. */
    suspend fun refresh(): String? = mutex.withLock {
        val settings = store.settings.first()
        if (settings.demo) {
            _state.value = DataState(summary = DemoData.generate(), source = Source.Demo, fetchedAt = nowSec())
            return null
        }
        if (settings.serverUrl.isBlank()) return "No server configured"
        _state.update { it.copy(refreshing = true, error = null) }
        val result = runCatching { withContext(Dispatchers.IO) { fetch(settings.serverUrl, settings.token, useEtag = _state.value.summary != null) } }
        val error = result.exceptionOrNull()?.let { friendly(it) }
        val fetched = result.getOrNull()
        _state.update { s ->
            when {
                fetched?.summary != null -> s.copy(summary = fetched.summary, source = Source.Live, fetchedAt = nowSec(), refreshing = false, error = null)
                fetched != null -> s.copy(source = Source.Live, refreshing = false, fetchedAt = nowSec(), error = null) // 304
                else -> s.copy(refreshing = false, error = error)
            }
        }
        error
    }

    @Serializable
    private data class SyncStatus(val enabled: Boolean = false, val state: String = "idle", val finishedAt: Long? = null, val message: String? = null)

    /**
     * Ask the bridge to pull from the Zepp cloud, wait for it, then refresh. The watch only reaches the Zepp cloud
     * through the Zepp phone app, so "no new data" is common and gets a note telling people to open Zepp first.
     */
    suspend fun syncFromCloud(): String? {
        val settings = store.settings.first()
        if (settings.demo || settings.serverUrl.isBlank()) return refresh()
        if (_state.value.syncing) return null
        val before = _state.value.summary?.freshness?.lastReadingAt
        _state.update { it.copy(syncing = true, syncNote = null, error = null) }
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                post("${settings.serverUrl.trimEnd('/')}/api/v1/sync", settings.token)
                var st: SyncStatus
                val deadline = System.currentTimeMillis() + 15 * 60_000
                do {
                    kotlinx.coroutines.delay(2_000)
                    st = getJson("${settings.serverUrl.trimEnd('/')}/api/v1/sync", settings.token, SyncStatus.serializer())
                } while (st.state == "running" && System.currentTimeMillis() < deadline)
                st
            }
        }
        val st = outcome.getOrNull()
        val err = outcome.exceptionOrNull()?.let { friendly(it) }
        val refreshError = if (err == null) refresh() else null
        val after = _state.value.summary?.freshness?.lastReadingAt
        val note = when {
            err != null -> err
            refreshError != null -> refreshError
            st?.state == "failed" -> "Zepp cloud sync failed — ${st.message ?: "check the bridge logs"}"
            st?.state == "busy" -> "A scheduled sync was already running — your data is up to date with it"
            after != null && before != null && after <= before -> "No new readings in the Zepp cloud yet. Open the Zepp app so your watch syncs, then try again."
            else -> null
        }
        _state.update { it.copy(syncing = false, syncNote = note, syncedAt = nowSec()) }
        return note
    }

    private fun post(url: String, token: String) {
        val conn = openConnection(url, token).apply { requestMethod = "POST"; doOutput = true; setFixedLengthStreamingMode(0) }
        try {
            when (val code = conn.responseCode) {
                200, 202 -> Unit
                401 -> throw BridgeException("The bridge rejected the token")
                403 -> throw BridgeException("Cloud sync is turned off on this bridge")
                404 -> throw BridgeException("This bridge is too old for cloud sync — update it")
                else -> throw BridgeException("The bridge answered HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun <T> getJson(url: String, token: String, serializer: kotlinx.serialization.KSerializer<T>): T {
        val conn = openConnection(url, token)
        try {
            if (conn.responseCode != 200) throw BridgeException("The bridge answered HTTP ${conn.responseCode}")
            return Summary.json.decodeFromString(serializer, conn.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            conn.disconnect()
        }
    }

    fun clear() {
        cacheFile.delete(); metaFile.delete()
        _state.value = DataState()
    }

    suspend fun enterDemo() {
        _state.value = DataState(summary = DemoData.generate(), source = Source.Demo, fetchedAt = nowSec())
    }

    private class Fetched(val summary: Summary?)

    private fun fetch(baseUrl: String, token: String, useEtag: Boolean): Fetched {
        val conn = openConnection("${baseUrl.trimEnd('/')}/api/v1/summary?days=400", token)
        val etag = if (useEtag) metaFile.takeIf { it.exists() }?.readText()?.lineSequence()?.firstOrNull() else null
        if (!etag.isNullOrBlank()) conn.setRequestProperty("If-None-Match", etag)
        try {
            when (val code = conn.responseCode) {
                200 -> {
                    val text = conn.inputStream.use { it.readBytes().decodeToString() }
                    val summary = Summary.json.decodeFromString(Summary.serializer(), text)
                    if (!summary.schema.startsWith("baseline.")) throw BridgeException("That server isn't an Baseline Bridge")
                    cacheFile.parentFile?.mkdirs()
                    cacheFile.writeText(text)
                    metaFile.writeText("${conn.getHeaderField("ETag") ?: ""}\n${nowSec()}")
                    return Fetched(summary)
                }
                304 -> {
                    metaFile.writeText("${etag ?: ""}\n${nowSec()}")
                    return Fetched(null)
                }
                401 -> throw BridgeException("The bridge rejected the token")
                404 -> throw BridgeException("No Baseline Bridge at that address (404)")
                503 -> throw BridgeException("The bridge couldn't read ZeppBridge — check its logs")
                else -> throw BridgeException("The bridge answered HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readCache(): Pair<Summary, Long?>? = runCatching {
        if (!cacheFile.exists()) return null
        val s = Summary.json.decodeFromString(Summary.serializer(), cacheFile.readText())
        val at = metaFile.takeIf { it.exists() }?.readLines()?.getOrNull(1)?.toLongOrNull()
        s to at
    }.getOrNull()

    companion object {
        fun nowSec() = System.currentTimeMillis() / 1000

        fun openConnection(url: String, token: String): HttpURLConnection {
            val parsed = URI(url)
            require(parsed.scheme == "https" || parsed.scheme == "http") { "URL must start with https:// or http://" }
            return (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
        }

        /** Checks the URL + token against `/api/v1/ping`. Returns null if it works, else a reason. */
        suspend fun testConnection(baseUrl: String, token: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                val conn = openConnection("${baseUrl.trimEnd('/')}/api/v1/ping", token)
                try {
                    when (val code = conn.responseCode) {
                        200 -> null
                        401 -> "The bridge rejected the token"
                        404 -> "No Baseline Bridge at that address"
                        else -> "The server answered HTTP $code"
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { friendly(it) }
        }

        fun friendly(t: Throwable): String = when (t) {
            is BridgeException -> t.message ?: "Bridge error"
            is java.net.UnknownHostException -> "Can't find that host — is Tailscale connected?"
            is java.net.ConnectException -> "Couldn't connect — is the bridge running and reachable?"
            is java.net.SocketTimeoutException -> "The bridge took too long to answer"
            is javax.net.ssl.SSLException -> "TLS problem: ${t.message ?: "certificate not trusted"}"
            is kotlinx.serialization.SerializationException -> "The bridge sent data this app can't read — update both"
            is IllegalArgumentException -> t.message ?: "Invalid URL"
            else -> t.message ?: t.javaClass.simpleName
        }

        /** True if plain http to this host would leave the device unencrypted on a public network. */
        fun isInsecure(url: String): Boolean {
            val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            if (u.scheme != "http") return false
            val host = u.host ?: return true
            if (host == "localhost" || host.endsWith(".ts.net") || host.endsWith(".local")) return false
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return true
            val (a, b) = parts[0] to parts[1]
            return !(a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127))
        }
    }
}
