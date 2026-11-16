package com.lfnj.sport_app.features.integrations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaLastSync
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

/** HU031: estados, consentimiento, desvinculación, errores recuperables y accesibilidad de «Strava». */
@RunWith(AndroidJUnit4::class)
class StravaScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val syncedAt = Instant.parse("2026-11-15T15:00:00Z")
    private val requested = mutableListOf<String>()

    private fun integration(status: StravaLinkStatus, lastSync: StravaLastSync? = null) = StravaIntegration(
        status = status,
        linkedAt = if (status == StravaLinkStatus.NOT_LINKED) null else Instant.parse("2026-11-10T12:00:00Z"),
        scopes = if (status == StravaLinkStatus.ACTIVE) listOf("read", "activity:read") else emptyList(),
        lastSync = lastSync,
        syncedAt = syncedAt,
    )

    private fun sync(status: StravaSyncStatus, error: StravaSyncError? = null, imported: Long = 0) = StravaLastSync(
        status, Instant.parse("2026-11-15T14:00:00Z"), if (status.inProgress) null else Instant.parse("2026-11-15T14:05:00Z"), imported, error,
    )

    private fun show(state: StravaUiState, opens: Boolean = true) {
        var current by mutableStateOf(state)
        composeRule.setContent {
            SportappTheme {
                StravaScreen(
                    state = current,
                    onRequestLink = { requested += "link" },
                    onOpenAuthorization = { url -> requested += "open:$url"; opens },
                    onAuthorizationOpened = { opened -> requested += "opened:$opened"; current = current.copy(authorizationUrl = null, linking = opened) },
                    onSync = { requested += "sync" },
                    onUnlink = { requested += "unlink" },
                    onRefresh = { requested += "refresh" },
                    onRetry = { requested += "retry" },
                    onDismissNotice = { requested += "dismiss" },
                    onBack = { requested += "back" },
                    zone = ZoneOffset.UTC,
                )
            }
        }
    }

    @Test
    fun cargando() {
        show(StravaUiState())

        composeRule.onNodeWithTag("strava_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_link").assertDoesNotExist()
    }

    @Test
    fun noVinculadoMuestraElConsentimientoAntesDePedirLaUrl() {
        show(StravaUiState(integration = integration(StravaLinkStatus.NOT_LINKED)))

        composeRule.onNodeWithTag("strava_status_not_linked").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_sync_card").assertDoesNotExist()
        composeRule.onNodeWithTag("strava_link").assertIsEnabled().performClick()

        composeRule.onNodeWithTag("strava_consent").assertIsDisplayed()
        composeRule.onNodeWithText("leer tu perfil público y leer tus actividades", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("nunca ve tu usuario ni tu contraseña", substring = true).assertIsDisplayed()
        assertTrue(requested.isEmpty())

        composeRule.onNodeWithTag("strava_consent_cancel").performClick()
        composeRule.onNodeWithTag("strava_consent").assertDoesNotExist()
        assertTrue(requested.isEmpty())

        composeRule.onNodeWithTag("strava_link").performClick()
        composeRule.onNodeWithTag("strava_consent_accept").performClick()
        assertEquals(listOf("link"), requested)
    }

    @Test
    fun laUrlSeAbreUnaSolaVezYQuedaVinculando() {
        show(StravaUiState(integration = integration(StravaLinkStatus.NOT_LINKED), authorizationUrl = "https://www.strava.com/oauth/mobile/authorize?state=x"))

        composeRule.waitForIdle()
        assertEquals(listOf("open:https://www.strava.com/oauth/mobile/authorize?state=x", "opened:true"), requested)
        composeRule.onNodeWithTag("strava_status_linking").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_link").assertIsNotEnabled()
        composeRule.onNodeWithText("https://www.strava.com", substring = true).assertDoesNotExist()
    }

    @Test
    fun vinculadoSinSincronizarDistingueVinculadoDeSincronizado() {
        show(StravaUiState(integration = integration(StravaLinkStatus.ACTIVE)))

        composeRule.onNodeWithTag("strava_status_active").assertIsDisplayed()
        composeRule.onNodeWithText("Vinculado desde el", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("strava_scope_read").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_scope_activity:read").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_sync_never").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("strava_link").assertDoesNotExist()
        composeRule.onNodeWithTag("strava_sync").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("sync"), requested)
    }

    @Test
    fun sincronizandoDeshabilitaLaSolicitud() {
        show(StravaUiState(integration = integration(StravaLinkStatus.ACTIVE, sync(StravaSyncStatus.RUNNING))))

        composeRule.onNodeWithTag("strava_sync_in_progress").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sincronizando tus actividades…").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_sync").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun sincronizadoMuestraFechaYActividades() {
        show(StravaUiState(integration = integration(StravaLinkStatus.ACTIVE, sync(StravaSyncStatus.SUCCEEDED, imported = 3))))

        composeRule.onNodeWithTag("strava_sync_succeeded").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("3 actividades importadas o actualizadas.").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_sync").performScrollTo().assertIsEnabled()
    }

    @Test
    fun corridaFallidaEsRecuperable() {
        show(StravaUiState(integration = integration(StravaLinkStatus.ACTIVE, sync(StravaSyncStatus.RATE_LIMITED, StravaSyncError.RATE_LIMITED))))

        composeRule.onNodeWithTag("strava_sync_failed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Strava limitó las solicitudes", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("strava_sync").performScrollTo().assertIsEnabled()
    }

    @Test
    fun expiradoPermiteVolverAVincularODesvincular() {
        show(StravaUiState(integration = integration(StravaLinkStatus.EXPIRED, sync(StravaSyncStatus.FAILED, StravaSyncError.TOKEN_EXPIRED))))

        composeRule.onNodeWithTag("strava_status_expired").assertIsDisplayed()
        composeRule.onNodeWithText("Volver a vincular").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_unlink").assertIsEnabled()
        composeRule.onNodeWithText("la autorización venció", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("strava_sync").assertDoesNotExist()
    }

    @Test
    fun revocadoNoPermiteSincronizar() {
        show(StravaUiState(integration = integration(StravaLinkStatus.REVOKED)))

        composeRule.onNodeWithTag("strava_status_revoked").assertIsDisplayed()
        composeRule.onNodeWithText("Las actividades ya importadas se conservan", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("strava_unlink").assertDoesNotExist()
        composeRule.onNodeWithTag("strava_sync").assertDoesNotExist()
    }

    @Test
    fun desvincularExplicaSusEfectosYPideConfirmacion() {
        show(StravaUiState(integration = integration(StravaLinkStatus.ACTIVE)))

        composeRule.onNodeWithTag("strava_unlink").performClick()
        composeRule.onNodeWithTag("strava_unlink_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("dejará de sincronizar", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("se conservan en tu historial", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("strava_unlink_cancel").performClick()
        assertTrue(requested.isEmpty())

        composeRule.onNodeWithTag("strava_unlink").performClick()
        composeRule.onNodeWithTag("strava_unlink_confirm").performClick()
        assertEquals(listOf("unlink"), requested)
    }

    @Test
    fun sinRedConEstadoGuardadoMuestraAntiguedadYReintento() {
        show(
            StravaUiState(
                integration = integration(StravaLinkStatus.ACTIVE),
                stale = true,
                problem = StravaProblem.NO_CONNECTION,
                failedAction = StravaAction.REFRESH,
            )
        )

        composeRule.onNodeWithTag("strava_problem_no_connection").assertIsDisplayed()
        composeRule.onNodeWithText("nada queda pendiente en el teléfono", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("strava_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_stale").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_retry").performClick()
        assertEquals(listOf("retry"), requested)
    }

    @Test
    fun sinRedYSinEstadoGuardado() {
        show(StravaUiState(problem = StravaProblem.NO_CONNECTION, failedAction = StravaAction.REFRESH))

        composeRule.onNodeWithTag("strava_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_link").assertDoesNotExist()
    }

    @Test
    fun sinCapacidadNoOfreceReintento() {
        show(StravaUiState(problem = StravaProblem.CAPABILITY_REQUIRED, failedAction = StravaAction.REFRESH))

        composeRule.onNodeWithTag("strava_problem_capability_required").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_retry").assertDoesNotExist()
    }

    @Test
    fun avisosDelRetornoSinDetallesSensibles() {
        show(StravaUiState(integration = integration(StravaLinkStatus.NOT_LINKED), notice = StravaNotice.LINK_INVALID_STATE))

        composeRule.onNodeWithTag("strava_notice_link_invalid_state").assertIsDisplayed()
        composeRule.onNodeWithText("no se creó ninguna vinculación", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("strava_notice_dismiss").performClick()
        assertEquals(listOf("dismiss"), requested)
    }

    @Test
    fun sinNavegadorSeInforma() {
        show(StravaUiState(integration = integration(StravaLinkStatus.NOT_LINKED), problem = StravaProblem.BROWSER_UNAVAILABLE, failedAction = StravaAction.LINK))

        composeRule.onNodeWithTag("strava_problem_browser_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("strava_retry").assertIsDisplayed()
    }

    @Test
    fun volverYActualizar() {
        show(StravaUiState(integration = integration(StravaLinkStatus.ACTIVE)))

        composeRule.onNodeWithTag("strava_refresh").performScrollTo().performClick()
        composeRule.onNodeWithTag("strava_back").performClick()
        assertEquals(listOf("refresh", "back"), requested)
    }
}
