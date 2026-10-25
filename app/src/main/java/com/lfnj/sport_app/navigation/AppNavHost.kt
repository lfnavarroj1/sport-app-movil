package com.lfnj.sport_app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import com.lfnj.sport_app.features.events.MyRegistrationsScreen
import com.lfnj.sport_app.features.events.MyRegistrationsViewModel
import com.lfnj.sport_app.features.events.model.EventRegistrationsOwner
import com.lfnj.sport_app.features.home.AthleteHomeScreen
import com.lfnj.sport_app.features.home.HomeViewModel
import com.lfnj.sport_app.features.identity.login.LoginNotice
import com.lfnj.sport_app.features.identity.login.LoginScreen
import com.lfnj.sport_app.features.identity.login.LoginViewModel
import com.lfnj.sport_app.features.identity.start.SessionStartScreen
import com.lfnj.sport_app.features.identity.start.SessionStartViewModel
import com.lfnj.sport_app.features.identity.unauthorized.UnauthorizedScreen
import com.lfnj.sport_app.features.recommendations.PlanSuggestionsScreen
import com.lfnj.sport_app.features.recommendations.PlanSuggestionsViewModel
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsAccess
import com.lfnj.sport_app.features.recommendations.model.PlanSuggestionsOwner
import com.lfnj.sport_app.features.recommendations.RouteMapLauncher
import com.lfnj.sport_app.features.recommendations.RouteSuggestionsScreen
import com.lfnj.sport_app.features.recommendations.RouteSuggestionsViewModel
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsAccess
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsOwner
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
