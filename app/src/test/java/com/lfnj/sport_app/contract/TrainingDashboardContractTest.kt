package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.training.dashboard.data.DashboardBySourceDto
import com.lfnj.sport_app.features.training.dashboard.data.DashboardMetricDto
import com.lfnj.sport_app.features.training.dashboard.data.DashboardPeriodDto
import com.lfnj.sport_app.features.training.dashboard.data.DashboardSourceDto
import com.lfnj.sport_app.features.training.dashboard.data.DashboardTotalsDto
import com.lfnj.sport_app.features.training.dashboard.data.SourceBreakdownDto
import com.lfnj.sport_app.features.training.dashboard.data.SportBreakdownDto
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardApi
import com.lfnj.sport_app.features.training.dashboard.data.TrainingDashboardResponseDto
import com.lfnj.sport_app.features.training.dashboard.data.WeekBreakdownDto
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSnapshot
import com.lfnj.sport_app.features.training.dashboard.model.DashboardSourceStatus
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU033 en el cliente móvil frente a la copia exacta de `bff-mobile` (ADR-043). */
class TrainingDashboardContractTest {
    private val contract = OpenApiContract.load()
    private val path = "/v1/me/training-dashboard"

    @Test
    fun `la operacion del tablero existe con su periodo`() {
        contract.assertApiMatches(TrainingDashboardApi::class.java)
        assertEquals(setOf("from", "to"), contract.parameters("get", path, "query"))
        assertTrue("authorization" in contract.parameters("get", path, "header"))
    }

    @Test
    fun `la respuesta y sus agregados corresponden al contrato`() {
        contract.assertModelMatches("TrainingDashboardResponse", TrainingDashboardResponseDto.serializer())
        contract.assertModelMatches("DashboardPeriodView", DashboardPeriodDto.serializer())
        contract.assertModelMatches("DashboardTotals", DashboardTotalsDto.serializer())
        contract.assertModelMatches("SportBreakdown", SportBreakdownDto.serializer())
        contract.assertModelMatches("WeekBreakdown", WeekBreakdownDto.serializer())
        contract.assertModelMatches("DashboardBySource", DashboardBySourceDto.serializer())
        contract.assertModelMatches("SourceBreakdown", SourceBreakdownDto.serializer())
        contract.assertModelMatches("DashboardMetric", DashboardMetricDto.serializer())
        contract.assertModelMatches("DashboardSource", DashboardSourceDto.serializer())
    }

    @Test
    fun `las fuentes, sus estados y los deportes del contrato son los que el cliente conoce`() {
        OpenApiContract.assertSameElements(
            "DashboardSource.source", contract.enumValues("DashboardSource", "source"), DashboardSnapshot.EXPECTED_SOURCES.toSet(),
        )
        OpenApiContract.assertSameElements(
            "DashboardSource.status", contract.enumValues("DashboardSource", "status"), DashboardSourceStatus.entries.map { it.name }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "SportBreakdown.sport", contract.enumValues("SportBreakdown", "sport"), TrainingSport.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas (CA4)`() {
        assertEquals(setOf("200", "401", "403", "422", "503", "504"), contract.responseCodes("get", path))
    }
}
