package app.baseline.labs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.baseline.BaselineApp
import app.baseline.ai.AiState
import app.baseline.ai.Gemini
import app.baseline.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

enum class DocKind { Lab, InBody }

/** Upload progress shown at the top of the Labs / Body composition screens. */
sealed interface UploadState {
    data object Idle : UploadState
    data class Working(val kind: DocKind) : UploadState
    data class Done(val kind: DocKind, val message: String, val canUndo: Boolean = false) : UploadState
    data class Failed(val kind: DocKind, val message: String) : UploadState
}

/**
 * State for Lab reports and Body composition. In demo mode it serves [DemoLabs] and never touches the store;
 * otherwise everything lives in the encrypted [LabStore].
 */
class LabsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LabStore.get(app)
    private val settingsStore = (app as BaselineApp).store
    private val demoNotes = MutableStateFlow<Map<String, AiNote>>(emptyMap())

    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val data: StateFlow<LabsData> = combine(store.data, settingsStore.settings, demoNotes) { stored, s, dn ->
        if (s.demo) DemoLabs.data().let { it.copy(notes = it.notes + dn) } else stored
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LabsData())

    private val _upload = MutableStateFlow<UploadState>(UploadState.Idle)
    val upload: StateFlow<UploadState> = _upload

    /** AI requests in flight / failed, by note key. Finished answers live in [LabsData.notes]. */
    private val _ai = MutableStateFlow<Map<String, AiState>>(emptyMap())
    val ai: StateFlow<Map<String, AiState>> = _ai

    val aiReady: Boolean get() = settings.value.aiEnabled && settings.value.geminiKey.isNotBlank()
    val demo: Boolean get() = settings.value.demo

    fun giveConsent() = viewModelScope.launch { store.update { it.copy(consent = true) } }

    fun dismissUpload() { _upload.value = UploadState.Idle; undoScans = null }

    /** Scans as they were before the last CSV import, for Undo. */
    private var undoScans: List<InBodyScan>? = null

    /**
     * A file was picked. An InBody CSV export is imported right here on the phone; anything else needs AI, so
     * [needsAi] is called and the screen asks for setup or consent before [upload].
     */
    fun picked(uri: Uri, kind: DocKind, needsAi: (Uri) -> Unit) {
        if (_upload.value is UploadState.Working) return
        viewModelScope.launch {
            if (kind == DocKind.InBody && LabExtractor.isCsv(getApplication(), uri)) importCsv(uri) else needsAi(uri)
        }
    }

    private suspend fun importCsv(uri: Uri) {
        _upload.value = UploadState.Working(DocKind.InBody)
        _upload.value = runCatching {
            val text = LabExtractor.readText(getApplication(), uri)
            val r = withContext(Dispatchers.Default) { InBodyCsv.parse(text) }
            if (r.scans.isEmpty()) error("No scans with a date and body numbers were found in that file.")
            var merge: ScanMerge? = null
            store.update { d -> undoScans = d.scans; mergeScans(d.scans, r.scans).also { merge = it }.let { d.copy(scans = it.scans) } }
            val replaced = merge?.replaced ?: 0
            val first = r.scans.first().date; val last = r.scans.last().date
            val skipped = listOfNotNull(
                r.duplicates.takeIf { it > 0 }?.let { "$it duplicate ${if (it == 1) "row" else "rows"}" },
                r.sameDay.takeIf { it > 0 }?.let { "$it repeat ${if (it == 1) "scan" else "scans"} on the same day (kept the last)" },
                r.unreadable.takeIf { it > 0 }?.let { "$it unreadable ${if (it == 1) "row" else "rows"}" },
            )
            UploadState.Done(
                DocKind.InBody,
                "Imported ${r.scans.size} ${if (r.scans.size == 1) "scan" else "scans"}" +
                    (if (first != last) " from ${LabDates.pretty(first)} to ${LabDates.pretty(last)}" else " from ${LabDates.pretty(first)}") + "." +
                    (if (replaced > 0) " Updated $replaced already here." else "") +
                    (if (skipped.isNotEmpty()) " Skipped ${skipped.joinToString(", ")}." else ""),
                canUndo = true,
            )
        }.getOrElse { UploadState.Failed(DocKind.InBody, it.message ?: "Couldn't read that file") }
    }

    fun undoImport() {
        val before = undoScans ?: return
        undoScans = null
        _upload.value = UploadState.Idle
        viewModelScope.launch { store.update { it.copy(scans = before) } }
    }

    fun upload(uri: Uri, kind: DocKind) {
        if (_upload.value is UploadState.Working) return
        _upload.value = UploadState.Working(kind)
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            val model = s.geminiDocModel.ifBlank { Gemini.DEFAULT_DOC_MODEL }
            _upload.value = runCatching {
                val picked = LabExtractor.read(getApplication(), uri)
                when (kind) {
                    DocKind.Lab -> {
                        val x = LabExtractor.extractLab(picked, s.geminiKey, model)
                        if (!x.isLabReport || x.markers.isEmpty()) error("Couldn't find lab results in that file. Try a clearer photo or the lab's PDF.")
                        val report = LabReport(
                            id = UUID.randomUUID().toString(), date = (x.date ?: LocalDate.now()).toString(),
                            lab = x.lab, addedAt = System.currentTimeMillis(), markers = x.markers,
                        )
                        store.update { it.copy(reports = it.reports + report) }
                        UploadState.Done(kind, "Added ${report.markers.size} results from ${LabDates.pretty(report.date)}" + if (x.date == null) " (no date found, used today)" else "")
                    }
                    DocKind.InBody -> {
                        val x = LabExtractor.extractInBody(picked, s.geminiKey, model)
                        if (!x.isInBody) error("Couldn't find body composition results in that file. Try a clearer photo of the result sheet.")
                        var merge: ScanMerge? = null
                        store.update { d -> undoScans = d.scans; mergeScans(d.scans, x.scans).also { merge = it }.let { d.copy(scans = it.scans) } }
                        val mg = merge!!
                        val n = x.scans.size
                        UploadState.Done(
                            kind,
                            (if (n == 1) "Read the scan from ${LabDates.pretty(x.scan.date)}."
                            else "Read $n scans from ${LabDates.pretty(x.scans.first().date)} to ${LabDates.pretty(x.scans.last().date)}.") +
                                (if (mg.replaced > 0) " Updated ${mg.replaced} already here." else "") +
                                (if (mg.kept > 0) " Kept ${mg.kept} from your CSV import, which has the exact numbers." else "") +
                                (if (n == 1) " For your full history, import the CSV export from the InBody app." else ""),
                            canUndo = true,
                        )
                    }
                }
            }.getOrElse { UploadState.Failed(kind, it.message ?: "Something went wrong") }
        }
    }

    fun deleteReport(id: String) = viewModelScope.launch {
        store.update { d -> d.copy(reports = d.reports.filterNot { it.id == id }, notes = d.notes.filterKeys { !it.contains(id) }) }
    }

    fun deleteScan(id: String) = viewModelScope.launch {
        store.update { d -> d.copy(scans = d.scans.filterNot { it.id == id }, notes = d.notes.filterKeys { !it.contains(id) }) }
    }

    /** Ask Gemini for [key]'s note using [prompt]; the answer is cached in the encrypted store (memory in demo). */
    fun explain(key: String, prompt: String) {
        if (_ai.value[key] == AiState.Loading || prompt.isBlank()) return
        _ai.value = _ai.value + (key to AiState.Loading)
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            val model = s.geminiModel.ifBlank { Gemini.DEFAULT_TEXT_MODEL }
            runCatching {
                Gemini.generate(s.geminiKey, model, LabAi.SYSTEM, listOf(Gemini.Part.Text(prompt)), temperature = 0.4, maxOutputTokens = 2048)
            }.onSuccess { text ->
                val note = AiNote(text, model, System.currentTimeMillis())
                if (s.demo) demoNotes.value = demoNotes.value + (key to note)
                else store.update { it.copy(notes = it.notes + (key to note)) }
                _ai.value = _ai.value - key
            }.onFailure {
                _ai.value = _ai.value + (key to AiState.Error(it.message ?: "Something went wrong"))
            }
        }
    }

    /** What an AI card for [key] should show. */
    fun aiState(d: LabsData, ai: Map<String, AiState>, key: String): AiState =
        ai[key]?.takeIf { it != AiState.Idle } ?: d.notes[key]?.let { AiState.Done(it.text, if (it.sample) "Sample text for demo mode" else null) } ?: AiState.Idle
}
