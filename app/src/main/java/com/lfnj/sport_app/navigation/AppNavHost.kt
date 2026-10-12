package com.lfnj.sport_app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lfnj.sport_app.di.AppContainer
import com.lfnj.sport_app.features.status.StatusScreen
import com.lfnj.sport_app.features.status.StatusViewModel

/** Destinos de la aplicación. */
object Destinations {
    const val STATUS = "status"
}

@Composable
fun AppNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Destinations.STATUS, modifier = modifier) {
        composable(Destinations.STATUS) {
            val viewModel: StatusViewModel = viewModel(
                factory = viewModelFactory { initializer { StatusViewModel(container.config, container.statusRepository) } }
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            StatusScreen(state = state, onRetry = viewModel::refresh)
        }
    }
}
