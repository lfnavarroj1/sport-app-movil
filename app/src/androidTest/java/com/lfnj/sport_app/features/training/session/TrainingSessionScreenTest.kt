package com.lfnj.sport_app.features.training.session

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.training.session.data.RemoteCheck
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.SyncState
import com.lfnj.sport_app.features.training.session.model.TrainingSessionDetails
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** HU032: estados de la pantalla `/entrenamientos/sesion`, sincronización visible y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class TrainingSessionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val start = Instant.parse("2026-10-12T12:30:00Z")

    /** Reloj fijo: el cronómetro muestra 1 min 5 s desde la marca monotónica 10 000. */
    private val clock = object : TrainingClock {
        override fun now(): Instant = start.plusSeconds(3_725)
        override fun elapsedRealtimeMillis(): Long = 75_000L
    }

    private fun session(
        id: String,
        state: SyncState,
        finished: Boolean = false,
        official: Long? = null,
        failure: SyncFailure? = null,
        sport: TrainingSport = TrainingSport.RUNNING,
    ) = LocalTrainingSession(
        id, state, failure,
        TrainingSessionDetails(sport, null, start, if (finished) start.plusSeconds(2_700) else null, official),
    )

    private val events = mutableListOf<String>()

    private fun show(state: TrainingSessionUiState) = composeRule.setContent {
        SportappTheme {
            TrainingSessionScreen(
                state = state,
                onSelectSport = { events += "sport:${it.wire}" },
                onStart = { events += "start" },
                onFinish = { events += "finish" },
                onDismiss = { events += "dismiss:$it" },
                onBack = { events += "back" },
                clock = clock,
            )
        }
    }

    @Test
    fun cargaMuestraProgreso() {
        show(TrainingSessionUiState(loading = true))

        composeRule.onNodeWithTag("training_loading").assertIsDisplayed()
    }

    @Test
    fun sinRegistrosOfreceIniciarYEstadoVacio() {
        show(TrainingSessionUiState(loading = false))

        composeRule.onNodeWithText("Nueva sesión").assertIsDisplayed()
        composeRule.onNodeWithTag("training_records_empty").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("training_start").assertIsEnabled()
    }

    @Test
    fun losDeportesSonUnGrupoDeOpcionesAccesible() {
        show(TrainingSessionUiState(loading = false, selectedSport = TrainingSport.CYCLING))

        TrainingSport.entries.forEach {
            composeRule.onNodeWithTag("sport_${it.wire}").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        composeRule.onNodeWithTag("sport_cycling").assertIsSelected()
        composeRule.onNodeWithText("Natación").performClick()
        composeRule.onNodeWithTag("training_back").performClick()
        assertEquals(listOf("sport:swimming", "back"), events)
    }

    @Test
    fun deporteFaltanteYPlanSinCapacidadSeExplican() {
        show(TrainingSessionUiState(loading = false, sportMissing = true, canStart = false))

        composeRule.onNodeWithTag("training_sport_required").assertIsDisplayed()
        composeRule.onNodeWithTag("training_cannot_start").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("training_start").assertIsNotEnabled()
    }

    @Test
    fun sesionAbiertaMuestraCronometroEstadoLocalYFinalizar() {
        show(
            TrainingSessionUiState(
                loading = false,
                current = session("s-1", SyncState.LOCAL),
                monotonicStartMillis = 10_000L,
            )
        )

        composeRule.onNodeWithText("Entrenamiento en curso").assertIsDisplayed()
        composeRule.onNodeWithTag("training_chronometer")
            .assertTextEquals("0:01:05")
            .assertContentDescriptionEquals("Tiempo transcurrido: 0 h 01 min 05 s")
        composeRule.onNodeWithText("Guardado solo en este dispositivo").assertIsDisplayed()
        composeRule.onNodeWithTag("training_finish").performScrollTo().performClick()
        assertEquals(listOf("finish"), events)
    }

    @Test
    fun trasReiniciarElCronometroSeReconstruyeDesdeElInicio() {
        show(TrainingSessionUiState(loading = false, current = session("s-1", SyncState.PENDING_SYNC), monotonicStartMillis = null))

        composeRule.onNodeWithTag("training_chronometer").assertTextEquals("1:02:05")
        composeRule.onNodeWithText("Pendiente de sincronizar").assertIsDisplayed()
    }

    @Test
    fun losRegistrosDistinguenConfirmadoPendienteYRechazado() {
        show(
            TrainingSessionUiState(
                loading = false,
                records = listOf(
                    session("s-ok", SyncState.SYNCED, finished = true, official = 2_700),
                    session("s-pend", SyncState.PENDING_SYNC, finished = true, sport = TrainingSport.CYCLING),
                    session("s-fail", SyncState.SYNC_FAILED, failure = SyncFailure.ACTIVE_SESSION_EXISTS, sport = TrainingSport.STRENGTH),
                ),
            )
        )

        composeRule.onNodeWithText("Duración confirmada: 0 h 45 min 00 s").performScrollTo().assertIsDisplayed()
        // Cada texto se desplaza a la vista antes de verificarlo: en pantallas bajas (emulador de CI)
        // el estado de la tarjeta queda por debajo de su duración.
        composeRule.onNodeWithText("Confirmado por SportApp").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Duración en el dispositivo (sin confirmar): 0 h 45 min 00 s").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pendiente de sincronizar").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("No sincronizado").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Ya tenías otro entrenamiento en curso, por ejemplo desde otro dispositivo. Este registro no fue aceptado."
        ).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("training_dismiss_s-pend").assertDoesNotExist()
        composeRule.onNodeWithTag("training_dismiss_s-ok").assertContentDescriptionEquals("Descartar el registro de Carrera")
        composeRule.onNodeWithTag("training_dismiss_s-fail").performScrollTo().performClick()
        assertEquals(listOf("dismiss:s-fail"), events)
    }

    @Test
    fun unRegistroIlegibleSeExplicaYSePuedeDescartar() {
        show(
            TrainingSessionUiState(
                loading = false,
                records = listOf(LocalTrainingSession("s-x", SyncState.SYNC_FAILED, SyncFailure.UNREADABLE, null)),
            )
        )

        composeRule.onNodeWithText("Registro ilegible").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("No pudimos leer este registro guardado en el dispositivo.").assertIsDisplayed()
        composeRule.onNodeWithTag("training_dismiss_s-x").assertIsDisplayed()
    }

    @Test
    fun sinConexionSeInformaQueNoSeVerificoOtraSesionYLaFallaLocal() {
        show(
            TrainingSessionUiState(
                loading = false,
                remoteCheck = RemoteCheck.NO_CONNECTION,
                actionError = TrainingActionError.STORAGE,
            )
        )

        composeRule.onNodeWithText("No pudimos guardar el registro en este dispositivo. Intenta de nuevo.").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Sin conexión: no pudimos verificar si tienes un entrenamiento en curso en otro dispositivo. " +
                "Puedes registrar uno nuevo y se sincronizará después."
        ).assertIsDisplayed()
    }

    @Test
    fun mientrasGuardaNoSePuedeRepetirLaAccion() {
        show(TrainingSessionUiState(loading = false, current = session("s-1", SyncState.LOCAL), busy = true))

        composeRule.onNodeWithTag("training_finish").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Guardando…").assertIsDisplayed()
    }
}
