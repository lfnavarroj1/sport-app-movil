package com.lfnj.sport_app.features.integrations

import com.lfnj.sport_app.features.integrations.data.StravaFailure
import com.lfnj.sport_app.features.integrations.data.StravaGateway
import com.lfnj.sport_app.features.integrations.data.StravaOutcome
import com.lfnj.sport_app.features.integrations.data.StravaSyncAcceptance
import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import com.lfnj.sport_app.features.integrations.system.StravaLinkLaunch
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.StravaFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * HU031: estados de `/integraciones/strava` (no vinculado, vinculando, vinculado, expirado, revocado,
 * sincronizando, sincronizado, error recuperable, sin red y reintento) con un BFF simulado en memoria.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StravaViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-11-15T15:00:00Z")
    private val clock = MutableClock(now, ZoneOffset.UTC)
    private val links = StravaLinkLaunch()
    private var keys = 0

    /** Caché observable y respuestas del BFF programadas por la prueba; un estado confirmado reemplaza la caché. */
    private class FakeGateway(initial: StravaIntegration? = null) : StravaGateway {
        val cache = MutableStateFlow(initial)
        val refreshes = ArrayDeque<StravaOutcome<StravaIntegration>>()
        var defaultRefresh: StravaOutcome<StravaIntegration> = StravaOutcome.Failed(StravaFailure.NO_CONNECTION)
        var refreshCount = 0
        val authorizations = ArrayDeque<StravaOutcome<String>>()
        var authorizationCount = 0
        val syncs = ArrayDeque<StravaOutcome<StravaSyncAcceptance>>()
        val syncKeys = mutableListOf<String>()
        val unlinks = ArrayDeque<StravaOutcome<Unit>>()
        var unlinkCount = 0
        var syncGate: CompletableDeferred<Unit>? = null

        override fun observe(owner: String): Flow<StravaIntegration?> = cache

        override suspend fun refresh(owner: String): StravaOutcome<StravaIntegration> {
            refreshCount++
            val outcome = refreshes.removeFirstOrNull() ?: defaultRefresh
            if (outcome is StravaOutcome.Done) cache.value = outcome.value
            return outcome
        }

        override suspend fun startAuthorization(): StravaOutcome<String> {
            authorizationCount++
            return authorizations.removeFirstOrNull() ?: StravaOutcome.Failed(StravaFailure.NO_CONNECTION)
        }

        override suspend fun requestSync(idempotencyKey: String): StravaOutcome<StravaSyncAcceptance> {
            syncKeys += idempotencyKey
            syncGate?.await()
            return syncs.removeFirstOrNull() ?: StravaOutcome.Failed(StravaFailure.NO_CONNECTION)
        }

        override suspend fun unlink(): StravaOutcome<Unit> {
            unlinkCount++
            return unlinks.removeFirstOrNull() ?: StravaOutcome.Failed(StravaFailure.NO_CONNECTION)
        }
    }

    private fun done(integration: StravaIntegration) = StravaOutcome.Done(integration)

    private fun viewModel(gateway: FakeGateway) =
        StravaViewModel("owner", gateway, links, clock, newIdempotencyKey = { "clave-${++keys}" }, pollInterval = Duration.ofSeconds(5), maxPolls = 3)

    private val notLinked = StravaFixtures.integration(status = StravaLinkStatus.NOT_LINKED, lastSync = null, syncedAt = now)
    private val active = StravaFixtures.integration(syncedAt = now)

    // ----------------------------------------------------------------- estado y caché

    @Test
    fun `carga, consulta el estado y lo muestra desde la cache confirmada`() = runTest {
        val gateway = FakeGateway().apply { refreshes += done(active) }
        val vm = viewModel(gateway)

        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()

        assertEquals(1, gateway.refreshCount)
        assertEquals(active, vm.uiState.value.integration)
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.stale)
        assertNull(vm.uiState.value.problem)
    }

    @Test
    fun `sin red conserva el ultimo estado con su antiguedad y lo marca STALE despues de 24 h`() = runTest {
        val old = StravaFixtures.integration(syncedAt = now.minus(Duration.ofHours(25)))
        val vm = viewModel(FakeGateway(old))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(old, state.integration)
        assertTrue(state.stale)
        assertEquals(StravaProblem.NO_CONNECTION, state.problem)
        assertEquals(StravaAction.REFRESH, state.failedAction)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `sin red y sin estado guardado se informa que nunca se sincronizo, y el reintento consulta de nuevo`() = runTest {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.neverSynced)
        assertFalse(vm.uiState.value.canLink)

        gateway.refreshes += done(notLinked)
        vm.retry()
        advanceUntilIdle()

        assertEquals(2, gateway.refreshCount)
        assertNull(vm.uiState.value.problem)
        assertEquals(StravaLinkStatus.NOT_LINKED, vm.uiState.value.status)
        assertTrue(vm.uiState.value.canLink)
    }

    @Test
    fun `sin la capacidad o sin permiso no se muestran datos guardados`() = runTest {
        val gateway = FakeGateway(active).apply { refreshes += StravaOutcome.Failed(StravaFailure.CAPABILITY_REQUIRED) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertNull(vm.uiState.value.integration)
        assertEquals(StravaProblem.CAPABILITY_REQUIRED, vm.uiState.value.problem)
        assertFalse(vm.uiState.value.canSync)

        gateway.refreshes += StravaOutcome.Failed(StravaFailure.PERMISSION_REQUIRED)
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.uiState.value.integration)
        assertEquals(StravaProblem.PERMISSION_REQUIRED, vm.uiState.value.problem)
    }

    @Test
    fun `una sesion vencida vuelve al acceso`() = runTest {
        val vm = viewModel(FakeGateway().apply { refreshes += StravaOutcome.Failed(StravaFailure.SIGNED_OUT) })
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }

    @Test
    fun `acciones disponibles por estado de vinculacion`() = runTest {
        fun stateFor(status: StravaLinkStatus): StravaUiState {
            val vm = viewModel(FakeGateway().apply { refreshes += done(StravaFixtures.integration(status = status, syncedAt = now)) })
            advanceUntilIdle()
            return vm.uiState.value
        }
        stateFor(StravaLinkStatus.NOT_LINKED).let { assertTrue(it.canLink); assertFalse(it.canSync); assertFalse(it.canUnlink) }
        stateFor(StravaLinkStatus.ACTIVE).let { assertFalse(it.canLink); assertTrue(it.canSync); assertTrue(it.canUnlink) }
        stateFor(StravaLinkStatus.EXPIRED).let { assertTrue(it.canLink); assertFalse(it.canSync); assertTrue(it.canUnlink) }
        stateFor(StravaLinkStatus.REVOKED).let { assertTrue(it.canLink); assertFalse(it.canSync); assertFalse(it.canUnlink) }
    }

    // ----------------------------------------------------------------- vinculación (CA1–CA3)

    @Test
    fun `vincular pide la URL una sola vez, la abre y espera el retorno (CA1)`() = runTest {
        val gateway = FakeGateway().apply {
            refreshes += done(notLinked)
            authorizations += StravaOutcome.Done(StravaFixtures.AUTHORIZATION_URL)
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestLink()
        assertTrue(vm.uiState.value.inLinking)
        assertFalse(vm.uiState.value.canLink)
        vm.requestLink()
        advanceUntilIdle()

        assertEquals(1, gateway.authorizationCount)
        assertEquals(StravaFixtures.AUTHORIZATION_URL, vm.uiState.value.authorizationUrl)
        vm.onAuthorizationOpened(true)
        assertNull(vm.uiState.value.authorizationUrl)
        assertTrue(vm.uiState.value.linking)
        assertTrue(vm.uiState.value.inLinking)
    }

    @Test
    fun `el retorno LINKED se confirma con el BFF antes de mostrarse como vinculado (CA2)`() = runTest {
        val gateway = FakeGateway().apply {
            refreshes += done(notLinked)
            authorizations += StravaOutcome.Done(StravaFixtures.AUTHORIZATION_URL)
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.requestLink()
        advanceUntilIdle()
        vm.onAuthorizationOpened(true)

        gateway.refreshes += done(active)
        links.offer("sportapp://integrations/strava?result=LINKED")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(StravaNotice.LINKED, state.notice)
        assertFalse(state.linking)
        assertEquals(2, gateway.refreshCount)
        assertEquals(StravaLinkStatus.ACTIVE, state.status)
        assertNull(links.pending.value)
        vm.onResumed()
        assertEquals(2, gateway.refreshCount)
    }

    @Test
    fun `un LINKED que el BFF no confirma no se presenta como vinculado`() = runTest {
        val gateway = FakeGateway().apply { refreshes += done(notLinked) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        gateway.refreshes += done(notLinked)
        links.offer("sportapp://integrations/strava?result=LINKED")
        advanceUntilIdle()

        assertEquals(StravaLinkStatus.NOT_LINKED, vm.uiState.value.status)
    }

    @Test
    fun `rechazo, state invalido o error no crean vinculacion y se informan sin detalles (CA3)`() = runTest {
        val gateway = FakeGateway().apply { defaultRefresh = done(notLinked) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        listOf(
            "DENIED" to StravaNotice.LINK_DENIED,
            "INVALID_STATE" to StravaNotice.LINK_INVALID_STATE,
            "ERROR" to StravaNotice.LINK_ERROR,
            "OTRO" to StravaNotice.LINK_ERROR,
        ).forEach { (result, notice) ->
            links.offer("sportapp://integrations/strava?result=$result")
            advanceUntilIdle()
            assertEquals(notice, vm.uiState.value.notice)
            assertEquals(StravaLinkStatus.NOT_LINKED, vm.uiState.value.status)
            assertTrue(vm.uiState.value.canLink)
            vm.dismissNotice()
            assertNull(vm.uiState.value.notice)
        }
    }

    @Test
    fun `volver de Strava sin enlace profundo deja la vinculacion como no completada y consulta el estado`() = runTest {
        val gateway = FakeGateway().apply {
            defaultRefresh = done(notLinked)
            authorizations += StravaOutcome.Done(StravaFixtures.AUTHORIZATION_URL)
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.onResumed()
        assertEquals(1, gateway.refreshCount)

        vm.requestLink()
        advanceUntilIdle()
        vm.onAuthorizationOpened(true)
        vm.onResumed()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.linking)
        assertEquals(StravaNotice.LINK_NOT_COMPLETED, vm.uiState.value.notice)
        assertEquals(2, gateway.refreshCount)
        assertTrue(vm.uiState.value.canLink)
    }

    @Test
    fun `sin navegador se informa y el reintento pide una URL nueva`() = runTest {
        val gateway = FakeGateway().apply {
            refreshes += done(notLinked)
            authorizations += StravaOutcome.Done(StravaFixtures.AUTHORIZATION_URL)
            authorizations += StravaOutcome.Done(StravaFixtures.AUTHORIZATION_URL)
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        vm.requestLink()
        advanceUntilIdle()

        vm.onAuthorizationOpened(false)
        assertEquals(StravaProblem.BROWSER_UNAVAILABLE, vm.uiState.value.problem)
        assertFalse(vm.uiState.value.linking)

        vm.retry()
        advanceUntilIdle()
        assertEquals(2, gateway.authorizationCount)
        assertEquals(StravaFixtures.AUTHORIZATION_URL, vm.uiState.value.authorizationUrl)
        assertNull(vm.uiState.value.problem)
    }

    @Test
    fun `sin red no se abre nada ni se encola y el reintento vuelve a pedir la URL`() = runTest {
        val gateway = FakeGateway().apply { refreshes += done(notLinked) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.requestLink()
        advanceUntilIdle()
        assertEquals(StravaProblem.NO_CONNECTION, vm.uiState.value.problem)
        assertEquals(StravaAction.LINK, vm.uiState.value.failedAction)
        assertNull(vm.uiState.value.authorizationUrl)

        gateway.authorizations += StravaOutcome.Done(StravaFixtures.AUTHORIZATION_URL)
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, gateway.authorizationCount)
        assertEquals(StravaFixtures.AUTHORIZATION_URL, vm.uiState.value.authorizationUrl)
    }

    // ----------------------------------------------------------------- sincronización (CA4, CA6)

    @Test
    fun `sincronizar envia una clave, muestra sincronizando y consulta hasta que termina (CA4)`() = runTest {
        val running = StravaFixtures.integration(syncedAt = now, lastSync = StravaFixtures.sync(StravaSyncStatus.RUNNING))
        val gateway = FakeGateway().apply {
            refreshes += done(active)
            syncs += StravaOutcome.Done(StravaSyncAcceptance.Accepted(StravaSyncStatus.QUEUED))
            refreshes += done(running)
            refreshes += done(running)
            refreshes += done(active)
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.sync()
        assertTrue(vm.uiState.value.syncing)
        assertFalse(vm.uiState.value.canSync)
        runCurrent()

        assertEquals(listOf("clave-1"), gateway.syncKeys)
        assertEquals(StravaNotice.SYNC_REQUESTED, vm.uiState.value.notice)
        assertTrue(vm.uiState.value.syncing)
        assertEquals(2, gateway.refreshCount)

        advanceTimeBy(5_001)
        assertEquals(3, gateway.refreshCount)
        advanceTimeBy(5_001)
        assertEquals(4, gateway.refreshCount)
        assertFalse(vm.uiState.value.syncing)
        assertEquals(StravaSyncStatus.SUCCEEDED, vm.uiState.value.integration?.lastSync?.status)

        advanceTimeBy(60_000)
        assertEquals(4, gateway.refreshCount)
    }

    @Test
    fun `la consulta periodica de una corrida en curso es acotada`() = runTest {
        val running = StravaFixtures.integration(syncedAt = now, lastSync = StravaFixtures.sync(StravaSyncStatus.QUEUED))
        val gateway = FakeGateway().apply { defaultRefresh = done(running) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertEquals(1 + 3, gateway.refreshCount)
        assertTrue(vm.uiState.value.syncing)
        assertFalse(vm.uiState.value.canSync)
    }

    @Test
    fun `sin red la clave se conserva y el reintento no duplica la corrida`() = runTest {
        val gateway = FakeGateway().apply { defaultRefresh = done(active) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.sync()
        advanceUntilIdle()
        assertEquals(StravaProblem.NO_CONNECTION, vm.uiState.value.problem)
        assertEquals(StravaAction.SYNC, vm.uiState.value.failedAction)

        gateway.syncs += StravaOutcome.Failed(StravaFailure.UNAVAILABLE)
        vm.retry()
        advanceUntilIdle()
        assertEquals(StravaProblem.UNAVAILABLE, vm.uiState.value.problem)

        gateway.syncs += StravaOutcome.Done(StravaSyncAcceptance.Accepted(StravaSyncStatus.QUEUED))
        vm.retry()
        advanceUntilIdle()

        gateway.syncs += StravaOutcome.Done(StravaSyncAcceptance.Accepted(StravaSyncStatus.QUEUED))
        vm.sync()
        advanceUntilIdle()
        assertEquals(listOf("clave-1", "clave-1", "clave-1", "clave-2"), gateway.syncKeys)
    }

    @Test
    fun `una respuesta definitiva descarta la clave`() = runTest {
        val gateway = FakeGateway().apply { defaultRefresh = done(active) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        gateway.syncs += StravaOutcome.Failed(StravaFailure.INVALID_RESPONSE)
        vm.sync()
        advanceUntilIdle()
        assertEquals(StravaProblem.INVALID_RESPONSE, vm.uiState.value.problem)
        gateway.syncs += StravaOutcome.Done(StravaSyncAcceptance.Accepted(StravaSyncStatus.QUEUED))
        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf("clave-1", "clave-2"), gateway.syncKeys)
    }

    @Test
    fun `una sincronizacion en curso o una cuenta no vinculada se informan (409)`() = runTest {
        val gateway = FakeGateway().apply { defaultRefresh = done(active) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        gateway.syncs += StravaOutcome.Done(StravaSyncAcceptance.InProgress)
        vm.sync()
        advanceUntilIdle()
        assertEquals(StravaNotice.SYNC_ALREADY_RUNNING, vm.uiState.value.notice)

        gateway.defaultRefresh = done(StravaFixtures.integration(status = StravaLinkStatus.EXPIRED, syncedAt = now))
        gateway.syncs += StravaOutcome.Done(StravaSyncAcceptance.NotLinked)
        vm.sync()
        advanceUntilIdle()
        assertEquals(StravaNotice.SYNC_NOT_LINKED, vm.uiState.value.notice)
        assertEquals(StravaLinkStatus.EXPIRED, vm.uiState.value.status)
        assertTrue(vm.uiState.value.canLink)
    }

    @Test
    fun `no se envian solicitudes duplicadas mientras una esta en curso`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway().apply {
            defaultRefresh = done(active)
            syncGate = gate
            syncs += StravaOutcome.Done(StravaSyncAcceptance.Accepted(StravaSyncStatus.QUEUED))
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.sync()
        runCurrent()
        vm.sync()
        vm.unlink()
        vm.requestLink()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.syncKeys.size)
        assertEquals(0, gateway.unlinkCount)
        assertEquals(0, gateway.authorizationCount)
    }

    @Test
    fun `una corrida fallida se muestra como recuperable y permite volver a sincronizar (CA6)`() = runTest {
        val failed = StravaFixtures.integration(
            syncedAt = now,
            lastSync = StravaFixtures.sync(StravaSyncStatus.RATE_LIMITED, com.lfnj.sport_app.features.integrations.model.StravaSyncError.RATE_LIMITED),
        )
        val vm = viewModel(FakeGateway().apply { refreshes += done(failed) })
        advanceUntilIdle()

        assertEquals(StravaSyncStatus.RATE_LIMITED, vm.uiState.value.integration?.lastSync?.status)
        assertFalse(vm.uiState.value.syncing)
        assertTrue(vm.uiState.value.canSync)
    }

    // ----------------------------------------------------------------- desvinculación (CA9)

    @Test
    fun `desvincular confirma con el BFF y muestra el estado revocado (CA9)`() = runTest {
        val revoked = StravaFixtures.integration(status = StravaLinkStatus.REVOKED, syncedAt = now)
        val gateway = FakeGateway().apply {
            refreshes += done(active)
            unlinks += StravaOutcome.Done(Unit)
            refreshes += done(revoked)
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.unlink()
        assertEquals(StravaAction.UNLINK, vm.uiState.value.busy)
        advanceUntilIdle()

        assertEquals(1, gateway.unlinkCount)
        assertEquals(StravaNotice.UNLINKED, vm.uiState.value.notice)
        assertEquals(StravaLinkStatus.REVOKED, vm.uiState.value.status)
        assertFalse(vm.uiState.value.canSync)
        assertTrue(vm.uiState.value.canLink)
    }

    @Test
    fun `una desvinculacion sin red se informa y se puede reintentar`() = runTest {
        val gateway = FakeGateway().apply { defaultRefresh = done(active) }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.unlink()
        advanceUntilIdle()
        assertEquals(StravaProblem.NO_CONNECTION, vm.uiState.value.problem)
        assertEquals(StravaAction.UNLINK, vm.uiState.value.failedAction)
        assertEquals(StravaLinkStatus.ACTIVE, vm.uiState.value.status)

        gateway.unlinks += StravaOutcome.Done(Unit)
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, gateway.unlinkCount)
        assertEquals(StravaNotice.UNLINKED, vm.uiState.value.notice)
    }

    @Test
    fun `un fallo inesperado del almacenamiento se informa sin cerrar la pantalla`() = runTest {
        val gateway = object : StravaGateway by FakeGateway() {
            override suspend fun refresh(owner: String): StravaOutcome<StravaIntegration> = error("disco")
        }
        val vm = StravaViewModel("owner", gateway, links, clock)
        advanceUntilIdle()

        assertEquals(StravaProblem.STORAGE, vm.uiState.value.problem)
    }
}
