package com.lfnj.sport_app.features.training.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.training.dashboard.model.CustomRangeError
import com.lfnj.sport_app.features.training.dashboard.model.DashboardPeriod
import com.lfnj.sport_app.features.training.dashboard.model.DashboardRange
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSourceState
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSourceStatus
import com.lfnj.sport_app.features.training.dashboard.model.DashboardTotals
import com.lfnj.sport_app.features.training.dashboard.model.LatestMetric
import com.lfnj.sport_app.features.training.dashboard.model.SourceTotal
import com.lfnj.sport_app.features.training.dashboard.model.SourceTotals
import com.lfnj.sport_app.features.training.dashboard.model.SportTotal
import com.lfnj.sport_app.features.training.dashboard.model.WeekTotal
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** HU033: estados de `/entrenamientos/tablero`, gráficos con alternativa textual, unidades y estado vacío. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TrainingDashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")
    private val today = LocalDate.of(2026, 10, 25)
    private val period = DashboardPeriod.preset(DashboardRange.LAST_30, today, bogota)
    private val events = mutableListOf<String>()
    private val locale: Locale = Locale.getDefault()

    private fun snapshot(
        hasData: Boolean = true,
        sessions: DashboardSourceStatus = DashboardSourceStatus.OK,
        metrics: DashboardSourceStatus = DashboardSourceStatus.OK,
        distance: Long? = null,
    ) = DashboardSnapshot(
        range = DashboardRange.LAST_30,
        from = period.from.toInstant(),
        to = period.to.toInstant(),
        hasData = hasData,
        totals = if (hasData) DashboardTotals(2, 1_095, 548, distance) else DashboardTotals(0, 0, 0, null),
        bySport = if (hasData) listOf(SportTotal("running", 1, 45, null), SportTotal("cycling", 1, 1_050, distance)) else emptyList(),
        byWeek = if (hasData) listOf(WeekTotal(LocalDate.of(2026, 10, 12), 1, 45), WeekTotal(LocalDate.of(2026, 10, 19), 1, 1_050)) else emptyList(),
        bySource = SourceTotals(SourceTotal(if (hasData) 2 else 0, if (hasData) 1_095 else 0), SourceTotal(0, 0)),
        latestMetrics = if (hasData) {
            listOf(LatestMetric("VO2MAX_ESTIMATED", BigDecimal("47.25"), "ml/kg/min", "COOPER_12_MIN_V1", 1, Instant.parse("2026-10-20T12:30:00Z"), "estimated_not_diagnostic"))
        } else {
            emptyList()
        },
        sources = listOf(DashboardSourceState("training_sessions", sessions), DashboardSourceState("training_metrics", metrics)),
        syncedAt = Instant.parse("2026-10-25T13:30:00Z"),
    )

    private fun state(
        snapshot: DashboardSnapshot? = snapshot(),
        range: DashboardRange = DashboardRange.LAST_30,
        problem: DashboardProblem? = null,
        stale: Boolean = false,
        customError: CustomRangeError? = null,
    ) = TrainingDashboardUiState(
        zone = bogota, today = today, range = range,
        period = if (range == DashboardRange.CUSTOM) null else period,
        snapshot = snapshot, stale = stale, problem = problem, customError = customError,
    )

    private fun show(state: TrainingDashboardUiState) = composeRule.setContent {
        SportappTheme {
            TrainingDashboardScreen(
                state = state,
                onSelectRange = { events += "range:${it.name}" },
                onSelectCustomRange = { start, end -> events += "custom:$start..$end" },
                onDismissCustomError = { events += "dismiss" },
                onRetry = { events += "retry" },
                onRegisterTraining = { events += "register" },
                onBack = { events += "back" },
            )
        }
    }

    private fun number(value: Long) = java.text.NumberFormat.getIntegerInstance(locale).format(value)

    @Test
    fun muestraTotalesConUnidadesYFormatoRegionalSinAlterarElValor() {
        show(state())

        composeRule.onNodeWithTag("dashboard_range_last_30").assertIsSelected()
        composeRule.onNodeWithTag("dashboard_total_sessions").assertIsDisplayed()
        composeRule.onNodeWithText("Duración total: ${number(1_095)} min").assertIsDisplayed()
        composeRule.onNodeWithText("Duración promedio: ${number(548)} min por sesión").assertIsDisplayed()
        // Sin filas con distancia el contrato envía null: se declara no disponible, nunca 0.
        composeRule.onNodeWithTag("dashboard_distance_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_synced_at").assertIsDisplayed()
    }

    @Test
    fun laDistanciaDisponibleSeMuestraEnMetros() {
        show(state(snapshot(distance = 20_500)))

        composeRule.onNodeWithText("Distancia: ${number(20_500)} m").assertIsDisplayed()
    }

    @Test
    fun lasBarrasTienenAlternativaTextualAccesible() {
        show(state())

        composeRule.onNodeWithTag("dashboard_week_2026-10-19").performScrollTo().assertIsDisplayed()
            .assertContentDescriptionContains("${number(1_050)} minutos", substring = true)
            .assertContentDescriptionContains("1 sesión", substring = true)
        composeRule.onNodeWithTag("dashboard_sport_cycling").performScrollTo()
            .assertContentDescriptionContains("Ciclismo", substring = true)
        composeRule.onNodeWithTag("dashboard_sport_running").performScrollTo()
            .assertContentDescriptionContains("Carrera: ${number(45)} minutos", substring = true)
    }

    @Test
    fun muestraElOrigenDeLosDatosYElUltimoIndicadorConMetodoFechaYAviso() {
        show(state())

        composeRule.onNodeWithTag("dashboard_source_sportapp").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_source_strava").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_metric_VO2MAX_ESTIMATED").performScrollTo().assertIsDisplayed()
        val value = java.text.NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2 }.format(BigDecimal("47.25"))
        composeRule.onNodeWithText("$value ml/kg/min").assertExists()
        composeRule.onNodeWithText("Método: Prueba de Cooper (12 minutos) (versión 1)").assertExists()
        composeRule.onNodeWithText("Es una estimación de campo", substring = true).assertExists()
    }

    @Test
    fun sinDatosMuestraElEstadoVacioConEnlaceARegistrarSinGraficos() {
        show(state(snapshot(hasData = false)))

        composeRule.onNodeWithTag("dashboard_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_weeks").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboard_totals").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboard_register_training").performClick()
        assertEquals(listOf("register"), events)
    }

    @Test
    fun unaConsultaCaidaNoSePresentaComoCerosNiComoVacio() {
        show(state(snapshot(hasData = false, sessions = DashboardSourceStatus.UNAVAILABLE)))

        composeRule.onNodeWithTag("dashboard_incomplete_training_sessions").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_empty").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboard_totals").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboard_metrics_empty").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedMuestraLaCacheConSuAntiguedadYAvisoDeDesactualizado() {
        show(state(problem = DashboardProblem.NO_CONNECTION, stale = true))

        composeRule.onNodeWithTag("dashboard_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_stale").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_synced_at").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_totals").assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheLoInformaYPermiteReintentar() {
        show(state(snapshot = null, problem = DashboardProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("dashboard_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_empty").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboard_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun cargandoMuestraElIndicador() {
        show(state(snapshot = null))

        composeRule.onNodeWithTag("dashboard_loading").assertIsDisplayed()
    }

    @Test
    fun unErrorSinCacheSeExplicaSinDetallesTecnicos() {
        show(state(snapshot = null, problem = DashboardProblem.CAPABILITY_REQUIRED))

        composeRule.onNodeWithText("Tu plan actual no incluye el tablero de entrenamiento.").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_retry").assertIsDisplayed()
    }

    @Test
    fun elSelectorCambiaElPeriodoYPersonalizadoAbreLasFechas() {
        show(state())

        composeRule.onNodeWithTag("dashboard_range_last_7").performClick()
        composeRule.onNodeWithTag("dashboard_range_last_90").performClick()
        composeRule.onNodeWithTag("dashboard_range_custom").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("dashboard_custom_picker"), 5_000)
        composeRule.onNodeWithTag("dashboard_back").assertExists()

        assertEquals(listOf("range:LAST_7", "range:LAST_90", "range:CUSTOM"), events)
    }

    @Test
    fun personalizadoSinFechasPideElegirlas() {
        show(state(snapshot = null, range = DashboardRange.CUSTOM))

        composeRule.onNodeWithTag("dashboard_custom_prompt").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_choose_dates").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_loading").assertDoesNotExist()
    }

    @Test
    fun unRangoDeMasDe366DiasSeExplica() {
        show(state(range = DashboardRange.CUSTOM, snapshot = null, customError = CustomRangeError.TOO_LONG))

        composeRule.onNodeWithTag("dashboard_custom_error").assertIsDisplayed()
        composeRule.onNodeWithText("Aceptar").performClick()
        assertEquals(listOf("dismiss"), events)
    }
}
