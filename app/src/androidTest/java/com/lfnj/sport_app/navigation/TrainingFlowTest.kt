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
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * HU032 en la aplicación (navegación, ViewModel, Room, cifrado y motor de sincronización reales)
 * contra un `bff-mobile` simulado con MockWebServer. La prueba ejecuta el motor en lugar de
 * WorkManager. Es una prueba de componente con contrato simulado, **no** un E2E.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TrainingFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val server = MockWebServer()
    private val owner = TrainingOwner.keyOf(USER_ID)
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

    private fun trainingSession(id: String, finished: Boolean = false) = json(
        """{"session_id":"$id","athlete_id":"$USER_ID","plan_id":null,"sport":"running",""" +
            """"started_at":"2026-10-12T12:30:00Z",""" +
            (if (finished) """"ended_at":"2026-10-12T13:15:00Z","duration_seconds":2700,"status":"FINISHED","version":2,""" else """"status":"ACTIVE","version":1,""") +
            """"created_at":"2026-10-12T12:30:01Z","updated_at":"2026-10-12T12:30:01Z"}""",
        status = if (finished) 200 else 201,
    )

    /** [activeResponse]: respuesta de `GET /v1/training-sessions/active`; sin ella, falta de red. */
    private fun openTrainingFromMenu(activeResponse: MockResponse? = null) {
        server.enqueue(json(SESSION))
        server.enqueue(json(CAPABILITIES))
        activeResponse?.let(server::enqueue)
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), 5_000)
        composeRule.onNodeWithTag("login_email").performTextInput("ana@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("Secreta-123")
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
        composeRule.waitUntilExactlyOneExists(hasText("Plan FREE activo."), 5_000)
        server.takeRequest(1, TimeUnit.SECONDS)
        server.takeRequest(1, TimeUnit.SECONDS)

        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_training.session.record"))
        composeRule.onNodeWithTag("menu_training.session.record").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("training_session"), 5_000)
    }

    private fun waitForText(text: String) = composeRule.waitUntilAtLeastOneExists(hasText(text), 5_000)

    private fun sync(): SyncRun = runBlocking { container.trainingSyncEngine.run(owner) }

    @Test
    fun registraInicioYFinLosSincronizaEnOrdenYMuestraLaDuracionOficial() {
        openTrainingFromMenu(MockResponse().setResponseCode(204)) // sin sesión activa remota
        assertEquals("/v1/training-sessions/active", server.takeRequest(1, TimeUnit.SECONDS)?.path)

        composeRule.onNodeWithTag("sport_running").performClick()
        composeRule.onNodeWithTag("training_start").performScrollTo().performClick()
        waitForText("Guardado solo en este dispositivo")
        composeRule.onNodeWithTag("training_chronometer").assertIsDisplayed()
        assertEquals(listOf(owner), container.scheduler.scheduled)

        val sessionId = runBlocking { container.trainingSessionRepository.observe(owner).first() }.single().sessionId
        server.enqueue(trainingSession(sessionId))
        assertEquals(SyncRun.COMPLETED, sync())
        waitForText("Confirmado por SportApp")

        composeRule.onNodeWithTag("training_finish").performScrollTo().performClick()
        // Duración real medida en el dispositivo: informativa hasta que el backend confirme la oficial.
        composeRule.waitUntilAtLeastOneExists(hasText("Duración en el dispositivo (sin confirmar):", substring = true), 5_000)
        server.enqueue(trainingSession(sessionId, finished = true))
        assertEquals(SyncRun.COMPLETED, sync())
        waitForText("Duración confirmada: 0 h 45 min 00 s")

        val start = server.takeRequest(1, TimeUnit.SECONDS)!!
        val finish = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/training-sessions", start.path)
        assertEquals("/v1/training-sessions/$sessionId/finish", finish.path)
        assertNotEquals(start.getHeader("Idempotency-Key"), finish.getHeader("Idempotency-Key"))

        composeRule.onNodeWithTag("training_back").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), 5_000)
    }

    @Test
    fun sinRedElRegistroQuedaPendienteYNuncaSeMuestraConfirmado() {
        openTrainingFromMenu()
        waitForText("Sin conexión: no pudimos verificar si tienes un entrenamiento en curso en otro dispositivo. Puedes registrar uno nuevo y se sincronizará después.")

        composeRule.onNodeWithTag("sport_cycling").performClick()
        composeRule.onNodeWithTag("training_start").performScrollTo().performClick()
        waitForText("Guardado solo en este dispositivo")

        assertEquals(SyncRun.RETRY, sync())
        waitForText("Pendiente de sincronizar")
        composeRule.onNodeWithTag("training_finish").performScrollTo().performClick()
        waitForText("Pendiente de sincronizar")
        assertEquals(0, composeRule.onAllNodes(hasText("Confirmado por SportApp")).fetchSemanticsNodes().size)
    }

    @Test
    fun unConflictoFuncionalQuedaVisibleYPermiteIniciarOtraSesion() {
        openTrainingFromMenu(MockResponse().setResponseCode(204))
        composeRule.onNodeWithTag("sport_strength").performClick()
        composeRule.onNodeWithTag("training_start").performScrollTo().performClick()
        waitForText("Guardado solo en este dispositivo")

        server.enqueue(json("""{"code":"active_session_exists","message":"m","correlation_id":"c-1"}""", status = 409))
        assertEquals(SyncRun.COMPLETED, sync())

        waitForText("No sincronizado")
        waitForText("Ya tenías otro entrenamiento en curso, por ejemplo desde otro dispositivo. Este registro no fue aceptado.")
        composeRule.onNodeWithTag("training_start").performScrollTo().assertIsDisplayed()
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
                """"permissions":[],"capabilities":["training.session.record","training.dashboard.view"],""" +
                """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }
}
