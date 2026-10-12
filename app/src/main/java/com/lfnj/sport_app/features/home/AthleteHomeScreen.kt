package com.lfnj.sport_app.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.session.AccessOutcome
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.navigation.MenuAction
import com.lfnj.sport_app.navigation.MenuEntry
import com.lfnj.sport_app.navigation.NavigationRegistry
import kotlinx.coroutines.launch

/**
 * Inicio del deportista con menú lateral construido desde las capacidades vigentes (HU003 CA7)
 * y la acción «Cerrar sesión» (HU004).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AthleteHomeScreen(
    state: HomeUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(Modifier.testTag("athlete_menu")) {
                AthleteMenu(
                    state,
                    onSelectHome = { scope.launch { drawerState.close() } },
                    onLogout = {
                        scope.launch { drawerState.close() }
                        onLogout()
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.home_athlete_title)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.testTag("open_menu")) {
                            Icon(painterResource(R.drawable.ic_menu), contentDescription = stringResource(R.string.menu_open))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .testTag("athlete_home"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = state.user?.let { stringResource(R.string.home_greeting, it.fullName) }
                        ?: stringResource(R.string.home_greeting_anonymous),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.home_athlete_purpose))
                if (state.loggingOut) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .testTag("logout_progress")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.home_logging_out))
                    }
                } else {
                    PlanStatusCard(state, onRetry)
                }
            }
        }
    }
}

@Composable
private fun PlanStatusCard(state: HomeUiState, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .testTag("plan_status")
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.home_plan_title), style = MaterialTheme.typography.titleMedium)
            val access = state.access
            val failure = state.accessFailure
            when {
                state.loadingAccess -> {
                    CircularProgressIndicator(Modifier.testTag("plan_loading"))
                    Text(stringResource(R.string.home_plan_loading))
                }
                failure != null -> {
                    Text(
                        if (failure is AccessOutcome.Unavailable && failure.correlationId != null) {
                            stringResource(R.string.home_plan_unavailable_reference, failure.correlationId)
                        } else if (failure is AccessOutcome.Unavailable) {
                            stringResource(R.string.home_plan_unavailable)
                        } else {
                            stringResource(R.string.home_plan_no_connection)
                        }
                    )
                    Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
                access?.subscriptionStatus == SubscriptionStatus.ACTIVE ->
                    Text(stringResource(R.string.home_plan_active, access.planCode ?: "—"))
                access?.subscriptionStatus == SubscriptionStatus.PROVISIONING ->
                    Text(stringResource(R.string.home_plan_provisioning))
                else -> Text(stringResource(R.string.home_plan_none))
            }
        }
    }
}

@Composable
private fun AthleteMenu(state: HomeUiState, onSelectHome: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        state.user?.let {
            Text(it.fullName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 28.dp))
            Text(
                stringResource(R.string.actor_athlete),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
            )
        }
        NavigationRegistry.grouped(state.menu).forEach { (group, entries) ->
            group.label?.let {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(horizontal = 28.dp, vertical = 8.dp)
                        .semantics { heading() },
                )
            }
            entries.forEach { entry -> MenuItem(entry, state.loggingOut, onSelectHome, onLogout) }
        }
        if (state.access?.subscriptionStatus == SubscriptionStatus.PROVISIONING) {
            Text(
                stringResource(R.string.menu_plan_pending),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun MenuItem(entry: MenuEntry, loggingOut: Boolean, onSelectHome: () -> Unit, onLogout: () -> Unit) {
    if (entry.action == MenuAction.LOGOUT) {
        NavigationDrawerItem(
            label = { Text(stringResource(if (loggingOut) R.string.home_logging_out else entry.label)) },
            selected = false,
            onClick = { if (!loggingOut) onLogout() },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .testTag("menu_${entry.id}"),
        )
    } else if (entry.route != null) {
        NavigationDrawerItem(
            label = { Text(stringResource(entry.label)) },
            selected = entry.id == "home",
            onClick = onSelectHome,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .testTag("menu_${entry.id}"),
        )
    } else {
        // HU aún no implementada en móvil: visible pero deshabilitada, sin enlace ni acción.
        ListItem(
            headlineContent = { Text(stringResource(entry.label)) },
            supportingContent = { Text(stringResource(R.string.menu_coming_soon)) },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .alpha(0.6f)
                .testTag("menu_${entry.id}")
                .semantics { disabled() },
        )
    }
}
