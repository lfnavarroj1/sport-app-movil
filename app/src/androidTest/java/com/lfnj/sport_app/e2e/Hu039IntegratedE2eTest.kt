package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.MainActivity
import com.lfnj.sport_app.SportApp
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.features.notifications.data.InboxRefresh
import com.lfnj.sport_app.features.notifications.model.NotificationType
import com.lfnj.sport_app.features.notifications.model.NotificationsOwner
import com.lfnj.sport_app.features.notifications.sync.InboxSyncRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E de HU039 (ADR-045, «Evidencia requerida»): aplicación real (MainActivity, Room, Keystore y
 * URL de la variante) → `bff-mobile` → `events_management` en la composición local. Un organizador
 * real publica un evento (HU024) que coincide con el deporte y la ciudad del deportista; el worker
 * de notificaciones genera `EVENT_PUBLISHED_MATCH`, la sincronización de la app (la misma que corre
 * WorkManager) la trae a la bandeja una sola vez, el deportista la toca, se abre el detalle del
 * evento (HU041) y queda leída en el backend.
 *
 * Requiere la cuenta sintética del deportista y el `event_id` ya publicado (con el worker,
 * la suscripción `eventos-notificaciones-v1` y el proyector desplegados por `T-HU039-PLAT`). Se
 * omite sin argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu039IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eEventId=<event_id publicado>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu039IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val password: String? = args.getString("e2ePassword")
    private val email: String? = args.getString("e2eAthleteEmail")
    private val eventId: String? = args.getString("e2eEventId")
    private val container get() = (context as SportApp).container

    @Before
    fun setUp() {
        assumeTrue("E2E omitido: faltan la cuenta sintética o el evento", password != null && email != null && eventId != null)
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
    }

    private fun login() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(requireNotNull(email))
        composeRule.onNodeWithTag("login_password").performTextInput(requireNotNull(password))
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun laNotificacionDelEventoPublicadoLlegaUnaVezALaBandejaYAbreElDetalle() {
        val event = requireNotNull(eventId)
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val owner = NotificationsOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)

            // Generación asíncrona (worker → outbox → proyector): se sincroniza como lo hace WorkManager hasta verla.
            val notification = runBlocking {
                val deadline = System.currentTimeMillis() + SYNC_TIMEOUT
                while (System.currentTimeMillis() < deadline) {
                    assertEquals(InboxSyncRun.COMPLETED, container.notificationsSyncEngine.run())
                    val found = container.notificationsRepository.observe(owner).first()?.items
                        ?.filter { it.eventId == event && it.type == NotificationType.EVENT_PUBLISHED_MATCH }
                    if (!found.isNullOrEmpty()) {
                        assertEquals("una sola notificación por type:event_id:recipient_id", 1, found.size)
                        return@runBlocking found.single()
                    }
                    delay(2_000)
                }
                error("La notificación del evento $event no llegó a la bandeja")
            }
            // Repetir la sincronización no duplica la notificación.
            runBlocking { assertEquals(InboxRefresh.Updated, container.notificationsRepository.refresh(owner)) }

            composeRule.onNodeWithTag("open_menu").performClick()
            composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_notifications"))
            composeRule.onNodeWithTag("menu_notifications").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("notification_${notification.notificationId}"), TIMEOUT)
            composeRule.onNodeWithTag("notification_${notification.notificationId}").performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_name"), TIMEOUT)

            // La lectura queda confirmada por el backend.
            runBlocking {
                val deadline = System.currentTimeMillis() + SYNC_TIMEOUT
                while (System.currentTimeMillis() < deadline) {
                    container.notificationsRepository.refresh(owner)
                    val item = container.notificationsRepository.observe(owner).first()?.items
                        ?.single { it.notificationId == notification.notificationId }
                    if (item?.unread == false) return@runBlocking
                    delay(1_000)
                }
                error("La notificación no quedó leída")
            }
            val items = runBlocking { container.notificationsRepository.observe(owner).first()!!.items }
            assertFalse(items.single { it.notificationId == notification.notificationId }.unread)
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val SYNC_TIMEOUT = 60_000L
    }
}
