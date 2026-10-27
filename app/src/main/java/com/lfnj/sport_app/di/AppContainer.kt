package com.lfnj.sport_app.di

import android.content.Context
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.network.HealthApi
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.core.session.SecureTokenStore
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionRevoker
import com.lfnj.sport_app.core.session.revocation.RevocationDatabase
import com.lfnj.sport_app.core.session.revocation.RevocationQueue
import com.lfnj.sport_app.core.session.revocation.RevocationResumer
import com.lfnj.sport_app.core.session.revocation.RevocationScheduler
import com.lfnj.sport_app.core.session.revocation.WorkManagerRevocationScheduler
import com.lfnj.sport_app.features.calendar.data.CalendarApi
import com.lfnj.sport_app.features.calendar.data.CalendarCacheJanitor
import com.lfnj.sport_app.features.calendar.data.CalendarRepository
import com.lfnj.sport_app.features.calendar.data.local.CalendarCacheCodec
import com.lfnj.sport_app.features.calendar.data.local.CalendarDatabase
import com.lfnj.sport_app.features.events.data.AndroidConnectivityMonitor
import com.lfnj.sport_app.features.events.data.ConnectivityMonitor
import com.lfnj.sport_app.features.events.data.EventRegistrationApi
import com.lfnj.sport_app.features.events.data.EventRegistrationRepository
import com.lfnj.sport_app.features.events.data.EventRegistrationsCacheJanitor
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCodec
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsDatabase
import com.lfnj.sport_app.features.events.data.EventSuggestionsApi
import com.lfnj.sport_app.features.events.data.EventSuggestionsCacheJanitor
import com.lfnj.sport_app.features.events.data.EventSuggestionsRepository
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCodec
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsDatabase
import com.lfnj.sport_app.features.recommendations.data.PlanSuggestionsApi
import com.lfnj.sport_app.features.recommendations.data.PlanSuggestionsCacheJanitor
import com.lfnj.sport_app.features.recommendations.data.PlanSuggestionsRepository
import com.lfnj.sport_app.features.recommendations.data.local.PlanSuggestionsCodec
import com.lfnj.sport_app.features.recommendations.data.local.PlanSuggestionsDatabase
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsApi
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsCacheJanitor
import com.lfnj.sport_app.features.recommendations.data.RouteSuggestionsRepository
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsCodec
import com.lfnj.sport_app.features.recommendations.data.local.RouteSuggestionsDatabase
import com.lfnj.sport_app.features.services.data.VirtualSessionsApi
import com.lfnj.sport_app.features.services.data.VirtualSessionsCacheJanitor
import com.lfnj.sport_app.features.services.data.VirtualSessionsRepository
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCodec
import com.lfnj.sport_app.features.services.data.SupportSuggestionsApi
import com.lfnj.sport_app.features.services.data.SupportSuggestionsCacheJanitor
import com.lfnj.sport_app.features.services.data.SupportSuggestionsRepository
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsCodec
import com.lfnj.sport_app.features.services.data.local.SupportSuggestionsDatabase
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsDatabase
import com.lfnj.sport_app.features.status.HttpStatusRepository
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardApi
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardCacheJanitor
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardRepository
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCodec
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardDatabase
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsApi
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsRepository
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsCodec
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsDatabase
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncEngine
import com.lfnj.sport_app.features.training.metrics.sync.WorkManagerMetricsSyncScheduler
import com.lfnj.sport_app.features.status.StatusRepository
import com.lfnj.sport_app.features.training.session.data.TrainingSessionApi
import com.lfnj.sport_app.features.training.session.data.TrainingSessionRepository
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.data.local.TrainingDatabase
import com.lfnj.sport_app.features.training.session.data.local.TrainingRecordCodec
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncCoordinator
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncEngine
import com.lfnj.sport_app.features.training.session.sync.WorkManagerTrainingSyncScheduler
import com.lfnj.sport_app.features.notifications.data.NotificationAnnouncer
import com.lfnj.sport_app.features.notifications.data.NotificationsApi
import com.lfnj.sport_app.features.notifications.data.NotificationsCacheJanitor
import com.lfnj.sport_app.features.notifications.data.NotificationsRepository
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCodec
import com.lfnj.sport_app.features.notifications.data.local.NotificationsDatabase
import com.lfnj.sport_app.features.notifications.sync.InboxSyncCoordinator
import com.lfnj.sport_app.features.notifications.sync.InboxSyncScheduler
import com.lfnj.sport_app.features.notifications.sync.NotificationsSyncEngine
import com.lfnj.sport_app.features.notifications.sync.WorkManagerInboxSyncScheduler
import com.lfnj.sport_app.features.notifications.system.AndroidNotificationAnnouncer
import com.lfnj.sport_app.features.notifications.system.NotificationLaunch
import com.lfnj.sport_app.features.notifications.system.SystemNotificationsProbe
import com.lfnj.sport_app.features.nutrition.reminders.data.SessionRemindersApi
import com.lfnj.sport_app.features.nutrition.reminders.data.SessionRemindersJanitor
import com.lfnj.sport_app.features.nutrition.reminders.data.SessionRemindersRepository
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersCodec
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersDatabase
import com.lfnj.sport_app.features.nutrition.reminders.engine.AndroidReminderNotifier
import com.lfnj.sport_app.features.nutrition.reminders.engine.ReminderBannerCenter
import com.lfnj.sport_app.features.nutrition.reminders.engine.ReminderNotifier
import com.lfnj.sport_app.features.nutrition.reminders.engine.SessionReminderEngine
import com.lfnj.sport_app.features.nutrition.reminders.model.SessionRemindersAccess
import com.lfnj.sport_app.features.training.session.data.SystemTrainingClock
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionPointer
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionServiceLauncher
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionSource
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionTask
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionTracker
import com.lfnj.sport_app.features.training.session.foreground.AndroidActiveSessionServiceLauncher
import com.lfnj.sport_app.features.training.session.foreground.SharedPreferencesActiveSessionPointer
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import retrofit2.Retrofit

/** Inyección de dependencias manual: un contenedor por proceso, creado en [com.lfnj.sport_app.SportApp]. */
open class AppContainer(
    val config: AppConfig,
    private val secureTokenStore: SecureTokenStore,
    private val appContext: Context,
) {
    private val retrofit: Retrofit? by lazy {
        if (config.isBffConfigured) BffMobileClient.retrofit(config.bffMobileBaseUrl) else null
    }

    protected fun <T> bffApi(service: Class<T>): T? = retrofit?.create(service)

    open val statusRepository: StatusRepository by lazy {
        HttpStatusRepository(bffApi(HealthApi::class.java), BffMobileClient.json)
    }

    // ------------------------------------------------------------------ HU004 (ADR-026)

    protected open val revocationDatabase: RevocationDatabase by lazy { RevocationDatabase.create(appContext) }

    /** Clave AES-GCM propia de la cola de revocación, distinta de la del almacén de sesión. */
    protected open val revocationCipher: TokenCipher by lazy { KeystoreTokenCipher(RevocationQueue.KEY_ALIAS) }

    protected open val revocationScheduler: RevocationScheduler by lazy { WorkManagerRevocationScheduler(appContext) }

    open val revocationQueue: RevocationQueue by lazy {
        RevocationQueue(
            dao = revocationDatabase.revocationDao(),
            cipher = revocationCipher,
            api = bffApi(SessionApi::class.java),
            json = BffMobileClient.json,
            scheduler = revocationScheduler,
        )
    }

    /** Revocación remota de la sesión mediante `POST /v1/sessions/revoke` y su cola cifrada (HU004). */
    protected open val sessionRevoker: SessionRevoker? get() = revocationQueue

    val revocationResumer: RevocationResumer by lazy { RevocationResumer(revocationQueue, revocationScheduler) }

    open val sessionManager: SessionManager by lazy {
        SessionManager(bffApi(SessionApi::class.java), secureTokenStore, BffMobileClient.json, revoker = sessionRevoker)
    }

    // ------------------------------------------------------------------ HU032 (ADR-029)

    protected open val trainingDatabase: TrainingDatabase by lazy { TrainingDatabase.create(appContext) }

    /** Clave AES-GCM propia de los registros de entrenamiento, distinta de la de la sesión. */
    protected open val trainingCipher: TokenCipher by lazy { KeystoreTokenCipher(TrainingRecordCodec.KEY_ALIAS) }

    protected open val trainingSyncScheduler: TrainingSyncScheduler by lazy { WorkManagerTrainingSyncScheduler(appContext) }

    private val trainingCodec by lazy { TrainingRecordCodec(trainingCipher, BffMobileClient.json) }

    private val trainingApi by lazy { bffApi(TrainingSessionApi::class.java) }

    open val trainingSessionRepository: TrainingSessionRepository by lazy {
        TrainingSessionRepository(
            dao = trainingDatabase.trainingSessionDao(),
            transactions = trainingDatabase.transactions,
            codec = trainingCodec,
            scheduler = trainingSyncScheduler,
            api = trainingApi,
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    open val trainingSyncEngine: TrainingSyncEngine by lazy {
        TrainingSyncEngine(
            dao = trainingDatabase.trainingSessionDao(),
            transactions = trainingDatabase.transactions,
            codec = trainingCodec,
            api = trainingApi,
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val trainingSyncCoordinator: TrainingSyncCoordinator by lazy {
        TrainingSyncCoordinator(sessionManager.currentUser, trainingSessionRepository::hasPendingCommands, trainingSyncScheduler)
    }

    // ------------------------------------------------------------------ HU034 (ADR-030)

    protected open val trainingMetricsDatabase: TrainingMetricsDatabase by lazy { TrainingMetricsDatabase.create(appContext) }

    /** Clave AES-GCM propia de los indicadores, distinta de la de la sesión, HU032 y HU035. */
    protected open val trainingMetricsCipher: TokenCipher by lazy { KeystoreTokenCipher(TrainingMetricsCodec.KEY_ALIAS) }

    protected open val trainingMetricsScheduler: TrainingSyncScheduler by lazy { WorkManagerMetricsSyncScheduler(appContext) }

    private val trainingMetricsCodec by lazy { TrainingMetricsCodec(trainingMetricsCipher, BffMobileClient.json) }

    private val trainingMetricsApi by lazy { bffApi(TrainingMetricsApi::class.java) }

    open val trainingMetricsRepository: TrainingMetricsRepository by lazy {
        TrainingMetricsRepository(
            dao = trainingMetricsDatabase.trainingMetricsDao(),
            transactions = trainingMetricsDatabase.transactions,
            codec = trainingMetricsCodec,
            api = trainingMetricsApi,
            sessionManager = sessionManager,
            scheduler = trainingMetricsScheduler,
            json = BffMobileClient.json,
        )
    }

    open val metricsSyncEngine: MetricsSyncEngine by lazy {
        MetricsSyncEngine(
            dao = trainingMetricsDatabase.trainingMetricsDao(),
            transactions = trainingMetricsDatabase.transactions,
            codec = trainingMetricsCodec,
            api = trainingMetricsApi,
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    /** Detiene la cola de HU034 al cerrar sesión y la reanuda solo para el mismo deportista (ADR-030 offline §5). */
    val metricsSyncCoordinator: TrainingSyncCoordinator by lazy {
        TrainingSyncCoordinator(sessionManager.currentUser, trainingMetricsRepository::hasPending, trainingMetricsScheduler)
    }

    // ------------------------------------------------------------------ HU035 (ADR-031)

    protected open val calendarDatabase: CalendarDatabase by lazy { CalendarDatabase.create(appContext) }

    /** Clave AES-GCM propia de la caché del calendario, distinta de la de la sesión y de HU032. */
    protected open val calendarCipher: TokenCipher by lazy { KeystoreTokenCipher(CalendarCacheCodec.KEY_ALIAS) }

    open val calendarRepository: CalendarRepository by lazy {
        CalendarRepository(
            dao = calendarDatabase.calendarCacheDao(),
            codec = CalendarCacheCodec(calendarCipher, BffMobileClient.json),
            api = bffApi(CalendarApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val calendarCacheJanitor: CalendarCacheJanitor by lazy { CalendarCacheJanitor(sessionManager.currentUser, calendarRepository) }

    // ------------------------------------------------------------------ HU028 (ADR-041)

    protected open val planSuggestionsDatabase: PlanSuggestionsDatabase by lazy { PlanSuggestionsDatabase.create(appContext) }

    /** Clave AES-GCM propia de la caché de sugerencias, distinta de la de la sesión, HU032, HU034 y HU035. */
    protected open val planSuggestionsCipher: TokenCipher by lazy { KeystoreTokenCipher(PlanSuggestionsCodec.KEY_ALIAS) }

    open val planSuggestionsRepository: PlanSuggestionsRepository by lazy {
        PlanSuggestionsRepository(
            dao = planSuggestionsDatabase.planSuggestionsCacheDao(),
            codec = PlanSuggestionsCodec(planSuggestionsCipher, BffMobileClient.json),
            api = bffApi(PlanSuggestionsApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val planSuggestionsCacheJanitor: PlanSuggestionsCacheJanitor by lazy {
        PlanSuggestionsCacheJanitor(sessionManager.currentUser, planSuggestionsRepository)
    }

    // ------------------------------------------------------------------ HU026 (ADR-041)

    protected open val routeSuggestionsDatabase: RouteSuggestionsDatabase by lazy { RouteSuggestionsDatabase.create(appContext) }

    /** Clave AES-GCM propia de la caché de rutas sugeridas, distinta de la de la sesión y de las demás cachés. */
    protected open val routeSuggestionsCipher: TokenCipher by lazy { KeystoreTokenCipher(RouteSuggestionsCodec.KEY_ALIAS) }

    open val routeSuggestionsRepository: RouteSuggestionsRepository by lazy {
        RouteSuggestionsRepository(
            dao = routeSuggestionsDatabase.routeSuggestionsCacheDao(),
            codec = RouteSuggestionsCodec(routeSuggestionsCipher, BffMobileClient.json),
            api = bffApi(RouteSuggestionsApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val routeSuggestionsCacheJanitor: RouteSuggestionsCacheJanitor by lazy {
        RouteSuggestionsCacheJanitor(sessionManager.currentUser, routeSuggestionsRepository)
    }

    // ------------------------------------------------------------------ HU027 (ADR-041)

    protected open val eventSuggestionsDatabase: EventSuggestionsDatabase by lazy { EventSuggestionsDatabase.create(appContext) }

    /** Clave AES-GCM propia de la caché de eventos sugeridos, distinta de la de la sesión y de las demás cachés. */
    protected open val eventSuggestionsCipher: TokenCipher by lazy { KeystoreTokenCipher(EventSuggestionsCodec.KEY_ALIAS) }

    open val eventSuggestionsRepository: EventSuggestionsRepository by lazy {
        EventSuggestionsRepository(
            dao = eventSuggestionsDatabase.eventSuggestionsCacheDao(),
            codec = EventSuggestionsCodec(eventSuggestionsCipher, BffMobileClient.json),
            api = bffApi(EventSuggestionsApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val eventSuggestionsCacheJanitor: EventSuggestionsCacheJanitor by lazy {
        EventSuggestionsCacheJanitor(sessionManager.currentUser, eventSuggestionsRepository)
    }

    // ------------------------------------------------------------------ HU033 (ADR-043)

    protected open val trainingDashboardDatabase: TrainingDashboardDatabase by lazy { TrainingDashboardDatabase.create(appContext) }

    /** Clave AES-GCM propia de la caché del tablero, distinta de la de la sesión, HU028, HU032, HU034 y HU035. */
    protected open val trainingDashboardCipher: TokenCipher by lazy { KeystoreTokenCipher(TrainingDashboardCodec.KEY_ALIAS) }

    open val trainingDashboardRepository: TrainingDashboardRepository by lazy {
        TrainingDashboardRepository(
            dao = trainingDashboardDatabase.trainingDashboardCacheDao(),
            codec = TrainingDashboardCodec(trainingDashboardCipher, BffMobileClient.json),
            api = bffApi(TrainingDashboardApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val trainingDashboardCacheJanitor: TrainingDashboardCacheJanitor by lazy {
        TrainingDashboardCacheJanitor(sessionManager.currentUser, trainingDashboardRepository)
    }

    // ------------------------------------------------------------------ HU040 (ADR-046)

    protected open val virtualSessionsDatabase: VirtualSessionsDatabase by lazy { VirtualSessionsDatabase.create(appContext) }

    /** Clave AES-GCM propia de la caché de sesiones virtuales, distinta de la de la sesión y de las demás cachés. */
    protected open val virtualSessionsCipher: TokenCipher by lazy { KeystoreTokenCipher(VirtualSessionsCodec.KEY_ALIAS) }

    open val virtualSessionsRepository: VirtualSessionsRepository by lazy {
        VirtualSessionsRepository(
            dao = virtualSessionsDatabase.virtualSessionsCacheDao(),
            codec = VirtualSessionsCodec(virtualSessionsCipher, BffMobileClient.json),
            api = bffApi(VirtualSessionsApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    // ------------------------------------------------------------------ HU041 (ADR-032)

    protected open val eventRegistrationsDatabase: EventRegistrationsDatabase by lazy { EventRegistrationsDatabase.create(appContext) }

    /** Clave AES-GCM propia de «Mis inscripciones», distinta de la de la sesión y de las demás cachés. */
    protected open val eventRegistrationsCipher: TokenCipher by lazy { KeystoreTokenCipher(EventRegistrationsCodec.KEY_ALIAS) }

    /** Conectividad del dispositivo: sin red se deshabilita la inscripción (CA10). */
    open val connectivityMonitor: ConnectivityMonitor by lazy { AndroidConnectivityMonitor(appContext) }

    open val eventRegistrationRepository: EventRegistrationRepository by lazy {
        EventRegistrationRepository(
            dao = eventRegistrationsDatabase.eventRegistrationsCacheDao(),
            codec = EventRegistrationsCodec(eventRegistrationsCipher, BffMobileClient.json),
            api = bffApi(EventRegistrationApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val virtualSessionsCacheJanitor: VirtualSessionsCacheJanitor by lazy {
        VirtualSessionsCacheJanitor(sessionManager.currentUser, virtualSessionsRepository)
    }

    val eventRegistrationsCacheJanitor: EventRegistrationsCacheJanitor by lazy {
        EventRegistrationsCacheJanitor(sessionManager.currentUser, eventRegistrationRepository)
    }

    // ------------------------------------------------------------------ HU029 (ADR-041)

    protected open val supportSuggestionsDatabase: SupportSuggestionsDatabase by lazy { SupportSuggestionsDatabase.create(appContext) }

    /** Clave AES-GCM propia de la caché de acompañamiento y logística, distinta de la de la sesión y de las demás cachés. */
    protected open val supportSuggestionsCipher: TokenCipher by lazy { KeystoreTokenCipher(SupportSuggestionsCodec.KEY_ALIAS) }

    open val supportSuggestionsRepository: SupportSuggestionsRepository by lazy {
        SupportSuggestionsRepository(
            dao = supportSuggestionsDatabase.supportSuggestionsCacheDao(),
            codec = SupportSuggestionsCodec(supportSuggestionsCipher, BffMobileClient.json),
            api = bffApi(SupportSuggestionsApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val supportSuggestionsCacheJanitor: SupportSuggestionsCacheJanitor by lazy {
        SupportSuggestionsCacheJanitor(sessionManager.currentUser, supportSuggestionsRepository)
    }

    // ------------------------------------------------------------------ HU039 (ADR-045)

    protected open val notificationsDatabase: NotificationsDatabase by lazy { NotificationsDatabase.create(appContext) }

    /** Clave AES-GCM propia de la bandeja de notificaciones, distinta de la de la sesión y de las demás cachés. */
    protected open val notificationsCipher: TokenCipher by lazy { KeystoreTokenCipher(NotificationsCodec.KEY_ALIAS) }

    /** Notificaciones del sistema en los canales `event_alerts` y `event_updates`. */
    val androidNotificationAnnouncer: AndroidNotificationAnnouncer by lazy { AndroidNotificationAnnouncer(appContext) }

    protected open val notificationAnnouncer: NotificationAnnouncer get() = androidNotificationAnnouncer

    open val systemNotificationsProbe: SystemNotificationsProbe get() = androidNotificationAnnouncer

    protected open val inboxSyncScheduler: InboxSyncScheduler by lazy { WorkManagerInboxSyncScheduler(appContext) }

    open val notificationsRepository: NotificationsRepository by lazy {
        NotificationsRepository(
            dao = notificationsDatabase.notificationsCacheDao(),
            codec = NotificationsCodec(notificationsCipher, BffMobileClient.json),
            api = bffApi(NotificationsApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
            announcer = notificationAnnouncer,
        )
    }

    open val notificationsSyncEngine: NotificationsSyncEngine by lazy { NotificationsSyncEngine(sessionManager, notificationsRepository) }

    /** Toque de una notificación del sistema pendiente de abrir el detalle del evento (HU041). */
    val notificationLaunch: NotificationLaunch by lazy { NotificationLaunch() }

    /** Ámbito propio de la lectura enviada al abrir una notificación: no depende de la pantalla visible. */
    private val notificationsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Envía la lectura de una notificación tocada en el sistema; un fallo la deja sin leer en la bandeja. */
    fun markNotificationReadInBackground(owner: String, notificationId: String) {
        notificationsScope.launch { runCatching { notificationsRepository.markRead(owner, notificationId) } }
    }

    val notificationsCacheJanitor: NotificationsCacheJanitor by lazy {
        NotificationsCacheJanitor(sessionManager.currentUser, notificationsRepository)
    }

    /** Sincronización periódica de la bandeja mientras haya un deportista autenticado. */
    val inboxSyncCoordinator: InboxSyncCoordinator by lazy { InboxSyncCoordinator(sessionManager.currentUser, inboxSyncScheduler) }

    // ------------------------------------------------------------------ HU038 (ADR-045)

    protected open val sessionRemindersDatabase: SessionRemindersDatabase by lazy { SessionRemindersDatabase.create(appContext) }

    /** Clave AES-GCM propia de los recordatorios en sesión, distinta de la de la sesión y de las demás bases. */
    protected open val sessionRemindersCipher: TokenCipher by lazy { KeystoreTokenCipher(SessionRemindersCodec.KEY_ALIAS) }

    open val sessionRemindersRepository: SessionRemindersRepository by lazy {
        SessionRemindersRepository(
            dao = sessionRemindersDatabase.sessionRemindersDao(),
            codec = SessionRemindersCodec(sessionRemindersCipher, BffMobileClient.json),
            api = bffApi(SessionRemindersApi::class.java),
            sessionManager = sessionManager,
            json = BffMobileClient.json,
        )
    }

    val sessionRemindersJanitor: SessionRemindersJanitor by lazy {
        SessionRemindersJanitor(sessionManager.currentUser, sessionRemindersRepository)
    }

    /** Notificaciones locales en el canal `session_reminders`; el permiso lo solicita la bandeja (HU039). */
    val androidReminderNotifier: AndroidReminderNotifier by lazy { AndroidReminderNotifier(appContext) }

    open val reminderNotifier: ReminderNotifier get() = androidReminderNotifier

    /** Banner global de los recordatorios, sobre cualquier pantalla. */
    val reminderBanners: ReminderBannerCenter by lazy { ReminderBannerCenter() }

    /** Reloj del motor: el de HU032 (monotónico y de pared). Solo las pruebas instrumentadas lo aceleran. */
    protected open val reminderClock: TrainingClock get() = SystemTrainingClock

    open val sessionReminderEngine: SessionReminderEngine by lazy {
        SessionReminderEngine(
            configs = sessionRemindersRepository::observe,
            allowed = sessionManager.access.map(SessionRemindersAccess::allowsLocalEvaluation),
            deliveries = sessionRemindersRepository,
            notifier = reminderNotifier,
            banners = reminderBanners,
            clock = reminderClock,
            refresh = { owner -> sessionRemindersRepository.refresh(owner) },
        )
    }

    // Servicio en primer plano de la sesión de HU032 (creado por HU038; reutilizable por HU036/HU037).

    protected open val activeSessionPointer: ActiveSessionPointer by lazy { SharedPreferencesActiveSessionPointer(appContext) }

    protected open val activeSessionLauncher: ActiveSessionServiceLauncher by lazy { AndroidActiveSessionServiceLauncher(appContext) }

    /** Lectura de HU032 sin modificar su repositorio ni su almacenamiento. */
    private val activeSessionSource: ActiveSessionSource by lazy {
        object : ActiveSessionSource {
            override fun observe(owner: String): Flow<List<LocalTrainingSession>> = trainingSessionRepository.observe(owner)
            override fun monotonicStartMillis(sessionId: String): Long? = trainingSessionRepository.monotonicStartMillis(sessionId)
        }
    }

    val activeSessionTracker: ActiveSessionTracker by lazy {
        ActiveSessionTracker(sessionManager.currentUser, activeSessionSource, activeSessionPointer, activeSessionLauncher)
    }

    /** Tareas que acompañan la sesión activa en el servicio en primer plano. */
    open val activeSessionTasks: List<ActiveSessionTask> get() = listOf(sessionReminderEngine)
}
