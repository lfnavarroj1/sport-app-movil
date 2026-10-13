package com.lfnj.sport_app.features.training.session

import com.lfnj.sport_app.features.training.session.data.FinishResult
import com.lfnj.sport_app.features.training.session.data.RemoteCheck
import com.lfnj.sport_app.features.training.session.data.StartResult
import com.lfnj.sport_app.features.training.session.data.TrainingSessionGateway
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingSessionDetails
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSessionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun session(
        id: String,
        state: SyncState = SyncState.LOCAL,
        finished: Boolean = false,
        failure: SyncFailure? = null,
    ) = LocalTrainingSession(
        sessionId = id,
        syncState = state,
        failure = failure,
        details = TrainingSessionDetails(
            sport = TrainingSport.RUNNING,
            planId = null,
            startedAt = TrainingFixtures.START,
            endedAt = if (finished) TrainingFixtures.START.plusSeconds(600) else null,
            officialDurationSeconds = null,
        ),
    )

    private inner class FakeGateway : TrainingSessionGateway {
        val sessions = MutableStateFlow<List<LocalTrainingSession>>(emptyList())
        var startResult: StartResult = StartResult.Started("s-1")
        var remote = RemoteCheck.CHECKED
        var failObserve = false
        var gate: CompletableDeferred<Unit>? = null
        val started = mutableListOf<TrainingSport>()
        val finished = mutableListOf<String>()
        val dismissed = mutableListOf<String>()

        override fun observe(owner: String): Flow<List<LocalTrainingSession>> =
            if (failObserve) flow { throw IllegalStateException("base dañada") } else sessions

        override fun monotonicStartMillis(sessionId: String): Long? = if (sessionId == "s-1") 1_234L else null

        override suspend fun start(owner: String, sport: TrainingSport): StartResult {
            gate?.await()
            started += sport
            if (startResult is StartResult.Started) sessions.value = listOf(session("s-1")) + sessions.value
            return startResult
        }

        override suspend fun finish(owner: String, sessionId: String): FinishResult {
            gate?.await()
            finished += sessionId
            sessions.value = sessions.value.map { if (it.sessionId == sessionId) session(sessionId, finished = true) else it }
            return FinishResult.FINISHED
        }

        override suspend fun dismiss(owner: String, sessionId: String): Boolean {
            dismissed += sessionId
            return true
        }

        override suspend fun checkRemoteActive(owner: String): RemoteCheck = remote
    }

    private val gateway = FakeGateway()

    @Test
    fun `sin registros muestra el estado vacio para iniciar`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TrainingSessionViewModel("owner", gateway)
        assertTrue(vm.uiState.value.loading)

        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertNull(state.current)
        assertTrue(state.records.isEmpty())
        assertEquals(RemoteCheck.CHECKED, state.remoteCheck)
    }

    @Test
    fun `iniciar exige seleccionar un deporte`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TrainingSessionViewModel("owner", gateway)
        advanceUntilIdle()

        vm.start()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sportMissing)
        assertTrue(gateway.started.isEmpty())
        vm.selectSport(TrainingSport.CYCLING)
        assertFalse(vm.uiState.value.sportMissing)
    }

    @Test
    fun `CA6 iniciar registra una sola vez aunque se toque repetidamente y muestra el cronometro`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TrainingSessionViewModel("owner", gateway)
        advanceUntilIdle()
        gateway.gate = CompletableDeferred()
        vm.selectSport(TrainingSport.SWIMMING)

        vm.start()
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.busy)
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(TrainingSport.SWIMMING), gateway.started)
        assertFalse(state.busy)
        assertEquals("s-1", state.current?.sessionId)
        assertEquals(1_234L, state.monotonicStartMillis)
        assertEquals(SyncState.LOCAL, state.current?.syncState)
    }

    @Test
    fun `las fallas locales al iniciar se informan`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TrainingSessionViewModel("owner", gateway)
        advanceUntilIdle()
        vm.selectSport(TrainingSport.RUNNING)

        gateway.startResult = StartResult.StorageFailed
        vm.start()
        advanceUntilIdle()
        assertEquals(TrainingActionError.STORAGE, vm.uiState.value.actionError)

        gateway.startResult = StartResult.AlreadyOpen
        vm.start()
        advanceUntilIdle()
        assertEquals(TrainingActionError.ALREADY_OPEN, vm.uiState.value.actionError)
    }

    @Test
    fun `sin la capacidad vigente no se inicia`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TrainingSessionViewModel("owner", gateway, canStart = false)
        advanceUntilIdle()
        vm.selectSport(TrainingSport.RUNNING)

        vm.start()
        advanceUntilIdle()

        assertTrue(gateway.started.isEmpty())
    }

    @Test
    fun `CA2 finalizar la sesion abierta una sola vez y pasarla a los registros`() = runTest(mainDispatcherRule.dispatcher) {
        gateway.sessions.value = listOf(session("s-1"))
        val vm = TrainingSessionViewModel("owner", gateway)
        advanceUntilIdle()
        gateway.gate = CompletableDeferred()

        vm.finish()
        vm.finish()
        gateway.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("s-1"), gateway.finished)
        assertNull(vm.uiState.value.current)
        assertEquals(listOf("s-1"), vm.uiState.value.records.map { it.sessionId })
    }

    @Test
    fun `una sesion rechazada sin finalizar va a los registros y permite iniciar otra`() = runTest(mainDispatcherRule.dispatcher) {
        gateway.sessions.value = listOf(
            session("s-2", SyncState.SYNC_FAILED, failure = SyncFailure.ACTIVE_SESSION_EXISTS),
            session("s-0", SyncState.SYNCED, finished = true),
        )
        val vm = TrainingSessionViewModel("owner", gateway)
        advanceUntilIdle()

        assertNull(vm.uiState.value.current)
        assertEquals(listOf("s-2", "s-0"), vm.uiState.value.records.map { it.sessionId })
        vm.dismiss("s-2")
        advanceUntilIdle()
        assertEquals(listOf("s-2"), gateway.dismissed)
    }

    @Test
    fun `sesion de la aplicacion vencida al consultar la sesion remota lleva al acceso`() = runTest(mainDispatcherRule.dispatcher) {
        gateway.remote = RemoteCheck.SIGNED_OUT
        val vm = TrainingSessionViewModel("owner", gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }

    @Test
    fun `una falla de la fuente local se informa sin quedar cargando`() = runTest(mainDispatcherRule.dispatcher) {
        gateway.failObserve = true
        val vm = TrainingSessionViewModel("owner", gateway)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.loading)
        assertEquals(TrainingActionError.STORAGE, vm.uiState.value.actionError)
    }
}
