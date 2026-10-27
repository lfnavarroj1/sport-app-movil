package com.lfnj.sport_app.e2e

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.MainActivity
import com.lfnj.sport_app.SportApp
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.features.nutrition.reminders.engine.AndroidReminderNotifier
import com.lfnj.sport_app.features.nutrition.reminders.engine.SessionReminderEngine
import com.lfnj.sport_app.features.nutrition.reminders.model.SessionRemindersAccess
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionDependencies
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionService
import com.lfnj.sport_app.features.training.session.foreground.AndroidActiveSessionServiceLauncher
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.testing.AcceleratedTrainingClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * E2E de HU038: aplicación real (MainActivity, navegación, ViewModels, Room y Keystore, servicio en
 * primer plano, notificaciones del sistema) → `bff-mobile` ≥ 0.16.0 → `training_management` en la
 * composición local. La sesión de HU032 se inicia y finaliza contra el BFF real; solo el reloj del
 * motor de recordatorios se acelera ×60 (argumento de instrumentación, nunca en producción), de modo
 * que 95 minutos de sesión duran unos 95 s. Precondición: un deportista `INTERMEDIATE` sintético con
 * `nutrition.session.reminders`. Se omite si no se reciben los argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu038IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eIntermediateEmail=<correo INTERMEDIATE> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu038IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context as SportApp).container
    private val args = InstrumentationRegistry.getArguments()
    private val clock = AcceleratedTrainingClock(SPEED)
    private val defaultDependencies = ActiveSessionService.dependencies

    @Before
    fun setUp() {
        assumeTrue(
            "E2E omitido: faltan la cuenta INTERMEDIATE sintética o la contraseña",
            listOf("e2ePassword", "e2eIntermediateEmail").all { args.getString(it) != null },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
        // Aplicación real; solo el motor que corre en el servicio usa el reloj acelerado de la prueba.
        val app = container
        val engine = SessionReminderEngine(
            configs = app.sessionRemindersRepository::observe,
            allowed = app.sessionManager.access.map(SessionRemindersAccess::allowsLocalEvaluation),
            deliveries = app.sessionRemindersRepository,
            notifier = app.reminderNotifier,
            banners = app.reminderBanners,
            clock = clock,
            refresh = { owner -> app.sessionRemindersRepository.refresh(owner) },
        )
        ActiveSessionService.dependencies = { ActiveSessionDependencies(app.activeSessionTracker, listOf(engine)) }
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, ActiveSessionService::class.java))
        ActiveSessionService.dependencies = defaultDependencies
    }

    private fun arg(key: String): String = requireNotNull(args.getString(key)) { "Falta el argumento $key" }

    private fun login() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(arg("e2eIntermediateEmail"))
        composeRule.onNodeWithTag("login_password").performTextInput(arg("e2ePassword"))
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openMenu(tag: String, screen: String) {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(screen), TIMEOUT)
    }

    /** Lleva un intervalo a su mínimo con los controles reales de la pantalla. */
    private fun toMinimum(type: String) {
        while (composeRule.onAllNodes(hasTestTag("reminders_decrease_$type") and isEnabled()).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("reminders_decrease_$type").performScrollTo().performClick()
        }
    }

    /** Espera a que el BFF confirme la última acción de la sesión (HU032, WorkManager con red). */
    private fun awaitSynced(owner: String, sessionId: String, finished: Boolean = false) = composeRule.waitUntil(SYNC_TIMEOUT) {
        val local = runBlocking { container.trainingSessionRepository.observe(owner).first() }.firstOrNull { it.sessionId == sessionId }
        local?.syncState == SyncState.SYNCED && local.details?.finished == finished
    }

    private fun waitAccelerated(minutes: Long) = Thread.sleep(clock.realMillis(minutes * 60_000) + 1_500)

    @Test
    fun sesionRealConIntervalosMinimosPresentaLosRecordatoriosATiempoSinDuplicados() {
        ActivityScenario.launch(MainActivity::class.java).use {
            login()
            val owner = TrainingOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)

            // Reglas y preferencias reales: textos aprobados y rangos del ruleset; se fijan los mínimos.
            openMenu("menu_nutrition.session.reminders", "reminders_screen")
            composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_card_hydration"), TIMEOUT)
            composeRule.onNodeWithTag("reminders_message_hydration").assertTextContains("Hidrátate: bebe agua o bebida isotónica")
            composeRule.onNodeWithTag("reminders_message_nutrition").performScrollTo().assertTextContains("Consume un alimento ligero según tu plan")
            composeRule.onNodeWithTag("reminders_disclaimer").performScrollTo()
                .assertTextContains("Recordatorio general: no es un diagnóstico médico ni prescribe cantidades.")
            toMinimum("hydration")
            toMinimum("nutrition")
            if (composeRule.onAllNodes(hasTestTag("reminders_save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag("reminders_save").performScrollTo().performClick()
                composeRule.waitUntilExactlyOneExists(hasTestTag("reminders_save_outcome") and hasText("Preferencias guardadas."), TIMEOUT)
            }
            val config = checkNotNull(runBlocking { container.sessionRemindersRepository.observe(owner).first() })
            assertEquals(10, config.preferences.hydration.intervalMinutes)
            assertEquals(30, config.preferences.nutrition.intervalMinutes)
            composeRule.onNodeWithTag("reminders_back").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)

            // Sesión real de HU032, confirmada por el BFF.
            openMenu("menu_training.session.record", "training_session")
            composeRule.onNodeWithTag("sport_running").performClick()
            composeRule.onNodeWithTag("training_start").performScrollTo().performClick()
            val session = runBlocking { container.trainingSessionRepository.observe(owner).first { list -> list.any { s -> s.open } } }.first { it.open }
            clock.anchor(checkNotNull(container.trainingSessionRepository.monotonicStartMillis(session.sessionId)))
            // WorkManager de la aplicación confirma el inicio contra el BFF real.
            awaitSynced(owner, session.sessionId)
            val startedAt = checkNotNull(session.details).startedAt

            waitAccelerated(45)
            // Reinicio del servicio a mitad de la sesión: no debe repetir ocurrencias (CA3).
            context.stopService(Intent(context, ActiveSessionService::class.java))
            Thread.sleep(1_000)
            assertTrue(AndroidActiveSessionServiceLauncher(context).start())
            waitAccelerated(50)

            val elapsedMinutes = Duration.between(startedAt, clock.now()).toMinutes()
            val deliveries = runBlocking { container.sessionRemindersRepository.deliveries(owner, session.sessionId) }
            val hydration = deliveries.filter { it.type == ReminderType.HYDRATION }.map { it.n }.sorted()
            val nutrition = deliveries.filter { it.type == ReminderType.NUTRITION }.map { it.n }.sorted()
            assertTrue("sesión acelerada entre 90 y 120 min: $elapsedMinutes", elapsedMinutes in 92..118)
            // Cada 10 min, sin huecos ni repeticiones, hasta el tiempo transcurrido (±1 por el borde de la lectura).
            assertEquals((1..hydration.size).toList(), hydration)
            assertTrue("hidrataciones ${hydration.size} para $elapsedMinutes min", hydration.size.toLong() in (elapsedMinutes / 10 - 1)..(elapsedMinutes / 10))
            assertEquals("la alimentación solo aparece tras 60 min: primera a los 90", listOf(3), nutrition)
            deliveries.forEach { delivery ->
                val interval = if (delivery.type == ReminderType.HYDRATION) 10L else 30L
                val due = startedAt.plusSeconds(delivery.n * interval * 60)
                val lateReal = clock.realMillis(delivery.deliveredAt.toEpochMilli() - due.toEpochMilli())
                assertTrue("${delivery.type} ${delivery.n} entregado $lateReal ms reales tarde (HA007)", lateReal < 3_000)
                assertEquals(ReminderPresentation.NOTIFICATION_AND_BANNER, delivery.presentation)
            }
            val tags = context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.id == AndroidReminderNotifier.NOTIFICATION_ID }.mapNotNull { it.tag }
            assertTrue("notificación local de la alimentación", "${session.sessionId}:NUTRITION:3" in tags)
            assertEquals("sin notificaciones duplicadas", tags.size, tags.toSet().size)
            composeRule.waitUntilExactlyOneExists(hasTestTag("reminder_banner"), TIMEOUT)

            // Fin real de la sesión: el servicio se detiene y la sesión queda confirmada.
            composeRule.onNodeWithTag("reminder_banner_dismiss").performClick()
            composeRule.onNodeWithTag("training_finish").performScrollTo().performClick()
            awaitSynced(owner, session.sessionId, finished = true)
            composeRule.waitUntil(TIMEOUT) { container.activeSessionTracker.active.value == null }
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SYNC_TIMEOUT = 60_000L
        const val SPEED = 60L
    }
}
