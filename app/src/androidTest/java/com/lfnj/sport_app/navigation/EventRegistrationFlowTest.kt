package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * HU041 en la aplicación (navegación desde el calendario, guarda, ViewModels, Room y cifrado
 * reales) contra un `bff-mobile` simulado con MockWebServer según ADR-032. Prueba de componente
 * con contrato simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EventRegistrationFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController
    private val month = YearMonth.now(ZoneId.systemDefault())
    private val startsAt = "${month.plusMonths(1).atDay(15)}T12:00:00Z"

    @Before
    fun setUp() {
        // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.15.0", "0.1.0")
        container = TestAppContainer(config, InMemoryTokenStore(), ApplicationProvider.getApplicationContext())
        composeRule.setContent {
            navController = rememberNavController()
            SportappTheme { AppNavHost(container, navController = navController) }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        container.close()
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun calendarBody(): String {
        val day15 = month.atDay(15)
        return """{"from":"${month.atDay(1)}T00:00:00Z","to":"${month.atEndOfMonth()}T23:59:59Z","items":[""" +
            """{"item_id":"EVENT_PUBLISHED:$EVENT_ID","source":"EVENT_PUBLISHED","occurs_on":"$day15",""" +
            """"title":"Carrera 10K Bogotá","sport":"Running","starts_at":"${day15}T12:00:00Z","ends_at":"${day15}T15:00:00Z",""" +
            """"time_zone":"America/Bogota","location":{"name":"Parque Metropolitano Simón Bolívar","city":"Bogotá","country_code":"CO"},""" +
            """"reference":{"event_id":"$EVENT_ID"}}],""" +
            """"sources":[{"name":"events","status":"OK","truncated":false},{"name":"training","status":"OK","truncated":false}],""" +
            """"evaluated_at":"2026-10-12T22:00:00Z"}"""
    }

    private fun detailBody(myRegistration: String = "null") =
        """{"event_id":"$EVENT_ID","name":"Carrera 10K Bogotá","description":"Recorrido urbano de 10 km.",""" +
            """"discipline":"Running","location":{"name":"Parque Metropolitano Simón Bolívar","city":"Bogotá","country_code":"CO"},""" +
            """"starts_at":"$startsAt","ends_at":"$startsAt","time_zone":"America/Bogota","capacity":500,""" +
            """"status":"PUBLISHED","version":2,"fee":{"amount":"85000.00","currency":"COP"},"my_registration":$myRegistration}"""

    private val myRegistration = """{"registration_id":"$REGISTRATION_ID","status":"REGISTERED","registered_at":"2026-10-12T22:30:00Z"}"""

    private fun registrationBody() =
        """{"registration_id":"$REGISTRATION_ID","event_id":"$EVENT_ID","status":"REGISTERED",""" +
            """"registered_at":"2026-10-12T22:30:00Z","applied_fee":{"amount":"85000.00","currency":"COP"},"version":1,""" +
            """"event":{"name":"Carrera 10K Bogotá","starts_at":"$startsAt","ends_at":"$startsAt","time_zone":"America/Bogota",""" +
            """"location":{"name":"Parque Metropolitano Simón Bolívar","city":"Bogotá","country_code":"CO"}}}"""

    private fun login() {
        server.enqueue(json(SESSION))
        server.enqueue(json(CAPABILITIES))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        takeRequest()
        takeRequest()
    }

    /** Calendario → lista → tarjeta del evento: el punto de entrada de HU035. */
    private fun openEventFromCalendar(detail: MockResponse) {
        server.enqueue(json(calendarBody()))
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_calendar.sport.view"))
        composeRule.onNodeWithTag("menu_calendar.sport.view").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("calendar_screen"), 5_000)
        assertEquals("/v1/me/calendar", takeRequest().requestUrl?.encodedPath)
        composeRule.onNodeWithTag("calendar_mode_list").performClick()
        server.enqueue(detail)
        composeRule.waitUntilExactlyOneExists(hasTestTag("calendar_item_EVENT_PUBLISHED:$EVENT_ID"), 5_000)
        composeRule.onNodeWithTag("calendar_item_EVENT_PUBLISHED:$EVENT_ID").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_detail_screen"), 5_000)
        val request = takeRequest()
        assertEquals("/v1/events/$EVENT_ID", request.requestUrl?.encodedPath)
        assertEquals("Bearer access-sintetico", request.getHeader("Authorization"))
    }

    @Test
    fun desdeElCalendarioSeInscribeConConfirmacionYApareceEnMisInscripciones() {
        login()
        openEventFromCalendar(json(detailBody()))
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_name"), 5_000)
        composeRule.onNodeWithTag("event_fee_notice").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("event_register").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_confirm"), 5_000)
        server.enqueue(json(registrationBody(), status = 201).setHeader("Location", "/v1/me/event-registrations/$REGISTRATION_ID"))
        composeRule.onNodeWithTag("event_confirm").performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_registration_confirmed"), 5_000)
        val command = takeRequest()
        assertEquals("POST", command.method)
        assertEquals("/v1/events/$EVENT_ID/registrations", command.requestUrl?.encodedPath)
        val key = checkNotNull(command.getHeader("Idempotency-Key"))
        assertEquals(key, UUID.fromString(key).toString())
        assertEquals(0L, command.bodySize)
        composeRule.onNodeWithTag("event_register").assertDoesNotExist()

        server.enqueue(json("""{"items":[${registrationBody()}],"next_cursor":null}"""))
        composeRule.onNodeWithTag("event_open_registrations").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("registration_$REGISTRATION_ID"), 5_000)
        assertEquals("/v1/me/event-registrations", takeRequest().requestUrl?.encodedPath)
        composeRule.onNodeWithTag("registrations_synced_at").assertIsDisplayed()
    }

    @Test
    fun unSegundoIntentoConOtraClaveInformaYaInscrito() {
        login()
        openEventFromCalendar(json(detailBody()))
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_name"), 5_000)

        composeRule.onNodeWithTag("event_register").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_confirm"), 5_000)
        server.enqueue(
            json(
                """{"code":"event_registration_exists","message":"m","correlation_id":"c-1",""" +
                    """"details":[{"field":"registration_id","issue":"$REGISTRATION_ID"}]}""",
                status = 409,
            )
        )
        server.enqueue(json(detailBody(myRegistration)))
        composeRule.onNodeWithTag("event_confirm").performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_failure_already_registered"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_registered"), 5_000)
        composeRule.onNodeWithTag("event_register").assertDoesNotExist()
        composeRule.onNodeWithTag("event_registration_confirmed").assertDoesNotExist()
        assertEquals("POST", takeRequest().method)
        assertEquals("GET", takeRequest().method)
    }

    @Test
    fun sinCupoSeInformaSinReintentarNiMostrarInscripcion() {
        login()
        openEventFromCalendar(json(detailBody()))
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_name"), 5_000)

        composeRule.onNodeWithTag("event_register").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_confirm"), 5_000)
        server.enqueue(json("""{"code":"event_full","message":"m","correlation_id":"c-1"}""", status = 409))
        composeRule.onNodeWithTag("event_confirm").performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_failure_full"), 5_000)
        composeRule.onNodeWithTag("event_registration_confirmed").assertDoesNotExist()
        takeRequest()
        composeRule.waitForIdle()
        assertEquals("solo un envío: sin reintento automático de un 409", 5, server.requestCount)
    }

    @Test
    fun sinRedElBotonQuedaDeshabilitadoYMisInscripcionesSeMuestraDesdeLaCache() {
        login()
        // Primera visita con red: la lista confirmada queda cifrada en el dispositivo.
        server.enqueue(json("""{"items":[${registrationBody()}],"next_cursor":null}"""))
        composeRule.runOnIdle { navController.navigate(Destinations.MY_EVENT_REGISTRATIONS) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("registration_$REGISTRATION_ID"), 5_000)
        takeRequest()
        composeRule.onNodeWithTag("registrations_back").performClick()

        // Sin red: el detalle no se confirma y la inscripción queda deshabilitada con explicación.
        container.online.value = false
        composeRule.runOnIdle { navController.navigate(Destinations.eventDetail(EVENT_ID)) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_error"), 5_000)
        composeRule.onNodeWithTag("event_register").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("event_register_block").assertIsDisplayed()

        composeRule.onNodeWithTag("event_open_registrations").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("registrations_problem"), 10_000)
        composeRule.onNodeWithTag("registration_$REGISTRATION_ID").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("registrations_synced_at").assertIsDisplayed()
    }

    @Test
    fun conRedPeroDesconectadoAntesDeInscribirseElBotonSeDeshabilita() {
        login()
        openEventFromCalendar(json(detailBody()))
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_name"), 5_000)

        container.online.value = false

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("event_register_block")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("event_register").performScrollTo().assertIsNotEnabled()
        assertTrue(server.requestCount <= 4)
    }

    private companion object {
        const val EVENT_ID = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"
        const val REGISTRATION_ID = "6d2f0c8e-4a1b-4f3e-9c7d-2b8a5e1f0d43"
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record","calendar.sport.view"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
