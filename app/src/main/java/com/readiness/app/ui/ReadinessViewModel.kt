package com.readiness.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readiness.app.data.Snapshot
import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.repo.ReadinessRepository
import com.readiness.app.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val snapshot: Snapshot? = null,
    val loading: Boolean = false,
    val filling: Boolean = false,
    val error: String? = null,
    val settings: SettingsState = SettingsState(),
)

data class SettingsState(
    val apiKey: String = "", val athlete: String = "0",
    val cycles: Int = 1, val sleepNeed: String = "", val napMinutes: String = "",
)

class ReadinessViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ReadinessRepository(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var fillJob: Job? = null

    init {
        _state.value = _state.value.copy(snapshot = repo.cached(), settings = readSettings())
        if (repo.settings.apiKey.isNotBlank()) refresh()
    }

    private fun readSettings() = with(repo.settings) {
        SettingsState(apiKey, athlete, cycles,
            sleepNeedHours?.let { String.format("%.2f", it).trimEnd('0').trimEnd('.', ',') } ?: "",
            napMinutes.takeIf { it > 0 }?.toString() ?: "")
    }

    fun refresh() {
        if (_state.value.loading) return
        fillJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val (snap, _) = withContext(Dispatchers.IO) { repo.refresh() }
                _state.value = _state.value.copy(snapshot = snap, loading = false)
                WidgetUpdater.update(getApplication())
                startBackgroundFill()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Fehler beim Laden")
            }
        }
    }

    /**
     * Nach dem ersten Anzeigen im Hintergrund weiterladen, bis alle Kraftdaten
     * ausgewertet sind. Ohne das endete das Nachladen beim ersten Bild und der Rest kam
     * erst beim nächsten manuellen Aufruf — praktisch ein „komm später wieder".
     */
    private fun startBackgroundFill() {
        val scan = _state.value.snapshot?.progression
        fillJob = viewModelScope.launch {
            _state.value = _state.value.copy(filling = true)
            try {
                repeat(40) {
                    val (snap, more) = withContext(Dispatchers.IO) { repo.fillTorqueStep() }
                    _state.value = _state.value.copy(snapshot = snap)
                    if (!more) return@repeat
                    delay(250)
                }
            } catch (_: Exception) {
                // Nachladen ist bestes Bemühen — ein Fehler darf die Anzeige nicht kippen
            } finally {
                _state.value = _state.value.copy(filling = false)
                WidgetUpdater.update(getApplication())
            }
        }
    }

    fun saveSettings(s: SettingsState) {
        with(repo.settings) {
            apiKey = s.apiKey; athlete = s.athlete.ifBlank { "0" }
            cycles = s.cycles
            sleepNeedHours = s.sleepNeed.replace(',', '.').toDoubleOrNull()
            napMinutes = s.napMinutes.toIntOrNull() ?: 0
        }
        _state.value = _state.value.copy(settings = readSettings())
        refresh()
    }

    fun setCycles(n: Int) {
        repo.settings.cycles = n
        _state.value = _state.value.copy(settings = _state.value.settings.copy(cycles = n))
        refresh()
    }

    fun setConfounders(date: String, causes: List<String>) {
        val keep = java.time.LocalDate.now().minusDays(AnalysisConfig(repo.settings.cycles).historyDays.toLong()).toString()
        repo.settings.setConfounder(date, causes, keep)
        refresh()
    }
}
