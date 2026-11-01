package com.lfnj.sport_app.features.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.notifications.data.PreferencesGateway
import com.lfnj.sport_app.features.notifications.data.PreferencesResult
import com.lfnj.sport_app.features.notifications.data.SavePreferencesResult
import com.lfnj.sport_app.features.notifications.model.NotificationPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Motivo por el que las preferencias no pudieron leerse o guardarse. */
enum class PreferencesProblem { NO_CONNECTION, UNAVAILABLE, PERMISSION_REQUIRED, INVALID, CONFLICT }

data class NotificationPreferencesUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** Últimas preferencias confirmadas por el BFF, o `null` si no se han podido leer. */
    val confirmed: NotificationPreferences? = null,
    /** Valores elegidos en pantalla; parten de [confirmed]. */
    val eventMatches: Boolean = false,
    val eventReminders: Boolean = false,
    val problem: PreferencesProblem? = null,
    /** El último guardado fue confirmado por el BFF. */
    val saved: Boolean = false,
    val sessionExpired: Boolean = false,
) {
    val changed: Boolean
        get() = confirmed != null && (eventMatches != confirmed.eventMatches || eventReminders != confirmed.eventReminders)

    val canSave: Boolean get() = changed && !saving && !loading
}

/**
 * Preferencias de HU039 (`event_matches`, `event_reminders`). Solo con conectividad: se leen del
 * BFF y se reemplazan con `expected_version`; ante `409 version_conflict` se vuelven a leer y no se
 * sobrescribe la versión ajena. `push_enabled` se conserva como lo confirmó el BFF porque la entrega
 * push (FCM) no está habilitada en la aplicación.
 */
class NotificationPreferencesViewModel(private val gateway: PreferencesGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(NotificationPreferencesUiState())
    val uiState: StateFlow<NotificationPreferencesUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun retry() = load()

    fun setEventMatches(enabled: Boolean) = _uiState.update { it.copy(eventMatches = enabled, saved = false) }

    fun setEventReminders(enabled: Boolean) = _uiState.update { it.copy(eventReminders = enabled, saved = false) }

    fun save() {
        val state = _uiState.value
        val confirmed = state.confirmed ?: return
        if (!state.canSave || job?.isActive == true) return
        _uiState.update { it.copy(saving = true, problem = null, saved = false) }
        val desired = confirmed.copy(eventMatches = state.eventMatches, eventReminders = state.eventReminders)
        job = viewModelScope.launch {
            val result = try {
                gateway.savePreferences(desired)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SavePreferencesResult.Unavailable(null)
            }
            when (result) {
                is SavePreferencesResult.Saved -> _uiState.update {
                    it.copy(saving = false, saved = true, confirmed = result.preferences,
                        eventMatches = result.preferences.eventMatches, eventReminders = result.preferences.eventReminders)
                }
                SavePreferencesResult.Conflict -> {
                    _uiState.update { it.copy(saving = false) }
                    reload(PreferencesProblem.CONFLICT)
                }
                SavePreferencesResult.SignedOut -> _uiState.update { it.copy(saving = false, sessionExpired = true) }
                else -> _uiState.update { it.copy(saving = false, problem = result.toProblem()) }
            }
        }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun load() {
        if (job?.isActive == true) return
        job = viewModelScope.launch { reload(null) }
    }

    /** Lee la versión vigente; [reason] se conserva para explicar por qué se recargó. */
    private suspend fun reload(reason: PreferencesProblem?) {
        _uiState.update { it.copy(loading = true, problem = null) }
        val result = try {
            gateway.preferences()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PreferencesResult.Unavailable(null)
        }
        _uiState.update {
            when (result) {
                is PreferencesResult.Loaded -> it.copy(
                    loading = false,
                    confirmed = result.preferences,
                    eventMatches = result.preferences.eventMatches,
                    eventReminders = result.preferences.eventReminders,
                    problem = reason,
                )
                PreferencesResult.SignedOut -> it.copy(loading = false, sessionExpired = true)
                PreferencesResult.NoConnection -> it.copy(loading = false, problem = PreferencesProblem.NO_CONNECTION)
                PreferencesResult.PermissionRequired -> it.copy(loading = false, confirmed = null, problem = PreferencesProblem.PERMISSION_REQUIRED)
                is PreferencesResult.Unavailable, PreferencesResult.InvalidResponse -> it.copy(loading = false, problem = PreferencesProblem.UNAVAILABLE)
            }
        }
    }

    private fun SavePreferencesResult.toProblem(): PreferencesProblem = when (this) {
        SavePreferencesResult.NoConnection -> PreferencesProblem.NO_CONNECTION
        SavePreferencesResult.PermissionRequired -> PreferencesProblem.PERMISSION_REQUIRED
        is SavePreferencesResult.Invalid -> PreferencesProblem.INVALID
        SavePreferencesResult.Conflict -> PreferencesProblem.CONFLICT
        else -> PreferencesProblem.UNAVAILABLE
    }
}
