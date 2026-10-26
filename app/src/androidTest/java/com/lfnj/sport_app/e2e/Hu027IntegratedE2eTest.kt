package com.lfnj.sport_app.e2e

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.EncryptedTokenStore
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.features.events.data.EventSuggestionsApi
import com.lfnj.sport_app.features.events.data.EventSuggestionsRefresh
import com.lfnj.sport_app.features.events.data.EventSuggestionsRepository
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsCodec
import com.lfnj.sport_app.features.events.data.local.EventSuggestionsDatabase
import com.lfnj.sport_app.features.events.model.EventMissingContext
import com.lfnj.sport_app.features.events.model.EventPrice
import com.lfnj.sport_app.features.events.model.EventSuggestionReason
import com.lfnj.sport_app.features.events.model.EventSuggestionsOwner
import com.lfnj.sport_app.features.events.model.EventSuggestionsSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E de HU027 (ADR-041 «Evidencia requerida»): aplicación real (MainActivity, Room, Keystore y
 * URL de la variante) → `bff-mobile` 0.15.0 → `events_management` y contexto de Usuarios en la
 * composición local. Un deportista con deporte `running` y una ciudad propia recibe primero los
 * dos eventos publicados por un organizador real en su ciudad con disciplinas alias de `running`
 * (`SPORT_MATCH` y `CITY_MATCH`), abre uno desde la lista y se inscribe en el detalle de HU041;
 * al volver a consultar, el evento aparece «Ya inscrito» (`my_registration`). Sin conexión con el
 * BFF la lista se lee de la caché cifrada. Con una segunda cuenta sin perfil deportivo, la lista
 * no es personalizada y declara `missing_context`.
 *
 * Datos sintéticos preparados antes por `bff-web` (`localhost:18080`): deportista con
 * `PUT /v1/me/demographic-profile` (ciudad única) y `PUT /v1/me/sport-habits` (`running`);
 * organizador que crea y publica los dos eventos en esa ciudad, el primero con tarifa
 * (`PUT /v1/events/{event_id}/fee`, `45000.00 COP`) y el segundo sin tarifa ni inscripciones.
 * Se omite sin argumentos:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu027IntegratedE2eTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña sintética> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eFeeEventId=<event_id con tarifa> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eRegisterEventId=<event_id sin tarifa> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eIncompleteAthleteEmail=<correo sin perfil deportivo>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Hu027IntegratedE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val password: String? = args.getString("e2ePassword")
    private val email: String? = args.getString("e2eAthleteEmail")
    private val incompleteEmail: String? = args.getString("e2eIncompleteAthleteEmail")
    private val feeEventId: String? = args.getString("e2eFeeEventId")
    private val registerEventId: String? = args.getString("e2eRegisterEventId")
    private val container get() = (context as SportApp).container

    @Before
    fun setUp() {
        assumeTrue("E2E omitido: faltan la cuenta sintética o la contraseña", password != null && email != null)
        // Las pruebas comparten el proceso: se elimina también la sesión en memoria del contenedor.
        runBlocking { container.sessionManager.clearLocalSession() }
        EncryptedTokenStore(context).clear()
    }

    private fun login(account: String) {
        composeRule.waitUntilExactlyOneExists(hasTestTag("login_email"), TIMEOUT)
        composeRule.onNodeWithTag("login_email").performTextInput(account)
        composeRule.onNodeWithTag("login_password").performTextInput(requireNotNull(password))
        composeRule.onNodeWithTag("login_submit").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("athlete_home"), TIMEOUT)
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodes(hasText("Plan ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun owner(): String = EventSuggestionsOwner.keyOf(checkNotNull(container.sessionManager.currentUser.value).userId)

    /** Consulta real hasta que la lista confirmada cumpla [ready]; la lectura es eventual (proyecciones). */
    private fun refreshUntil(owner: String, ready: (EventSuggestionsSnapshot) -> Boolean): EventSuggestionsSnapshot = runBlocking {
        val deadline = System.currentTimeMillis() + SYNC_TIMEOUT
        var cached: EventSuggestionsSnapshot
        do {
            assertEquals(EventSuggestionsRefresh.Updated, container.eventSuggestionsRepository.refresh(owner))
            cached = checkNotNull(container.eventSuggestionsRepository.observe(owner).first())
            if (ready(cached)) break
            delay(2_000)
        } while (System.currentTimeMillis() < deadline)
        cached
    }

    private fun openSuggestionsFromMenu() {
        composeRule.onNodeWithTag("open_menu").performClick()
        composeRule.onNodeWithTag("athlete_menu").performScrollToNode(hasTestTag("menu_events.suggest"))
        composeRule.onNodeWithTag("menu_events.suggest").performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_screen"), TIMEOUT)
    }

    @Test
    fun sugiereLosEventosDeSuDeporteYCiudadYMarcaYaInscritoTrasInscribirseEnElDetalle() {
        val feeEvent = requireNotNull(feeEventId) { "Falta e2eFeeEventId" }
        val registerEvent = requireNotNull(registerEventId) { "Falta e2eRegisterEventId" }
        ActivityScenario.launch(MainActivity::class.java).use {
            login(requireNotNull(email))
            val owner = owner()

            // CA1: personalizada; los dos eventos de su deporte y ciudad encabezan la lista, por fecha.
            val first = refreshUntil(owner) { snapshot -> snapshot.items.map { it.eventId }.containsAll(listOf(feeEvent, registerEvent)) }
            assertTrue(first.personalized)
            assertTrue(first.missingContext.isEmpty())
            assertEquals(listOf(feeEvent, registerEvent), first.items.take(2).map { it.eventId })
            first.items.take(2).forEach { item ->
                assertTrue(item.eventId, item.knownReasons.containsAll(listOf(EventSuggestionReason.SPORT_MATCH, EventSuggestionReason.CITY_MATCH)))
            }
            // CA4: tarifa informativa o sin tarifa y cupo declarado; CA3: aún sin inscripción.
            val withFee = first.items[0]
            val withoutFee = first.items[1]
            assertEquals(EventPrice("45000.00", "COP"), withFee.fee)
            assertNull(withoutFee.fee)
            assertTrue(withFee.capacity >= 1)
            assertNull(withoutFee.myRegistration)

            openSuggestionsFromMenu()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestion_$feeEvent"), TIMEOUT)
            composeRule.onNodeWithTag("event_suggestions_personalized").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("event_suggestion_$feeEvent").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("event_suggestion_reason_${feeEvent}_CITY_MATCH", useUnmergedTree = true).assertExists()
            composeRule.onNodeWithTag("event_suggestion_registered_$registerEvent", useUnmergedTree = true).assertDoesNotExist()

            // La sugerencia abre el detalle de HU041, donde se confirma la inscripción real.
            composeRule.onNode(hasTestTag("event_suggestion_$registerEvent") and hasClickAction()).performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_name"), TIMEOUT)
            composeRule.onNodeWithTag("event_register").performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_confirm"), TIMEOUT)
            composeRule.onNodeWithTag("event_confirm").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_registration_confirmed"), TIMEOUT)

            // CA3: al volver a consultar, el evento inscrito queda marcado con my_registration.
            val after = refreshUntil(owner) { snapshot -> snapshot.items.any { it.eventId == registerEvent && it.registered } }
            assertTrue("El evento inscrito no llegó marcado", after.items.single { it.eventId == registerEvent }.registered)
            assertFalse(after.items.single { it.eventId == feeEvent }.registered)
            composeRule.onNodeWithTag("event_back").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_screen"), TIMEOUT)
            composeRule.onNodeWithTag("event_suggestion_$registerEvent").performScrollTo().assertIsDisplayed()
            composeRule.waitUntil(TIMEOUT) {
                composeRule.onAllNodes(hasTestTag("event_suggestion_registered_$registerEvent"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Sin conexión con el BFF: la lista sigue disponible desde la caché cifrada, con su antigüedad.
            runBlocking {
                val database = EventSuggestionsDatabase.create(context)
                try {
                    val offline = EventSuggestionsRepository(
                        dao = database.eventSuggestionsCacheDao(),
                        codec = EventSuggestionsCodec(KeystoreTokenCipher(EventSuggestionsCodec.KEY_ALIAS), BffMobileClient.json),
                        api = BffMobileClient.retrofit(UNREACHABLE_BFF).create(EventSuggestionsApi::class.java),
                        sessionManager = container.sessionManager,
                        json = BffMobileClient.json,
                    )
                    assertEquals(EventSuggestionsRefresh.NoConnection, offline.refresh(owner))
                    val cached = checkNotNull(offline.observe(owner).first())
                    assertTrue(cached.items.any { it.eventId == registerEvent && it.registered })
                    assertEquals(after.syncedAt, cached.syncedAt)
                } finally {
                    database.close()
                }
            }
        }
    }

    @Test
    fun sinPerfilDeportivoLaListaNoEsPersonalizadaYDeclaraElContextoFaltante() {
        val account = incompleteEmail
        assumeTrue("Sin e2eIncompleteAthleteEmail se omite el caso no personalizado", account != null)
        ActivityScenario.launch(MainActivity::class.java).use {
            login(requireNotNull(account))
            val owner = owner()

            // CA2: nunca se presenta como personalizada; los eventos vienen por fecha con UPCOMING.
            val snapshot = refreshUntil(owner) { true }
            assertFalse(snapshot.personalized)
            assertTrue(snapshot.knownMissingContext.contains(EventMissingContext.SPORTS))
            assertTrue(snapshot.items.all { it.knownReasons == listOf(EventSuggestionReason.UPCOMING) })
            assertEquals(snapshot.items.sortedBy { it.startsAt }.map { it.startsAt }, snapshot.items.map { it.startsAt })

            openSuggestionsFromMenu()
            composeRule.waitUntilExactlyOneExists(hasTestTag("event_suggestions_not_personalized"), TIMEOUT)
            composeRule.onNodeWithTag("event_suggestions_personalized").assertDoesNotExist()
            composeRule.onNodeWithTag("event_suggestions_missing_sports", useUnmergedTree = true).assertExists()
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SYNC_TIMEOUT = 120_000L
        /** Puerto cerrado en el dispositivo: la conexión se rechaza como sin red. */
        const val UNREACHABLE_BFF = "http://127.0.0.1:9/"
    }
}
