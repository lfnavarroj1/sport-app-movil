package com.lfnj.sport_app.features.nutrition.reminders.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.nutrition.reminders.data.local.ReminderDelivery
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionReminderConfigEntity
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionReminderDeliveryEntity
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersCodec
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersDao
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant

/** Resultado de descargar reglas y preferencias. Ninguna variante contiene datos del deportista. */
sealed interface ReminderRefresh {
    /** El BFF confirmó ruleset y preferencias y la configuración local quedó reemplazada. */
    data object Updated : ReminderRefresh
    data object NoConnection : ReminderRefresh
    /** `503`/`504`, tiempo agotado o renovación no disponible. */
    data class Unavailable(val correlationId: String?) : ReminderRefresh
    /** `403 capability_required`: el plan ya no incluye `nutrition.session.reminders`; se borra la configuración. */
    data object CapabilityRequired : ReminderRefresh
    /** `403 permission_required`: la cuenta no es de deportista. */
    data object PermissionRequired : ReminderRefresh
    data object InvalidResponse : ReminderRefresh
    data object SignedOut : ReminderRefresh
    data object StorageFailed : ReminderRefresh
}

/** Resultado de guardar las preferencias (`PUT` con `expected_version`). */
sealed interface ReminderSave {
    data object Saved : ReminderSave
    /** `409 version_conflict`: otra actualización ganó; se recargan las preferencias vigentes. */
    data object VersionConflict : ReminderSave
    /** `422 validation_error`: campos rechazados (`hydration.interval_minutes`, …), sin valores. */
    data class Invalid(val fields: List<String>) : ReminderSave
    /** Guardar exige red: no existe cola offline para las preferencias (ADR-045 §5). */
    data object NoConnection : ReminderSave
    data class Unavailable(val correlationId: String?) : ReminderSave
    data object CapabilityRequired : ReminderSave
    data object PermissionRequired : ReminderSave
    data object InvalidResponse : ReminderSave
    data object SignedOut : ReminderSave
    /** El BFF confirmó el cambio, pero no pudo guardarse en el dispositivo. */
    data object StorageFailed : ReminderSave
}

/** Operaciones de la pantalla de configuración; las implementa [SessionRemindersRepository]. */
interface SessionRemindersGateway {
    /** Última configuración confirmada del deportista, o `null` si nunca se descargó o es ilegible. */
    fun observe(owner: String): Flow<ReminderConfig?>
    suspend fun refresh(owner: String): ReminderRefresh
    suspend fun save(owner: String, hydration: ReminderSetting, nutrition: ReminderSetting, expectedVersion: Int): ReminderSave
}

/** Registro local de ocurrencias del motor (CA2, CA3). */
interface ReminderDeliveryStore {
    /** Registra la ocurrencia si no existía; `false` si ya se había presentado. */
    suspend fun claim(owner: String, sessionId: String, type: ReminderType, n: Int, deliveredAt: Instant, presentation: ReminderPresentation): Boolean
    suspend fun deliveries(owner: String, sessionId: String): List<ReminderDelivery>
    /** Retención: borra los registros de sesiones distintas de [sessionId]. */
    suspend fun keepOnly(owner: String, sessionId: String)
}

/** Limpieza local al terminar la sesión de la aplicación o cambiar de deportista. */
interface SessionRemindersStore {
    suspend fun clear(owner: String)
    suspend fun clearAllExcept(owner: String)
}

/**
 * Reglas y preferencias de HU038 offline first (ADR-026, ADR-045): el motor y la pantalla observan
 * la configuración cifrada del deportista y [refresh] la reemplaza con cada par confirmado por
 * `bff-mobile`. Guardar preferencias exige red y nunca se encola. El registro de entregas es solo
 * local.
 */
class SessionRemindersRepository(
    private val dao: SessionRemindersDao,
    private val codec: SessionRemindersCodec,
    private val api: SessionRemindersApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : SessionRemindersGateway, ReminderDeliveryStore, SessionRemindersStore {

    override fun observe(owner: String): Flow<ReminderConfig?> =
        dao.observeConfig(owner).map { entity -> entity?.let(codec::decodeConfig) }.distinctUntilChanged()

    override suspend fun refresh(owner: String): ReminderRefresh {
        val api = api ?: return ReminderRefresh.Unavailable(null)
        val rules = when (val outcome = sessionManager.authorized { auth -> apiCall(json) { api.rules(auth) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return ReminderRefresh.SignedOut
            AuthorizedResult.RenewalUnavailable -> return ReminderRefresh.Unavailable(null)
        }
        val ruleset = when (rules) {
            is ApiResult.Success -> try {
                rules.value.toRuleset()
            } catch (_: InvalidSessionRemindersResponse) {
                return ReminderRefresh.InvalidResponse
            }
            else -> return refreshFailure(owner, rules)
        }
        val fetched = fetchPreferences(api) ?: return ReminderRefresh.SignedOut
        val preferences = when (fetched) {
            is ApiResult.Success -> fetched.value ?: return ReminderRefresh.InvalidResponse
            else -> return refreshFailure(owner, fetched)
        }
        return storage(ReminderRefresh.StorageFailed) {
            dao.replaceConfig(SessionReminderConfigEntity(owner, codec.encodeConfig(ReminderConfig(ruleset, preferences, clock.instant()))))
            ReminderRefresh.Updated
        }
    }

    override suspend fun save(owner: String, hydration: ReminderSetting, nutrition: ReminderSetting, expectedVersion: Int): ReminderSave {
        val api = api ?: return ReminderSave.Unavailable(null)
        val body = ReminderPreferencesRequestDto(hydration.toDto(), nutrition.toDto(), expectedVersion)
        val result = when (val outcome = sessionManager.authorized { auth -> apiCall(json) { api.updatePreferences(auth, body) } }) {
            is AuthorizedResult.Completed -> outcome.result
            AuthorizedResult.SignedOut -> return ReminderSave.SignedOut
            AuthorizedResult.RenewalUnavailable -> return ReminderSave.Unavailable(null)
        }
        return when (result) {
            is ApiResult.Success -> {
                val preferences = try {
                    result.value.toPreferences()
                } catch (_: InvalidSessionRemindersResponse) {
                    return ReminderSave.InvalidResponse
                }
                storage(ReminderSave.StorageFailed) {
                    val current = dao.config(owner)?.let(codec::decodeConfig) ?: return@storage ReminderSave.StorageFailed
                    dao.replaceConfig(SessionReminderConfigEntity(owner, codec.encodeConfig(current.copy(preferences = preferences, syncedAt = clock.instant()))))
                    ReminderSave.Saved
                }
            }
            is ApiResult.HttpFailure -> when {
                result.status == 409 && result.code == CODE_VERSION_CONFLICT -> {
                    // La versión vigente se recarga para que el deportista decida sobre datos actuales.
                    refresh(owner)
                    ReminderSave.VersionConflict
                }
                result.status == 422 -> ReminderSave.Invalid(result.fieldIssues.mapNotNull { it.field })
                result.status == 403 && result.code == CODE_CAPABILITY_REQUIRED -> {
                    storage(Unit) { dao.deleteConfig(owner) }
                    ReminderSave.CapabilityRequired
                }
                result.status == 403 -> ReminderSave.PermissionRequired
                else -> ReminderSave.Unavailable(result.correlationId)
            }
            ApiResult.NoConnection -> ReminderSave.NoConnection
            ApiResult.Timeout, ApiResult.NotConfigured -> ReminderSave.Unavailable(null)
            ApiResult.InvalidResponse -> ReminderSave.InvalidResponse
        }
    }

    /** `null` si la sesión dejó de ser válida; un éxito con cuerpo incoherente se entrega como `Success(null)`. */
    private suspend fun fetchPreferences(api: SessionRemindersApi): ApiResult<ReminderPreferences?>? =
        when (val outcome = sessionManager.authorized { auth -> apiCall(json) { api.preferences(auth) } }) {
            is AuthorizedResult.Completed -> when (val result = outcome.result) {
                is ApiResult.Success -> ApiResult.Success(
                    try {
                        result.value.toPreferences()
                    } catch (_: InvalidSessionRemindersResponse) {
                        null
                    }
                )
                is ApiResult.Failure -> result
            }
            AuthorizedResult.SignedOut -> null
            AuthorizedResult.RenewalUnavailable -> ApiResult.Timeout
        }

    private suspend fun refreshFailure(owner: String, failure: ApiResult<*>): ReminderRefresh = when (failure) {
        is ApiResult.HttpFailure -> when {
            failure.status == 403 && failure.code == CODE_CAPABILITY_REQUIRED -> {
                // Sin la capacidad el motor no debe seguir evaluando una configuración anterior.
                storage(Unit) { dao.deleteConfig(owner) }
                ReminderRefresh.CapabilityRequired
            }
            failure.status == 403 -> ReminderRefresh.PermissionRequired
            else -> ReminderRefresh.Unavailable(failure.correlationId)
        }
        ApiResult.NoConnection -> ReminderRefresh.NoConnection
        ApiResult.InvalidResponse -> ReminderRefresh.InvalidResponse
        else -> ReminderRefresh.Unavailable(null)
    }

    override suspend fun claim(
        owner: String,
        sessionId: String,
        type: ReminderType,
        n: Int,
        deliveredAt: Instant,
        presentation: ReminderPresentation,
    ): Boolean {
        val entity = SessionReminderDeliveryEntity(owner, sessionId, SessionRemindersCodec.occurrenceKey(type, n), codec.encodeDelivery(deliveredAt, presentation))
        return dao.claim(entity) != -1L
    }

    override suspend fun deliveries(owner: String, sessionId: String): List<ReminderDelivery> =
        dao.deliveries(owner, sessionId).mapNotNull(codec::decodeDelivery)

    override suspend fun keepOnly(owner: String, sessionId: String) = dao.deleteDeliveriesExceptSession(owner, sessionId)

    override suspend fun clear(owner: String) {
        dao.deleteConfig(owner)
        dao.deleteDeliveries(owner)
    }

    override suspend fun clearAllExcept(owner: String) {
        dao.deleteConfigsExcept(owner)
        dao.deleteDeliveriesExcept(owner)
    }

    /** Errores de Room o del Keystore se informan como falla de almacenamiento, sin detalles. */
    private suspend fun <T> storage(onFailure: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        onFailure
    }

    private companion object {
        const val CODE_CAPABILITY_REQUIRED = "capability_required"
        const val CODE_VERSION_CONFLICT = "version_conflict"
    }
}

/**
 * Elimina la configuración y el registro de recordatorios cuando termina la sesión de un deportista
 * (cierre, vencimiento o cambio de cuenta) y, al autenticarse uno, los de cualquier otro deportista
 * que hubiera quedado en el dispositivo (patrón ADR-031).
 */
class SessionRemindersJanitor(
    private val currentUser: StateFlow<SessionUser?>,
    private val store: SessionRemindersStore,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var previous: String? = null
        currentUser
            .map { user -> user?.userId?.let(TrainingOwner::keyOf) }
            .distinctUntilChanged()
            .catch { }
            .collect { owner ->
                try {
                    previous?.takeIf { it != owner }?.let { store.clear(it) }
                    owner?.let { store.clearAllExcept(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // La limpieza se reintenta en el próximo cambio de sesión; la partición protege mientras tanto.
                }
                previous = owner
            }
    }
}
