package com.lfnj.sport_app.features.notifications

import com.lfnj.sport_app.features.notifications.data.InboxGateway
import com.lfnj.sport_app.features.notifications.data.InboxRefresh
import com.lfnj.sport_app.features.notifications.data.MarkReadResult
import com.lfnj.sport_app.features.notifications.model.InboxSnapshot
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.testing.MainDispatcherRule
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.NotificationFixtures
import com.lfnj.sport_app.testing.NotificationFixtures.NOTIFICATION_ID
import com.lfnj.sport_app.testing.NotificationFixtures.OTHER_NOTIFICATION_ID
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
import java.time.Instant
import java.time.ZoneId

/** HU039: bandeja (carga, vacío, sin red con caché, error, reintento, leída/no leída y sistema deshabilitado). */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsInboxViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-10-25T15:00:00Z")
    private val clock = MutableClock(now, ZoneId.of("America/Bogota"))
    private var systemStatus = SystemNotificationsStatus.ENABLED

    private class FakeGateway(initial: InboxSnapshot? = null) : InboxGateway {
        val cache = MutableStateFlow(initial)
        val responses = ArrayDeque<InboxRefresh>()
        val readResults = ArrayDeque<MarkReadResult>()
        var onUpdate: () -> InboxSnapshot? = { null }
        var refreshes = 0
        var loads = 0
        val marked = mutableListOf<String>()

        override fun observe(owner: String): Flow<InboxSnapshot?> = cache

        override suspend fun refresh(owner: String): InboxRefresh {
            refreshes++
            return respond()
        }

        override suspend fun loadMore(owner: String): InboxRefresh {
            loads++
            return respond()
        }

        override suspend fun markRead(owner: String, notificationId: String): MarkReadResult {
            marked += notificationId
            return readResults.removeFirstOrNull() ?: MarkReadResult.NoConnection
        }

        private fun respond(): InboxRefresh {
            val result = responses.removeFirstOrNull() ?: InboxRefresh.NoConnection
            if (result == InboxRefresh.Updated) cache.value = onUpdate()
            return result
        }
    }

    private fun snapshot(vararg items: com.lfnj.sport_app.features.notifications.model.InboxNotification, cursor: String? = null) =
        InboxSnapshot(items.toList(), cursor, now.minusSeconds(600))

    private fun viewModel(gateway: FakeGateway) = NotificationsInboxViewModel("owner", gateway, { systemStatus }, clock)

    @Test
    fun `al abrir muestra carga y luego la bandeja confirmada mas reciente primero`() = runTest {
        val gateway = FakeGateway().apply {
            responses += InboxRefresh.Updated
            onUpdate = {
                snapshot(
                    NotificationFixtures.notification(NOTIFICATION_ID, createdAt = Instant.parse("2026-10-25T13:00:00Z")),
                    NotificationFixtures.notification(OTHER_NOTIFICATION_ID, createdAt = Instant.parse("2026-10-25T14:00:00Z"), readAt = now),
                )
            }
        }
        val vm = viewModel(gateway)
        assertTrue(vm.uiState.value.loading)

        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf(OTHER_NOTIFICATION_ID, NOTIFICATION_ID), state.items.map { it.notificationId })
        assertEquals(1, state.unreadCount)
        assertNull(state.problem)
        assertEquals(1, gateway.refreshes)
    }

    @Test
    fun `una bandeja confirmada sin elementos es vacia y no error`() = runTest {
        val gateway = FakeGateway().apply {
            responses += InboxRefresh.Updated
            onUpdate = { snapshot() }
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.empty)
        assertNull(vm.uiState.value.problem)
    }

    @Test
    fun `sin red conserva la bandeja cacheada y lo declara`() = runTest {
        val gateway = FakeGateway(snapshot(NotificationFixtures.notification())).apply { responses += InboxRefresh.NoConnection }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(InboxProblem.NO_CONNECTION, state.problem)
        assertEquals(1, state.items.size)
        assertFalse(state.neverSynced)
    }

    @Test
    fun `sin red y sin bandeja guardada se declara nunca sincronizada y permite reintentar`() = runTest {
        val gateway = FakeGateway().apply {
            responses += InboxRefresh.NoConnection
            responses += InboxRefresh.Updated
            onUpdate = { snapshot(NotificationFixtures.notification()) }
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.neverSynced)

        vm.retry()
        advanceUntilIdle()

        assertNull(vm.uiState.value.problem)
        assertEquals(1, vm.uiState.value.items.size)
        assertEquals(2, gateway.refreshes)
    }

    @Test
    fun `un error del servicio sin cache muestra error y sin permiso oculta la cache`() = runTest {
        val unavailable = FakeGateway().apply { responses += InboxRefresh.Unavailable(null) }
        val vm = viewModel(unavailable)
        advanceUntilIdle()
        assertEquals(InboxProblem.UNAVAILABLE, vm.uiState.value.problem)
        assertNull(vm.uiState.value.snapshot)

        val forbidden = FakeGateway(snapshot(NotificationFixtures.notification())).apply { responses += InboxRefresh.PermissionRequired }
        val vm2 = viewModel(forbidden)
        advanceUntilIdle()
        assertEquals(InboxProblem.PERMISSION_REQUIRED, vm2.uiState.value.problem)
        assertNull(vm2.uiState.value.snapshot)
    }

    @Test
    fun `sesion vencida pide volver al acceso`() = runTest {
        val gateway = FakeGateway().apply { responses += InboxRefresh.SignedOut }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.sessionExpired)
        vm.onSessionExpiredHandled()
        assertFalse(vm.uiState.value.sessionExpired)
    }

    @Test
    fun `abrir una no leida envia la lectura una vez y una leida no`() = runTest {
        val unread = NotificationFixtures.notification()
        val read = NotificationFixtures.notification(OTHER_NOTIFICATION_ID, readAt = now)
        val gateway = FakeGateway(snapshot(unread, read)).apply {
            responses += InboxRefresh.NoConnection
            readResults += MarkReadResult.Marked(unread.copy(readAt = now))
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.open(unread)
        vm.open(unread)
        vm.open(read)
        advanceUntilIdle()

        assertEquals(listOf(NOTIFICATION_ID), gateway.marked)
        assertNull(vm.uiState.value.markReadProblem)
        assertTrue(vm.uiState.value.marking.isEmpty())
    }

    @Test
    fun `si la lectura falla sin red se informa y la notificacion sigue sin leer`() = runTest {
        val unread = NotificationFixtures.notification()
        val gateway = FakeGateway(snapshot(unread)).apply {
            responses += InboxRefresh.NoConnection
            readResults += MarkReadResult.NoConnection
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()

        vm.open(unread)
        advanceUntilIdle()

        assertEquals(MarkReadProblem.NO_CONNECTION, vm.uiState.value.markReadProblem)
        assertTrue(vm.uiState.value.items.single().unread)
        vm.dismissMarkReadProblem()
        assertNull(vm.uiState.value.markReadProblem)
    }

    @Test
    fun `refleja las notificaciones del sistema deshabilitadas y su cambio al volver`() = runTest {
        systemStatus = SystemNotificationsStatus.PERMISSION_REQUIRED
        val vm = viewModel(FakeGateway())
        assertEquals(SystemNotificationsStatus.PERMISSION_REQUIRED, vm.uiState.value.systemNotifications)

        systemStatus = SystemNotificationsStatus.ENABLED
        vm.refreshSystemStatus()

        assertEquals(SystemNotificationsStatus.ENABLED, vm.uiState.value.systemNotifications)
    }

    @Test
    fun `cargar mas solo con cursor y sin consulta en curso`() = runTest {
        val gateway = FakeGateway(snapshot(NotificationFixtures.notification(), cursor = "c2")).apply {
            responses += InboxRefresh.NoConnection
            responses += InboxRefresh.Updated
            onUpdate = { snapshot(NotificationFixtures.notification(), NotificationFixtures.notification(OTHER_NOTIFICATION_ID)) }
        }
        val vm = viewModel(gateway)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canLoadMore)

        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(1, gateway.loads)
        assertEquals(2, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.canLoadMore)
    }
}
