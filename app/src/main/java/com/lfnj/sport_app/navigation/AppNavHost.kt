package com.lfnj.sport_app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.lfnj.sport_app.features.home.AthleteHomeScreen
import com.lfnj.sport_app.features.home.HomeViewModel
import com.lfnj.sport_app.features.identity.login.LoginNotice
import com.lfnj.sport_app.features.identity.login.LoginScreen
import com.lfnj.sport_app.features.identity.login.LoginViewModel
import com.lfnj.sport_app.features.identity.start.SessionStartScreen
import com.lfnj.sport_app.features.identity.start.SessionStartViewModel
import com.lfnj.sport_app.features.identity.unauthorized.UnauthorizedScreen
import com.lfnj.sport_app.features.status.StatusScreen
import com.lfnj.sport_app.features.status.StatusViewModel

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
                                HomeViewModel(user, sessionManager::loadAccess, sessionManager.canLogout, sessionManager::logout)
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
                    AthleteHomeScreen(state = state, onRetry = viewModel::refresh, onLogout = viewModel::logout)
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
