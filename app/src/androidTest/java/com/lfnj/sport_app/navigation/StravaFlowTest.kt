package com.lfnj.sport_app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * HU031 en la aplicación (navegación, guarda, ViewModel, Room y cifrado reales) contra un `bff-mobile`
 * simulado con MockWebServer según el contrato 0.22.0. El navegador se sustituye por un registro y el
 * retorno de Strava se simula entregando el enlace profundo: prueba de componente con contrato
 * simulado, **no** un E2E (no hay aplicación OAuth registrada en Strava ni `T-HU031-PLAT`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StravaFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        server.start()
        val config = AppConfig(server.url("/").toString(), "test", "0.3.0", "0.1.0")
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

    private fun take(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun login(capabilities: String = CAPABILITIES) {
        server.enqueue(json(SESSION))
        server.enqueue(json(capabilities))
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        take()
        take()
    }

    /** [response]: respuesta de `GET /v1/me/integrations/strava`; sin ella, falta de red. */
    private fun openStravaFromMenu(response: MockResponse? = null) {
        response?.let(server::enqueue)
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_integrations.strava.connect"))
        composeRule.onNodeWithTag("menu_integrations.strava.connect").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_screen"), 5_000)
    }

    private fun assertStatusRequest(request: RecordedRequest = take()) {
        assertEquals("GET", request.method)
        assertEquals("/v1/me/integrations/strava", request.requestUrl?.encodedPath)
        assertEquals("Bearer access-sintetico", request.getHeader("Authorization"))
    }

    @Test
    fun vincularAbreElConsentimientoOficialYConfirmaElRetornoLinked() {
        login()
        openStravaFromMenu(json(NOT_LINKED))
        assertStatusRequest()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_status_not_linked"), 5_000)

        server.enqueue(json(AUTHORIZATION, status = 201))
        composeRule.onNodeWithTag("strava_link").performClick()
        composeRule.onNodeWithTag("strava_consent_accept").performClick()

        val authorization = take()
        assertEquals("POST", authorization.method)
        assertEquals("/v1/me/integrations/strava/authorization", authorization.requestUrl?.encodedPath)
        assertEquals("Bearer access-sintetico", authorization.getHeader("Authorization"))
        assertEquals(0L, authorization.bodySize)
        composeRule.waitUntil(5_000) { container.browser.opened.isNotEmpty() }
        assertEquals(listOf(AUTHORIZATION_URL), container.browser.opened.toList())
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_status_linking"), 5_000)

        // Retorno de Strava por el callback de bff-mobile: el estado se confirma con el BFF.
        server.enqueue(json(ACTIVE_NEVER_SYNCED))
        composeRule.runOnIdle { assertTrue(container.stravaLinkLaunch.offer("sportapp://integrations/strava?result=LINKED")) }
        assertStatusRequest()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_notice_linked"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_status_active"), 5_000)
        composeRule.onNodeWithTag("strava_sync_never").performScrollTo().assertIsDisplayed()
        assertNull(container.stravaLinkLaunch.pending.value)
    }

    @Test
    fun sincronizarConClaveDeIdempotenciaYDesvincular() {
        login()
        openStravaFromMenu(json(ACTIVE_NEVER_SYNCED))
        assertStatusRequest()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_status_active"), 5_000)

        server.enqueue(json(SYNC_ACCEPTED, status = 202))
        server.enqueue(json(ACTIVE_SYNCED))
        composeRule.onNodeWithTag("strava_sync").performScrollTo().performClick()
        val sync = take()
        assertEquals("POST", sync.method)
        assertEquals("/v1/me/integrations/strava/syncs", sync.requestUrl?.encodedPath)
        assertFalse(sync.getHeader("Idempotency-Key").isNullOrBlank())
        assertStatusRequest()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_notice_sync_requested"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_sync_succeeded"), 5_000)
        composeRule.onNodeWithTag("strava_sync_succeeded").performScrollTo().assertTextContains("3 actividades importadas o actualizadas.", substring = true)

        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(json(REVOKED))
        composeRule.onNodeWithTag("strava_notice_dismiss").performClick()
        composeRule.onNodeWithTag("strava_unlink").performScrollTo().performClick()
        composeRule.onNodeWithTag("strava_unlink_confirm").performClick()
        val unlink = take()
        assertEquals("DELETE", unlink.method)
        assertEquals("/v1/me/integrations/strava", unlink.requestUrl?.encodedPath)
        assertStatusRequest()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_notice_unlinked"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_status_revoked"), 5_000)
        composeRule.onNodeWithTag("strava_sync").assertDoesNotExist()
    }

    @Test
    fun sinRedLaSincronizacionNoSeEncolaYElReintentoRepiteLaClave() {
        login()
        openStravaFromMenu(json(ACTIVE_NEVER_SYNCED))
        assertStatusRequest()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_status_active"), 5_000)

        composeRule.onNodeWithTag("strava_sync").performScrollTo().performClick()
        val first = take()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_problem_no_connection"), 5_000)

        server.enqueue(json(SYNC_ACCEPTED, status = 202))
        server.enqueue(json(ACTIVE_SYNCED))
        composeRule.onNodeWithTag("strava_retry").performClick()
        val retry = take()
        assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_sync_succeeded"), 5_000)
    }

    @Test
    fun elRetornoDesdeStravaAbreLaPantallaYUnRechazoNoVincula() {
        login()

        server.enqueue(json(NOT_LINKED))
        composeRule.runOnIdle { assertTrue(container.stravaLinkLaunch.offer("sportapp://integrations/strava?result=DENIED")) }

        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_screen"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_notice_link_denied"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_status_not_linked"), 5_000)
        assertNull(container.stravaLinkLaunch.pending.value)
    }

    @Test
    fun sinRedConservaElUltimoEstadoCifradoYSinEstadoGuardadoLoInforma() {
        login()
        openStravaFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_never_synced"), 5_000)
        take()

        server.enqueue(json(ACTIVE_SYNCED))
        composeRule.onNodeWithTag("strava_retry").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_status_active"), 5_000)
        take()

        composeRule.onNodeWithTag("strava_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        openStravaFromMenu()
        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_problem_no_connection"), 5_000)
        composeRule.onNodeWithTag("strava_status_active").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_synced_at").assertIsDisplayed()
    }

    @Test
    fun sinLaCapacidadLaOpcionNoApareceLaRutaSeRechazaYElRetornoSeDescarta() {
        login(CAPABILITIES.replace(""","integrations.strava.connect"""", ""))

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("menu_integrations.strava.connect").assertDoesNotExist()

        composeRule.runOnIdle { navController.navigate(Destinations.STRAVA) }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.onNodeWithTag("strava_screen").assertDoesNotExist()

        composeRule.runOnIdle { container.stravaLinkLaunch.offer("sportapp://integrations/strava?result=LINKED") }
        composeRule.waitUntil(5_000) { container.stravaLinkLaunch.pending.value == null }
        composeRule.onNodeWithTag("strava_screen").assertDoesNotExist()
        assertEquals("solo inicio de sesión y capacidades", 2, server.requestCount)
    }

    @Test
    fun unaRespuesta403CapabilityRequiredSeInformaSinDatos() {
        login()
        openStravaFromMenu(json("""{"code":"capability_required","message":"m","correlation_id":"c-1"}""", status = 403))

        composeRule.waitUntilExactlyOneExists(hasTestTag("strava_problem_capability_required"), 5_000)
        composeRule.onNodeWithTag("strava_link").assertDoesNotExist()
    }

    private companion object {
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val SESSION =
            """{"token_type":"Bearer","access_token":"access-sintetico","expires_in":900,""" +
                """"refresh_token":"refresh-token-sintetico-000000000001","refresh_expires_in":604800,""" +
                """"user":{"user_id":"$USER_ID","full_name":"Ana Deportista",""" +
                """"email":"ana@example.com","actor_type":"athlete"}}"""
        const val CAPABILITIES =
            """{"actor_type":"athlete","subscription_status":"ACTIVE","plan_code":"FREE","plan_version":1,""" +
                """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
                """"permissions":[],"capabilities":["training.session.record","integrations.strava.connect"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
        const val AUTHORIZATION_URL =
            "https://www.strava.com/oauth/mobile/authorize?client_id=12345&response_type=code&approval_prompt=auto&scope=read%2Cactivity%3Aread&state=estado-sintetico"
        const val AUTHORIZATION = """{"authorization_url":"$AUTHORIZATION_URL","expires_at":"2030-01-01T00:10:00Z"}"""
        const val NOT_LINKED = """{"status":"NOT_LINKED","external_athlete_id":null,"linked_at":null,"scopes":[],"last_sync":null}"""
        const val ACTIVE_NEVER_SYNCED =
            """{"status":"ACTIVE","external_athlete_id":"98765","linked_at":"2026-11-10T12:00:00Z","scopes":["read","activity:read"],"last_sync":null}"""
        const val ACTIVE_SYNCED =
            """{"status":"ACTIVE","external_athlete_id":"98765","linked_at":"2026-11-10T12:00:00Z","scopes":["read","activity:read"],""" +
                """"last_sync":{"sync_run_id":"5f0c2f6e-1b7a-5c33-9d1e-3a4b5c6d7e8f","status":"SUCCEEDED","requested_at":"2026-11-15T14:00:00Z",""" +
                """"finished_at":"2026-11-15T14:05:00Z","imported_count":3,"error_code":null}}"""
        const val REVOKED =
            """{"status":"REVOKED","external_athlete_id":null,"linked_at":"2026-11-10T12:00:00Z","scopes":[],"last_sync":null}"""
        const val SYNC_ACCEPTED = """{"sync_run_id":"5f0c2f6e-1b7a-5c33-9d1e-3a4b5c6d7e8f","status":"QUEUED"}"""
    }
}
