package com.lfnj.sport_app.features.nutrition.reminders

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.engine.ReminderBanner
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRule
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRuleset
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** HU038: estados de «Recordatorios durante sesión» y del banner, con datos sintéticos. */
@RunWith(AndroidJUnit4::class)
class SessionRemindersScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Bogota")
    private val events = mutableListOf<String>()

    private fun show(state: SessionRemindersUiState) = composeRule.setContent {
        SportappTheme {
            SessionRemindersScreen(
                state = state,
                onToggle = { type, enabled -> events += "toggle:$type:$enabled" },
                onChangeInterval = { type, delta -> events += "interval:$type:$delta" },
                onSave = { events += "save" },
                onDiscard = { events += "discard" },
                onRetry = { events += "retry" },
                onEnableSystemNotifications = { events += "enable" },
                onBack = { events += "back" },
            )
        }
    }

    private fun loaded(
        problem: RemindersProblem? = null,
        system: SystemNotificationsStatus = SystemNotificationsStatus.ENABLED,
        draft: ReminderSetting? = null,
        outcome: RemindersSaveOutcome? = null,
    ) = SessionRemindersUiState(
        zone = zone, loadingCache = false, config = CONFIG, problem = problem, systemNotifications = system,
        draftHydration = draft, saveOutcome = outcome,
    )

    @Test
    fun cargaMuestraElIndicador() {
        show(SessionRemindersUiState(zone = zone))
        composeRule.onNodeWithTag("reminders_loading").assertIsDisplayed()
    }

    @Test
    fun normalMuestraMensajesAprobadosIntervalosRangosYAviso() {
        show(loaded())
        composeRule.onNodeWithTag("reminders_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("reminders_message_hydration").assertTextContains(HYDRATION_MESSAGE)
        composeRule.onNodeWithTag("reminders_interval_hydration").assertTextContains("Cada 15 minutos")
        composeRule.onNodeWithTag("reminders_message_nutrition").performScrollTo().assertTextContains(NUTRITION_MESSAGE)
        composeRule.onNodeWithText("Solo en sesiones de más de 60 minutos.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Permitido: de 10 a 60 minutos (recomendado 15).").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("reminders_disclaimer").performScrollTo().assertTextContains(DISCLAIMER)
        composeRule.onNodeWithTag("reminders_save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun losControlesEmitenLosCambiosYRespetanLosLimites() {
        show(loaded(draft = ReminderSetting(true, 10)))
        composeRule.onNodeWithTag("reminders_decrease_hydration").assertIsNotEnabled()
        composeRule.onNodeWithTag("reminders_increase_hydration").performClick()
        composeRule.onNodeWithTag("reminders_toggle_nutrition").performScrollTo().performClick()
        composeRule.onNodeWithTag("reminders_save").performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("reminders_discard").performScrollTo().performClick()

        assertEquals(listOf("interval:HYDRATION:5", "toggle:NUTRITION:false", "save", "discard"), events)
    }

    @Test
    fun sinReglasDescargadasDeclaraInformacionInsuficiente() {
        show(SessionRemindersUiState(zone = zone, loadingCache = false, problem = RemindersProblem.NO_CONNECTION))
        composeRule.onNodeWithTag("reminders_insufficient_information").assertIsDisplayed()
        composeRule.onNodeWithTag("reminders_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun notificacionesDeshabilitadasLoDeclaraYOfreceActivarlas() {
        show(loaded(system = SystemNotificationsStatus.PERMISSION_REQUIRED))
        composeRule.onNodeWithTag("reminders_system_disabled").assertIsDisplayed()
        composeRule.onNodeWithText("Permitir notificaciones").assertIsDisplayed()
        composeRule.onNodeWithTag("reminders_enable_system").performClick()
        assertEquals(listOf("enable"), events)
    }

    @Test
    fun sinRedConservaLaConfiguracionYLoInforma() {
        show(loaded(problem = RemindersProblem.NO_CONNECTION))
        composeRule.onNodeWithTag("reminders_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("reminders_card_hydration").assertIsDisplayed()
    }

    @Test
    fun errorConCacheOfreceReintentar() {
        show(loaded(problem = RemindersProblem.UNAVAILABLE))
        composeRule.onNodeWithTag("reminders_retry").performClick()
        assertTrue("retry" in events)
    }

    @Test
    fun sinCapacidadNoMuestraLaConfiguracion() {
        show(SessionRemindersUiState(zone = zone, loadingCache = false, problem = RemindersProblem.CAPABILITY_REQUIRED))
        composeRule.onNodeWithTag("reminders_forbidden").assertIsDisplayed()
        composeRule.onNodeWithTag("reminders_card_hydration").assertDoesNotExist()
    }

    @Test
    fun conflictoDeVersionSeExplica() {
        show(loaded(outcome = RemindersSaveOutcome.VERSION_CONFLICT))
        composeRule.onNodeWithTag("reminders_save_outcome").performScrollTo().assertTextContains("cambiaron en otro lugar", substring = true)
    }

    @Test
    fun elBannerMuestraElMensajeYDeclaraCuandoEsElUnicoAviso() {
        var dismissed = false
        composeRule.setContent {
            SportappTheme {
                ReminderBannerCard(
                    ReminderBanner("s", ReminderType.HYDRATION, 1, HYDRATION_MESSAGE, DISCLAIMER, systemNotificationShown = false),
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithTag("reminder_banner_message").assertTextContains(HYDRATION_MESSAGE)
        composeRule.onNodeWithText(DISCLAIMER).assertIsDisplayed()
        composeRule.onNodeWithTag("reminder_banner_only").assertIsDisplayed()
        composeRule.onNodeWithTag("reminder_banner_dismiss").performClick()
        assertTrue(dismissed)
    }

    private companion object {
        const val HYDRATION_MESSAGE = "Hidrátate: bebe agua o bebida isotónica"
        const val NUTRITION_MESSAGE = "Consume un alimento ligero según tu plan"
        const val DISCLAIMER = "Recordatorio general: no es un diagnóstico médico ni prescribe cantidades."
        val CONFIG = ReminderConfig(
            ReminderRuleset(
                "session-reminders-2026-10-13.v1",
                listOf(
                    ReminderRule(ReminderType.HYDRATION, 15, 10, 60, 0, HYDRATION_MESSAGE, DISCLAIMER),
                    ReminderRule(ReminderType.NUTRITION, 45, 30, 120, 60, NUTRITION_MESSAGE, DISCLAIMER),
                ),
            ),
            ReminderPreferences(ReminderSetting(true, 15), ReminderSetting(true, 45), 0),
            Instant.parse("2026-10-26T12:00:00Z"),
        )
    }
}
