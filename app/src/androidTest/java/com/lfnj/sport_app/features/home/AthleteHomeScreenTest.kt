package com.lfnj.sport_app.features.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.session.AccessOutcome
import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.navigation.NavigationRegistry
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AthleteHomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val user = SessionUser("u-1", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val free = AccessSnapshot(
        ActorType.ATHLETE, SubscriptionStatus.ACTIVE, "FREE", emptySet(),
        setOf("training.routes.suggest", "events.suggest", "integrations.strava.connect", "calendar.sport.view"),
    )

    private fun show(state: HomeUiState) = composeRule.setContent {
        SportappTheme { AthleteHomeScreen(state = state, onRetry = {}) }
    }

    @Test
    fun cargaDelPlan() {
        show(HomeUiState(user = user))

        composeRule.onNodeWithText("Hola, Ana Deportista").assertIsDisplayed()
        composeRule.onNodeWithTag("plan_loading").assertIsDisplayed()
    }

    @Test
    fun ca7MenuConLasCapacidadesDelPlan() {
        show(HomeUiState(user = user, loadingAccess = false, access = free, menu = NavigationRegistry.athleteMenu(free)))

        composeRule.onNodeWithText("Plan FREE activo.").assertIsDisplayed()
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_home").assertIsDisplayed()
        // HU043: «Mi alimentación» es opción base habilitada, sin capacidad de plan.
        composeRule.onNodeWithTag("menu_nutrition").assertIsDisplayed().assertIsEnabled()
        // HU039: opción base habilitada, sin capacidad de plan.
        composeRule.onNodeWithTag("menu_notifications").assertIsDisplayed().assertIsEnabled()
        // HU042: «Mi salud» es opción base habilitada, sin capacidad de plan.
        composeRule.onNodeWithTag("menu_health").assertIsDisplayed().assertIsEnabled()
        // HU026 ya tiene destino en el móvil: la opción queda habilitada.
        composeRule.onNodeWithTag("menu_training.routes.suggest").assertIsDisplayed().assertIsEnabled()
        // HU027 ya tiene destino en el móvil: la opción queda habilitada.
        composeRule.onNodeWithTag("menu_events.suggest").assertIsDisplayed().assertIsEnabled()
        // «Próximamente»: HU031 (Strava) aún no tiene destino móvil; cambiará cuando esa HU se implemente.
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_integrations.strava.connect"))
        composeRule.onNodeWithTag("menu_integrations.strava.connect").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_calendar.sport.view"))
        composeRule.onNodeWithTag("menu_calendar.sport.view").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_training.session.record").assertDoesNotExist()
    }

    @Test
    fun aprovisionamientoNoMuestraOpcionesDelPlan() {
        val provisioning = free.copy(subscriptionStatus = SubscriptionStatus.PROVISIONING)
        show(HomeUiState(user = user, loadingAccess = false, access = provisioning, menu = NavigationRegistry.athleteMenu(provisioning)))

        composeRule.onNodeWithText("Estamos activando tu plan.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_events.suggest").assertDoesNotExist()
        // HU043: «Mi alimentación» también sigue disponible durante el aprovisionamiento.
        composeRule.onNodeWithTag("menu_nutrition").assertIsDisplayed().assertIsEnabled()
        // HU039: la bandeja es opción base y sigue disponible durante el aprovisionamiento.
        composeRule.onNodeWithTag("menu_notifications").assertIsDisplayed().assertIsEnabled()
        // HU042: «Mi salud» también es opción base.
        composeRule.onNodeWithTag("menu_health").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun errorDeCapacidadesOfreceReintentar() {
        show(HomeUiState(user = user, loadingAccess = false, accessFailure = AccessOutcome.NoConnection))

        composeRule.onNodeWithText("No hay conexión para consultar tu plan.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Reintentar").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ HU004

    @Test
    fun hu004CerrarSesionSinOperacionEnElBffApareceComoProximamente() {
        show(HomeUiState(user = user, loadingAccess = false, access = free, menu = NavigationRegistry.athleteMenu(free)))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_logout"))
        composeRule.onNodeWithTag("menu_logout").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun hu004CerrarSesionDisponibleInvocaLaAccion() {
        var logouts = 0
        val menu = NavigationRegistry.athleteMenu(free, logoutAvailable = true)
        composeRule.setContent {
            SportappTheme {
                AthleteHomeScreen(
                    state = HomeUiState(user = user, loadingAccess = false, access = free, menu = menu),
                    onRetry = {},
                    onLogout = { logouts++ },
                )
            }
        }

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_logout"))
        composeRule.onNodeWithTag("menu_logout").performClick()

        composeRule.runOnIdle { assertEquals(1, logouts) }
    }

    @Test
    fun hu004CierreEnCursoMuestraProgreso() {
        show(HomeUiState(user = user, loadingAccess = false, access = free, loggingOut = true))

        composeRule.onNodeWithTag("logout_progress").assertIsDisplayed()
        composeRule.onNodeWithText("Cerrando sesión…").assertIsDisplayed()
    }
}
