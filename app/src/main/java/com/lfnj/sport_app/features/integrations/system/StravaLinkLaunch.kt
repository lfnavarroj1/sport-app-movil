package com.lfnj.sport_app.features.integrations.system

import com.lfnj.sport_app.features.integrations.StravaLinkResults
import com.lfnj.sport_app.features.integrations.model.StravaLinkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Traslada el retorno desde Strava (enlace profundo del callback de `bff-mobile`) a la pantalla de
 * Strava. [StravaRedirectActivity] lo ofrece; la navegación abre la pantalla tras validar la sesión
 * y su ViewModel lo consume una sola vez. Solo conserva el resultado, nunca el enlace completo.
 */
class StravaLinkLaunch : StravaLinkResults {
    private val _pending = MutableStateFlow<StravaLinkResult?>(null)
    override val pending: StateFlow<StravaLinkResult?> = _pending.asStateFlow()

    /** Registra el resultado si [link] es el retorno de Strava; devuelve si lo aceptó. */
    fun offer(link: String?): Boolean {
        val result = StravaLinkResult.fromDeepLink(link) ?: return false
        _pending.value = result
        return true
    }

    /** Devuelve y elimina el resultado pendiente. */
    override fun consume(): StravaLinkResult? = _pending.getAndUpdate { null }
}
