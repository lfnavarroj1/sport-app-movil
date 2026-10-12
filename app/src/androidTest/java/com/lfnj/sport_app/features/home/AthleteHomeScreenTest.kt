package com.lfnj.sport_app.features.home

import androidx.compose.ui.test.assertIsDisplayed
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
        setOf("training.routes.suggest", "events.suggest", "calendar.sport.view"),
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
        composeRule.onNodeWithTag("menu_training.routes.suggest").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("menu_events.suggest").assertIsDisplayed()
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
    }

    @Test
    fun errorDeCapacidadesOfreceReintentar() {
        show(HomeUiState(user = user, loadingAccess = false, accessFailure = AccessOutcome.NoConnection))

        composeRule.onNodeWithText("No hay conexión para consultar tu plan.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Reintentar").assertIsDisplayed()
    }
}
