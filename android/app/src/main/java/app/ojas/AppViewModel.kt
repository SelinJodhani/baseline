package app.ojas

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ojas.data.AppSettings
import app.ojas.data.DataState
import app.ojas.data.Repository
import app.ojas.insight.InsightEngine
import app.ojas.insight.Insights
import app.ojas.widget.TodayWidget
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

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ojas = app as OjasApp
    private val store = ojas.store
    private val repo = ojas.repository

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
        repo.refresh()
        TodayWidget.updateAll(getApplication())
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

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { store.update(transform) }

    suspend fun test(url: String, token: String): String? = Repository.testConnection(url.trim(), token.trim())
}
