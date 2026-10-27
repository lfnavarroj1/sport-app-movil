package com.lfnj.sport_app.features.nutrition.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderRefresh
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderSave
import com.lfnj.sport_app.features.nutrition.reminders.data.SessionRemindersGateway
import com.lfnj.sport_app.features.nutrition.reminders.engine.ReminderNotifier
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Motivo por el que la configuración no pudo confirmarse; nunca describe datos ni detalles técnicos. */
enum class RemindersProblem { NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, INVALID_RESPONSE, STORAGE }

/** Resultado visible del último intento de guardar. */
enum class RemindersSaveOutcome { SAVED, VERSION_CONFLICT, INVALID, NO_CONNECTION, UNAVAILABLE, CAPABILITY_REQUIRED, PERMISSION_REQUIRED, STORAGE }

data class SessionRemindersUiState(
    val zone: ZoneId,
    /** La caché local aún no respondió. */
    val loadingCache: Boolean = true,
    val refreshing: Boolean = false,
    /** Última configuración confirmada (desde la caché), o `null` si nunca se descargó. */
    val config: ReminderConfig? = null,
    /** Cambios del deportista aún no guardados; `null` = iguales a los confirmados. */
    val draftHydration: ReminderSetting? = null,
    val draftNutrition: ReminderSetting? = null,
    val saving: Boolean = false,
    val problem: RemindersProblem? = null,
    val saveOutcome: RemindersSaveOutcome? = null,
    val systemNotifications: SystemNotificationsStatus = SystemNotificationsStatus.ENABLED,
    val sessionExpired: Boolean = false,
) {
    /** Sin datos que mostrar y esperando la caché o el BFF. */
    val loading: Boolean get() = config == null && (loadingCache || (refreshing && problem == null))

    /** Nunca se descargó el ruleset: no hay recordatorios y la pantalla lo declara (CA5). */
    val insufficientInformation: Boolean get() = config == null && !loading

    fun setting(type: ReminderType): ReminderSetting? = when (type) {
        ReminderType.HYDRATION -> draftHydration
        ReminderType.NUTRITION -> draftNutrition
    } ?: config?.preferences?.setting(type)

    val dirty: Boolean get() = draftHydration != null || draftNutrition != null

    /** Los intervalos editados están dentro de los rangos del ruleset (la validación final es del BFF). */
    val valid: Boolean
        get() = config != null && ReminderType.entries.all { type ->
            val rule = config.ruleset.rule(type)
            val setting = setting(type)
            rule != null && setting != null && rule.accepts(setting.intervalMinutes)
        }

    val canSave: Boolean get() = dirty && valid && !saving
}

/**
 * «Recordatorios durante sesión» (HU038). Observa la configuración cifrada del deportista
 * ([owner]) y la actualiza desde el BFF; sin red conserva la última confirmada. Guardar exige red
 * y usa la versión leída; un conflicto recarga la vigente. Lee, sin solicitarlo, el estado de las
 * notificaciones del sistema para declarar cuando solo habrá banner (CA4).
 */
class SessionRemindersViewModel(
    private val owner: String,
    private val gateway: SessionRemindersGateway,
    private val notifier: ReminderNotifier,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionRemindersUiState(zone = zone, systemNotifications = probe()))
    val uiState: StateFlow<SessionRemindersUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            gateway.observe(owner)
                .catch { emit(null) }
                .collect { config -> _uiState.update { it.copy(loadingCache = false, config = config) } }
        }
        refresh()
    }

    fun retry() = refresh()

    fun refreshSystemStatus() = _uiState.update { it.copy(systemNotifications = probe()) }

    fun setEnabled(type: ReminderType, enabled: Boolean) = edit(type) { it.copy(enabled = enabled) }

    /** Ajusta el intervalo en [deltaMinutes] sin salir del rango de la regla. */
    fun changeInterval(type: ReminderType, deltaMinutes: Int) {
        val rule = _uiState.value.config?.ruleset?.rule(type) ?: return
        edit(type) { it.copy(intervalMinutes = (it.intervalMinutes + deltaMinutes).coerceIn(rule.minIntervalMinutes, rule.maxIntervalMinutes)) }
    }

    fun discardChanges() = _uiState.update { it.copy(draftHydration = null, draftNutrition = null, saveOutcome = null) }

    fun save() {
        val state = _uiState.value
        if (saveJob?.isActive == true || !state.canSave) return
        val config = state.config ?: return
        val hydration = state.setting(ReminderType.HYDRATION) ?: return
        val nutrition = state.setting(ReminderType.NUTRITION) ?: return
        _uiState.update { it.copy(saving = true, saveOutcome = null) }
        saveJob = viewModelScope.launch {
            val result = try {
                gateway.save(owner, hydration, nutrition, config.preferences.version)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ReminderSave.StorageFailed
            }
            _uiState.update {
                val keepDraft = result !is ReminderSave.Saved && result != ReminderSave.VersionConflict
                it.copy(
                    saving = false,
                    saveOutcome = result.toOutcome(),
                    draftHydration = it.draftHydration.takeIf { keepDraft },
                    draftNutrition = it.draftNutrition.takeIf { keepDraft },
                    sessionExpired = result == ReminderSave.SignedOut,
                )
            }
        }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun edit(type: ReminderType, change: (ReminderSetting) -> ReminderSetting) = _uiState.update { state ->
        val confirmed = state.config?.preferences?.setting(type) ?: return@update state
        val updated = change(state.setting(type) ?: confirmed).takeIf { it != confirmed }
        when (type) {
            ReminderType.HYDRATION -> state.copy(draftHydration = updated, saveOutcome = null)
            ReminderType.NUTRITION -> state.copy(draftNutrition = updated, saveOutcome = null)
        }
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        _uiState.update { it.copy(refreshing = true, problem = null) }
        refreshJob = viewModelScope.launch {
            val result = try {
                gateway.refresh(owner)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ReminderRefresh.StorageFailed
            }
            _uiState.update {
                it.copy(refreshing = false, problem = result.toProblem(), sessionExpired = result == ReminderRefresh.SignedOut)
            }
        }
    }

    private fun probe(): SystemNotificationsStatus = try {
        notifier.status()
    } catch (_: Exception) {
        SystemNotificationsStatus.DISABLED
    }

    private fun ReminderRefresh.toProblem(): RemindersProblem? = when (this) {
        ReminderRefresh.Updated, ReminderRefresh.SignedOut -> null
        ReminderRefresh.NoConnection -> RemindersProblem.NO_CONNECTION
        is ReminderRefresh.Unavailable -> RemindersProblem.UNAVAILABLE
        ReminderRefresh.CapabilityRequired -> RemindersProblem.CAPABILITY_REQUIRED
        ReminderRefresh.PermissionRequired -> RemindersProblem.PERMISSION_REQUIRED
        ReminderRefresh.InvalidResponse -> RemindersProblem.INVALID_RESPONSE
        ReminderRefresh.StorageFailed -> RemindersProblem.STORAGE
    }

    private fun ReminderSave.toOutcome(): RemindersSaveOutcome? = when (this) {
        ReminderSave.Saved -> RemindersSaveOutcome.SAVED
        ReminderSave.VersionConflict -> RemindersSaveOutcome.VERSION_CONFLICT
        is ReminderSave.Invalid -> RemindersSaveOutcome.INVALID
        ReminderSave.NoConnection -> RemindersSaveOutcome.NO_CONNECTION
        is ReminderSave.Unavailable, ReminderSave.InvalidResponse -> RemindersSaveOutcome.UNAVAILABLE
        ReminderSave.CapabilityRequired -> RemindersSaveOutcome.CAPABILITY_REQUIRED
        ReminderSave.PermissionRequired -> RemindersSaveOutcome.PERMISSION_REQUIRED
        ReminderSave.StorageFailed -> RemindersSaveOutcome.STORAGE
        ReminderSave.SignedOut -> null
    }
}
