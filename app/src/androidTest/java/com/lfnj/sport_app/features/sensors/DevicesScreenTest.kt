package com.lfnj.sport_app.features.sensors

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.sensors.ble.BleAvailability
import com.lfnj.sport_app.features.sensors.model.BatchReport
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.features.sensors.model.DiscoveredMonitor
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.IngestionStatus
import com.lfnj.sport_app.features.sensors.model.LinkedDevice
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * HU037: estados de «Dispositivos de medición» con estados construidos a mano (sin red ni Bluetooth):
 * permisos, Android < 12, Bluetooth apagado, búsqueda, sin compatibles, transparencia antes de
 * vincular, verificación, servicio ausente, conexión con batería y FC, desconexión sin lectura
 * actual, revocación, sin red, error, aviso de fuente simulada y accesibilidad.
 */
@RunWith(AndroidJUnit4::class)
class DevicesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val events = mutableListOf<String>()
    private val now = Instant.parse("2026-10-27T15:00:00Z")
    private val monitor = DiscoveredMonitor("00:11:22:33:44:55", "Banda pecho")

    private fun device(status: DeviceStatus = DeviceStatus.ACTIVE, hasLocalAddress: Boolean = true) = LinkedDevice(
        deviceId = "a".repeat(64),
        displayName = "Banda pecho",
        status = status,
        version = if (status == DeviceStatus.ACTIVE) 1 else 2,
        createdAt = now,
        updatedAt = now,
        revokedAt = if (status == DeviceStatus.REVOKED) now else null,
        hasLocalAddress = hasLocalAddress,
    )

    private fun state(
        availability: BleAvailability = BleAvailability.READY,
        simulated: Boolean = false,
        devices: List<LinkedDevice>? = emptyList(),
        scan: ScanState = ScanState.Idle,
        linking: LinkDraft? = null,
        connected: LinkedDevice? = null,
        monitorSnapshot: MonitorSnapshot? = null,
        listProblem: ListProblem? = null,
        refreshing: Boolean = false,
        ingestion: IngestionStatus = IngestionStatus(),
        notice: DevicesNotice? = null,
    ) = DevicesUiState(
        availability = availability,
        simulatedSource = simulated,
        devices = devices,
        refreshing = refreshing,
        listProblem = listProblem,
        scan = scan,
        linking = linking,
        connected = connected,
        monitor = monitorSnapshot,
        ingestion = ingestion,
        notice = notice,
    )

    private fun show(state: DevicesUiState) = composeRule.setContent {
        SportappTheme {
            DevicesScreen(
                state = state,
                onRequestPermissions = { events += "permissions" },
                onOpenBluetoothSettings = { events += "bluetooth" },
                onSearch = { events += "search" },
                onStopSearch = { events += "stop" },
                onSelect = { events += "select:${it.advertisedName}" },
                onNameChange = { events += "name:$it" },
                onConfirmLink = { events += "link" },
                onCancelLink = { events += "cancelLink" },
                onAskRevoke = { events += "revoke:${it.displayName}" },
                onConfirmRevoke = { events += "confirmRevoke" },
                onCancelRevoke = { events += "cancelRevoke" },
                onRetry = { events += "retry" },
                onBack = { events += "back" },
            )
        }
    }

    private fun shown(tag: String) = composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    private fun absent(tag: String) = assertEquals(
        "no debe existir $tag",
        0,
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().size,
    )

    @Test
    fun sinPermisoExplicaYPideDispositivosCercanosSinBuscar() {
        show(state(availability = BleAvailability.PERMISSION_REQUIRED))
        shown("devices_permission_required").assertTextEquals(text(R.string.devices_permission_required))
        shown("devices_grant_permissions").performClick()
        absent("devices_search")
        assertEquals(listOf("permissions"), events)
    }

    @Test
    fun androidAnteriorA12ExplicaQueNoSePuedeVincular() {
        show(state(availability = BleAvailability.UNSUPPORTED_ANDROID_VERSION))
        shown("devices_unsupported_android").assertTextEquals(text(R.string.devices_requires_android_12))
        absent("devices_search")
        absent("devices_grant_permissions")
    }

    @Test
    fun bluetoothApagadoOfreceAbrirLosAjustes() {
        show(state(availability = BleAvailability.BLUETOOTH_OFF))
        shown("devices_bluetooth_off").assertTextEquals(text(R.string.devices_bluetooth_off))
        shown("devices_open_bluetooth").performClick()
        absent("devices_search")
        assertEquals(listOf("bluetooth"), events)
    }

    @Test
    fun laTransparenciaSeMuestraAntesDeBuscarYElBotonBusca() {
        show(state())
        shown("devices_transparency").assertTextEquals(text(R.string.devices_transparency))
        shown("devices_empty")
        shown("devices_search").assertIsEnabled().performClick()
        assertEquals(listOf("search"), events)
    }

    @Test
    fun buscandoMuestraProgresoLosMonitoresHallados() {
        show(state(scan = ScanState.Scanning(listOf(monitor))))
        shown("devices_scanning")
        shown("devices_found").assertTextContains("Banda pecho", substring = true)
        shown("devices_stop_search").performClick()
        shown("devices_found").performClick()
        assertEquals(listOf("stop", "select:Banda pecho"), events)
        // Nunca se muestra la dirección Bluetooth.
        assertEquals(0, composeRule.onAllNodes(hasText("00:11:22", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun sinMonitoresCompatiblesExplicaYPermiteBuscarDeNuevo() {
        show(state(scan = ScanState.Finished(emptyList())))
        shown("devices_none_compatible").assertTextEquals(text(R.string.devices_none_compatible))
        shown("devices_search").assertTextContains(text(R.string.devices_search_again))
        absent("devices_found")
    }

    @Test
    fun laConfirmacionDeVinculoDeclaraQueSeCapturaAntesDeVincular() {
        show(state(linking = LinkDraft(monitor, "Banda pecho", IDEMPOTENCY_KEY_FIXTURE)))
        shown("devices_link_section")
        shown("devices_link_transparency").assertTextEquals(text(R.string.devices_transparency))
        shown("devices_name_input").assertTextContains("Banda pecho")
        shown("devices_link_confirm").assertIsEnabled().performClick()
        shown("devices_link_cancel").performClick()
        // La búsqueda queda oculta mientras se confirma.
        absent("devices_search")
        assertEquals(listOf("link", "cancelLink"), events)
    }

    @Test
    fun verificandoElServicioDeshabilitaLaConfirmacion() {
        show(state(linking = LinkDraft(monitor, "Banda pecho", IDEMPOTENCY_KEY_FIXTURE, phase = LinkPhase.VERIFYING)))
        shown("devices_verifying").assertTextEquals(text(R.string.devices_verifying))
        shown("devices_link_confirm").assertIsNotEnabled()
        shown("devices_link_cancel").assertIsNotEnabled()
    }

    @Test
    fun vinculandoMuestraElEnvioEnCurso() {
        show(state(linking = LinkDraft(monitor, "Banda pecho", IDEMPOTENCY_KEY_FIXTURE, phase = LinkPhase.SENDING)))
        shown("devices_linking").assertTextEquals(text(R.string.devices_linking))
        shown("devices_link_confirm").assertIsNotEnabled()
    }

    @Test
    fun dispositivoSinServicioDeFrecuenciaCardiacaNoSeVincula() {
        show(state(linking = LinkDraft(monitor, "Banda pecho", IDEMPOTENCY_KEY_FIXTURE, problem = LinkProblem.MISSING_HEART_RATE_SERVICE)))
        shown("devices_link_problem").assertTextEquals(text(R.string.devices_link_missing_service))
        shown("devices_link_confirm").assertIsEnabled()
    }

    @Test
    fun conectadoMuestraEstadoBateriaFrecuenciaYUltimoLote() {
        val linked = device()
        show(
            state(
                devices = listOf(linked),
                connected = linked,
                monitorSnapshot = MonitorSnapshot(MonitorConnection.CONNECTED, 76, HeartRateReading(132, now)),
                ingestion = IngestionStatus(IngestionState.SENDING, 0, BatchReport(now, 5, 4, 1, 0)),
                notice = DevicesNotice.Linked(linked),
            ),
        )
        shown("devices_notice_linked").assertTextEquals(text(R.string.devices_notice_linked))
        shown("devices_connected")
        shown("devices_connection_state").assertTextEquals(text(R.string.devices_connection_connected))
        shown("devices_battery").assertTextEquals(text(R.string.devices_battery, 76))
        shown("devices_heart_rate").assertTextEquals(text(R.string.devices_heart_rate, 132))
        shown("devices_ingestion").assertTextEquals(text(R.string.devices_ingestion_last_batch, 4, 1, 0))
        // Un solo monitor conectado a la vez: no se ofrece buscar otro.
        absent("devices_search")
    }

    @Test
    fun sinBateriaInformadaLoDiceSinInventarla() {
        val linked = device()
        show(state(devices = listOf(linked), connected = linked, monitorSnapshot = MonitorSnapshot(MonitorConnection.CONNECTED, null, HeartRateReading(120, now))))
        shown("devices_battery").assertTextEquals(text(R.string.devices_battery_unknown))
    }

    @Test
    fun desconectadoNoPresentaLaLecturaAnteriorComoActual() {
        val linked = device()
        show(
            state(
                devices = listOf(linked),
                connected = linked,
                monitorSnapshot = MonitorSnapshot(MonitorConnection.DISCONNECTED, 76, HeartRateReading(132, now)),
                ingestion = IngestionStatus(IngestionState.PENDING_RETRY, 7),
            ),
        )
        shown("devices_connection_state").assertTextEquals(text(R.string.devices_connection_disconnected))
        shown("devices_heart_rate").assertTextEquals(text(R.string.devices_heart_rate_none))
        assertEquals(0, composeRule.onAllNodes(hasText("132", substring = true)).fetchSemanticsNodes().size)
        shown("devices_ingestion").assertTextEquals(text(R.string.devices_ingestion_pending, 7))
    }

    @Test
    fun revocadoSeMuestraSinOpcionDeRevocarYConAviso() {
        val revoked = device(status = DeviceStatus.REVOKED)
        show(state(devices = listOf(revoked), notice = DevicesNotice.Revoked(revoked)))
        shown("devices_notice_revoked").assertTextEquals(text(R.string.devices_notice_revoked))
        shown("devices_item_status").assertTextEquals(text(R.string.devices_status_revoked))
        absent("devices_revoke")
        absent("devices_connected")
    }

    @Test
    fun ingestionRechazadaPorRevocacionLoExplica() {
        val linked = device()
        show(
            state(
                devices = listOf(linked),
                connected = linked,
                monitorSnapshot = MonitorSnapshot(MonitorConnection.CONNECTED, 50, HeartRateReading(110, now)),
                ingestion = IngestionStatus(IngestionState.DEVICE_REVOKED),
            ),
        )
        shown("devices_ingestion").assertTextEquals(text(R.string.devices_ingestion_revoked))
    }

    @Test
    fun activoPermiteSolicitarLaRevocacionYConfirmarla() {
        val linked = device(hasLocalAddress = false)
        show(state(devices = listOf(linked)).copy(revoking = RevokeDraft(linked)))
        shown("devices_item_status").assertTextEquals(text(R.string.devices_status_active))
        shown("devices_revoke").assertIsNotEnabled()
        shown("devices_revoke_section")
        shown("devices_revoke_message").assertTextContains("Banda pecho", substring = true)
        shown("devices_revoke_confirm").performClick()
        shown("devices_revoke_cancel").performClick()
        assertEquals(listOf("confirmRevoke", "cancelRevoke"), events)
    }

    @Test
    fun revocarSinRedExplicaQueNoSeGuardo() {
        val linked = device()
        show(state(devices = listOf(linked)).copy(revoking = RevokeDraft(linked, problem = RevokeProblem.OFFLINE)))
        shown("devices_revoke_problem").assertTextEquals(text(R.string.devices_revoke_offline))
    }

    @Test
    fun sinRedMuestraLoGuardadoYPermiteReintentar() {
        show(state(devices = listOf(device(hasLocalAddress = false)), listProblem = ListProblem.OFFLINE))
        shown("devices_list_problem").assertTextEquals(text(R.string.devices_offline))
        shown("devices_item")
        shown("devices_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun errorDelServicioPermiteReintentar() {
        show(state(devices = null, listProblem = ListProblem.UNAVAILABLE))
        shown("devices_list_problem").assertTextEquals(text(R.string.devices_error_unavailable))
        shown("devices_retry")
    }

    @Test
    fun sinCapacidadExplicaElPlanSinReintento() {
        show(state(devices = null, listProblem = ListProblem.CAPABILITY_REQUIRED))
        shown("devices_list_problem").assertTextEquals(text(R.string.devices_error_capability))
        absent("devices_retry")
    }

    @Test
    fun fuenteSimuladaSeDeclaraSiempre() {
        show(state(simulated = true))
        shown("devices_simulated_source").assertTextEquals(text(R.string.devices_simulated_source))
    }

    @Test
    fun fuenteRealNoMuestraElAvisoDeSimulacion() {
        show(state(simulated = false))
        absent("devices_simulated_source")
    }

    @Test
    fun accesibilidadEncabezadosProgresoConDescripcionYVolver() {
        show(state(devices = null, refreshing = true))
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        composeRule.onNode(hasText(text(R.string.devices_list_title)) and heading).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasText(text(R.string.devices_add_title)) and heading).performScrollTo().assertIsDisplayed()
        shown("devices_refreshing").assert(hasContentDescription(text(R.string.devices_refreshing)))
        shown("devices_loading")
        composeRule.onNodeWithTag("devices_back").assert(hasContentDescription(text(R.string.action_back))).performClick()
        assertTrue("back" in events)
    }

    private companion object {
        /** Clave de idempotencia sintética derivada (no es un secreto). */
        val IDEMPOTENCY_KEY_FIXTURE: String = java.util.UUID.nameUUIDFromBytes("hu037-screen".toByteArray()).toString()
    }
}
