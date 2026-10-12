package com.lfnj.sport_app.navigation

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.testing.SessionFixtures.FREE_CAPABILITIES
import com.lfnj.sport_app.testing.SessionFixtures.INTERMEDIATE_CAPABILITIES
import com.lfnj.sport_app.testing.SessionFixtures.PREMIUM_CAPABILITIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU003 CA7: menú del deportista = actor ∩ canal móvil ∩ capacidades vigentes. */
class NavigationRegistryTest {
    private fun athlete(capabilities: List<String>, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
        AccessSnapshot(ActorType.ATHLETE, status, "FREE", emptySet(), capabilities.toSet())

    private fun huOf(menu: List<MenuEntry>) = menu.map { it.hu }

    @Test
    fun `FREE muestra inicio y las diez capacidades moviles del plan`() {
        val menu = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES))

        assertEquals(
            listOf("HU003", "HU026", "HU027", "HU028", "HU029", "HU030", "HU031", "HU032", "HU033", "HU034", "HU035", "HU004"),
            huOf(menu),
        )
    }

    @Test
    fun `INTERMEDIATE agrega exactamente tres opciones`() {
        val free = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES))
        val intermediate = NavigationRegistry.athleteMenu(athlete(INTERMEDIATE_CAPABILITIES))

        assertEquals(listOf("HU036", "HU037", "HU038"), huOf(intermediate - free.toSet()))
    }

    @Test
    fun `PREMIUM agrega solo la opcion aprobada para movil`() {
        val intermediate = NavigationRegistry.athleteMenu(athlete(INTERMEDIATE_CAPABILITIES))
        val premium = NavigationRegistry.athleteMenu(athlete(PREMIUM_CAPABILITIES))

        assertEquals(listOf("HU040"), huOf(premium - intermediate.toSet()))
    }

    @Test
    fun `las capacidades de HU web no aparecen en movil`() {
        val ids = NavigationRegistry.athleteMenu(athlete(PREMIUM_CAPABILITIES)).map { it.id }

        assertTrue("training.plan.manage" !in ids)
        assertTrue("services.sports_medicine.access" !in ids)
    }

    @Test
    fun `PROVISIONING no concede opciones del plan`() {
        val menu = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.PROVISIONING))

        assertEquals(listOf("home", "logout"), menu.map { it.id })
    }

    @Test
    fun `sin capacidades cargadas solo quedan las opciones base`() {
        assertEquals(listOf("home", "logout"), NavigationRegistry.athleteMenu(null).map { it.id })
    }

    @Test
    fun `una vista de otro actor no concede opciones de deportista`() {
        val organizer = AccessSnapshot(
            ActorType.ORGANIZER, SubscriptionStatus.ACTIVE, null, setOf("training.routes.recommend"), FREE_CAPABILITIES.toSet(),
        )

        assertEquals(listOf("home", "logout"), NavigationRegistry.athleteMenu(organizer).map { it.id })
    }

    @Test
    fun `capacidades desconocidas se ignoran`() {
        assertEquals(listOf("home", "logout"), NavigationRegistry.athleteMenu(athlete(listOf("admin.everything"))).map { it.id })
    }

    @Test
    fun `solo el inicio es navegable mientras las demas HU no esten implementadas`() {
        val menu = NavigationRegistry.athleteMenu(athlete(PREMIUM_CAPABILITIES))

        assertEquals(listOf(Destinations.ATHLETE_HOME), menu.mapNotNull { it.route })
    }

    @Test
    fun `el registro solo contiene HU aprobadas para movil y codigos unicos`() {
        val entries = NavigationRegistry.athlete
        val mobileHu = setOf("HU003", "HU004") + (26..40).map { "HU0$it" }

        assertTrue(entries.all { it.hu in mobileHu })
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
    }

    @Test
    fun `HU004 cerrar sesion esta deshabilitada mientras no exista la operacion en el BFF`() {
        val logout = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).single { it.id == "logout" }

        assertEquals(false, logout.enabled)
        assertEquals("HU004", logout.hu)
    }

    @Test
    fun `HU004 cerrar sesion se habilita como accion cuando el BFF la ofrece`() {
        val menu = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES), logoutAvailable = true)
        val logout = menu.single { it.id == "logout" }

        assertEquals(MenuAction.LOGOUT, logout.action)
        assertEquals(null, logout.route)
        assertEquals(logout, menu.last())
        assertEquals(MenuGroup.ACCOUNT, NavigationRegistry.grouped(menu).last().first)
    }

    @Test
    fun `los grupos conservan el orden del menu`() {
        val grouped = NavigationRegistry.grouped(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)))

        assertEquals(MenuGroup.MAIN, grouped.first().first)
        assertEquals(MenuGroup.TRAINING, grouped[1].first)
    }
}
