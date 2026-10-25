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
import com.lfnj.sport_app.features.training.dashboard.data.DashboardRefresh
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardApi
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardRepository
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardCodec
import com.lfnj.sport_app.features.training.dashboard.data.local.TrainingDashboardDatabase
import com.lfnj.sport_app.features.training.dashboard.model.DashboardPeriod
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import com.lfnj.sport_app.features.training.metrics.data.SubmitResult
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.session.data.FinishResult
import com.lfnj.sport_app.features.training.session.data.StartResult
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * E2E de HU033 (ADR-043, «Evidencia requerida»): aplicación real (MainActivity, Room, Keystore,
 * WorkManager y URL de la variante) → `bff-mobile` ≥ 0.9.0 → `training_management` en la
 * composición local. Tras finalizar dos sesiones (HU032) y calcular una FTP (HU034), el tablero
 * muestra los totales, las barras de ambos deportes y la métrica; sin conexión con el BFF conserva
 * el tablero guardado con su antigüedad. Requiere una cuenta sintética de deportista sin sesión de
 * entrenamiento abierta y se omite sin argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu033IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu033IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val password: String? = args.getString("e2ePassword")
    private val email: String? = args.getString("e2eAthleteEmail")
    private val container get() = (context as SportApp).container

    @Before
    fun setUp() {
        assumeTrue("E2E omitido: falta la cuenta sintética", password != null && email != null)
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

    private fun period() = DashboardPeriod.preset(DashboardRange.LAST_30, LocalDate.now(ZoneId.systemDefault()), ZoneId.systemDefault())

    private suspend fun confirmedDashboard(owner: String): DashboardSnapshot {
        assertEquals(DashboardRefresh.Updated, container.trainingDashboardRepository.refresh(owner, period()))
        return checkNotNull(container.trainingDashboardRepository.observe(owner, DashboardRange.LAST_30).first())
    }

    /** Sesión real de HU032: se guarda cifrada y WorkManager la sincroniza con el BFF. */
    private suspend fun recordSession(owner: String, sport: TrainingSport) {
        val started = container.trainingSessionRepository.start(owner, sport)
        assertTrue("No se pudo iniciar: $started", started is StartResult.Started)
        delay(1_500)
        assertEquals(FinishResult.FINISHED, container.trainingSessionRepository.finish(owner, (started as StartResult.Started).sessionId))
    }

    @Test
    fun trasDosSesionesYUnaFtpElTableroMuestraTotalesBarrasYMetricaYLosConservaSinConexion() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val owner = TrainingOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)
            val measuredAt = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)

            val confirmed = runBlocking {
                val baseline = confirmedDashboard(owner)
                recordSession(owner, TrainingSport.RUNNING)
                recordSession(owner, TrainingSport.CYCLING)
                container.trainingMetricsRepository.refreshCatalog(owner)
                val queued = container.trainingMetricsRepository.submit(
                    owner, FTP_METHOD, listOf(MetricInputValue("mean_power_watts", 250, "W")), measuredAt,
                )
                assertTrue("No se encoló el cálculo: $queued", queued is SubmitResult.Queued)

                // La lectura es eventual (outbox → proyección): se consulta hasta ver ambos efectos.
                var current = baseline
                val deadline = System.currentTimeMillis() + SYNC_TIMEOUT
                while (System.currentTimeMillis() < deadline) {
                    current = confirmedDashboard(owner)
                    val ftp = current.latestMetrics.firstOrNull { it.indicator == "FTP_ESTIMATED" }
                    if (current.totals.sessionsCount >= baseline.totals.sessionsCount + 2 && ftp?.measuredAt == measuredAt) break
                    delay(3_000)
                }
                assertEquals(baseline.totals.sessionsCount + 2, current.totals.sessionsCount)
                assertTrue(current.hasData)
                assertTrue(current.complete)
                assertTrue(current.bySport.map { it.sport }.containsAll(listOf("running", "cycling")))
                assertEquals(baseline.bySource.sportApp.sessionsCount + 2, current.bySource.sportApp.sessionsCount)
                assertEquals(measuredAt, current.latestMetrics.single { it.indicator == "FTP_ESTIMATED" }.measuredAt)
                current
            }

            // Pantalla real con el tablero confirmado por el BFF.
            composeRule.onNodeWithTag("open_menu").performClick()
            composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_training.dashboard.view"))
            composeRule.onNodeWithTag("menu_training.dashboard.view").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("dashboard_totals"), TIMEOUT)
            composeRule.onNodeWithTag("dashboard_sport_running").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("dashboard_sport_cycling").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("dashboard_source_sportapp").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("dashboard_metric_FTP_ESTIMATED").performScrollTo().assertIsDisplayed()

            // Sin conexión con el BFF: la misma caché cifrada del dispositivo conserva el tablero y su antigüedad.
            runBlocking {
                val database = TrainingDashboardDatabase.create(context)
                try {
                    val unreachable = BffMobileClient.retrofit(UNREACHABLE_BFF).create(TrainingDashboardApi::class.java)
                    val offline = TrainingDashboardRepository(
                        dao = database.trainingDashboardCacheDao(),
                        codec = TrainingDashboardCodec(KeystoreTokenCipher(TrainingDashboardCodec.KEY_ALIAS), BffMobileClient.json),
                        api = unreachable,
                        sessionManager = container.sessionManager,
                        json = BffMobileClient.json,
                    )
                    assertEquals(DashboardRefresh.NoConnection, offline.refresh(owner, period()))
                    val cached = checkNotNull(offline.observe(owner, DashboardRange.LAST_30).first())
                    assertEquals(confirmed.syncedAt, cached.syncedAt)
                    assertEquals(confirmed.totals, cached.totals)
                } finally {
                    database.close()
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SYNC_TIMEOUT = 120_000L
        const val FTP_METHOD = "FTP_20_MIN_95_V1@1"
        /** Puerto cerrado en el dispositivo: la conexión se rechaza como sin red. */
        const val UNREACHABLE_BFF = "http://127.0.0.1:9/"
    }
}
