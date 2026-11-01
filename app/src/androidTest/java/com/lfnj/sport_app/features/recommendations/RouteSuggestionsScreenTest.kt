package com.lfnj.sport_app.features.recommendations

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.recommendations.model.ContextSourceStatus
import com.lfnj.sport_app.features.recommendations.model.RouteFormat
import com.lfnj.sport_app.features.recommendations.model.RoutePoint
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestion
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsSnapshot
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** HU026: estados de «Rutas sugeridas», personalización, aviso de seguridad, detalle con `geo:` y accesibilidad. */
@RunWith(AndroidJUnit4::class)
class RouteSuggestionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bogota = ZoneId.of("America/Bogota")

    /** Los números siguen el idioma del dispositivo (emulador de CI o teléfono): se formatean igual que la pantalla. */
    private val locale: Locale = Locale.getDefault()
    private fun km(value: String) = RouteFormat.distance(value, locale)
    private fun deg(value: Double) = RouteFormat.coordinate(value, locale)
    private val events = mutableListOf<String>()
    private var mapAvailable = true

    private val cityRoute = RouteSuggestion(
        routeId = "a", title = "Circuito Parque Simón Bolívar", summary = "Recorrido plano alrededor del lago.",
        sport = "running", distanceKm = "5.0", difficulty = "LOW", city = "Bogotá", countryCode = "CO",
        startPoint = RoutePoint(4.6097, -74.0817), waypoints = listOf(RoutePoint(4.6584, -74.0937), RoutePoint(4.6601, -74.0952)),
        publishedAt = Instant.parse("2026-10-20T14:00:00Z"), authorName = "Carlos Ruiz", reasons = listOf("SPORT_MATCH", "CITY_MATCH"),
    )
    private val sportRoute = cityRoute.copy(
        routeId = "b", title = "Ascenso a Patios", summary = null, distanceKm = "12.5", difficulty = "HIGH", city = "La Calera",
        waypoints = emptyList(), authorName = null, reasons = listOf("SPORT_MATCH"),
    )

    private fun snapshot(
        items: List<RouteSuggestion> = listOf(cityRoute, sportRoute),
        personalized: Boolean = true,
        missing: List<String> = emptyList(),
        users: ContextSourceStatus = ContextSourceStatus.OK,
    ) = RouteSuggestionsSnapshot(items, personalized, missing, Instant.EPOCH, users, Instant.parse("2026-10-24T13:30:00Z"))

    private fun show(state: RouteSuggestionsUiState) = composeRule.setContent {
        SportappTheme {
            RouteSuggestionsScreen(
                state = state,
                onOpenDetail = { events += "open:$it" },
                onCloseDetail = { events += "close" },
                onRetry = { events += "retry" },
                onOpenMap = { uri -> events += "map:$uri"; mapAvailable },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun muestraCargaSinInventarDatos() {
        show(RouteSuggestionsUiState(zone = bogota, refreshing = true))

        composeRule.onNodeWithTag("route_suggestions_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_empty").assertDoesNotExist()
    }

    @Test
    fun cadaRutaMuestraDistanciaDificultadAutorLugarYMotivo() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("route_suggestions_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Para ti: rutas de tus deportes, primero las de tu ciudad y luego las más cortas.").assertExists()
        composeRule.onNodeWithTag("route_suggestion_a").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Por Carlos Ruiz", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Carrera · ${km("5.0")} km · Dificultad baja", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Bogotá, CO", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Está en tu ciudad", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag("route_suggestion_reason_SPORT_MATCH", useUnmergedTree = true).assertCountEquals(2)
        // Sin autor resuelto se dice así, sin inventar un nombre.
        composeRule.onNodeWithTag("route_suggestion_b").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Autor no disponible", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Carrera · ${km("12.5")} km · Dificultad alta", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_synced_at").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun siempreAvisaQueUnaSugerenciaNoGarantizaSeguridad() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNodeWithTag("route_suggestions_safety").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Una sugerencia no garantiza la seguridad de la ruta", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun tocarUnaRutaAbreSuDetalleConEtiquetaAccesible() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot()))

        composeRule.onNode(hasTestTag("route_suggestion_a") and hasClickAction())
            .assert(SemanticsMatcher("etiqueta de acción") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Ver detalle de Circuito Parque Simón Bolívar"
            })
            .performScrollTo()
            .performClick()

        assertEquals(listOf("open:a"), events)
    }

    @Test
    fun laListaNoPersonalizadaLoDiceYExplicaQueFalta() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot(items = listOf(cityRoute.copy(reasons = emptyList())), personalized = false, missing = listOf("sports", "city"))))

        composeRule.onNodeWithTag("route_suggestions_not_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_personalized").assertDoesNotExist()
        composeRule.onNodeWithText("Lista no personalizada: mostramos las rutas publicadas más recientes.", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("route_suggestions_missing_sports", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("route_suggestions_missing_city", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("route_suggestion_reason_SPORT_MATCH", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun conUsuariosCaidoIndicaQueNoSePudoConsultarElPerfil() {
        show(
            RouteSuggestionsUiState(
                zone = bogota,
                snapshot = snapshot(items = listOf(cityRoute.copy(reasons = emptyList(), authorName = null)), personalized = false, missing = listOf("sports", "city"), users = ContextSourceStatus.UNAVAILABLE),
            )
        )

        composeRule.onNodeWithTag("route_suggestions_context_unavailable", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("route_suggestions_missing_sports", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun sinCiudadLaListaSigueSiendoPorDeporteYLoExplica() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot(missing = listOf("city"))))

        composeRule.onNodeWithTag("route_suggestions_personalized").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Para ti: rutas de tus deportes, de la más corta a la más larga. Sin tu ciudad no podemos priorizar las cercanas.", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Falta en tu perfil: tu ciudad.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun unaListaConfirmadaVaciaMuestraElEstadoVacio() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot(items = emptyList())))

        composeRule.onNodeWithTag("route_suggestions_empty").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Por ahora no hay rutas publicadas para tus deportes.").assertExists()
    }

    @Test
    fun sinRedConservaLaListaConSuAntiguedadYAvisaSiEstaDesactualizada() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot(), stale = true, problem = RouteSuggestionsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("route_suggestions_stale").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_problem").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sin conexión. Mostramos las rutas sincronizadas por última vez.").assertExists()
        composeRule.onNodeWithTag("route_suggestions_synced_at").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestion_a").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinRedYSinCacheInformaQueAunNoSeSincronizoYPermiteReintentar() {
        show(RouteSuggestionsUiState(zone = bogota, problem = RouteSuggestionsProblem.NO_CONNECTION))

        composeRule.onNodeWithTag("route_suggestions_never_synced").assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    @Test
    fun unErrorConCacheOfreceReintentarYDeshabilitaElBotonDuranteLaConsulta() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot(), problem = RouteSuggestionsProblem.UNAVAILABLE, refreshing = true))

        // Con el indicador indeterminado activo no se desplaza: la pantalla no queda inactiva y la acción no termina.
        composeRule.onNodeWithTag("route_suggestions_refreshing").assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_retry").assertIsNotEnabled()
    }

    @Test
    fun sinLaCapacidadExplicaElPlanRequerido() {
        show(RouteSuggestionsUiState(zone = bogota, problem = RouteSuggestionsProblem.CAPABILITY_REQUIRED))

        composeRule.onNodeWithText("Tu plan actual no incluye rutas sugeridas.").assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestion_a").assertDoesNotExist()
    }

    @Test
    fun elDetalleMuestraPuntoDeInicioPuntosIntermediosYAbreMapasConGeo() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot(), selected = cityRoute))

        composeRule.onNodeWithTag("route_suggestion_detail").assertExists()
        composeRule.onNodeWithTag("route_suggestion_distance").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Distancia: ${km("5.0")} km").assertExists()
        composeRule.onNodeWithText("Dificultad: baja").assertExists()
        composeRule.onNodeWithTag("route_suggestion_description").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestion_start_point").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Latitud ${deg(4.6097)}, longitud ${deg(-74.0817)}").assertExists()
        composeRule.onNodeWithText("2 puntos intermedios, en orden").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestion_waypoint_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1. Latitud ${deg(4.6584)}, longitud ${deg(-74.0937)}").assertExists()
        composeRule.onNodeWithTag("route_suggestion_waypoint_1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestions_safety").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("route_suggestion_open_map")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Abrir el punto de inicio de Circuito Parque Simón Bolívar en la aplicación de mapas")))
            .performScrollTo()
            .performClick()

        assertEquals(listOf("map:geo:4.6097,-74.0817?q=4.6097,-74.0817(Circuito%20Parque%20Sim%C3%B3n%20Bol%C3%ADvar)"), events)
        composeRule.onNodeWithTag("route_suggestion_map_unavailable").assertDoesNotExist()
    }

    @Test
    fun sinAplicacionDeMapasLoInformaYSinPuntosIntermediosLoDice() {
        mapAvailable = false
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot(), selected = sportRoute))

        composeRule.onNodeWithTag("route_suggestion_waypoints_empty").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("route_suggestion_description").assertDoesNotExist()
        composeRule.onNodeWithTag("route_suggestion_open_map").performScrollTo().performClick()
        composeRule.onNodeWithTag("route_suggestion_map_unavailable").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun enElDetalleVolverCierraElDetalleYEnLaListaSaleDeLaPantalla() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot(), selected = cityRoute))
        composeRule.onNodeWithTag("route_suggestions_back").performClick()
        assertEquals(listOf("close"), events)
    }

    @Test
    fun enLaListaVolverSaleDeLaPantalla() {
        show(RouteSuggestionsUiState(zone = bogota, snapshot = snapshot()))
        composeRule.onNodeWithTag("route_suggestions_back").performClick()
        assertEquals(listOf("back"), events)
    }

    @Test
    fun elLanzadorConstruyeUnIntentDeVistaConLaUriGeo() {
        val uri = "geo:4.6097,-74.0817?q=4.6097,-74.0817(Ruta)"
        val intent = RouteMapLauncher.intent(uri)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo", intent.data?.scheme)
        assertEquals(uri, intent.dataString)
    }
}
