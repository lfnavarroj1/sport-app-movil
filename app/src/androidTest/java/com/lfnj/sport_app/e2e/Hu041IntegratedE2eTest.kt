package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.features.events.EventDetailViewModel
import com.lfnj.sport_app.features.events.RegisterBlock
import com.lfnj.sport_app.features.events.EventDetailProblem
import com.lfnj.sport_app.features.events.data.ConnectivityMonitor
import com.lfnj.sport_app.features.events.data.EventDetailResult
import com.lfnj.sport_app.features.events.data.EventRegistrationApi
import com.lfnj.sport_app.features.events.data.EventRegistrationRepository
import com.lfnj.sport_app.features.events.data.RegisterResult
import com.lfnj.sport_app.features.events.data.RegistrationsRefresh
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsCodec
import com.lfnj.sport_app.features.events.data.local.EventRegistrationsDatabase
import com.lfnj.sport_app.features.events.model.EventRegistrationsOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * E2E de HU041 (ADR-032, «Evidencia requerida»): aplicación real (MainActivity, Room, Keystore y
 * URL de la variante) → `bff-mobile` 0.15.0 → `events_management` en la composición local. El
 * deportista abre desde el calendario (HU035) un evento publicado por un organizador real, se
 * inscribe, la inscripción aparece en «Mis inscripciones» (proyección `event_registration_read`),
 * un segundo intento con otra clave responde «ya inscrito» y, sin conexión con el BFF, el detalle
 * no permite inscribirse y la lista se lee de la caché cifrada.
 *
 * Requiere una cuenta sintética de deportista con `calendar.sport.view` y un evento `PUBLISHED`
 * sintético, con inicio futuro dentro de los próximos dos meses, cupo libre y sin inscripción de
 * esa cuenta. Se omite sin argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu041IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eEventId=<event_id publicado>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu041IntegratedE2eTest {
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

    /** Calendario real → vista de lista → mes del evento (hasta dos meses adelante) → tarjeta del evento. */
    private fun openEventFromCalendar() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_calendar.sport.view"))
        composeRule.onNodeWithTag("menu_calendar.sport.view").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("calendar_screen"), TIMEOUT)
        composeRule.onNodeWithTag("calendar_mode_list").performClick()
        val card = hasTestTag("calendar_item_EVENT_PUBLISHED:$eventId")
        var found = false
        for (attempt in 0..2) {
            found = runCatching { composeRule.waitUntilExactlyOneExists(card, TIMEOUT) }.isSuccess
            if (found) break
            composeRule.onNodeWithTag("calendar_next").performClick()
        }
        assertTrue("El evento $eventId no aparece en el calendario de los próximos dos meses", found)
        composeRule.onNode(card).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_name"), TIMEOUT)
    }

    @Test
    fun seInscribeDesdeElCalendarioApareceEnMisInscripcionesYUnSegundoIntentoEsYaInscrito() {
        val event = requireNotNull(eventId)
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val owner = EventRegistrationsOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)

            // Inscripción real con confirmación explícita: solo el 201 del backend la muestra.
            openEventFromCalendar()
            composeRule.onNodeWithTag("event_register").performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_confirm"), TIMEOUT)
            composeRule.onNodeWithTag("event_confirm").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_registration_confirmed"), TIMEOUT)

            // La lectura es eventual (outbox → proyección): se consulta hasta verla en «Mis inscripciones».
            runBlocking {
                val deadline = System.currentTimeMillis() + SYNC_TIMEOUT
                while (System.currentTimeMillis() < deadline) {
                    assertEquals(RegistrationsRefresh.Updated, container.eventRegistrationRepository.refresh(owner))
                    val cached = container.eventRegistrationRepository.observe(owner).first()
                    if (cached?.items?.any { it.eventId == event } == true) break
                    delay(2_000)
                }
            }
            val registration = runBlocking {
                checkNotNull(container.eventRegistrationRepository.observe(owner).first()).items.single { it.eventId == event }
            }
            composeRule.onNodeWithTag("event_open_registrations").performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("registration_${registration.registrationId}"), TIMEOUT)
            composeRule.onNodeWithTag("registration_${registration.registrationId}").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("registrations_synced_at").assertIsDisplayed()

            // Segundo intento con otra clave: el backend responde «ya inscrito» con la inscripción existente.
            val again = runBlocking { container.eventRegistrationRepository.register(event, UUID.randomUUID().toString()) }
            assertEquals(RegisterResult.AlreadyRegistered(registration.registrationId), again)
            val detail = runBlocking { container.eventRegistrationRepository.detail(event) } as EventDetailResult.Loaded
            assertEquals(registration.registrationId, detail.detail.myRegistration?.registrationId)
            composeRule.onNodeWithTag("registration_${registration.registrationId}").performClick()
            composeRule.waitUntil(TIMEOUT) {
                composeRule.onAllNodes(hasTestTag("event_registered")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("event_register").assertDoesNotExist()

            // Sin conexión con el BFF: el detalle no permite inscribirse y la lista sale de la caché cifrada.
            runBlocking {
                val database = EventRegistrationsDatabase.create(context)
                try {
                    val unreachable = BffMobileClient.retrofit(UNREACHABLE_BFF).create(EventRegistrationApi::class.java)
                    val offline = EventRegistrationRepository(
                        dao = database.eventRegistrationsCacheDao(),
                        codec = EventRegistrationsCodec(KeystoreTokenCipher(EventRegistrationsCodec.KEY_ALIAS), BffMobileClient.json),
                        api = unreachable,
                        sessionManager = container.sessionManager,
                        json = BffMobileClient.json,
                        retryDelay = {},
                    )
                    assertEquals(RegistrationsRefresh.NoConnection, offline.refresh(owner))
                    val cached = checkNotNull(offline.observe(owner).first())
                    assertTrue(cached.items.any { it.registrationId == registration.registrationId })
                    assertNotNull(cached.syncedAt)

                    val state = withContext(Dispatchers.Main) {
                        EventDetailViewModel(event, offline, ConnectivityMonitor { flowOf(false) })
                    }.uiState.let { flow -> withTimeout(TIMEOUT) { flow.first { !it.loading } } }
                    assertEquals(EventDetailProblem.NO_CONNECTION, state.problem)
                    assertEquals(RegisterBlock.OFFLINE, state.block)
                    assertFalse(state.canRegister)
                    assertFalse(state.registered)
                } finally {
                    database.close()
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SYNC_TIMEOUT = 120_000L
        /** Puerto cerrado en el dispositivo: la conexión se rechaza como sin red. */
        const val UNREACHABLE_BFF = "http://127.0.0.1:9/"
    }
}
