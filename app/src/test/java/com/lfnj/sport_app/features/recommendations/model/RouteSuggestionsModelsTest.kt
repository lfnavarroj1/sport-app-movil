package com.lfnj.sport_app.features.recommendations.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.testing.SessionFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** HU026: apertura en mapas con `geo:`, formatos, guarda de capacidad y partición local. */
class RouteSuggestionsModelsTest {
    private val spanish = Locale.forLanguageTag("es-CO")

    @Test
    fun `el punto de inicio se abre con geo lat,lng y el nombre de la ruta como etiqueta`() {
        assertEquals(
            "geo:4.6097,-74.0817?q=4.6097,-74.0817(Circuito%20Parque%20Sim%C3%B3n%20Bol%C3%ADvar)",
            RouteMaps.geoUri(RoutePoint(4.6097, -74.0817), "Circuito Parque Simón Bolívar"),
        )
    }

    @Test
    fun `las coordenadas usan punto decimal sin notacion cientifica y la etiqueta escapa los reservados`() {
        val uri = RouteMaps.geoUri(RoutePoint(0.00001, 180.0), "Ruta (A+B) & 10%")

        assertEquals("geo:0.00001,180?q=0.00001,180(Ruta%20%28A%2BB%29%20%26%2010%25)", uri)
        assertFalse(uri.contains("E-"))
    }

    @Test
    fun `la distancia se muestra con un decimal en el idioma del dispositivo sin redondear el dato`() {
        assertEquals("12,5", RouteFormat.distance("12.5", spanish))
        assertEquals("5,0", RouteFormat.distance("5.0", spanish))
        assertEquals("500,0", RouteFormat.distance("500.0", spanish))
        assertEquals("12.5", RouteFormat.distance("12.5", Locale.US))
    }

    @Test
    fun `las coordenadas se presentan localizadas`() {
        assertEquals("4,6097", RouteFormat.coordinate(4.6097, spanish))
        assertEquals("-74,081701", RouteFormat.coordinate(-74.0817012, spanish))
    }

    @Test
    fun `un punto solo es valido dentro de los rangos de latitud y longitud`() {
        assertTrue(RoutePoint(-90.0, 180.0).valid)
        assertTrue(RoutePoint(90.0, -180.0).valid)
        assertFalse(RoutePoint(90.1, 0.0).valid)
        assertFalse(RoutePoint(0.0, -180.1).valid)
        assertFalse(RoutePoint(Double.NaN, 0.0).valid)
    }

    @Test
    fun `solo un deportista activo con training_routes_suggest puede abrir la pantalla`() {
        fun access(status: SubscriptionStatus, capabilities: List<String>) =
            AccessSnapshot(ActorType.ATHLETE, status, "FREE", setOf(), capabilities.toSet())

        assertTrue(RouteSuggestionsAccess.canView(access(SubscriptionStatus.ACTIVE, SessionFixtures.FREE_CAPABILITIES)))
        assertFalse(RouteSuggestionsAccess.canView(access(SubscriptionStatus.ACTIVE, SessionFixtures.FREE_CAPABILITIES - TRAINING_ROUTES_SUGGEST_CAPABILITY)))
        assertFalse(
            RouteSuggestionsAccess.canView(access(SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE }, SessionFixtures.FREE_CAPABILITIES))
        )
        assertFalse(RouteSuggestionsAccess.canView(null))
    }

    @Test
    fun `la particion es una huella del usuario y no contiene su identificador`() {
        val userId = "7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b"
        val key = RouteSuggestionsOwner.keyOf(userId)

        assertEquals(64, key.length)
        assertFalse(userId in key)
        assertEquals(key, RouteSuggestionsOwner.keyOf(userId))
        assertNotEquals(key, RouteSuggestionsOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100"))
    }
}
