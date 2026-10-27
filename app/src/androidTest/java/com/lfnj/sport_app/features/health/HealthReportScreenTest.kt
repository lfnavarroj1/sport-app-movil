package com.lfnj.sport_app.features.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.HealthSyncFailure
import com.lfnj.sport_app.features.health.model.HealthSyncState
import com.lfnj.sport_app.features.health.model.LocalHealthReport
import com.lfnj.sport_app.features.health.model.RecentTrainingSession
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

/** HU042 en la pantalla «Mi salud»: formulario, consentimiento, sin red, pendiente, fallido y severidad alta. */
@RunWith(AndroidJUnit4::class)
class HealthReportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-10-24T18:35:00Z")
    private val events = mutableListOf<String>()
    private val details = HealthReportDetails(HealthReportType.INJURY, BodyArea.KNEE, HealthSeverity.MODERATE, now.minusSeconds(300), null, 7)

    private fun report(id: String, state: HealthSyncState, failure: HealthSyncFailure? = null, details: HealthReportDetails? = this.details) =
        LocalHealthReport(id, state, failure, null, null, now, details, if (state == HealthSyncState.SYNCED) now.plusSeconds(2) else null)

    private fun show(state: HealthReportUiState) = composeRule.setContent {
        SportappTheme {
            HealthReportScreen(
                state = state,
                onSelectType = { events += "type:${it.wire}" },
                onSelectBodyArea = { events += "area:${it.wire}" },
                onSelectSeverity = { events += "severity:${it.wire}" },
                onOccurredAtChange = { events += "occurred" },
                onSelectSession = { events += "session:$it" },
                onRecoveryDaysChange = { events += "recovery:$it" },
                onConsentChange = { events += "consent:$it" },
                onSubmit = { events += "submit" },
                onDismiss = { events += "dismiss:$it" },
                onBack = {},
                zone = ZoneOffset.UTC,
            )
        }
    }

    private fun base() = HealthReportUiState(loading = false, occurredAt = now)

    @Test
    fun cargaMuestraProgreso() {
        show(HealthReportUiState(loading = true))

        composeRule.onNodeWithTag("health_loading").assertIsDisplayed()
    }

    @Test
    fun elFormularioOfreceSoloLosCatalogosCerradosYEnviaLasElecciones() {
        show(base().copy(sessions = listOf(RecentTrainingSession("s-1", now.minusSeconds(7200)))))

        HealthReportType.entries.forEach { composeRule.onNodeWithTag("health_type_${it.wire}").performScrollTo().assertIsDisplayed() }
        BodyArea.entries.forEach { composeRule.onNodeWithTag("health_area_${it.wire}").performScrollTo().assertIsDisplayed() }
        HealthSeverity.entries.forEach { composeRule.onNodeWithTag("health_severity_${it.wire}").performScrollTo().assertIsDisplayed() }

        composeRule.onNodeWithTag("health_type_INJURY").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_area_ANKLE_FOOT").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_severity_LOW").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_session_s-1").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_consent").performScrollTo().performClick()
        composeRule.onNodeWithTag("health_submit").performScrollTo().performClick()

        assertEquals(listOf("type:INJURY", "area:ANKLE_FOOT", "severity:LOW", "session:s-1", "consent:true", "submit"), events)
    }

    @Test
    fun laSeleccionYElConsentimientoSeAnuncianComoEstadoAccesible() {
        show(base().copy(type = HealthReportType.DISCOMFORT, consentAccepted = true))

        composeRule.onNodeWithTag("health_type_DISCOMFORT").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("health_consent").performScrollTo().assertIsOn()
    }

    @Test
    fun ca2CamposFaltantesYConsentimientoSeExplican() {
        show(base().copy(missing = setOf(HealthField.TYPE, HealthField.BODY_AREA, HealthField.SEVERITY, HealthField.CONSENT)))

        listOf("health_type_missing", "health_area_missing", "health_severity_missing", "health_consent_missing").forEach {
            composeRule.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithTag("health_consent").performScrollTo().assertIsOff()
    }

    @Test
    fun ca6ConSeveridadAltaSeMuestraElTextoAprobadoSinDiagnostico() {
        show(base().copy(severity = HealthSeverity.HIGH))

        composeRule.onNodeWithTag("health_professional_advice").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Indicaste una severidad alta. Te sugerimos consultar a un profesional de la salud. SportApp no emite diagnósticos."
        ).assertIsDisplayed()
    }

    @Test
    fun ca6SinSeveridadAltaNoHayAvisoNiDiagnostico() {
        show(base().copy(severity = HealthSeverity.MODERATE, reports = listOf(report("r-1", HealthSyncState.SYNCED))))

        composeRule.onNodeWithTag("health_professional_advice").assertDoesNotExist()
        composeRule.onNodeWithTag("health_report_advice_r-1").assertDoesNotExist()
    }

    @Test
    fun ca5SinRedSeExplicaQueElReporteSeGuardaraYEnviara() {
        show(base().copy(online = false))

        composeRule.onNodeWithTag("health_offline").assertIsDisplayed()
        composeRule.onNodeWithTag("health_submit").performScrollTo().assertIsEnabled()
    }

    @Test
    fun ca5PendienteConfirmadoYFallidoSeDistinguen() {
        val high = details.copy(severity = HealthSeverity.HIGH)
        show(
            base().copy(
                reports = listOf(
                    report("r-p", HealthSyncState.PENDING_SYNC, details = high),
                    report("r-s", HealthSyncState.SYNCED),
                    report("r-f", HealthSyncState.SYNC_FAILED, HealthSyncFailure.SESSION_NOT_FOUND),
                ),
            ),
        )

        composeRule.onNodeWithTag("health_state_r-p").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pendiente de sincronizar").assertIsDisplayed()
        // Un pendiente nunca se presenta como confirmado ni se puede retirar.
        composeRule.onNodeWithTag("health_dismiss_r-p").assertDoesNotExist()
        composeRule.onNodeWithTag("health_report_advice_r-p").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("health_state_r-s").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Confirmado").assertIsDisplayed()
        composeRule.onNodeWithTag("health_failure_r-f").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("La sesión asociada no existe o no es tuya. Crea el reporte de nuevo sin esa sesión.").assertIsDisplayed()
        composeRule.onNodeWithTag("health_dismiss_r-f").performScrollTo().performClick()
        assertTrue("dismiss:r-f" in events)
    }

    @Test
    fun unReporteIlegibleSeInformaSinInventarDatos() {
        show(base().copy(reports = listOf(report("r-u", HealthSyncState.SYNC_FAILED, HealthSyncFailure.UNREADABLE, details = null))))

        composeRule.onNodeWithText("No se pudo leer este reporte guardado.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("health_failure_r-u").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinVersionDeConsentimientoElFormularioQuedaDeshabilitadoConExplicacion() {
        show(base().copy(consentAvailable = false))

        composeRule.onNodeWithTag("health_consent_unavailable").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("health_submit").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("health_type_INJURY").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun sinReportesSeMuestraElEstadoVacio() {
        show(base())

        composeRule.onNodeWithTag("health_reports_empty").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("health_session_none").performScrollTo().assertIsDisplayed()
    }
}
