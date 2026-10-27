package com.lfnj.sport_app.features.nutrition.reminders.engine

import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.data.ReminderDeliveryStore
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.nutrition.reminders.model.SessionReminder
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Motor local de HU038 (ADR-045 §3, §6): durante la sesión `ACTIVE` evalúa la configuración
 * cifrada del deportista sobre el cronómetro de HU032, sin red. Cada ocurrencia `(session_id, type,
 * n)` se reclama primero en el registro local y solo entonces se presenta, de modo que un reinicio
 * del servicio o de la aplicación nunca la repite (CA3). Tras un reinicio solo se presenta la
 * ocurrencia vigente de cada tipo, no las que vencieron mientras el proceso no corría. Sin
 * configuración descargada, con el tipo deshabilitado o sin la capacidad no genera nada (CA4, CA5).
 */
class SessionReminderEngine(
    private val configs: (owner: String) -> Flow<ReminderConfig?>,
    /** `false` cuando las capacidades conocidas ya no incluyen `nutrition.session.reminders`. */
    private val allowed: Flow<Boolean>,
    private val deliveries: ReminderDeliveryStore,
    private val notifier: ReminderNotifier,
    private val banners: ReminderBannerCenter,
    private val clock: TrainingClock,
    /** Descarga de reglas y preferencias al iniciar la sesión, si hay red; nunca bloquea al motor. */
    private val refresh: suspend (owner: String) -> Unit = {},
    private val pollMillis: Long = POLL_MILLIS,
) : ActiveSessionTask {

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun run(session: ActiveSession) {
        try {
            coroutineScope {
                launch { quietly { refresh(session.owner) } }
                quietly { deliveries.keepOnly(session.owner, session.sessionId) }
                configs(session.owner)
                    .combine(allowed) { config, permitted -> config?.takeIf { permitted } }
                    .distinctUntilChanged()
                    .catch { emit(null) }
                    .collectLatest { config -> if (config != null) evaluate(session, config) }
            }
        } finally {
            banners.clearSession(session.sessionId)
        }
    }

    private suspend fun evaluate(session: ActiveSession, config: ReminderConfig) {
        while (true) {
            val elapsed = session.elapsedMillis(clock)
            ReminderType.entries.forEach { type -> deliverDue(session, config, type, elapsed) }
            val next = ReminderType.entries.mapNotNull { type ->
                config.ruleset.rule(type)?.let { ReminderSchedule.nextDueAt(it, config.preferences.setting(type), elapsed) }
            }.minOrNull()
            // Espera corta y acotada: absorbe cambios del reloj y mantiene la entrega en < 3 s (HA007).
            delay(next?.let { (it - elapsed).coerceIn(1, pollMillis) } ?: pollMillis)
        }
    }

    private suspend fun deliverDue(session: ActiveSession, config: ReminderConfig, type: ReminderType, elapsed: Long) {
        val rule = config.ruleset.rule(type) ?: return
        val occurrence = ReminderSchedule.latestDue(rule, config.preferences.setting(type), elapsed) ?: return
        val reminder = SessionReminder(session.sessionId, type, occurrence.n, occurrence.dueAtElapsedMillis, rule.message, rule.disclaimer)
        val systemEnabled = quietly { notifier.status() } == SystemNotificationsStatus.ENABLED
        val presentation = if (systemEnabled) ReminderPresentation.NOTIFICATION_AND_BANNER else ReminderPresentation.BANNER_ONLY
        val claimed = quietly { deliveries.claim(session.owner, session.sessionId, type, occurrence.n, clock.now(), presentation) } == true
        if (!claimed) return
        val shown = systemEnabled && quietly { notifier.notify(reminder) } == true
        banners.show(ReminderBanner(session.sessionId, type, occurrence.n, rule.message, rule.disclaimer, systemNotificationShown = shown))
    }

    /** Fallas de almacenamiento o del sistema no detienen el motor ni exponen detalles. */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Intervalo máximo entre evaluaciones, muy por debajo del límite de 3 s de HA007. */
        const val POLL_MILLIS = 1_000L
    }
}
