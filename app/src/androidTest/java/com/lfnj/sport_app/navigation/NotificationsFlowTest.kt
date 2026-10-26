package com.lfnj.sport_app.navigation

import android.content.Context
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
import com.lfnj.sport_app.features.notifications.system.NotificationLaunch
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * HU039 en la aplicación (menú, guarda, ViewModels, Room y cifrado reales) contra un `bff-mobile`
 * simulado según ADR-045 §11: bandeja → detalle del evento (HU041) con lectura, preferencias y
 * toque de una notificación del sistema con la aplicación abierta o en el arranque. Prueba de
 * componente con contrato simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NotificationsFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var container: TestAppContainer
    private lateinit var navController: NavHostController

    /** Respuestas por «MÉTODO ruta»; la lectura y el detalle se piden a la vez, por eso no se usa una cola. */
    private val routes = ConcurrentHashMap<String, MockResponse>()
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl?.encodedPath
                // Sin respuesta preparada, el servidor corta la conexión: así se simula la falta de red.
                return routes["${request.method} $path"] ?: MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
        }
        server.start()
        routes["POST /v1/sessions"] = json(SESSION)
        routes["GET /v1/me/capabilities"] = json(CAPABILITIES)
        val config = AppConfig(server.url("/").toString(), "test", "0.18.0", "0.1.0")
        container = TestAppContainer(config, InMemoryTokenStore(), context)
    }

    private fun start() = composeRule.setContent {
        navController = rememberNavController()
        SportappTheme { AppNavHost(container, navController = navController) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        container.close()
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)

    private fun requested(method: String, path: String) = requests.filter { it.method == method && it.requestUrl?.encodedPath == path }

    private fun login() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
    }

    private fun openInbox() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_notifications"))
        composeRule.onNodeWithTag("menu_notifications").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("notifications_screen"), 5_000)
    }

    @Test
    fun desdeLaBandejaAbreElDetalleDelEventoYLaMarcaComoLeida() {
        routes["GET /v1/me/notifications"] = json(page(item(NOTIFICATION_ID), item(READ_ID, readAt = "2026-10-25T14:30:00Z")))
        routes["POST /v1/me/notifications/$NOTIFICATION_ID/read"] = json(item(NOTIFICATION_ID, readAt = "2026-10-25T15:01:00Z"))
        routes["GET /v1/events/$EVENT_ID"] = json(DETAIL)
        start()
        login()

        openInbox()
        composeRule.waitUntilExactlyOneExists(hasTestTag("notification_unread_$NOTIFICATION_ID"), 5_000)
        composeRule.onNodeWithTag("notification_read_$READ_ID").assertIsDisplayed()
        composeRule.onNodeWithTag("notifications_unread_count").assertIsDisplayed()
        val inbox = requested("GET", "/v1/me/notifications").single()
        assertEquals("20", inbox.requestUrl?.queryParameter("limit"))
        assertEquals("Bearer access-sintetico", inbox.getHeader("Authorization"))

        composeRule.onNodeWithTag("notification_$NOTIFICATION_ID").performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_detail_screen"), 5_000)
        composeRule.waitUntil(5_000) { requested("POST", "/v1/me/notifications/$NOTIFICATION_ID/read").size == 1 }
        assertEquals(1, requested("GET", "/v1/events/$EVENT_ID").size)
        assertEquals(listOf(NOTIFICATION_ID), container.announcer.dismissed)

        // Atrás vuelve a la bandeja con la notificación ya leída desde la caché local.
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitUntilExactlyOneExists(hasTestTag("notification_read_$NOTIFICATION_ID"), 5_000)
    }

    @Test
    fun conLaAplicacionAbiertaDuranteUnEntrenamientoLaNotificacionAbreElEventoYAtrasConservaLaSesion() {
        routes["GET /v1/events/$EVENT_ID"] = json(DETAIL)
        routes["POST /v1/me/notifications/$NOTIFICATION_ID/read"] = json(item(NOTIFICATION_ID, readAt = "2026-10-25T15:01:00Z"))
        start()
        login()
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_training.session.record"))
        composeRule.onNodeWithTag("menu_training.session.record").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("training_session"), 5_000)
        composeRule.onNodeWithTag("sport_running").performClick()
        composeRule.onNodeWithTag("training_start").performScrollTo().performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("Guardado solo en este dispositivo"), 5_000)

        // Lo que entrega `onNewIntent` al tocar la notificación del sistema con la aplicación abierta.
        container.notificationLaunch.offer(NotificationLaunch.intent(context, EVENT_ID, NOTIFICATION_ID))

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_detail_screen"), 5_000)
        composeRule.waitUntil(5_000) { requested("POST", "/v1/me/notifications/$NOTIFICATION_ID/read").size == 1 }
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitUntilExactlyOneExists(hasTestTag("training_session"), 5_000)
        composeRule.onNodeWithTag("training_chronometer").assertIsDisplayed()
        composeRule.waitUntilAtLeastOneExists(hasText("Guardado solo en este dispositivo"), 5_000)
    }

    @Test
    fun alArrancarDesdeUnaNotificacionEsperaLaSesionYLuegoAbreElEvento() {
        routes["GET /v1/events/$EVENT_ID"] = json(DETAIL)
        // Intent de la notificación recibido en `onCreate`, antes de validar la sesión.
        container.notificationLaunch.offer(NotificationLaunch.intent(context, EVENT_ID, NOTIFICATION_ID))
        start()

        login()

        composeRule.waitUntilExactlyOneExists(hasTestTag("event_detail_screen"), 5_000)
        assertEquals(null, container.notificationLaunch.pending.value)
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
    }

    @Test
    fun unIntentAjenoOConIdentificadoresInvalidosNoNavega() {
        container.notificationLaunch.offer(NotificationLaunch.intent(context, "../v1/admin", NOTIFICATION_ID))
        container.notificationLaunch.offer(android.content.Intent("otra.accion").putExtra(NotificationLaunch.EXTRA_EVENT_ID, EVENT_ID))

        assertEquals(null, container.notificationLaunch.pending.value)
    }

    @Test
    fun lasPreferenciasSeGuardanConLaVersionLeida() {
        routes["GET /v1/me/notifications"] = json(page())
        routes["GET /v1/me/notification-preferences"] = json(PREFERENCES)
        routes["PUT /v1/me/notification-preferences"] =
            json("""{"event_matches":false,"event_reminders":true,"push_enabled":false,"version":1}""")
        start()
        login()
        openInbox()
        composeRule.waitUntilExactlyOneExists(hasTestTag("notifications_empty"), 5_000)

        composeRule.onNodeWithTag("notifications_open_preferences").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("preferences_event_matches"), 5_000)
        composeRule.onNodeWithTag("preferences_event_matches").performClick()
        composeRule.onNodeWithTag("preferences_save").performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag("preferences_saved"), 5_000)
        val put = requested("PUT", "/v1/me/notification-preferences").single()
        assertEquals("""{"event_matches":false,"event_reminders":true,"push_enabled":false,"expected_version":0}""", put.body.readUtf8())
        assertTrue(requested("PUT", "/v1/me/push-tokens").isEmpty())
    }

    private companion object {
        const val EVENT_ID = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"
        const val NOTIFICATION_ID = "5b0e7c1a-2d3f-4a5b-8c6d-7e8f9a0b1c2d"
        const val READ_ID = "4a9d6b09-1c2e-4f4a-9b5c-6d7e8f9a0b1c"
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"

        fun item(id: String, readAt: String? = null) =
            """{"notification_id":"$id","type":"EVENT_PUBLISHED_MATCH","priority":"NORMAL","event_id":"$EVENT_ID",""" +
                """"title":"Nuevo evento de running en Bogotá","body":"Carrera 10K Bogotá el 15 de noviembre.",""" +
                """"created_at":"2026-10-25T13:00:00Z","read_at":${readAt?.let { "\"$it\"" } ?: "null"}}"""

        fun page(vararg items: String) = """{"items":${items.joinToString(",", "[", "]")},"next_cursor":null}"""

        const val PREFERENCES = """{"event_matches":true,"event_reminders":true,"push_enabled":false,"version":0}"""

        const val DETAIL =
            """{"event_id":"$EVENT_ID","name":"Carrera 10K Bogotá","description":"Recorrido urbano de 10 km.",""" +
                """"discipline":"Running","location":{"name":"Parque Metropolitano Simón Bolívar","city":"Bogotá","country_code":"CO"},""" +
                """"starts_at":"2027-11-15T12:00:00Z","ends_at":"2027-11-15T15:00:00Z","time_zone":"America/Bogota","capacity":500,""" +
                """"status":"PUBLISHED","version":2,"fee":null,"my_registration":null}"""

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
