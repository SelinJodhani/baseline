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
import java.time.LocalDate
import java.util.UUID

enum class DocKind { Lab, InBody }

/** Upload progress shown at the top of the Labs / Body composition screens. */
sealed interface UploadState {
    data object Idle : UploadState
    data class Working(val kind: DocKind) : UploadState
    data class Done(val kind: DocKind, val message: String) : UploadState
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

    fun dismissUpload() { _upload.value = UploadState.Idle }

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
                        store.update { it.copy(scans = it.scans + x.scan) }
                        UploadState.Done(kind, "Added the scan from ${LabDates.pretty(x.scan.date)}")
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
