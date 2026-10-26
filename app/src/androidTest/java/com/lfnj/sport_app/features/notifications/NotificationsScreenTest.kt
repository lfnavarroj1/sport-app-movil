package com.lfnj.sport_app.features.notifications

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.InboxSnapshot
import com.lfnj.sport_app.features.notifications.model.NotificationPreferences
import com.lfnj.sport_app.features.notifications.model.NotificationPriority
import com.lfnj.sport_app.features.notifications.model.NotificationType
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** HU039: bandeja (normal, carga, vacío, sin red, error, leída/no leída, deshabilitadas) y preferencias, con accesibilidad. */
@RunWith(AndroidJUnit4::class)
class NotificationsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Bogota")
    private val opened = mutableListOf<String>()
    private var enableRequests = 0
    private var retries = 0

    private fun notification(id: String, readAt: Instant? = null, reminder: Boolean = false) = InboxNotification(
        notificationId = id,
        type = if (reminder) NotificationType.EVENT_REMINDER_24H else NotificationType.EVENT_PUBLISHED_MATCH,
        priority = if (reminder) NotificationPriority.HIGH else NotificationPriority.NORMAL,
        eventId = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef",
        title = if (reminder) "Tu evento empieza mañana" else "Nuevo evento de running en Bogotá",
        body = "Carrera 10K Bogotá el 15 de noviembre.",
        createdAt = Instant.parse("2026-10-25T13:00:00Z"),
        readAt = readAt,
    )

    private val unread = notification(UNREAD_ID, reminder = true)
    private val read = notification(READ_ID, readAt = Instant.parse("2026-10-25T14:00:00Z"))

    private fun snapshot(vararg items: InboxNotification) = InboxSnapshot(items.toList(), null, Instant.parse("2026-10-25T15:00:00Z"))

    private fun show(state: NotificationsInboxUiState) = composeRule.setContent {
        SportappTheme {
            NotificationsInboxScreen(
                state = state,
                onOpen = { opened += it.notificationId },
                onRetry = { retries++ },
                onLoadMore = {},
                onDismissMarkReadProblem = {},
                onEnableSystemNotifications = { enableRequests++ },
                onOpenPreferences = {},
                onBack = {},
            )
        }
    }

    private fun stateOf(snapshot: InboxSnapshot?, problem: InboxProblem? = null, system: SystemNotificationsStatus = SystemNotificationsStatus.ENABLED) =
        NotificationsInboxUiState(
            zone = zone,
            snapshot = snapshot,
            items = snapshot?.ordered.orEmpty(),
            problem = problem,
            systemNotifications = system,
        )

    @Test
    fun muestraLeidasYNoLeidasConSuEstadoAccesibleYAbreElEvento() {
        show(stateOf(snapshot(unread, read)))

        composeRule.onNodeWithTag("notification_unread_$UNREAD_ID").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_read_$READ_ID").assertIsDisplayed()
        composeRule.onNodeWithText("1 sin leer").assertIsDisplayed()
        composeRule.onNodeWithText("Recordatorio: tu evento empieza en 24 horas").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_$UNREAD_ID")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No leída"))
            .assertHasClickAction()
        composeRule.onNodeWithTag("notification_$READ_ID")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Leída"))
        composeRule.onNodeWithTag("notifications_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_system_disabled").assertDoesNotExist()

        composeRule.onNodeWithTag("notification_$UNREAD_ID").performClick()
        assertEquals(listOf(UNREAD_ID), opened)
    }

    @Test
    fun cargaVacioYSinRedConBandejaCacheada() {
        show(stateOf(null))
        composeRule.onNodeWithTag("notifications_loading").assertIsDisplayed()
    }

    @Test
    fun bandejaConfirmadaVaciaNoEsError() {
        show(stateOf(snapshot()))
        composeRule.onNodeWithTag("notifications_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_error").assertDoesNotExist()
    }

    @Test
    fun sinRedMuestraLaBandejaCacheadaYLoDeclara() {
        show(stateOf(snapshot(unread), InboxProblem.NO_CONNECTION))
        composeRule.onNodeWithTag("notifications_problem").assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión. Ves las últimas notificaciones sincronizadas.").assertIsDisplayed()
        composeRule.onNodeWithTag("notification_$UNREAD_ID").assertIsDisplayed()
    }

    @Test
    fun sinRedYSinBandejaGuardadaPermiteReintentar() {
        show(stateOf(null, InboxProblem.NO_CONNECTION))
        composeRule.onNodeWithTag("notifications_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun unErrorSinBandejaMuestraErrorYReintento() {
        show(stateOf(null, InboxProblem.UNAVAILABLE))
        composeRule.onNodeWithTag("notifications_error").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_retry").assertIsEnabled()
    }

    @Test
    fun conNotificacionesDelSistemaDeshabilitadasLoDeclaraYOfreceActivarlas() {
        show(stateOf(snapshot(unread), system = SystemNotificationsStatus.PERMISSION_REQUIRED))
        composeRule.onNodeWithTag("notifications_system_disabled").assertIsDisplayed()
        composeRule.onNodeWithText("Permitir notificaciones").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_enable_system").performClick()
        assertEquals(1, enableRequests)
        // La bandeja sigue disponible: es el canal base.
        composeRule.onNodeWithTag("notification_$UNREAD_ID").assertIsDisplayed()
    }

    @Test
    fun bloqueadasEnAjustesOfreceAbrirLosAjustes() {
        show(stateOf(snapshot(), system = SystemNotificationsStatus.DISABLED))
        composeRule.onNodeWithText("Abrir ajustes de notificaciones").assertIsDisplayed()
    }

    @Test
    fun preferenciasConInterruptoresAccesiblesYGuardarSoloConCambios() {
        var saves = 0
        composeRule.setContent {
            SportappTheme {
                NotificationPreferencesScreen(
                    state = NotificationPreferencesUiState(
                        loading = false,
                        confirmed = NotificationPreferences(eventMatches = true, eventReminders = false, pushEnabled = false, version = 2),
                        eventMatches = true,
                        eventReminders = false,
                    ),
                    onEventMatchesChange = {},
                    onEventRemindersChange = {},
                    onSave = { saves++ },
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("preferences_event_matches").assertIsOn()
        composeRule.onNodeWithTag("preferences_event_reminders").assertIsOff()
        composeRule.onNodeWithTag("preferences_save").assertIsNotEnabled()
        assertEquals(0, saves)
    }

    private companion object {
        const val UNREAD_ID = "5b0e7c1a-2d3f-4a5b-8c6d-7e8f9a0b1c2d"
        const val READ_ID = "4a9d6b09-1c2e-4f4a-9b5c-6d7e8f9a0b1c"
    }
}
