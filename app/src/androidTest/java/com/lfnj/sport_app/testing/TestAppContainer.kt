package com.lfnj.sport_app.testing

import android.content.Context
import androidx.room.Room
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.core.session.SecureTokenStore
import com.lfnj.sport_app.core.session.SessionRevoker
import com.lfnj.sport_app.core.session.revocation.RevocationDatabase
import com.lfnj.sport_app.core.session.revocation.RevocationQueue
import com.lfnj.sport_app.core.session.revocation.RevocationScheduler
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.di.AppContainer
import com.lfnj.sport_app.features.calendar.data.local.CalendarDatabase
import com.lfnj.sport_app.features.events.data.ConnectivityMonitor
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsDatabase
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsDatabase
import com.lfnj.sport_app.features.health.data.local.HealthReportsDatabase
import com.lfnj.sport_app.features.recommendations.data.local.PlanSuggestionsDatabase
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsDatabase
import com.lfnj.sport_app.features.nutrition.routines.data.local.NutritionRoutinesDatabase
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsDatabase
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsDatabase
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardDatabase
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsDatabase
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TrainingDatabase
import com.lfnj.sport_app.features.notifications.data.NotificationAnnouncer
import com.lfnj.sport_app.features.notifications.data.local.NotificationsDatabase
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.notifications.sync.InboxSyncScheduler
import com.lfnj.sport_app.features.notifications.system.SystemNotificationsProbe
import com.lfnj.sport_app.features.nutrition.intake.data.local.FoodIntakeDatabase
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersDatabase
import com.lfnj.sport_app.features.nutrition.reminders.engine.ReminderNotifier
import com.lfnj.sport_app.features.nutrition.reminders.model.SessionReminder
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionPointer
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyStore

/** Almacenamiento seguro en memoria para recorridos de prueba; el Keystore se prueba aparte. */
class InMemoryTokenStore : SecureTokenStore {
    var value: String? = null
    override fun read() = value
    override fun write(value: String) {
        this.value = value
    }
    override fun clear() {
        value = null
    }
}

/** Registra las solicitudes de sincronización: la prueba ejecuta el motor cuando corresponde. */
class RecordingScheduler : TrainingSyncScheduler {
    val scheduled = mutableListOf<String>()
    var cancellations = 0

    override fun schedule(owner: String) {
        scheduled += owner
    }

    override fun cancelAll() {
        cancellations++
    }
}

/** Registra las programaciones del trabajo de revocación (HU004); la prueba ejecuta la cola. */
class RecordingRevocationScheduler : RevocationScheduler {
    var scheduled = 0
    override fun schedule() {
        scheduled++
    }
}

/** Registra los anuncios de HU039 en lugar de publicarlos en el sistema del teléfono. */
class RecordingNotificationAnnouncer : NotificationAnnouncer {
    val announced = mutableListOf<InboxNotification>()
    val dismissed = mutableListOf<String>()
    override fun announce(items: List<InboxNotification>) {
        announced += items
    }
    override fun dismiss(notificationId: String) {
        dismissed += notificationId
    }
}

/** Registra la programación del trabajo periódico de HU039; la prueba ejecuta el motor. */
class RecordingInboxSyncScheduler : InboxSyncScheduler {
    var scheduled = 0
    var cancelled = 0
    override fun schedule() {
        scheduled++
    }
    override fun cancel() {
        cancelled++
    }
}

/** HU038: notificaciones locales registradas en lugar de publicarlas en el teléfono. */
class RecordingReminderNotifier(var status: SystemNotificationsStatus = SystemNotificationsStatus.ENABLED) : ReminderNotifier {
    val posted = java.util.Collections.synchronizedList(mutableListOf<SessionReminder>())
    override fun status(): SystemNotificationsStatus = status
    override fun notify(reminder: SessionReminder): Boolean {
        if (status != SystemNotificationsStatus.ENABLED) return false
        posted += reminder
        return true
    }
}

/** HU038: puntero de la sesión activa en memoria, para no tocar las preferencias de la aplicación instalada. */
class InMemoryActiveSessionPointer : ActiveSessionPointer {
    @Volatile var value: Pair<String, String>? = null
    override fun read() = value
    override fun write(owner: String, sessionId: String) {
        value = owner to sessionId
    }
    override fun clear() {
        value = null
    }
}

/**
 * Contenedor real con bases de HU026, HU028, HU029, HU038, HU039, HU040, HU042, HU043, HU032, HU033, HU034, HU035, HU041 y la cola de revocación de HU004 en memoria, claves de
 * Keystore de prueba y sincronización controlada por la prueba (sin WorkManager), para no tocar los
 * datos de la aplicación instalada.
 */
class TestAppContainer(
    config: AppConfig,
    store: SecureTokenStore,
    context: Context,
    private val revoker: SessionRevoker? = null,
) : AppContainer(config, store, context) {
    val database: TrainingDatabase = Room.inMemoryDatabaseBuilder(context, TrainingDatabase::class.java).build()
    val scheduler = RecordingScheduler()

    val revocation: RevocationDatabase = Room.inMemoryDatabaseBuilder(context, RevocationDatabase::class.java).build()
    val revocationRecorder = RecordingRevocationScheduler()

    /** Desplaza el reloj de la cola para alcanzar la próxima ejecución sin esperar. */
    var revocationClockOffsetMillis = 0L

    override val revocationDatabase: RevocationDatabase get() = revocation
    override val revocationCipher: TokenCipher = KeystoreTokenCipher(REVOCATION_TEST_KEY_ALIAS)
    override val revocationScheduler: RevocationScheduler get() = revocationRecorder
    override val revocationQueue: RevocationQueue by lazy {
        RevocationQueue(
            dao = revocation.revocationDao(),
            cipher = revocationCipher,
            api = bffApi(SessionApi::class.java),
            json = BffMobileClient.json,
            scheduler = revocationRecorder,
            clock = { System.currentTimeMillis() + revocationClockOffsetMillis },
        )
    }

    /** Sin [revoker] explícito se usa la cola real de revocación contra el BFF simulado. */
    override val sessionRevoker: SessionRevoker? get() = revoker ?: revocationQueue
    override val trainingDatabase: TrainingDatabase get() = database
    override val trainingSyncScheduler: TrainingSyncScheduler get() = scheduler
    override val trainingCipher: TokenCipher = KeystoreTokenCipher(TEST_KEY_ALIAS)

    val metrics: TrainingMetricsDatabase = Room.inMemoryDatabaseBuilder(context, TrainingMetricsDatabase::class.java).build()
    val metricsScheduler = RecordingScheduler()
    override val trainingMetricsDatabase: TrainingMetricsDatabase get() = metrics
    override val trainingMetricsCipher: TokenCipher = KeystoreTokenCipher(METRICS_TEST_KEY_ALIAS)
    override val trainingMetricsScheduler: TrainingSyncScheduler get() = metricsScheduler

    val calendar: CalendarDatabase = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    override val calendarDatabase: CalendarDatabase get() = calendar
    override val calendarCipher: TokenCipher = KeystoreTokenCipher(CALENDAR_TEST_KEY_ALIAS)

    // HU028: caché de entrenamientos sugeridos en memoria con su clave de prueba.
    val planSuggestions: PlanSuggestionsDatabase = Room.inMemoryDatabaseBuilder(context, PlanSuggestionsDatabase::class.java).build()
    override val planSuggestionsDatabase: PlanSuggestionsDatabase get() = planSuggestions
    override val planSuggestionsCipher: TokenCipher = KeystoreTokenCipher(PLAN_SUGGESTIONS_TEST_KEY_ALIAS)
    val dashboard: TrainingDashboardDatabase = Room.inMemoryDatabaseBuilder(context, TrainingDashboardDatabase::class.java).build()
    override val trainingDashboardDatabase: TrainingDashboardDatabase get() = dashboard
    override val trainingDashboardCipher: TokenCipher = KeystoreTokenCipher(DASHBOARD_TEST_KEY_ALIAS)

    // HU026: caché de rutas sugeridas en memoria con su clave de prueba.
    val routeSuggestions: RouteSuggestionsDatabase = Room.inMemoryDatabaseBuilder(context, RouteSuggestionsDatabase::class.java).build()
    override val routeSuggestionsDatabase: RouteSuggestionsDatabase get() = routeSuggestions
    override val routeSuggestionsCipher: TokenCipher = KeystoreTokenCipher(ROUTE_SUGGESTIONS_TEST_KEY_ALIAS)
    // HU041: «Mis inscripciones» en memoria con su clave de prueba y conectividad controlada por la prueba.
    val eventRegistrations: EventRegistrationsDatabase = Room.inMemoryDatabaseBuilder(context, EventRegistrationsDatabase::class.java).build()
    override val eventRegistrationsDatabase: EventRegistrationsDatabase get() = eventRegistrations
    override val eventRegistrationsCipher: TokenCipher = KeystoreTokenCipher(EVENT_REGISTRATIONS_TEST_KEY_ALIAS)
    val online = MutableStateFlow(true)
    override val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor { online }
    // HU027: caché de eventos sugeridos en memoria con su clave de prueba.
    val eventSuggestions: EventSuggestionsDatabase = Room.inMemoryDatabaseBuilder(context, EventSuggestionsDatabase::class.java).build()
    override val eventSuggestionsDatabase: EventSuggestionsDatabase get() = eventSuggestions
    override val eventSuggestionsCipher: TokenCipher = KeystoreTokenCipher(EVENT_SUGGESTIONS_TEST_KEY_ALIAS)

    // HU040: caché de sesiones virtuales en memoria con su clave de prueba.
    val virtualSessions: VirtualSessionsDatabase = Room.inMemoryDatabaseBuilder(context, VirtualSessionsDatabase::class.java).build()
    override val virtualSessionsDatabase: VirtualSessionsDatabase get() = virtualSessions
    override val virtualSessionsCipher: TokenCipher = KeystoreTokenCipher(VIRTUAL_SESSIONS_TEST_KEY_ALIAS)

    // HU039: bandeja en memoria con su clave de prueba, anuncios registrados y estado del sistema controlado.
    val notifications: NotificationsDatabase = Room.inMemoryDatabaseBuilder(context, NotificationsDatabase::class.java).build()
    override val notificationsDatabase: NotificationsDatabase get() = notifications
    override val notificationsCipher: TokenCipher = KeystoreTokenCipher(NOTIFICATIONS_TEST_KEY_ALIAS)
    val announcer = RecordingNotificationAnnouncer()
    override val notificationAnnouncer: NotificationAnnouncer get() = announcer
    val inboxScheduler = RecordingInboxSyncScheduler()
    override val inboxSyncScheduler: InboxSyncScheduler get() = inboxScheduler
    var systemNotifications = SystemNotificationsStatus.ENABLED
    override val systemNotificationsProbe: SystemNotificationsProbe = SystemNotificationsProbe { systemNotifications }
    // HU029: caché de acompañamiento y logística sugeridos en memoria con su clave de prueba.
    val supportSuggestions: SupportSuggestionsDatabase = Room.inMemoryDatabaseBuilder(context, SupportSuggestionsDatabase::class.java).build()
    override val supportSuggestionsDatabase: SupportSuggestionsDatabase get() = supportSuggestions
    override val supportSuggestionsCipher: TokenCipher = KeystoreTokenCipher(SUPPORT_SUGGESTIONS_TEST_KEY_ALIAS)
    // HU030: caché de rutinas de alimentación sugeridas en memoria con su clave de prueba.
    val nutritionRoutines: NutritionRoutinesDatabase = Room.inMemoryDatabaseBuilder(context, NutritionRoutinesDatabase::class.java).build()
    override val nutritionRoutinesDatabase: NutritionRoutinesDatabase get() = nutritionRoutines
    override val nutritionRoutinesCipher: TokenCipher = KeystoreTokenCipher(NUTRITION_ROUTINES_TEST_KEY_ALIAS)
    // HU042: reportes de salud en memoria con su clave de prueba y sincronización controlada por la prueba.
    val healthReports: HealthReportsDatabase = Room.inMemoryDatabaseBuilder(context, HealthReportsDatabase::class.java).build()
    val healthScheduler = RecordingScheduler()
    override val healthReportsDatabase: HealthReportsDatabase get() = healthReports
    override val healthReportsCipher: TokenCipher = KeystoreTokenCipher(HEALTH_REPORTS_TEST_KEY_ALIAS)
    override val healthReportsScheduler: TrainingSyncScheduler get() = healthScheduler

    // HU038: recordatorios en memoria con su clave de prueba, notificaciones registradas, puntero en memoria
    // y reloj del motor ajustable (solo pruebas: permite recorrer una sesión de 70 min acelerada).
    val sessionReminders: SessionRemindersDatabase = Room.inMemoryDatabaseBuilder(context, SessionRemindersDatabase::class.java).build()
    override val sessionRemindersDatabase: SessionRemindersDatabase get() = sessionReminders
    public override val sessionRemindersCipher: TokenCipher = KeystoreTokenCipher(SESSION_REMINDERS_TEST_KEY_ALIAS)
    val recordingReminderNotifier = RecordingReminderNotifier()
    var useSystemReminderNotifier = false
    override val reminderNotifier: ReminderNotifier get() = if (useSystemReminderNotifier) androidReminderNotifier else recordingReminderNotifier
    var reminderClockOverride: TrainingClock? = null
    override val reminderClock: TrainingClock get() = reminderClockOverride ?: super.reminderClock
    val pointer = InMemoryActiveSessionPointer()
    override val activeSessionPointer: ActiveSessionPointer get() = pointer

    // HU043: catálogo y consumos en memoria con su clave de prueba; la prueba ejecuta el motor.
    val foodIntakes: FoodIntakeDatabase = Room.inMemoryDatabaseBuilder(context, FoodIntakeDatabase::class.java).build()
    val foodScheduler = RecordingScheduler()
    override val foodIntakeDatabase: FoodIntakeDatabase get() = foodIntakes
    override val foodIntakeCipher: TokenCipher = KeystoreTokenCipher(FOOD_INTAKES_TEST_KEY_ALIAS)
    override val foodIntakeScheduler: TrainingSyncScheduler get() = foodScheduler

    fun close() {
        database.close()
        calendar.close()
        revocation.close()
        metrics.close()
        planSuggestions.close()
        dashboard.close()
        routeSuggestions.close()
        virtualSessions.close()
        eventRegistrations.close()
        eventSuggestions.close()
        notifications.close()
        supportSuggestions.close()
        nutritionRoutines.close()
        healthReports.close()
        sessionReminders.close()
        foodIntakes.close()
        deleteTestKey()
        deleteTestKey(CALENDAR_TEST_KEY_ALIAS)
        deleteTestKey(REVOCATION_TEST_KEY_ALIAS)
        deleteTestKey(METRICS_TEST_KEY_ALIAS)
        deleteTestKey(PLAN_SUGGESTIONS_TEST_KEY_ALIAS)
        deleteTestKey(DASHBOARD_TEST_KEY_ALIAS)
        deleteTestKey(ROUTE_SUGGESTIONS_TEST_KEY_ALIAS)
        deleteTestKey(VIRTUAL_SESSIONS_TEST_KEY_ALIAS)
        deleteTestKey(EVENT_REGISTRATIONS_TEST_KEY_ALIAS)
        deleteTestKey(EVENT_SUGGESTIONS_TEST_KEY_ALIAS)
        deleteTestKey(NOTIFICATIONS_TEST_KEY_ALIAS)
        deleteTestKey(SUPPORT_SUGGESTIONS_TEST_KEY_ALIAS)
        deleteTestKey(NUTRITION_ROUTINES_TEST_KEY_ALIAS)
        deleteTestKey(HEALTH_REPORTS_TEST_KEY_ALIAS)
        deleteTestKey(SESSION_REMINDERS_TEST_KEY_ALIAS)
        deleteTestKey(FOOD_INTAKES_TEST_KEY_ALIAS)
    }

    companion object {
        const val TEST_KEY_ALIAS = "sportapp.training.test"
        const val CALENDAR_TEST_KEY_ALIAS = "sportapp.calendar.test"
        const val REVOCATION_TEST_KEY_ALIAS = "sportapp.revocation.test"
        const val METRICS_TEST_KEY_ALIAS = "sportapp.training-metrics.test"
        const val PLAN_SUGGESTIONS_TEST_KEY_ALIAS = "sportapp.plan_suggestions.test"
        const val DASHBOARD_TEST_KEY_ALIAS = "sportapp.training-dashboard.test"
        const val ROUTE_SUGGESTIONS_TEST_KEY_ALIAS = "sportapp.route_suggestions.test"
        const val VIRTUAL_SESSIONS_TEST_KEY_ALIAS = "sportapp.virtual_sessions.test"
        const val EVENT_REGISTRATIONS_TEST_KEY_ALIAS = "sportapp.event_registrations.test"
        const val EVENT_SUGGESTIONS_TEST_KEY_ALIAS = "sportapp.event_suggestions.test"
        const val NOTIFICATIONS_TEST_KEY_ALIAS = "sportapp.notifications.test"
        const val SUPPORT_SUGGESTIONS_TEST_KEY_ALIAS = "sportapp.support_suggestions.test"
        const val NUTRITION_ROUTINES_TEST_KEY_ALIAS = "sportapp.nutrition_routines.test"
        const val HEALTH_REPORTS_TEST_KEY_ALIAS = "sportapp.health-reports.test"
        const val SESSION_REMINDERS_TEST_KEY_ALIAS = "sportapp.session_reminders.test"
        const val FOOD_INTAKES_TEST_KEY_ALIAS = "sportapp.food-intakes.test"

        fun deleteTestKey(alias: String = TEST_KEY_ALIAS) = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
