package com.lfnj.sport_app.features.training.session.foreground

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.core.config.AppConfig
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.ReminderBannerHost
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionReminderConfigEntity
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersCodec
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRule
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRuleset
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.training.session.data.StartResult
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.testing.AcceleratedTrainingClock
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import com.lfnj.sport_app.ui.theme.SportappTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * HU038 con el servicio en primer plano real, Room y Keystore reales y el motor con un reloj
 * acelerado ×120 (solo pruebas): una sesión de 70 min dura unos 35 s. Las notificaciones se
 * registran en memoria; el banner se dibuja con el composable real. Sin red: el motor usa la
 * configuración guardada (el BFF configurado no existe).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ActiveSessionServiceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = AcceleratedTrainingClock(SPEED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: TestAppContainer
    private val defaultDependencies = ActiveSessionService.dependencies
    private val owner = TrainingOwner.keyOf(USER_ID)

    @Before
    fun setUp() {
        container = TestAppContainer(AppConfig("http://127.0.0.1:9/", "test", "0.18.0", "0.1.0"), InMemoryTokenStore(), context)
        container.reminderClockOverride = clock
        // El servicio real usa el seguimiento y el motor del contenedor de prueba (reloj acelerado, bases en memoria).
        ActiveSessionService.dependencies = { ActiveSessionDependencies(container.activeSessionTracker, container.activeSessionTasks) }
        composeRule.setContent { SportappTheme { ReminderBannerHost(container.reminderBanners) } }
        runBlocking {
            val codec = SessionRemindersCodec(container.sessionRemindersCipher, BffMobileClient.json)
            container.sessionReminders.sessionRemindersDao().replaceConfig(SessionReminderConfigEntity(owner, codec.encodeConfig(CONFIG)))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.stopService(Intent(context, ActiveSessionService::class.java))
        ActiveSessionService.dependencies = defaultDependencies
        container.close()
    }

    /** Inicia una sesión real de HU032 en el dispositivo y la deja como sesión del puntero (proceso sin sesión de aplicación). */
    private fun startSession(): String = runBlocking {
        val started = container.trainingSessionRepository.start(owner, TrainingSport.RUNNING) as StartResult.Started
        clock.anchor(checkNotNull(container.trainingSessionRepository.monotonicStartMillis(started.sessionId)))
        container.pointer.write(owner, started.sessionId)
        container.activeSessionTracker.start(scope)
        started.sessionId
    }

    private fun waitForAccelerated(minutes: Long) = Thread.sleep(clock.realMillis(minutes * 60_000) + 2_000)

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean = (context.getSystemService(ActivityManager::class.java))
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == ActiveSessionService::class.java.name }

    private fun wakeLockHeld(): Boolean {
        val dump = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys power")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(dump).bufferedReader().use { it.readText() }
        // Solo la sección de wake locks vigentes, no el historial de adquisiciones.
        val current = text.substringAfter("Wake Locks: size=", "").substringBefore("\n\n")
        return current.contains("sportapp:training_session")
    }

    @Test
    fun sesionDe70MinutosAceleradaPresentaCuatroHidratacionesSinDuplicadosYLiberaElWakeLock() {
        val sessionId = startSession()
        composeRule.waitUntil(10_000) { serviceRunning() }
        assertTrue("el servicio mantiene el wake lock durante la sesión", wakeLockHeld())

        waitForAccelerated(70)

        val posted = container.recordingReminderNotifier.posted.toList()
        assertEquals(listOf(1, 2, 3, 4), posted.map { it.n })
        assertTrue(posted.all { it.type == ReminderType.HYDRATION && it.sessionId == sessionId })
        composeRule.onNodeWithTag("reminder_banner").assertExists()
        val deliveries = runBlocking { container.sessionRemindersRepository.deliveries(owner, sessionId) }
        assertEquals(listOf(1, 2, 3, 4), deliveries.map { it.n })
        assertTrue(deliveries.all { it.presentation == ReminderPresentation.NOTIFICATION_AND_BANNER })

        // HA007: cada entrega ocurrió en menos de 3 s reales de su momento.
        val started = runBlocking { container.trainingSessionRepository.observe(owner).first() }
            .single { it.sessionId == sessionId }.details!!.startedAt
        deliveries.forEach { delivery ->
            val due = started.plusSeconds(delivery.n * 15L * 60)
            val lateReal = clock.realMillis(delivery.deliveredAt.toEpochMilli() - due.toEpochMilli())
            assertTrue("ocurrencia ${delivery.n} entregada $lateReal ms reales tarde", lateReal < 3_000)
        }

        runBlocking { container.trainingSessionRepository.finish(owner, sessionId) }
        composeRule.waitUntil(10_000) { !serviceRunning() }
        assertFalse("el wake lock se libera al finalizar la sesión", wakeLockHeld())
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("reminder_banner")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun unReinicioDelServicioNoRepiteOcurrencias() {
        val sessionId = startSession()
        composeRule.waitUntil(10_000) { serviceRunning() }
        waitForAccelerated(32)
        assertEquals(listOf(1, 2), container.recordingReminderNotifier.posted.map { it.n })

        context.stopService(Intent(context, ActiveSessionService::class.java))
        composeRule.waitUntil(10_000) { !serviceRunning() }
        assertFalse("detener el servicio libera el wake lock", wakeLockHeld())
        assertTrue(AndroidActiveSessionServiceLauncher(context).start())
        composeRule.waitUntil(10_000) { serviceRunning() }
        waitForAccelerated(16)

        assertEquals(listOf(1, 2, 3), container.recordingReminderNotifier.posted.map { it.n })
        assertEquals(listOf(1, 2, 3), runBlocking { container.sessionRemindersRepository.deliveries(owner, sessionId) }.map { it.n })
        runBlocking { container.trainingSessionRepository.finish(owner, sessionId) }
    }

    @Test
    fun conLasNotificacionesDelSistemaDeshabilitadasSoloHayBannerYLoDeclara() {
        container.recordingReminderNotifier.status = SystemNotificationsStatus.DISABLED
        val sessionId = startSession()
        waitForAccelerated(16)

        assertTrue(container.recordingReminderNotifier.posted.isEmpty())
        composeRule.onNodeWithTag("reminder_banner").assertExists()
        composeRule.onNodeWithTag("reminder_banner_only").assertExists()
        val delivery = runBlocking { container.sessionRemindersRepository.deliveries(owner, sessionId) }.single()
        assertEquals(ReminderPresentation.BANNER_ONLY, delivery.presentation)
        runBlocking { container.trainingSessionRepository.finish(owner, sessionId) }
    }

    @Test
    fun sinRulesetDescargadoNoHayRecordatorios() {
        runBlocking { container.sessionReminders.sessionRemindersDao().deleteConfig(owner) }
        val sessionId = startSession()
        composeRule.waitUntil(10_000) { serviceRunning() }
        waitForAccelerated(31)

        assertTrue(container.recordingReminderNotifier.posted.isEmpty())
        assertTrue(composeRule.onAllNodes(hasTestTag("reminder_banner")).fetchSemanticsNodes().isEmpty())
        runBlocking { container.trainingSessionRepository.finish(owner, sessionId) }
    }

    private companion object {
        const val SPEED = 120L
        const val USER_ID = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        const val DISCLAIMER = "Recordatorio general: no es un diagnóstico médico ni prescribe cantidades."
        val CONFIG = ReminderConfig(
            ReminderRuleset(
                "session-reminders-2026-10-13.v1",
                listOf(
                    ReminderRule(ReminderType.HYDRATION, 15, 10, 60, 0, "Hidrátate: bebe agua o bebida isotónica", DISCLAIMER),
                    ReminderRule(ReminderType.NUTRITION, 45, 30, 120, 60, "Consume un alimento ligero según tu plan", DISCLAIMER),
                ),
            ),
            ReminderPreferences(ReminderSetting(true, 15), ReminderSetting(true, 45), 0),
            Instant.parse("2026-10-26T12:00:00Z"),
        )
    }
}
