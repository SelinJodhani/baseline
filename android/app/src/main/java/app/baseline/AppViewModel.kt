package app.baseline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.baseline.data.AppSettings
import app.baseline.data.DataState
import app.baseline.data.Repository
import app.baseline.insight.InsightEngine
import app.baseline.insight.Insights
import app.baseline.widget.TodayWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything a screen needs: the engine (for series), its computed judgements, and the data state. */
data class Model(val engine: InsightEngine, val insights: Insights, val data: DataState, val settings: AppSettings)

const val SYNC_AFTER_SEC = 10 * 60L

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val baseline = app as BaselineApp
    private val store = baseline.store
    private val repo = baseline.repository

    val settings: StateFlow<AppSettings?> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val data: StateFlow<DataState> = repo.state

    val model: StateFlow<Model?> = combine(repo.state, store.settings) { d, s ->
        d.summary?.let { sum -> InsightEngine(sum, s).let { e -> Model(e, e.compute(), d, s) } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            repo.loadInitial()
            val s = store.settings.first()
            if (s.onboarded) refresh()
        }
    }

    fun refresh() = viewModelScope.launch {
        if (repo.refresh() == null) {
            TodayWidget.updateAll(getApplication())
            if (!previewsPublished) { previewsPublished = true; TodayWidget.publishPreviews(getApplication()) }
        }
    }

    /** Pull from the Zepp cloud through the bridge (when allowed), then refresh. */
    fun syncNow() = viewModelScope.launch {
        repo.syncFromCloud()
        TodayWidget.updateAll(getApplication())
    }

    /**
     * Pull-to-refresh: if the bridge hasn't checked the Zepp cloud for a while, sync first; otherwise just
     * re-read the bridge (cheap, cached).
     */
    fun pullToRefresh() {
        val s = data.value.summary
        val now = Repository.nowSec()
        val libraryAge = s?.freshness?.libraryModifiedAt?.let { now - it } ?: Long.MAX_VALUE
        if (s?.capabilities?.sync == true && libraryAge > SYNC_AFTER_SEC) syncNow() else refresh()
    }

    /** Set when the user jumps to the Zepp app from here; coming back triggers a cloud sync. */
    var syncOnReturn = false

    fun onResumed() {
        if (syncOnReturn) { syncOnReturn = false; syncNow() } else refresh()
    }

    fun connect(url: String, token: String) = viewModelScope.launch {
        store.update { it.copy(serverUrl = url.trim().trimEnd('/'), token = token.trim(), demo = false, onboarded = true) }
        repo.clear()
        repo.refresh()
        TodayWidget.updateAll(getApplication())
    }

    fun startDemo() = viewModelScope.launch {
        store.update { it.copy(demo = true, onboarded = true) }
        repo.enterDemo()
        TodayWidget.updateAll(getApplication())
    }

    fun signOut() = viewModelScope.launch {
        store.update { AppSettings(theme = it.theme, dynamicColor = it.dynamicColor, sleepNeedMin = it.sleepNeedMin, stepGoal = it.stepGoal, weeklyActiveGoal = it.weeklyActiveGoal) }
        repo.clear()
        TodayWidget.updateAll(getApplication())
    }

    private var previewsPublished = false
    private val _requestedTab = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    /** A tab a widget asked to open; AppRoot consumes it. */
    val requestedTab: StateFlow<Int?> = _requestedTab
    fun openTab(tab: Int) { _requestedTab.value = tab }
    fun consumeTab() { _requestedTab.value = null }

    /** A pairing link the user opened: (url, token) to prefill the connect form. */
    val pairing = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    fun pair(url: String, token: String) { pairing.value = url to token }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { store.update(transform) }

    suspend fun test(url: String, token: String): String? = Repository.testConnection(url.trim(), token.trim())
}
