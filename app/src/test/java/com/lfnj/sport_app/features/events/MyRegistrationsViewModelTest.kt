package com.lfnj.sport_app.features.events

import com.lfnj.sport_app.features.events.data.MyRegistrationsGateway
import com.lfnj.sport_app.features.events.data.RegistrationsRefresh
import com.lfnj.sport_app.features.events.model.RegistrationsSnapshot
import com.lfnj.sport_app.testing.EventRegistrationFixtures
import com.lfnj.sport_app.testing.EventRegistrationFixtures.OTHER_REGISTRATION_ID
import com.lfnj.sport_app.testing.EventRegistrationFixtures.REGISTRATION_ID
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** HU041: «Mis inscripciones» (carga, vacío, sin red con caché, `STALE`, permisos y páginas). */
@OptIn(ExperimentalCoroutinesApi::class)
class MyRegistrationsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-11-10T15:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("America/Bogota"))

    private class FakeGateway(initial: RegistrationsSnapshot? = null) : MyRegistrationsGateway {
        val cache = MutableStateFlow(initial)
        val responses = ArrayDeque<RegistrationsRefresh>()
        var onUpdate: () -> RegistrationsSnapshot? = { null }
        var refreshes = 0
        var loads = 0

        override fun observe(owner: String): Flow<RegistrationsSnapshot?> = cache

        override suspend fun refresh(owner: String): RegistrationsRefresh {
            refreshes++
            return respond()
        }

        override suspend fun loadMore(owner: String): RegistrationsRefresh {
            loads++
            return respond()
        }

        private fun respond(): RegistrationsRefresh {
            val result = responses.removeFirstOrNull() ?: RegistrationsRefresh.NoConnection
            if (result == RegistrationsRefresh.Updated) cache.value = onUpdate()
            return result
        }
    }

    private fun snapshot(syncedAt: Instant = now.minus(Duration.ofHours(1)), cursor: String? = null) = RegistrationsSnapshot(
        items = listOf(
            EventRegistrationFixtures.confirmed(OTHER_REGISTRATION_ID, startsAt = Instant.parse("2026-12-01T12:00:00Z")),
            EventRegistrationFixtures.confirmed(REGISTRATION_ID),
        ),
        nextCursor = cursor,
        syncedAt = syncedAt,
    )

    @Test
    fun `muestra la lista confirmada ordenada por fecha del evento (CA8)`() = runTest {
        val gateway = FakeGateway().apply {
            responses += RegistrationsRefresh.Updated
            onUpdate = { snapshot() }
        }
        val vm = MyRegistrationsViewModel("owner", gateway, clock)
        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()

        assertEquals(listOf(REGISTRATION_ID, OTHER_REGISTRATION_ID), vm.uiState.value.items.map { it.registrationId })
        assertFalse(vm.uiState.value.stale)
        assertNull(vm.uiState.value.problem)
    }

    @Test
    fun `sin inscripciones se muestra el estado vacio`() = runTest {
        val gateway = FakeGateway().apply {
            responses += RegistrationsRefresh.Updated
            onUpdate = { RegistrationsSnapshot(emptyList(), null, now) }
        }
        val vm = MyRegistrationsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.empty)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `sin red muestra la cache con su sincronizacion y la marca STALE a las 24 horas (CA10)`() = runTest {
        val gateway = FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(25))))
        val vm = MyRegistrationsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(RegistrationsProblem.NO_CONNECTION, state.problem)
        assertEquals(2, state.items.size)
        assertEquals(now.minus(Duration.ofHours(25)), state.snapshot?.syncedAt)
        assertTrue(state.stale)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `exactamente 24 horas todavia no es STALE`() = runTest {
        val vm = MyRegistrationsViewModel("owner", FakeGateway(snapshot(syncedAt = now.minus(Duration.ofHours(24)))), clock)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.stale)
    }

    @Test
    fun `sin red y sin cache nunca sincronizada`() = runTest {
        val vm = MyRegistrationsViewModel("owner", FakeGateway(), clock)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.neverSynced)
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `sin permiso no se muestran datos guardados`() = runTest {
        val gateway = FakeGateway(snapshot()).apply { responses += RegistrationsRefresh.PermissionRequired }
        val vm = MyRegistrationsViewModel("owner", gateway, clock)
        advanceUntilIdle()

        assertEquals(RegistrationsProblem.PERMISSION_REQUIRED, vm.uiState.value.problem)
        assertNull(vm.uiState.value.snapshot)
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `carga la pagina siguiente solo si hay cursor`() = runTest {
        val gateway = FakeGateway(snapshot(cursor = "c2")).apply {
            responses += RegistrationsRefresh.Updated
            onUpdate = { snapshot(cursor = "c2") }
        }
        val vm = MyRegistrationsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canLoadMore)

        gateway.responses += RegistrationsRefresh.Updated
        gateway.onUpdate = { snapshot(cursor = null) }
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(1, gateway.loads)
        assertFalse(vm.uiState.value.canLoadMore)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(1, gateway.loads)
    }

    @Test
    fun `errores y reintento`() = runTest {
        val gateway = FakeGateway(snapshot()).apply {
            responses += RegistrationsRefresh.Unavailable(null)
            responses += RegistrationsRefresh.StorageFailed
            responses += RegistrationsRefresh.InvalidResponse
            responses += RegistrationsRefresh.InvalidRequest(listOf("invalid_cursor"))
            responses += RegistrationsRefresh.SignedOut
        }
        val vm = MyRegistrationsViewModel("owner", gateway, clock)
        advanceUntilIdle()
        val problems = mutableListOf(vm.uiState.value.problem)
        repeat(3) {
            vm.retry(); advanceUntilIdle()
            problems += vm.uiState.value.problem
        }
        vm.retry(); advanceUntilIdle()

        assertEquals(
            listOf(RegistrationsProblem.UNAVAILABLE, RegistrationsProblem.STORAGE, RegistrationsProblem.INVALID_RESPONSE, RegistrationsProblem.INVALID_REQUEST),
            problems,
        )
        assertEquals(2, vm.uiState.value.items.size)
        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }
}
