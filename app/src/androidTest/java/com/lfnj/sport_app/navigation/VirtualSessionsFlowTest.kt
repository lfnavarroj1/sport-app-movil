package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HU040 en la aplicación (menú, guarda, ViewModel, Room y cifrado reales) contra un `bff-mobile`
 * simulado con MockWebServer según la copia 0.18.0. Prueba de componente con contrato simulado,
 * **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VirtualSessionsFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController

    /** Respuestas por «MÉTODO ruta»; sin respuesta preparada se corta la conexión (falta de red). */
    private val responses = ConcurrentHashMap<String, ArrayDeque<MockResponse>>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val start: Instant = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.requestUrl?.encodedPath}"
                return synchronized(responses) { responses[key]?.removeFirstOrNull() }
                    ?: MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
        }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.18.0", "0.1.0")
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

    private fun respond(method: String, path: String, response: MockResponse) = synchronized(responses) {
        responses.getOrPut("$method $path") { ArrayDeque() }.addLast(response)
    }

    private fun requestsTo(method: String, path: String) = requests.filter { it.method == method && it.requestUrl?.encodedPath == path }

    private fun login(capabilities: List<String> = PREMIUM, plan: String = "PREMIUM") {
        respond("POST", "/v1/sessions", json(SESSION))
        respond("GET", "/v1/me/capabilities", json(capabilitiesBody(capabilities, plan)))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Plan $plan activo."), 5_000)
    }

    private fun openFromMenu() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_services.virtual_sessions.schedule"))
        composeRule.onNodeWithTag("menu_services.virtual_sessions.schedule").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_sessions_screen"), 5_000)
    }

    private fun interval(startsAt: Instant = start) =
        """{"availability_id":"$SLOT_ID","starts_at":"$startsAt","ends_at":"${startsAt.plus(Duration.ofHours(1))}","time_zone":"Europe/Madrid","capacity":1}"""

    private fun catalog(startsAt: Instant = start) =
        """{"items":[{"service_id":"$SERVICE_ID","provider_id":"$PROVIDER_ID","name":"Asesoría de ritmo para 10K",""" +
            """"description":"Revisión de objetivos.","category":"COACHING","modality":"VIRTUAL",""" +
            """"price":{"amount":"120000.00","currency":"COP","billing_unit":"SESSION"},""" +
            """"availability":[${interval(startsAt)}],"available":true}],"next_cursor":null,"restricted_categories":[]}"""

    private fun appointment(startsAt: Instant = start, status: String = "BOOKED", version: Int = 1): String {
        val cancelled = if (status == "BOOKED") "null" else "\"${Instant.now().truncatedTo(ChronoUnit.SECONDS)}\""
        return """{"appointment_id":"$APPOINTMENT_ID","athlete_id":"$USER_ID","provider_id":"$PROVIDER_ID","service_id":"$SERVICE_ID",""" +
            """"availability_id":"$SLOT_ID","service":{"name":"Asesoría de ritmo para 10K","category":"COACHING","modality":"VIRTUAL",""" +
            """"fee":{"amount":"120000.00","currency":"COP","billing_unit":"SESSION"}},"starts_at":"$startsAt",""" +
            """"ends_at":"${startsAt.plus(Duration.ofHours(1))}","time_zone":"Europe/Madrid","status":"$status","meeting_url":null,""" +
            """"booked_at":"2026-10-25T14:00:00Z","cancelled_at":$cancelled,"version":$version}"""
    }

    private fun appointments(vararg items: String) = """{"items":[${items.joinToString(",")}],"next_cursor":null}"""

    private fun error(code: String) = """{"code":"$code","message":"m","correlation_id":"c-40"}"""

    private fun bookFirstSlot() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_service_$SERVICE_ID"), 5_000)
        composeRule.onNodeWithTag("virtual_service_$SERVICE_ID").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_slot_book_$SLOT_ID"), 5_000)
        composeRule.onNodeWithTag("virtual_slot_book_$SLOT_ID").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_booking_dialog"), 5_000)
        composeRule.onNodeWithTag("virtual_booking_confirm").performClick()
    }

    @Test
    fun ca1Ca4ReservaVeLaCitaYLaCancelaATiempo() {
        login()
        respond("GET", "/v1/services/availability", json(catalog()))
        respond("GET", "/v1/me/appointments", json(appointments()))
        respond("POST", "/v1/me/appointments", json(appointment(), 201))
        respond("DELETE", "/v1/me/appointments/$APPOINTMENT_ID", json(appointment(status = "CANCELLED_BY_ATHLETE", version = 2)))
        respond("GET", "/v1/services/availability", json(catalog()))
        openFromMenu()

        bookFirstSlot()

        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_sessions_booked_notice"), 5_000)
        composeRule.onNodeWithTag("virtual_appointment_$APPOINTMENT_ID").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("virtual_appointment_meeting_$APPOINTMENT_ID", useUnmergedTree = true).assertExists()
        val post = requestsTo("POST", "/v1/me/appointments").single()
        UUID.fromString(post.getHeader("Idempotency-Key"))
        assertEquals("""{"service_id":"$SERVICE_ID","availability_id":"$SLOT_ID"}""", post.body.readUtf8())
        assertEquals("Bearer access-sintetico", post.getHeader("Authorization"))
        val availability = requestsTo("GET", "/v1/services/availability").first().requestUrl
        assertEquals(listOf("COACHING"), availability?.queryParameterValues("category"))
        assertEquals(listOf("VIRTUAL", "HYBRID"), availability?.queryParameterValues("modality"))

        composeRule.onNodeWithTag("virtual_appointment_cancel_$APPOINTMENT_ID").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_cancel_dialog"), 5_000)
        composeRule.onNodeWithTag("virtual_cancel_confirm").performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_sessions_cancelled_notice"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Cancelada por ti"), 5_000)
        assertEquals(1, requestsTo("DELETE", "/v1/me/appointments/$APPOINTMENT_ID").size)
        composeRule.waitUntil(5_000) { requestsTo("GET", "/v1/services/availability").size == 2 }
    }

    @Test
    fun ca4UnaCitaA2HorasNoSePuedeCancelarYLoExplica() {
        login()
        val soon = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS)
        respond("GET", "/v1/services/availability", json(catalog()))
        respond("GET", "/v1/me/appointments", json(appointments(appointment(startsAt = soon))))
        openFromMenu()

        composeRule.onNodeWithTag("virtual_sessions_tab_mine").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_appointment_window_closed_$APPOINTMENT_ID"), 5_000)
        composeRule.onNodeWithTag("virtual_appointment_cancel_$APPOINTMENT_ID").assertDoesNotExist()
        assertTrue(requestsTo("DELETE", "/v1/me/appointments/$APPOINTMENT_ID").isEmpty())
    }

    @Test
    fun ca2UnIntervaloLlenoSeTrataComoConflictoYActualizaLaDisponibilidad() {
        login()
        respond("GET", "/v1/services/availability", json(catalog()))
        respond("GET", "/v1/me/appointments", json(appointments()))
        respond("POST", "/v1/me/appointments", json(error("availability_full"), 409))
        respond("GET", "/v1/services/availability", json(catalog()))
        openFromMenu()

        bookFirstSlot()

        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_booking_problem_full"), 5_000)
        composeRule.onNodeWithTag("virtual_booking_confirm").assertDoesNotExist()
        composeRule.waitUntil(5_000) { requestsTo("GET", "/v1/services/availability").size == 2 }
        assertEquals(1, requestsTo("POST", "/v1/me/appointments").size)
    }

    @Test
    fun ca3SinLaCapacidadDelBackendExplicaElPlanRequerido() {
        login()
        respond("GET", "/v1/services/availability", json(catalog()))
        respond("GET", "/v1/me/appointments", json(appointments()))
        respond("POST", "/v1/me/appointments", json(error("capability_required"), 403))
        openFromMenu()

        bookFirstSlot()

        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_booking_problem_capability_required"), 5_000)
    }

    @Test
    fun ca3UnDeportistaIntermedioNoVeLaOpcionYLaRutaDirectaSeRechaza() {
        login(INTERMEDIATE, "INTERMEDIATE")

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_services.virtual_sessions.schedule").assertDoesNotExist()
        composeRule.runOnIdle { navController.navigate(Destinations.VIRTUAL_SESSIONS) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("virtual_sessions_screen").assertDoesNotExist()
        assertTrue(requestsTo("GET", "/v1/services/availability").isEmpty())
    }

    @Test
    fun sinRedMuestraLoGuardadoYNoReserva() {
        login()
        respond("GET", "/v1/services/availability", json(catalog()))
        respond("GET", "/v1/me/appointments", json(appointments()))
        openFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_service_$SERVICE_ID"), 5_000)
        composeRule.onNodeWithTag("virtual_sessions_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)

        // Sin respuestas preparadas el servidor corta la conexión: solo lectura cacheada.
        openFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_sessions_offline"), 10_000)
        composeRule.onNodeWithTag("virtual_service_$SERVICE_ID").performScrollTo().performClick()
        composeRule.onNodeWithTag("virtual_slot_book_$SLOT_ID").performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("virtual_booking_requires_connection"), 5_000)
        composeRule.onNodeWithTag("virtual_booking_confirm").performClick()
        assertTrue(requestsTo("POST", "/v1/me/appointments").isEmpty())
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val SERVICE_ID = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c"
        const val PROVIDER_ID = "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"
        const val SLOT_ID = "11111111-2222-4333-8444-555555555555"
        const val APPOINTMENT_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val INTERMEDIATE = listOf("training.session.record", "training.realtime.monitor")
        val PREMIUM = INTERMEDIATE + "services.virtual_sessions.schedule"
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""

        fun capabilitiesBody(capabilities: List<String>, plan: String) =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"$plan","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":${capabilities.joinToString(",", "[", "]") { "\"$it\"" }},""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
