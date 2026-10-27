package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.MainActivity
import com.lfnj.sport_app.SportApp
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.features.health.data.HealthReportsApi
import com.lfnj.sport_app.features.health.data.HealthReportsRepository
import com.lfnj.sport_app.features.health.data.HealthSubmitResult
import com.lfnj.sport_app.features.health.data.local.HealthReportsCodec
import com.lfnj.sport_app.features.health.data.local.HealthReportsDatabase
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthOwner
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.HealthSyncState
import com.lfnj.sport_app.features.health.model.LocalHealthReport
import com.lfnj.sport_app.features.health.sync.HealthReportsSyncEngine
import com.lfnj.sport_app.features.training.session.data.TrainingSyncScheduler
import com.lfnj.sport_app.features.training.session.sync.SyncRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * E2E de HU042 (ADR-037, «Evidencia requerida»): aplicación real (MainActivity, Room, Keystore y URL
 * de la variante) → `bff-mobile` 0.21.0 → `training_management` en la composición local. El
 * deportista inicia sesión; sin conexión con el BFF captura un reporte, que queda `PENDING_SYNC`
 * cifrado; tras «reiniciar» (base y repositorio nuevos sobre el mismo archivo) y recuperar la red,
 * la cola lo envía una sola vez, recibe el `201` `OPEN` y la pantalla «Mi salud» lo muestra
 * confirmado. La consulta del historial (HU012) es web y no forma parte de este recorrido.
 *
 * Requiere una cuenta sintética de deportista y `HEALTH_DATA_CONSENT_VERSION=local-dev` en la
 * composición local. Se omite sin argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu042IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu042IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val password: String? = args.getString("e2ePassword")
    private val email: String? = args.getString("e2eAthleteEmail")
    private val container get() = (context as SportApp).container

    /** El envío lo controla la prueba: así se observa el estado pendiente antes de recuperar la red. */
    private val heldScheduler = object : TrainingSyncScheduler {
        override fun schedule(owner: String) = Unit
        override fun cancelAll() = Unit
    }

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
    }

    private fun codec() = HealthReportsCodec(KeystoreTokenCipher(HealthReportsCodec.KEY_ALIAS), BffMobileClient.json)

    private fun engine(database: HealthReportsDatabase, baseUrl: String) = HealthReportsSyncEngine(
        dao = database.healthReportsDao(),
        transactions = database.transactions,
        codec = codec(),
        api = BffMobileClient.retrofit(baseUrl).create(HealthReportsApi::class.java),
        sessionManager = container.sessionManager,
        json = BffMobileClient.json,
    )

    @Test
    fun sinRedReportaReiniciaRecuperaLaRedYSincronizaUnaSolaVezCon201() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val owner = HealthOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)
            val consent = container.config.healthDataConsentVersion
            assertTrue("La variante debe tener la versión del consentimiento", consent.isNotBlank())

            // 1. Sin red: se captura y queda cifrado como pendiente; el envío falla y no se pierde.
            val occurredAt = Instant.now().minusSeconds(900).truncatedTo(ChronoUnit.SECONDS)
            val reportId = runBlocking {
                val database = HealthReportsDatabase.create(context)
                try {
                    val repository = HealthReportsRepository(database.healthReportsDao(), database.transactions, codec(), heldScheduler, consent)
                    val queued = repository.submit(
                        owner,
                        HealthReportDetails(HealthReportType.DISCOMFORT, BodyArea.KNEE, HealthSeverity.HIGH, occurredAt, null, 3),
                    ) as HealthSubmitResult.Queued
                    assertEquals(SyncRun.RETRY, engine(database, UNREACHABLE_BFF).run(owner))
                    assertEquals(HealthSyncState.PENDING_SYNC, repository.observe(owner).first().single { it.reportId == queued.reportId }.state)
                    queued.reportId
                } finally {
                    database.close()
                }
            }

            // 2. «Reinicio»: base y repositorio nuevos sobre el mismo archivo; 3. con red, una sola sincronización.
            runBlocking {
                val database = HealthReportsDatabase.create(context)
                try {
                    val repository = HealthReportsRepository(database.healthReportsDao(), database.transactions, codec(), heldScheduler, consent)
                    assertTrue(repository.hasPending(owner))
                    val online = engine(database, container.config.bffMobileBaseUrl)
                    assertEquals(SyncRun.COMPLETED, online.run(owner))
                    val confirmed = repository.observe(owner).first().single { it.reportId == reportId }
                    assertEquals(HealthSyncState.SYNCED, confirmed.state)
                    assertNotNull("El backend devolvió reported_at", confirmed.reportedAt)
                    // Sin pendientes, una nueva ejecución no envía nada más.
                    assertEquals(SyncRun.COMPLETED, online.run(owner))
                    assertTrue(!repository.hasPending(owner))
                } finally {
                    database.close()
                }
            }

            // 4. La pantalla «Mi salud» lo muestra confirmado con el texto de severidad alta.
            composeRule.onNodeWithTag("open_menu").performClick()
            composeRule.onNodeWithTag("menu_health").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("health_report_$reportId"), TIMEOUT)
            composeRule.onNodeWithTag("health_report_$reportId").performScrollTo().assertIsDisplayed()
            composeRule.waitUntilExactlyOneExists(hasTestTag("health_state_$reportId") and hasText("Confirmado"), TIMEOUT)
            composeRule.onNodeWithTag("health_report_advice_$reportId").performScrollTo().assertIsDisplayed()
        }
    }

    /** Ejecuta un comando de shell del dispositivo con los permisos de la instrumentación y espera su fin. */
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun report(owner: String, reportId: String): LocalHealthReport? =
        runBlocking { container.healthReportsRepository.observe(owner).first().firstOrNull { it.reportId == reportId } }

    /**
     * Caso offline real (CA5): sin Wi-Fi ni datos, la app captura el reporte por la interfaz, lo deja
     * `PENDING_SYNC` y WorkManager no lo envía; al restaurar la red, el trabajo durable real lo
     * sincroniza una sola vez con el backend (`201` `OPEN`). La red se restaura siempre.
     */
    @Test
    fun sinRedRealElReporteQuedaPendienteYAlVolverLaRedWorkManagerLoConfirmaUnaVez() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val owner = HealthOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)
            val before = runBlocking { container.healthReportsRepository.observe(owner).first().map { it.reportId }.toSet() }
            lateinit var reportId: String
            try {
                shell("svc wifi disable")
                shell("svc data disable")
                runBlocking { withTimeout(TIMEOUT) { container.connectivityMonitor.online().first { !it } } }

                composeRule.onNodeWithTag("open_menu").performClick()
                composeRule.onNodeWithTag("menu_health").performClick()
                composeRule.waitUntilExactlyOneExists(hasTestTag("health_offline"), TIMEOUT)
                composeRule.onNodeWithTag("health_type_INJURY").performScrollTo().performClick()
                composeRule.onNodeWithTag("health_area_ANKLE_FOOT").performScrollTo().performClick()
                composeRule.onNodeWithTag("health_severity_MODERATE").performScrollTo().performClick()
                composeRule.onNodeWithTag("health_consent").performScrollTo().performClick()
                composeRule.onNodeWithTag("health_submit").performScrollTo().performClick()

                reportId = runBlocking {
                    withTimeout(TIMEOUT) {
                        container.healthReportsRepository.observe(owner).first { list -> list.any { it.reportId !in before } }
                            .single { it.reportId !in before }.reportId
                    }
                }
                composeRule.waitUntilExactlyOneExists(hasTestTag("health_state_$reportId") and hasText("Pendiente de sincronizar"), TIMEOUT)
                // Sin red, el trabajo durable no se ejecuta: sigue pendiente y nunca se muestra confirmado.
                runBlocking { delay(OFFLINE_HOLD) }
                assertEquals(HealthSyncState.PENDING_SYNC, report(owner, reportId)?.state)
            } finally {
                shell("svc wifi enable")
                shell("svc data enable")
            }

            runBlocking {
                withTimeout(SYNC_TIMEOUT) {
                    container.healthReportsRepository.observe(owner).first { list -> list.any { it.reportId == reportId && it.state == HealthSyncState.SYNCED } }
                }
            }
            val confirmed = checkNotNull(report(owner, reportId))
            assertNotNull("El backend devolvió reported_at", confirmed.reportedAt)
            // Una sola sincronización: un único intento registrado para ese report_id.
            val attempts = runBlocking {
                val database = HealthReportsDatabase.create(context)
                try { checkNotNull(database.healthReportsDao().report(owner, reportId)).attempts } finally { database.close() }
            }
            assertEquals(1, attempts)
            composeRule.waitUntilExactlyOneExists(hasTestTag("health_state_$reportId") and hasText("Confirmado"), TIMEOUT)
        }
    }

    private companion object {
        const val OFFLINE_HOLD = 10_000L
        const val SYNC_TIMEOUT = 120_000L
        const val TIMEOUT = 20_000L
        const val UNREACHABLE_BFF = "http://127.0.0.1:9/"
    }
}
