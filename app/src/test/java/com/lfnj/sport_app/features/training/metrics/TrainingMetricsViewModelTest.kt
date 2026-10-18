package com.lfnj.sport_app.features.training.metrics

import com.lfnj.sport_app.features.training.metrics.data.MetricsLocalState
import com.lfnj.sport_app.features.training.metrics.data.MetricsRefresh
import com.lfnj.sport_app.features.training.metrics.data.MoreResults
import com.lfnj.sport_app.features.training.metrics.data.SubmitResult
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsGateway
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricCatalogDto
import com.lfnj.sport_app.features.training.metrics.model.InputError
import com.lfnj.sport_app.features.training.metrics.model.MeasuredAtError
import com.lfnj.sport_app.features.training.metrics.model.MetricCatalog
import com.lfnj.sport_app.features.training.metrics.model.MetricInputValue
import com.lfnj.sport_app.features.training.metrics.model.MetricResultsCache
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MetricsFixtures
import com.lfnj.sport_app.testing.MetricsClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingMetricsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = MetricsClock()

    private fun catalog(body: String = MetricsFixtures.CATALOG, syncedAt: Instant = clock.instant()): MetricCatalog {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString(TrainingMetricCatalogDto.serializer(), body)
        return MetricCatalog(dto.catalogVersion, dto.methods.map { it.toModel() }, MetricsFixtures.ETAG, syncedAt)
    }

    private class FakeGateway(initial: MetricsLocalState) : TrainingMetricsGateway {
        val local = MutableStateFlow(initial)
        var catalogRefresh: MetricsRefresh = MetricsRefresh.Updated
        var resultsRefresh: MetricsRefresh = MetricsRefresh.Updated
        val submitted = mutableListOf<Triple<String, List<MetricInputValue>, Instant>>()
        var submitGate: CompletableDeferred<SubmitResult>? = null

        override fun observe(owner: String): Flow<MetricsLocalState> = local
        override suspend fun refreshCatalog(owner: String) = catalogRefresh
        override suspend fun refreshResults(owner: String) = resultsRefresh
        override suspend fun loadMore(owner: String, cursor: String) = MoreResults.Loaded(emptyList(), null)
        override suspend fun submit(owner: String, methodId: String, inputs: List<MetricInputValue>, measuredAt: Instant): SubmitResult {
            submitted += Triple(methodId, inputs, measuredAt)
            return submitGate?.await() ?: SubmitResult.Queued("id-${submitted.size}")
        }
        override suspend fun dismiss(owner: String, calculationId: String) = true
    }

    private fun gateway(catalog: MetricCatalog? = catalog()) = FakeGateway(MetricsLocalState(catalog, MetricResultsCache.EMPTY, emptyList()))

    private fun vm(gateway: FakeGateway) = TrainingMetricsViewModel("owner", gateway, clock)

    @Test
    fun `CA7 los metodos y el formulario se construyen desde el catalogo confirmado`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm(gateway())
        advanceUntilIdle()

        assertEquals(listOf(MetricsFixtures.FTP_ID, MetricsFixtures.COOPER_ID), vm.uiState.value.methods.map { it.id })
        vm.selectMethod(MetricsFixtures.COOPER_ID)
        assertEquals(listOf("distance_meters"), vm.uiState.value.selectedMethod?.inputs?.map { it.code })
    }

    @Test
    fun `CA7 un tercer indicador escalar se ofrece sin cambiar el cliente`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway(catalog(MetricsFixtures.CATALOG_WITH_THIRD))
        val vm = vm(g)
        advanceUntilIdle()

        vm.selectMethod("TEST_SCALAR_V1@3")
        assertEquals(listOf("repetitions", "heart_rate_bpm"), vm.uiState.value.selectedMethod?.inputs?.map { it.code })
        vm.onInputChange("repetitions", "40")
        vm.requestSubmit()
        assertTrue("La entrada opcional vacía no bloquea", vm.uiState.value.confirming)
        vm.confirmSubmit()
        advanceUntilIdle()

        assertEquals(listOf(MetricInputValue("repetitions", 40, "rep")), g.submitted.single().second)
    }

    @Test
    fun `CA9 sin catalogo confirmado no se ofrece ningun metodo`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway(catalog = null).apply { catalogRefresh = MetricsRefresh.NoConnection }
        val vm = vm(g)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.methods.isEmpty())
        vm.selectMethod(MetricsFixtures.FTP_ID)
        assertNull(vm.uiState.value.selectedMethod)
        assertEquals(MetricsNotice.NO_CONNECTION, vm.uiState.value.notice)
    }

    @Test
    fun `CA9 un catalogo de mas de 24 horas se marca STALE`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm(gateway(catalog(syncedAt = clock.instant().minusSeconds(24 * 3600 + 60))).apply { catalogRefresh = MetricsRefresh.NoConnection })
        advanceUntilIdle()

        assertTrue(vm.uiState.value.catalogStale)
    }

    @Test
    fun `CA3 los errores de entrada y de fecha se muestran sin enviar`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway()
        val vm = vm(g)
        advanceUntilIdle()
        vm.selectMethod(MetricsFixtures.FTP_ID)

        vm.requestSubmit()
        assertEquals(InputError.REQUIRED, vm.uiState.value.inputErrors["mean_power_watts"])
        vm.onInputChange("mean_power_watts", "3000")
        vm.setMeasuredAt(clock.instant().plusSeconds(600))
        vm.requestSubmit()

        assertEquals(InputError.OUT_OF_RANGE, vm.uiState.value.inputErrors["mean_power_watts"])
        assertEquals(MeasuredAtError.IN_FUTURE, vm.uiState.value.measuredAtError)
        assertFalse(vm.uiState.value.confirming)
        assertTrue(g.submitted.isEmpty())
    }

    @Test
    fun `CA6 y CA8 antes de enviar se muestra el aviso y solo al confirmar se encola sin resultado local`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway()
        val vm = vm(g)
        advanceUntilIdle()
        vm.selectMethod(MetricsFixtures.FTP_ID)
        vm.onInputChange("mean_power_watts", "250")

        vm.requestSubmit()
        assertTrue(vm.uiState.value.confirming)
        assertTrue(g.submitted.isEmpty())
        vm.confirmSubmit()
        advanceUntilIdle()

        val (method, inputs, measuredAt) = g.submitted.single()
        assertEquals(MetricsFixtures.FTP_ID, method)
        assertEquals(listOf(MetricInputValue("mean_power_watts", 250, "W")), inputs)
        assertEquals(clock.instant(), measuredAt)
        assertEquals(SubmitNotice.QUEUED, vm.uiState.value.submitNotice)
        assertTrue("El formulario se limpia", vm.uiState.value.inputs.isEmpty())
        assertTrue("El móvil no muestra ningún valor calculado", vm.uiState.value.results.isEmpty())
    }

    @Test
    fun `cancelar el aviso no envia nada`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway()
        val vm = vm(g)
        advanceUntilIdle()
        vm.selectMethod(MetricsFixtures.FTP_ID)
        vm.onInputChange("mean_power_watts", "250")
        vm.requestSubmit()

        vm.cancelSubmit()
        vm.confirmSubmit()
        advanceUntilIdle()

        assertTrue(g.submitted.isEmpty())
    }

    @Test
    fun `toques repetidos no duplican la captura`() = runTest(mainDispatcherRule.dispatcher) {
        val g = gateway().apply { submitGate = CompletableDeferred() }
        val vm = vm(g)
        advanceUntilIdle()
        vm.selectMethod(MetricsFixtures.FTP_ID)
        vm.onInputChange("mean_power_watts", "250")
        vm.requestSubmit()

        vm.confirmSubmit()
        vm.requestSubmit()
        vm.confirmSubmit()
        advanceUntilIdle()
        g.submitGate?.complete(SubmitResult.Queued("x"))
        advanceUntilIdle()

        assertEquals(1, g.submitted.size)
    }

    @Test
    fun `la captura solo acepta digitos`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm(gateway())
        advanceUntilIdle()
        vm.selectMethod(MetricsFixtures.FTP_ID)

        vm.onInputChange("mean_power_watts", "2a5,0")

        assertEquals("250", vm.uiState.value.inputs["mean_power_watts"])
    }

    @Test
    fun `una sesion vencida vuelve al acceso`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm(gateway().apply { catalogRefresh = MetricsRefresh.SignedOut })
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
    }

    @Test
    fun `sin la capacidad se informa sin borrar lo confirmado`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm(gateway().apply { catalogRefresh = MetricsRefresh.CapabilityRequired })
        advanceUntilIdle()

        assertEquals(MetricsNotice.CAPABILITY_REQUIRED, vm.uiState.value.notice)
        assertTrue(vm.uiState.value.catalog != null)
    }
}
