package com.lfnj.sport_app.features.nutrition.reminders.engine

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.data.SessionRemindersRepository
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionReminderConfigEntity
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersCodec
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemorySessionRemindersDao
import com.lfnj.sport_app.testing.RecordingReminderNotifier
import com.lfnj.sport_app.testing.SessionRemindersFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Motor local de HU038 con tiempo virtual: el reloj de la sesión avanza con el planificador de
 * pruebas, de modo que una sesión de 70 minutos se recorre sin esperar. Sin Android ni red.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionReminderEngineTest {
    private val owner = TrainingOwner.keyOf(TrainingFixtures.USER_ID)
    private val dao = InMemorySessionRemindersDao()
    private val codec = SessionRemindersCodec(FakeCipher(), BffMobileClient.json)
    private val store = SessionRemindersRepository(
        dao, codec, api = null, sessionManager = SessionManager(null, FakeTokenStore(), BffMobileClient.json), json = BffMobileClient.json,
    )
    private val notifier = RecordingReminderNotifier()
    private val banners = ReminderBannerCenter()
    private val allowed = MutableStateFlow(true)
    private var refreshes = 0

    /** Reloj de la sesión ligado al tiempo virtual: pared y monotónico avanzan juntos. */
    private class SchedulerClock(private val scheduler: TestCoroutineScheduler) : TrainingClock {
        override fun now(): Instant = START.plusMillis(scheduler.currentTime)
        override fun elapsedRealtimeMillis(): Long = scheduler.currentTime
    }

    private fun TestScope.engine() = SessionReminderEngine(
        configs = store::observe,
        allowed = allowed,
        deliveries = store,
        notifier = notifier,
        banners = banners,
        clock = SchedulerClock(testScheduler),
        refresh = { refreshes++ },
    )

    private val session = ActiveSession(owner, SessionRemindersFixtures.SESSION_ID, START, monotonicStartMillis = 0)

    private suspend fun storeConfig(preferences: ReminderPreferences = SessionRemindersFixtures.DEFAULTS) =
        dao.replaceConfig(SessionReminderConfigEntity(owner, codec.encodeConfig(SessionRemindersFixtures.config(preferences))))

    private fun minutes(value: Long) = value * 60_000

    private suspend fun recorded() = store.deliveries(owner, SessionRemindersFixtures.SESSION_ID)

    @Test
    fun `sesion de 70 minutos con valores por defecto genera cuatro hidrataciones y ninguna alimentacion (CA1)`() = runTest {
        storeConfig()
        val job = launch { engine().run(session) }

        testScheduler.advanceTimeBy(minutes(70))
        testScheduler.runCurrent()
        job.cancel()

        assertEquals(listOf(1, 2, 3, 4), notifier.posted.map { it.n })
        assertTrue(notifier.posted.all { it.type == ReminderType.HYDRATION })
        assertTrue(notifier.posted.all { it.message == SessionRemindersFixtures.HYDRATION_MESSAGE && it.disclaimer == SessionRemindersFixtures.DISCLAIMER })
        assertEquals(listOf(15L, 30L, 45L, 60L).map(::minutes), notifier.posted.map { it.dueAtElapsedMillis })
        assertEquals(1, refreshes)
    }

    @Test
    fun `cada recordatorio se presenta en menos de 3 s de su momento y registra delivered_at (CA2)`() = runTest {
        storeConfig()
        val job = launch { engine().run(session) }

        testScheduler.advanceTimeBy(minutes(15) - 1)
        testScheduler.runCurrent()
        assertNull(banners.current.value)
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        job.cancel()

        val banner = assertNotNullValue(banners.current.value)
        assertEquals(ReminderType.HYDRATION, banner.type)
        assertEquals(1, banner.n)
        assertTrue(banner.systemNotificationShown)
        val delivery = recorded().single()
        val lateness = delivery.deliveredAt.toEpochMilli() - START.plusMillis(minutes(15)).toEpochMilli()
        assertTrue("entregado $lateness ms después", lateness in 0..2_999)
        assertEquals(ReminderPresentation.NOTIFICATION_AND_BANNER, delivery.presentation)
    }

    @Test
    fun `un reinicio del servicio no repite ocurrencias ya presentadas (CA3)`() = runTest {
        storeConfig()
        val first = launch { engine().run(session) }
        testScheduler.advanceTimeBy(minutes(32))
        testScheduler.runCurrent()
        first.cancel()
        assertEquals(listOf(1, 2), notifier.posted.map { it.n })

        // Nuevo motor (proceso reiniciado) sobre el mismo registro local, reconstruyendo el tiempo desde started_at.
        val restarted = launch { engine().run(session.copy(monotonicStartMillis = null)) }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(minutes(40))
        testScheduler.runCurrent()
        restarted.cancel()

        assertEquals(listOf(1, 2, 3, 4), notifier.posted.map { it.n })
        assertEquals(listOf(1, 2, 3, 4), recorded().map { it.n })
    }

    @Test
    fun `tras un reinicio tardio solo se presenta la ocurrencia vigente, sin las vencidas`() = runTest {
        storeConfig()
        testScheduler.advanceTimeBy(minutes(52))
        val job = launch { engine().run(session) }
        testScheduler.runCurrent()
        job.cancel()

        assertEquals(listOf(3), notifier.posted.map { it.n })
    }

    @Test
    fun `con las notificaciones del sistema deshabilitadas solo hay banner y lo declara (CA4)`() = runTest {
        notifier.status = SystemNotificationsStatus.PERMISSION_REQUIRED
        storeConfig()
        val job = launch { engine().run(session) }
        testScheduler.advanceTimeBy(minutes(16))
        testScheduler.runCurrent()
        job.cancel()

        assertTrue(notifier.posted.isEmpty())
        // El banner se retira al terminar la sesión; el registro conserva la forma de presentación.
        assertEquals(ReminderPresentation.BANNER_ONLY, recorded().single().presentation)
    }

    @Test
    fun `el banner declara que es el unico aviso cuando el sistema las bloquea (CA4)`() = runTest {
        notifier.status = SystemNotificationsStatus.DISABLED
        storeConfig()
        val job = launch { engine().run(session) }
        testScheduler.advanceTimeBy(minutes(16))
        testScheduler.runCurrent()

        val banner = assertNotNullValue(banners.current.value)
        assertFalse(banner.systemNotificationShown)
        assertEquals(SessionRemindersFixtures.HYDRATION_MESSAGE, banner.message)
        job.cancel()
        testScheduler.runCurrent()
        assertNull("el fin de la sesión retira su banner", banners.current.value)
    }

    @Test
    fun `un tipo deshabilitado en las preferencias no genera recordatorios (CA4)`() = runTest {
        storeConfig(ReminderPreferences(ReminderSetting(false, 15), ReminderSetting(true, 45), 3))
        val job = launch { engine().run(session) }
        testScheduler.advanceTimeBy(minutes(140))
        testScheduler.runCurrent()
        job.cancel()

        assertEquals(listOf(ReminderType.NUTRITION to 2, ReminderType.NUTRITION to 3), notifier.posted.map { it.type to it.n })
        assertTrue(notifier.posted.all { it.message == SessionRemindersFixtures.NUTRITION_MESSAGE })
    }

    @Test
    fun `sin ruleset descargado nunca se produce recordatorio (CA5)`() = runTest {
        val job = launch { engine().run(session) }
        testScheduler.advanceTimeBy(minutes(120))
        testScheduler.runCurrent()
        job.cancel()

        assertTrue(notifier.posted.isEmpty())
        assertNull(banners.current.value)
        assertTrue(recorded().isEmpty())
    }

    @Test
    fun `la configuracion descargada durante la sesion empieza a evaluarse sin reiniciar`() = runTest {
        val job = launch { engine().run(session) }
        testScheduler.advanceTimeBy(minutes(20))
        testScheduler.runCurrent()
        assertTrue(notifier.posted.isEmpty())

        storeConfig()
        testScheduler.advanceTimeBy(minutes(11))
        testScheduler.runCurrent()
        job.cancel()

        // Al llegar la configuración se presenta la ocurrencia vigente (15 min) y luego la de 30 min.
        assertEquals(listOf(1, 2), notifier.posted.map { it.n })
    }

    @Test
    fun `sin la capacidad vigente el motor se detiene`() = runTest {
        storeConfig()
        allowed.value = false
        val job = launch { engine().run(session) }
        testScheduler.advanceTimeBy(minutes(31))
        testScheduler.runCurrent()
        job.cancel()

        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `al iniciar se conserva solo el registro de la sesion en curso`() = runTest {
        store.claim(owner, "otra-sesion", ReminderType.HYDRATION, 1, START, ReminderPresentation.BANNER_ONLY)
        storeConfig()
        val job = launch { engine().run(session) }
        testScheduler.runCurrent()
        job.cancel()

        assertTrue(store.deliveries(owner, "otra-sesion").isEmpty())
    }

    @Test
    fun `una falla del almacenamiento no presenta la ocurrencia ni detiene el motor`() = runTest {
        storeConfig()
        dao.failWrites = true
        val job = launch { engine().run(session) }
        testScheduler.advanceTimeBy(minutes(16))
        testScheduler.runCurrent()
        assertTrue(notifier.posted.isEmpty())

        dao.failWrites = false
        testScheduler.advanceTimeBy(minutes(15))
        testScheduler.runCurrent()
        job.cancel()
        // Al recuperarse el almacenamiento se presenta una sola vez la ocurrencia vigente pendiente y luego la siguiente.
        assertEquals(listOf(1, 2), notifier.posted.map { it.n })
    }

    private fun <T> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        val START: Instant = Instant.parse("2026-10-26T13:00:00Z")
    }
}
