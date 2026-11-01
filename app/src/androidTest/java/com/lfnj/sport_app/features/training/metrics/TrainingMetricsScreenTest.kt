package com.lfnj.sport_app.features.training.metrics

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricCatalogDto
import com.lfnj.sport_app.features.training.metrics.model.CalculationRequest
import com.lfnj.sport_app.features.training.metrics.model.InputError
import com.lfnj.sport_app.features.training.metrics.model.MetricCatalog
import com.lfnj.sport_app.features.training.metrics.model.MetricFormat
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricResult
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncFailure
import com.lfnj.sport_app.features.training.metrics.model.MetricSyncState
import com.lfnj.sport_app.features.training.metrics.model.PendingCalculation
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/** HU034 en la pantalla: estados, accesibilidad, aviso no diagnóstico y tercer indicador (CA6–CA9). */
@RunWith(AndroidJUnit4::class)
class TrainingMetricsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-10-12T15:35:00Z")
    private val events = mutableListOf<String>()

    private fun catalog(body: String = TrainingMetricsLocalStorageTest.CATALOG, syncedAt: Instant = now): MetricCatalog {
        val dto = BffMobileClient.json.decodeFromString(TrainingMetricCatalogDto.serializer(), body)
        return MetricCatalog(dto.catalogVersion, dto.methods.map { it.toModel() }, null, syncedAt)
    }

    private fun show(state: TrainingMetricsUiState) = composeRule.setContent {
        SportappTheme {
            TrainingMetricsScreen(
                state = state,
                onSelectMethod = { events += "select:$it" },
                onInputChange = { code, text -> events += "input:$code=$text" },
                onMeasuredAtChange = { events += "measured" },
                onSubmit = { events += "submit" },
                onConfirmSubmit = { events += "confirm" },
                onCancelSubmit = { events += "cancel" },
                onDismiss = { events += "dismiss:$it" },
                onRetry = { events += "retry" },
                onLoadMore = { events += "more" },
                onBack = {},
                zone = ZoneOffset.UTC,
            )
        }
    }

    private val ftpResult = MetricResult(
        "c-1", "FTP_ESTIMATED", "FTP_20_MIN_95_V1", 1, Instant.parse("2026-10-12T15:30:00Z"), Instant.parse("2026-10-12T15:30:02Z"),
        listOf(MetricInputValue("mean_power_watts", 250, "W")), BigDecimal("238"), "W", 0, "estimated_not_diagnostic",
    )

    @Test
    fun cargaMuestraProgreso() {
        show(TrainingMetricsUiState(loading = true))

        composeRule.onNodeWithTag("metrics_loading").assertIsDisplayed()
    }

    @Test
    fun ca9SinCatalogoSolicitaConexionYNoOfreceMetodos() {
        show(TrainingMetricsUiState(loading = false, notice = MetricsNotice.NO_CONNECTION))

        composeRule.onNodeWithTag("metrics_catalog_missing").assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_method_FTP_20_MIN_95_V1").assertDoesNotExist()
        composeRule.onNodeWithTag("metrics_notice").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithTag("metrics_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun ca9UnCatalogoDeMasDe24HorasSeMuestraDesactualizado() {
        show(TrainingMetricsUiState(loading = false, catalog = catalog(syncedAt = now.minusSeconds(90_000)), catalogStale = true))

        composeRule.onNodeWithTag("metrics_catalog_stale").assertIsDisplayed()
        composeRule.onNodeWithText("Desactualizado", substring = true).assertIsDisplayed()
    }

    @Test
    fun ca7ElFormularioSeConstruyeDesdeElCatalogoConEtiquetaYUnidadAnunciadas() {
        show(TrainingMetricsUiState(loading = false, catalog = catalog(), selectedMethodId = "COOPER_12_MIN_V1@1", measuredAt = now))

        composeRule.onNodeWithText("FTP estimado").assertIsDisplayed()
        composeRule.onNodeWithText("VO₂max estimado").assertIsDisplayed()
        composeRule.onNodeWithText("Prueba de Cooper (12 minutos) · versión 1").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Distancia recorrida en 12 minutos (m)")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Entre 600 y 6000 m").assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_method_FTP_20_MIN_95_V1").performClick()
        assertEquals(listOf("select:FTP_20_MIN_95_V1@1"), events)
    }

    @Test
    fun ca7UnTercerIndicadorDesconocidoSeRenderizaConSusCodigosSinCambiarElApk() {
        val third = """{"metric_code":"TEST_SCALAR_ESTIMATED","label_key":"training_metric.test_scalar_estimated","result_unit":"pts",""" +
            """"result_scale":2,"method_code":"TEST_SCALAR_V1","method_version":3,"method_label_key":"training_metric.method.test_scalar_v1",""" +
            """"disclaimer_key":"estimated_not_diagnostic","inputs":[{"code":"repetitions","label_key":"training_metric.input.repetitions",""" +
            """"value_type":"INTEGER","unit":"rep","minimum":1,"maximum":500,"scale":0,"required":true}]}"""
        val body = TrainingMetricsLocalStorageTest.CATALOG.removeSuffix("]}") + ",$third]}"
        val result = ftpResult.copy(calculationId = "t-1", metricCode = "TEST_SCALAR_ESTIMATED", methodCode = "TEST_SCALAR_V1", methodVersion = 3,
            inputs = listOf(MetricInputValue("repetitions", 40, "rep")), value = BigDecimal("12.50"), unit = "pts", scale = 2)
        show(TrainingMetricsUiState(loading = false, catalog = catalog(body), selectedMethodId = "TEST_SCALAR_V1@3", measuredAt = now, results = listOf(result)))

        // La fila seleccionable fusiona sus textos: el código técnico se anuncia como la opción.
        composeRule.onNodeWithTag("metrics_method_TEST_SCALAR_V1").performScrollTo().assertIsDisplayed().assert(hasText("TEST_SCALAR_ESTIMATED"))
        composeRule.onNode(hasContentDescription("repetitions (rep)")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_result_value_t-1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("${MetricFormat.value(BigDecimal("12.50"), 2)} pts").assertIsDisplayed()
        composeRule.onNode(hasText("TEST_SCALAR_V1 · versión 3") and hasAnyAncestor(hasTestTag("metrics_result_t-1"))).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ca3LosErroresDeEntradaSeExplicanEnElCampo() {
        show(
            TrainingMetricsUiState(
                loading = false, catalog = catalog(), selectedMethodId = "FTP_20_MIN_95_V1@1", measuredAt = now,
                inputs = mapOf("mean_power_watts" to "3000"), inputErrors = mapOf("mean_power_watts" to InputError.OUT_OF_RANGE),
            )
        )

        composeRule.onNodeWithText("Fuera del rango permitido: Entre 1 y 2500 W.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ca6AntesDeEnviarSeMuestraElAvisoNoDiagnostico() {
        show(TrainingMetricsUiState(loading = false, catalog = catalog(), selectedMethodId = "FTP_20_MIN_95_V1@1", measuredAt = now, confirming = true))

        composeRule.onNodeWithTag("metrics_confirm_disclaimer").assertIsDisplayed()
        composeRule.onNodeWithText("no una medición fisiológica directa ni un diagnóstico", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_confirm").performClick()
        assertEquals(listOf("confirm"), events)
    }

    @Test
    fun ca8UnCalculoPendienteNuncaMuestraUnValorYUnRechazoExplicaElCampo() {
        val request = CalculationRequest("p-1", "FTP_ESTIMATED", "FTP_20_MIN_95_V1", 1, now, listOf(MetricInputValue("mean_power_watts", 250, "W")))
        show(
            TrainingMetricsUiState(
                loading = false, catalog = catalog(), measuredAt = now,
                pending = listOf(
                    PendingCalculation("p-1", MetricSyncState.PENDING_SYNC, null, null, null, now, request),
                    PendingCalculation("p-2", MetricSyncState.SYNC_FAILED, MetricSyncFailure.VALIDATION, "inputs[0].value", "out_of_range", now, request.copy(calculationId = "p-2")),
                ),
            )
        )

        composeRule.onNodeWithText("Pendiente de sincronizar: aún no tiene resultado").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("238", substring = true).assertDoesNotExist()
        // El estado y su causa forman un solo nodo accesible (se anuncian juntos); la etiqueta queda en el árbol sin fusionar.
        composeRule.onNodeWithTag("metrics_failure_p-2", useUnmergedTree = true).performScrollTo()
        composeRule.onNodeWithText("Potencia media de 20 minutos: el valor está fuera del rango permitido.", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_dismiss_p-1").assertDoesNotExist()
        composeRule.onNodeWithTag("metrics_dismiss_p-2").performScrollTo().performClick()
        assertEquals(listOf("dismiss:p-2"), events)
    }

    @Test
    fun ca6ElResultadoMuestraUnidadMetodoVersionEntradaFechasYAviso() {
        show(TrainingMetricsUiState(loading = false, catalog = catalog(), measuredAt = now, results = listOf(ftpResult), resultsSyncedAt = now, nextCursor = "c"))

        composeRule.onNodeWithTag("metrics_result_value_c-1").performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasContentDescription("FTP estimado: 238 W")).assertIsDisplayed()
        composeRule.onNode(hasText("Prueba de 20 minutos (95 % de la potencia media) · versión 1") and hasAnyAncestor(hasTestTag("metrics_result_c-1")))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Potencia media de 20 minutos: 250 W").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Confirmado por SportApp").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_disclaimer_c-1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_results_synced").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("metrics_load_more").performScrollTo().performClick()
        assertEquals(listOf("more"), events)
    }

    @Test
    fun sinResultadosSeMuestraElEstadoVacio() {
        show(TrainingMetricsUiState(loading = false, catalog = catalog(), measuredAt = now))

        composeRule.onNodeWithTag("metrics_results_empty").performScrollTo().assertIsDisplayed()
    }
}
