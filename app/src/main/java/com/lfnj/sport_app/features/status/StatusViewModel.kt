package com.lfnj.sport_app.features.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfnj.sport_app.core.config.AppConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
    val appVersion: String,
    val environment: String,
    val contractVersion: String,
    val checking: Boolean = false,
    val availability: BffAvailability? = null,
)

/** Pantalla técnica de arranque: muestra la configuración y la disponibilidad del BFF móvil. */
class StatusViewModel(
    config: AppConfig,
    private val repository: StatusRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        StatusUiState(
            appVersion = config.appVersion,
            environment = config.environment,
            contractVersion = config.contractVersion,
        )
    )
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    private var check: Job? = null

    init {
        refresh()
    }

    /** Ignora pulsaciones repetidas mientras hay una verificación en curso. */
    fun refresh() {
        if (check?.isActive == true) return
        _uiState.update { it.copy(checking = true) }
        check = viewModelScope.launch {
            val availability = repository.bffAvailability()
            _uiState.update { it.copy(checking = false, availability = availability) }
        }
    }
}
