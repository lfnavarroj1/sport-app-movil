package com.lfnj.sport_app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.di.AppContainer
import com.lfnj.sport_app.features.calendar.CalendarScreen
import com.lfnj.sport_app.features.calendar.CalendarViewModel
import com.lfnj.sport_app.features.calendar.model.CalendarAccess
import com.lfnj.sport_app.features.calendar.model.CalendarOwner
import com.lfnj.sport_app.features.events.EventDetailScreen
import com.lfnj.sport_app.features.events.EventDetailViewModel
import com.lfnj.sport_app.features.events.EventSuggestionsScreen
import com.lfnj.sport_app.features.events.EventSuggestionsViewModel
import com.lfnj.sport_app.features.events.model.EventSuggestionsAccess
import com.lfnj.sport_app.features.events.model.EventSuggestionsOwner
import com.lfnj.sport_app.features.events.MyRegistrationsScreen
import com.lfnj.sport_app.features.events.MyRegistrationsViewModel
import com.lfnj.sport_app.features.events.model.EventRegistrationsOwner
import com.lfnj.sport_app.features.home.AthleteHomeScreen
import com.lfnj.sport_app.features.home.HomeViewModel
import com.lfnj.sport_app.features.health.HealthReportScreen
import com.lfnj.sport_app.features.health.HealthReportViewModel
import com.lfnj.sport_app.features.health.model.HealthOwner
import com.lfnj.sport_app.features.health.recentConfirmedSessions
import com.lfnj.sport_app.features.identity.login.LoginNotice
import kotlinx.coroutines.flow.map
import com.lfnj.sport_app.features.identity.login.LoginScreen
import com.lfnj.sport_app.features.identity.login.LoginViewModel
import com.lfnj.sport_app.features.identity.start.SessionStartScreen
import com.lfnj.sport_app.features.identity.start.SessionStartViewModel
import com.lfnj.sport_app.features.identity.unauthorized.UnauthorizedScreen
import com.lfnj.sport_app.features.notifications.NotificationPreferencesScreen
import com.lfnj.sport_app.features.notifications.NotificationPreferencesViewModel
import com.lfnj.sport_app.features.notifications.NotificationsInboxScreen
import com.lfnj.sport_app.features.notifications.NotificationsInboxViewModel
import com.lfnj.sport_app.features.notifications.model.NotificationsOwner
import com.lfnj.sport_app.features.notifications.system.rememberSystemNotificationsEnabler
import com.lfnj.sport_app.features.nutrition.intake.FoodIntakeScreen
import com.lfnj.sport_app.features.nutrition.intake.FoodIntakeViewModel
import com.lfnj.sport_app.features.nutrition.intake.FoodLabels
import com.lfnj.sport_app.features.nutrition.intake.model.FoodOwner
import com.lfnj.sport_app.features.nutrition.reminders.SessionRemindersScreen
import com.lfnj.sport_app.features.nutrition.reminders.SessionRemindersViewModel
import com.lfnj.sport_app.features.nutrition.reminders.model.SessionRemindersAccess
import com.lfnj.sport_app.features.recommendations.PlanSuggestionsScreen
import com.lfnj.sport_app.features.recommendations.PlanSuggestionsViewModel
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsAccess
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsOwner
import com.lfnj.sport_app.features.recommendations.RouteMapLauncher
import com.lfnj.sport_app.features.recommendations.RouteSuggestionsScreen
import com.lfnj.sport_app.features.recommendations.RouteSuggestionsViewModel
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsAccess
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsOwner
import com.lfnj.sport_app.features.sensors.DevicesScreen
import com.lfnj.sport_app.features.sensors.DevicesViewModel
import com.lfnj.sport_app.features.sensors.model.DevicesAccess
import com.lfnj.sport_app.features.sensors.openBluetoothSettings
import com.lfnj.sport_app.features.sensors.rememberBluetoothPermissionRequester
import com.lfnj.sport_app.features.services.SupportSuggestionsScreen
import com.lfnj.sport_app.features.services.SupportSuggestionsViewModel
import com.lfnj.sport_app.features.services.VirtualSessionsScreen
import com.lfnj.sport_app.features.services.VirtualSessionsViewModel
import com.lfnj.sport_app.features.services.model.SupportSuggestionsAccess
import com.lfnj.sport_app.features.services.model.SupportSuggestionsOwner
import com.lfnj.sport_app.features.services.model.VirtualSessionsAccess
import com.lfnj.sport_app.features.services.model.VirtualSessionsOwner
import com.lfnj.sport_app.features.status.StatusScreen
import com.lfnj.sport_app.features.status.StatusViewModel
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.training.dashboard.TrainingDashboardScreen
import com.lfnj.sport_app.features.training.dashboard.TrainingDashboardViewModel
import com.lfnj.sport_app.features.training.dashboard.model.TrainingDashboardAccess
import com.lfnj.sport_app.features.training.metrics.TrainingMetricsScreen
import com.lfnj.sport_app.features.training.metrics.TrainingMetricsViewModel
import com.lfnj.sport_app.features.training.metrics.model.TrainingMetricsAccess
import com.lfnj.sport_app.features.training.session.TrainingSessionScreen
import com.lfnj.sport_app.features.training.session.TrainingSessionViewModel
import com.lfnj.sport_app.features.training.session.model.TRAINING_SESSION_CAPABILITY
import com.lfnj.sport_app.features.training.session.model.TrainingOwner

private const val NOTICE_ARG = "notice"

/** Destinos previos a una sesión validada: una notificación tocada espera hasta salir de ellos (HU039). */
private val PRE_SESSION_ROUTES = setOf(Destinations.SESSION_START, "${Destinations.LOGIN}?$NOTICE_ARG={$NOTICE_ARG}", Destinations.UNAUTHORIZED, Destinations.STATUS)

/** Navega a [route] eliminando la pila: Atrás no regresa a pantallas protegidas ni de otra sesión. */
private fun NavHostController.replaceAll(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

private fun loginRoute(notice: LoginNotice? = null) =
    if (notice == null) Destinations.LOGIN else "${Destinations.LOGIN}?$NOTICE_ARG=${notice.name}"

@Composable
fun AppNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val sessionManager = container.sessionManager
    // HU039: una notificación del sistema tocada abre el detalle del evento (HU041) cuando ya hay una
    // sesión de deportista validada y visible; antes (arranque, acceso) espera sin descartarla.
    val pendingOpen by container.notificationLaunch.pending.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(pendingOpen, currentEntry?.destination?.route) {
        val route = currentEntry?.destination?.route ?: return@LaunchedEffect
        val user = sessionManager.currentUser.value
        if (pendingOpen == null || user == null || user.actorType != ActorType.ATHLETE || route in PRE_SESSION_ROUTES) return@LaunchedEffect
        container.notificationLaunch.consume()?.let { open ->
            container.markNotificationReadInBackground(NotificationsOwner.keyOf(user.userId), open.notificationId)
            navController.navigate(Destinations.eventDetail(open.eventId)) { launchSingleTop = true }
        }
    }
    NavHost(navController = navController, startDestination = Destinations.SESSION_START, modifier = modifier) {
        composable(Destinations.SESSION_START) {
            val viewModel: SessionStartViewModel = viewModel(
                factory = viewModelFactory { initializer { SessionStartViewModel(sessionManager::restore) } }
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.navigateTo) {
                state.navigateTo?.let { target ->
                    viewModel.onNavigated()
                    navController.replaceAll(if (target.route == Destinations.LOGIN) loginRoute(target.notice) else target.route)
                }
            }
            SessionStartScreen(state = state, onRetry = viewModel::retry, onContinueToLogin = viewModel::continueToLogin)
        }

        composable(
            route = "${Destinations.LOGIN}?$NOTICE_ARG={$NOTICE_ARG}",
            arguments = listOf(navArgument(NOTICE_ARG) { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val notice = LoginNotice.fromRoute(entry.arguments?.getString(NOTICE_ARG))
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory { initializer { LoginViewModel(sessionManager::login, notice) } }
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.navigateTo) {
                state.navigateTo?.let { route ->
                    viewModel.onNavigated()
                    navController.replaceAll(route)
                }
            }
            LoginScreen(
                state = state,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
                onSubmit = viewModel::submit,
                onOpenStatus = { navController.navigate(Destinations.STATUS) },
            )
        }

        composable(Destinations.ATHLETE_HOME) {
            // Guarda de entrada: solo una sesión móvil válida de deportista abre athlete_home (CA10, CA11).
            val user = remember { sessionManager.currentUser.value }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                else -> {
                    val viewModel: HomeViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                HomeViewModel(
                                    user = user,
                                    loadAccess = sessionManager::loadAccess,
                                    logoutAvailable = sessionManager.canLogout,
                                    openTrainingSession = container.trainingSessionRepository
                                        .observeOpenSession(TrainingOwner.keyOf(user.userId)),
                                    performLogout = sessionManager::logout,
                                )
                            }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.navigateTo) {
                        state.navigateTo?.let { exit ->
                            viewModel.onNavigated()
                            navController.replaceAll(
                                if (exit.route == Destinations.LOGIN) loginRoute(exit.notice) else exit.route
                            )
                        }
                    }
                    AthleteHomeScreen(
                        state = state,
                        onRetry = viewModel::refresh,
                        onLogout = viewModel::logout,
                        onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
                    )
                }
            }
        }

        composable(Destinations.TRAINING_SESSION) {
            // HU032: misma guarda que athlete_home; la partición local es la del deportista autenticado.
            val user = remember { sessionManager.currentUser.value }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                else -> {
                    // Solo orienta la experiencia: sin capacidades conocidas el BFF decide al sincronizar.
                    val canStart = sessionManager.access.value?.let {
                        it.subscriptionStatus == SubscriptionStatus.ACTIVE && TRAINING_SESSION_CAPABILITY in it.capabilities
                    } ?: true
                    val viewModel: TrainingSessionViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                TrainingSessionViewModel(TrainingOwner.keyOf(user.userId), container.trainingSessionRepository, canStart)
                            }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    TrainingSessionScreen(
                        state = state,
                        onSelectSport = viewModel::selectSport,
                        onStart = viewModel::start,
                        onFinish = viewModel::finish,
                        onDismiss = viewModel::dismiss,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.TRAINING_DASHBOARD) {
            // HU033: misma guarda que athlete_home y, además, la capacidad training.dashboard.view vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { TrainingDashboardAccess.canView(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.TRAINING_DASHBOARD) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: TrainingDashboardViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { TrainingDashboardViewModel(TrainingOwner.keyOf(user.userId), container.trainingDashboardRepository) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    TrainingDashboardScreen(
                        state = state,
                        onSelectRange = viewModel::selectRange,
                        onSelectCustomRange = viewModel::selectCustomRange,
                        onDismissCustomError = viewModel::dismissCustomError,
                        onRetry = viewModel::retry,
                        onRegisterTraining = { navController.navigate(Destinations.TRAINING_SESSION) { launchSingleTop = true } },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.TRAINING_METRICS) {
            // HU034: misma guarda que athlete_home y, además, la capacidad training.metrics.ftp_vo2max vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { TrainingMetricsAccess.canUse(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.TRAINING_METRICS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: TrainingMetricsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { TrainingMetricsViewModel(TrainingOwner.keyOf(user.userId), container.trainingMetricsRepository) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    TrainingMetricsScreen(
                        state = state,
                        onSelectMethod = viewModel::selectMethod,
                        onInputChange = viewModel::onInputChange,
                        onMeasuredAtChange = viewModel::setMeasuredAt,
                        onSubmit = viewModel::requestSubmit,
                        onConfirmSubmit = viewModel::confirmSubmit,
                        onCancelSubmit = viewModel::cancelSubmit,
                        onDismiss = viewModel::dismiss,
                        onRetry = viewModel::refresh,
                        onLoadMore = viewModel::loadMore,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.CALENDAR) {
            // HU035: misma guarda que athlete_home y, además, la capacidad calendar.sport.view vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { CalendarAccess.canView(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.CALENDAR) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: CalendarViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { CalendarViewModel(CalendarOwner.keyOf(user.userId), container.calendarRepository) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    CalendarScreen(
                        state = state,
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onCurrentMonth = viewModel::currentMonth,
                        onModeChange = viewModel::setMode,
                        onSelectDay = viewModel::selectDay,
                        onRetry = viewModel::retry,
                        onShowSyncedPeriod = viewModel::showSyncedPeriod,
                        onBack = { navController.popBackStack() },
                        onOpenEvent = { eventId -> navController.navigate(Destinations.eventDetail(eventId)) { launchSingleTop = true } },
                        onOpenRegistrations = { navController.navigate(Destinations.MY_EVENT_REGISTRATIONS) { launchSingleTop = true } },
                    )
                }
            }
        }

        composable(
            route = Destinations.EVENT_DETAIL,
            arguments = listOf(navArgument(Destinations.EVENT_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            // HU041: misma guarda que athlete_home; la inscripción no exige capacidad de plan (ADR-032 §1).
            val user = remember { sessionManager.currentUser.value }
            val eventId = entry.arguments?.getString(Destinations.EVENT_ID_ARG).orEmpty()
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                else -> {
                    val viewModel: EventDetailViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { EventDetailViewModel(eventId, container.eventRegistrationRepository, container.connectivityMonitor) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    EventDetailScreen(
                        state = state,
                        onRegister = viewModel::requestRegister,
                        onConfirmRegister = viewModel::confirmRegister,
                        onCancelRegister = viewModel::cancelRegister,
                        onDismissFailure = viewModel::dismissFailure,
                        onRetry = viewModel::retry,
                        onOpenRegistrations = { navController.navigate(Destinations.MY_EVENT_REGISTRATIONS) { launchSingleTop = true } },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.MY_EVENT_REGISTRATIONS) {
            // HU041: misma guarda que athlete_home; sin capacidad de plan (ADR-032 §1).
            val user = remember { sessionManager.currentUser.value }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                else -> {
                    val viewModel: MyRegistrationsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                MyRegistrationsViewModel(EventRegistrationsOwner.keyOf(user.userId), container.eventRegistrationRepository)
                            }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    MyRegistrationsScreen(
                        state = state,
                        onRetry = viewModel::retry,
                        onLoadMore = viewModel::loadMore,
                        onOpenEvent = { eventId -> navController.navigate(Destinations.eventDetail(eventId)) { launchSingleTop = true } },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.PLAN_SUGGESTIONS) {
            // HU028: misma guarda que athlete_home y, además, la capacidad training.plans.suggest vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { PlanSuggestionsAccess.canView(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.PLAN_SUGGESTIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: PlanSuggestionsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { PlanSuggestionsViewModel(PlanSuggestionsOwner.keyOf(user.userId), container.planSuggestionsRepository) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    PlanSuggestionsScreen(
                        state = state,
                        onOpenDetail = viewModel::openDetail,
                        onCloseDetail = viewModel::closeDetail,
                        onRetry = viewModel::retry,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.ROUTE_SUGGESTIONS) {
            // HU026: misma guarda que athlete_home y, además, la capacidad training.routes.suggest vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { RouteSuggestionsAccess.canView(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.ROUTE_SUGGESTIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: RouteSuggestionsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { RouteSuggestionsViewModel(RouteSuggestionsOwner.keyOf(user.userId), container.routeSuggestionsRepository) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    RouteSuggestionsScreen(
                        state = state,
                        onOpenDetail = viewModel::openDetail,
                        onCloseDetail = viewModel::closeDetail,
                        onRetry = viewModel::retry,
                        onOpenMap = { uri -> RouteMapLauncher.open(context, uri) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.VIRTUAL_SESSIONS) {
            // HU040: misma guarda que athlete_home y, además, la capacidad services.virtual_sessions.schedule vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { VirtualSessionsAccess.canView(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.VIRTUAL_SESSIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: VirtualSessionsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { VirtualSessionsViewModel(VirtualSessionsOwner.keyOf(user.userId), container.virtualSessionsRepository) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    VirtualSessionsScreen(
                        state = state,
                        onSelectTab = viewModel::selectTab,
                        onOpenService = viewModel::openService,
                        onCloseService = viewModel::closeService,
                        onChooseSlot = viewModel::chooseSlot,
                        onConfirmBooking = viewModel::confirmBooking,
                        onDismissBooking = viewModel::dismissBooking,
                        onRequestCancel = viewModel::requestCancel,
                        onConfirmCancel = viewModel::confirmCancel,
                        onDismissCancel = viewModel::dismissCancel,
                        onRetry = viewModel::retry,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.EVENT_SUGGESTIONS) {
            // HU027: misma guarda que athlete_home y, además, la capacidad events.suggest vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { EventSuggestionsAccess.canView(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.EVENT_SUGGESTIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: EventSuggestionsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { EventSuggestionsViewModel(EventSuggestionsOwner.keyOf(user.userId), container.eventSuggestionsRepository) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    EventSuggestionsScreen(
                        state = state,
                        // El detalle y la inscripción son de HU041: se abre su destino sin modificarlo.
                        onOpenEvent = { eventId -> navController.navigate(Destinations.eventDetail(eventId)) { launchSingleTop = true } },
                        onRetry = viewModel::retry,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.SUPPORT_SUGGESTIONS) {
            // HU029: misma guarda que athlete_home y, además, la capacidad services.support.suggest vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { SupportSuggestionsAccess.canView(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.SUPPORT_SUGGESTIONS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: SupportSuggestionsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { SupportSuggestionsViewModel(SupportSuggestionsOwner.keyOf(user.userId), container.supportSuggestionsRepository) }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    SupportSuggestionsScreen(
                        state = state,
                        onOpenDetail = viewModel::openDetail,
                        onCloseDetail = viewModel::closeDetail,
                        onRetry = viewModel::retry,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.NOTIFICATIONS) {
            // HU039: misma guarda que athlete_home; la bandeja es opción base, sin capacidad de plan (ADR-045 §11).
            val user = remember { sessionManager.currentUser.value }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                else -> {
                    val viewModel: NotificationsInboxViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                NotificationsInboxViewModel(
                                    NotificationsOwner.keyOf(user.userId),
                                    container.notificationsRepository,
                                    container.systemNotificationsProbe,
                                )
                            }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSystemStatus() }
                    val enableSystemNotifications = rememberSystemNotificationsEnabler(onResult = viewModel::refreshSystemStatus)
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    NotificationsInboxScreen(
                        state = state,
                        onOpen = { item ->
                            viewModel.open(item)
                            // El detalle es de HU041: se abre su destino sin modificarlo.
                            navController.navigate(Destinations.eventDetail(item.eventId)) { launchSingleTop = true }
                        },
                        onRetry = viewModel::retry,
                        onLoadMore = viewModel::loadMore,
                        onDismissMarkReadProblem = viewModel::dismissMarkReadProblem,
                        onEnableSystemNotifications = { enableSystemNotifications(state.systemNotifications) },
                        onOpenPreferences = { navController.navigate(Destinations.NOTIFICATION_PREFERENCES) { launchSingleTop = true } },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.NOTIFICATION_PREFERENCES) {
            // HU039: misma guarda que athlete_home; preferencias solo con conectividad.
            val user = remember { sessionManager.currentUser.value }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                else -> {
                    val viewModel: NotificationPreferencesViewModel = viewModel(
                        factory = viewModelFactory { initializer { NotificationPreferencesViewModel(container.notificationsRepository) } }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    NotificationPreferencesScreen(
                        state = state,
                        onEventMatchesChange = viewModel::setEventMatches,
                        onEventRemindersChange = viewModel::setEventReminders,
                        onSave = viewModel::save,
                        onRetry = viewModel::retry,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.HEALTH) {
            // HU042: misma guarda que athlete_home; «Mi salud» es opción base, sin capacidad de plan (ADR-037).
            val user = remember { sessionManager.currentUser.value }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                else -> {
                    val viewModel: HealthReportViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                HealthReportViewModel(
                                    owner = HealthOwner.keyOf(user.userId),
                                    gateway = container.healthReportsRepository,
                                    // Sesiones de HU032 en solo lectura.
                                    sessions = container.trainingSessionRepository
                                        .observe(TrainingOwner.keyOf(user.userId))
                                        .map { it.recentConfirmedSessions() },
                                    online = container.connectivityMonitor.online(),
                                )
                            }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    HealthReportScreen(
                        state = state,
                        onSelectType = viewModel::selectType,
                        onSelectBodyArea = viewModel::selectBodyArea,
                        onSelectSeverity = viewModel::selectSeverity,
                        onOccurredAtChange = viewModel::setOccurredAt,
                        onSelectSession = viewModel::selectSession,
                        onRecoveryDaysChange = viewModel::onRecoveryDaysChange,
                        onConsentChange = viewModel::setConsent,
                        onSubmit = viewModel::submit,
                        onDismiss = viewModel::dismiss,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.NUTRITION) {
            // HU043: misma guarda que athlete_home; «Mi alimentación» es opción base, sin capacidad de plan (ADR-037).
            val user = remember { sessionManager.currentUser.value }
            val resources = LocalContext.current.resources
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                else -> {
                    val viewModel: FoodIntakeViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                FoodIntakeViewModel(
                                    owner = FoodOwner.keyOf(user.userId),
                                    gateway = container.foodIntakeRepository,
                                    online = container.connectivityMonitor.online(),
                                    labelOf = { food -> FoodLabels.resourceOf(food.labelKey)?.let(resources::getString) ?: food.foodCode },
                                )
                            }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    FoodIntakeScreen(
                        state = state,
                        onSelectMeal = viewModel::selectMeal,
                        onConsumedAtChange = viewModel::setConsumedAt,
                        onQueryChange = viewModel::onQueryChange,
                        onAddFood = viewModel::addFood,
                        onRemoveLine = viewModel::removeLine,
                        onQuantityChange = viewModel::onQuantityChange,
                        onSelectUnit = viewModel::selectUnit,
                        onSubmit = viewModel::submit,
                        onRetry = viewModel::refresh,
                        onDismiss = viewModel::dismiss,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.SESSION_REMINDERS) {
            // HU038: misma guarda que athlete_home y, además, la capacidad nutrition.session.reminders vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { SessionRemindersAccess.canUse(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.SESSION_REMINDERS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: SessionRemindersViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                SessionRemindersViewModel(TrainingOwner.keyOf(user.userId), container.sessionRemindersRepository, container.reminderNotifier)
                            }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSystemStatus() }
                    // El permiso POST_NOTIFICATIONS lo solicita el mecanismo de HU039, sin modificarlo.
                    val enableSystemNotifications = rememberSystemNotificationsEnabler(onResult = viewModel::refreshSystemStatus)
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    SessionRemindersScreen(
                        state = state,
                        onToggle = viewModel::setEnabled,
                        onChangeInterval = viewModel::changeInterval,
                        onSave = viewModel::save,
                        onDiscard = viewModel::discardChanges,
                        onRetry = viewModel::retry,
                        onEnableSystemNotifications = { enableSystemNotifications(state.systemNotifications) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.DEVICES) {
            // HU037: misma guarda que athlete_home y, además, la capacidad devices.measurement.connect vigente.
            val user = remember { sessionManager.currentUser.value }
            val allowed = remember { DevicesAccess.canUse(sessionManager.access.value) }
            when {
                user == null -> LaunchedEffect(Unit) { navController.replaceAll(loginRoute()) }
                user.actorType != ActorType.ATHLETE -> LaunchedEffect(Unit) {
                    sessionManager.clearLocalSession()
                    navController.replaceAll(Destinations.UNAUTHORIZED)
                }
                !allowed -> LaunchedEffect(Unit) {
                    navController.navigate(Destinations.ATHLETE_HOME) {
                        popUpTo(Destinations.DEVICES) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                else -> {
                    val viewModel: DevicesViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                DevicesViewModel(user, container.devicesRepository, container.devicesRepository, container.monitorLink, container.measurementUploader.status)
                            }
                        }
                    )
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAvailability() }
                    val requestPermissions = rememberBluetoothPermissionRequester(onResult = viewModel::refreshAvailability)
                    LaunchedEffect(state.sessionExpired) {
                        if (state.sessionExpired) {
                            viewModel.onSessionExpiredHandled()
                            navController.replaceAll(loginRoute(LoginNotice.SESSION_EXPIRED))
                        }
                    }
                    DevicesScreen(
                        state = state,
                        onRequestPermissions = requestPermissions,
                        onOpenBluetoothSettings = { openBluetoothSettings(context) },
                        onSearch = viewModel::search,
                        onStopSearch = viewModel::stopSearch,
                        onSelect = viewModel::select,
                        onNameChange = viewModel::onNameChange,
                        onConfirmLink = viewModel::confirmLink,
                        onCancelLink = viewModel::cancelLink,
                        onAskRevoke = viewModel::askRevoke,
                        onConfirmRevoke = viewModel::confirmRevoke,
                        onCancelRevoke = viewModel::cancelRevoke,
                        onRetry = viewModel::refresh,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable(Destinations.UNAUTHORIZED) {
            UnauthorizedScreen(onGoToLogin = { navController.replaceAll(loginRoute()) })
        }

        composable(Destinations.STATUS) {
            val viewModel: StatusViewModel = viewModel(
                factory = viewModelFactory { initializer { StatusViewModel(container.config, container.statusRepository) } }
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            StatusScreen(state = state, onRetry = viewModel::refresh)
        }
    }
}
