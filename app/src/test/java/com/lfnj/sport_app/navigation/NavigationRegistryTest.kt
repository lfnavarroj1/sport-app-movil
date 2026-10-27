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
            listOf("HU003", "HU043", "HU039", "HU026", "HU027", "HU028", "HU029", "HU030", "HU031", "HU032", "HU033", "HU034", "HU035", "HU004"),
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

        assertEquals(listOf("home", "nutrition", "notifications", "logout"), menu.map { it.id })
    }

    @Test
    fun `sin capacidades cargadas solo quedan las opciones base`() {
        assertEquals(listOf("home", "nutrition", "notifications", "logout"), NavigationRegistry.athleteMenu(null).map { it.id })
    }

    @Test
    fun `una vista de otro actor no concede opciones de deportista`() {
        val organizer = AccessSnapshot(
            ActorType.ORGANIZER, SubscriptionStatus.ACTIVE, null, setOf("training.routes.recommend"), FREE_CAPABILITIES.toSet(),
        )

        assertEquals(listOf("home", "nutrition", "notifications", "logout"), NavigationRegistry.athleteMenu(organizer).map { it.id })
    }

    @Test
    fun `capacidades desconocidas se ignoran`() {
        assertEquals(listOf("home", "nutrition", "notifications", "logout"), NavigationRegistry.athleteMenu(athlete(listOf("admin.everything"))).map { it.id })
    }

    @Test
    fun `solo son navegables el inicio y las HU implementadas`() {
        val menu = NavigationRegistry.athleteMenu(athlete(PREMIUM_CAPABILITIES))

        assertEquals(
            listOf(Destinations.ATHLETE_HOME, Destinations.NUTRITION, Destinations.NOTIFICATIONS, Destinations.ROUTE_SUGGESTIONS, Destinations.EVENT_SUGGESTIONS, Destinations.PLAN_SUGGESTIONS, Destinations.SUPPORT_SUGGESTIONS, Destinations.TRAINING_SESSION, Destinations.TRAINING_DASHBOARD, Destinations.TRAINING_METRICS, Destinations.CALENDAR, Destinations.SESSION_REMINDERS, Destinations.VIRTUAL_SESSIONS),
            menu.mapNotNull { it.route },
        )
    }

    @Test
    fun `el registro solo contiene HU aprobadas para movil y codigos unicos`() {
        val entries = NavigationRegistry.athlete
        val mobileHu = setOf("HU003", "HU004", "HU043") + (26..40).map { "HU0$it" }

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

    // ------------------------------------------------------------- HU032

    @Test
    fun `HU032 Registrar entrenamiento queda habilitado con la capacidad`() {
        val entry = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).single { it.hu == "HU032" }

        assertEquals(Destinations.TRAINING_SESSION, entry.route)
        assertTrue(entry.enabled)
    }

    @Test
    fun `HU032 sin capacidad no aparece salvo que haya una sesion abierta por finalizar`() {
        val withoutCapability = athlete(FREE_CAPABILITIES - "training.session.record")

        assertTrue(NavigationRegistry.athleteMenu(withoutCapability).none { it.hu == "HU032" })
        assertTrue(NavigationRegistry.athleteMenu(withoutCapability, openTrainingSession = true).any { it.hu == "HU032" })
        assertEquals(
            listOf("home", "nutrition", "notifications", "training.session.record", "logout"),
            NavigationRegistry.athleteMenu(null, openTrainingSession = true).map { it.id },
        )
    }

    // ------------------------------------------------------------- HU033

    @Test
    fun `HU033 Tablero de entrenamiento queda habilitado con la capacidad`() {
        val entry = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).single { it.hu == "HU033" }

        assertEquals(Destinations.TRAINING_DASHBOARD, entry.route)
        assertEquals("training.dashboard.view", entry.capability)
        assertEquals(MenuGroup.TRAINING, entry.group)
        assertEquals(180, entry.order)
        assertTrue(entry.enabled)
    }

    @Test
    fun `HU033 sin capacidad o sin suscripcion activa no aparece`() {
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES - "training.dashboard.view")).none { it.hu == "HU033" })
        assertTrue(
            NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE }))
                .none { it.hu == "HU033" }
        )
    }

    // ------------------------------------------------------------- HU035

    @Test
    fun `HU035 Calendario deportivo queda habilitado con la capacidad`() {
        val entry = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).single { it.hu == "HU035" }

        assertEquals(Destinations.CALENDAR, entry.route)
        assertEquals("calendar.sport.view", entry.capability)
        assertEquals(MenuGroup.CALENDAR, entry.group)
        assertTrue(entry.enabled)
    }

    @Test
    fun `HU035 sin capacidad, sin suscripcion activa o sin capacidades conocidas no aparece`() {
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES - "calendar.sport.view")).none { it.hu == "HU035" })
        assertTrue(
            NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE }))
                .none { it.hu == "HU035" }
        )
        assertTrue(NavigationRegistry.athleteMenu(null, openTrainingSession = true).none { it.hu == "HU035" })
    }

    // ------------------------------------------------------------- HU026

    @Test
    fun `HU026 Rutas sugeridas queda habilitado con la capacidad`() {
        val entry = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).single { it.hu == "HU026" }

        assertEquals(Destinations.ROUTE_SUGGESTIONS, entry.route)
        assertEquals("training.routes.suggest", entry.capability)
        assertEquals(MenuGroup.TRAINING, entry.group)
        assertEquals(110, entry.order)
        assertTrue(entry.enabled)
    }

    @Test
    fun `HU026 sin capacidad, sin suscripcion activa o sin capacidades conocidas no aparece`() {
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES - "training.routes.suggest")).none { it.hu == "HU026" })
        assertTrue(
            NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE }))
                .none { it.hu == "HU026" }
        )
        assertTrue(NavigationRegistry.athleteMenu(null).none { it.hu == "HU026" })
    }

    // ------------------------------------------------------------- HU027

    @Test
    fun `HU027 Eventos sugeridos queda habilitado con la capacidad`() {
        val entry = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).single { it.hu == "HU027" }

        assertEquals(Destinations.EVENT_SUGGESTIONS, entry.route)
        assertEquals("eventos/sugeridos", Destinations.EVENT_SUGGESTIONS)
        assertEquals("events.suggest", entry.capability)
        assertEquals(MenuGroup.EVENTS, entry.group)
        assertEquals(120, entry.order)
        assertTrue(entry.enabled)
    }

    @Test
    fun `HU027 sin capacidad, sin suscripcion activa o sin capacidades conocidas no aparece`() {
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES - "events.suggest")).none { it.hu == "HU027" })
        assertTrue(
            NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE }))
                .none { it.hu == "HU027" }
        )
        assertTrue(NavigationRegistry.athleteMenu(null).none { it.hu == "HU027" })
    }

    @Test
    fun `HU027 abre el detalle de HU041 sin registrar otro destino de detalle`() {
        val detail = NavigationRegistry.contextual.single { it.route == Destinations.EVENT_DETAIL }

        assertTrue("HU027" in detail.origins)
        assertEquals("eventos/detalle/3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c", Destinations.eventDetail("3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c"))
        assertTrue(NavigationRegistry.contextual.none { it.hu == "HU027" })
    }

    // ------------------------------------------------------------- HU028

    @Test
    fun `HU028 Entrenamientos sugeridos queda habilitado con la capacidad`() {
        val entry = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).single { it.hu == "HU028" }

        assertEquals(Destinations.PLAN_SUGGESTIONS, entry.route)
        assertEquals("training.plans.suggest", entry.capability)
        assertEquals(MenuGroup.TRAINING, entry.group)
        assertEquals(130, entry.order)
        assertTrue(entry.enabled)
    }

    @Test
    fun `HU028 sin capacidad, sin suscripcion activa o sin capacidades conocidas no aparece`() {
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES - "training.plans.suggest")).none { it.hu == "HU028" })
        assertTrue(
            NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE }))
                .none { it.hu == "HU028" }
        )
        assertTrue(NavigationRegistry.athleteMenu(null).none { it.hu == "HU028" })
    }

    // ------------------------------------------------------------- HU029

    @Test
    fun `HU029 Acompañamiento y logística queda habilitado con la capacidad`() {
        val entry = NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).single { it.hu == "HU029" }

        assertEquals(Destinations.SUPPORT_SUGGESTIONS, entry.route)
        assertEquals("servicios/acompanamiento", entry.route)
        assertEquals("services.support.suggest", entry.capability)
        assertEquals(MenuGroup.SERVICES, entry.group)
        assertEquals(140, entry.order)
        assertTrue(entry.enabled)
    }

    @Test
    fun `HU029 sin capacidad, sin suscripcion activa o sin capacidades conocidas no aparece`() {
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES - "services.support.suggest")).none { it.hu == "HU029" })
        assertTrue(
            NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE }))
                .none { it.hu == "HU029" }
        )
        assertTrue(NavigationRegistry.athleteMenu(null).none { it.hu == "HU029" })
    }

    // ------------------------------------------------------------- HU040

    @Test
    fun `HU040 Sesiones virtuales queda habilitada solo con la capacidad PREMIUM`() {
        val entry = NavigationRegistry.athleteMenu(athlete(PREMIUM_CAPABILITIES)).single { it.hu == "HU040" }

        assertEquals(Destinations.VIRTUAL_SESSIONS, entry.route)
        assertEquals("services.virtual_sessions.schedule", entry.capability)
        assertEquals(MenuGroup.SERVICES, entry.group)
        assertEquals(250, entry.order)
        assertTrue(entry.enabled)
        assertTrue(NavigationRegistry.athleteMenu(athlete(INTERMEDIATE_CAPABILITIES)).none { it.hu == "HU040" })
    }

    @Test
    fun `HU041 registra el detalle del evento y mis inscripciones como destinos sin opcion de menu`() {
        val hu041 = NavigationRegistry.contextual.filter { it.hu == "HU041" }

        assertEquals(listOf(Destinations.EVENT_DETAIL, Destinations.MY_EVENT_REGISTRATIONS), hu041.map { it.route })
        assertTrue(hu041.all { it.capability == null })
        assertTrue(hu041.first().origins.containsAll(listOf("HU035", "HU027")))
        val menuRoutes = NavigationRegistry.athleteMenu(athlete(PREMIUM_CAPABILITIES)).mapNotNull { it.route }
        assertTrue(hu041.none { it.route in menuRoutes })
        assertTrue(NavigationRegistry.athlete.none { it.hu == "HU041" })
    }

    @Test
    fun `la ruta concreta del detalle lleva el event_id`() {
        assertEquals("eventos/detalle/683f4e38-6bb3-4c47-a6dc-9bb1588c67ef", Destinations.eventDetail("683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"))
        assertEquals("eventos/detalle/{event_id}", Destinations.EVENT_DETAIL)
    }

    // ------------------------------------------------------------- HU039

    @Test
    fun `HU039 Notificaciones es opcion base sin capacidad de plan`() {
        val entry = NavigationRegistry.athlete.single { it.hu == "HU039" }

        assertEquals("notifications", entry.id)
        assertEquals(Destinations.NOTIFICATIONS, entry.route)
        assertEquals("notifications", Destinations.NOTIFICATIONS)
        assertEquals(null, entry.capability)
        assertEquals(MenuGroup.MAIN, entry.group)
        assertEquals(70, entry.order)
        assertTrue(entry.enabled)
        // Visible sin capacidades, con suscripción no activa y para cualquier plan.
        assertTrue(NavigationRegistry.athleteMenu(null).any { it.hu == "HU039" })
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.PROVISIONING)).any { it.hu == "HU039" })
        assertTrue(NavigationRegistry.athleteMenu(athlete(PREMIUM_CAPABILITIES)).any { it.hu == "HU039" })
    }

    @Test
    fun `HU039 abre el detalle de HU041 y registra sus preferencias como destino contextual`() {
        assertTrue("HU039" in NavigationRegistry.contextual.single { it.route == Destinations.EVENT_DETAIL }.origins)
        val preferences = NavigationRegistry.contextual.single { it.hu == "HU039" }
        assertEquals(Destinations.NOTIFICATION_PREFERENCES, preferences.route)
        assertEquals(null, preferences.capability)
    }

    // ------------------------------------------------------------- HU038

    @Test
    fun `HU038 Recordatorios durante sesion queda habilitada solo con la capacidad INTERMEDIATE`() {
        val entry = NavigationRegistry.athleteMenu(athlete(INTERMEDIATE_CAPABILITIES)).single { it.hu == "HU038" }

        assertEquals(Destinations.SESSION_REMINDERS, entry.route)
        assertEquals("entrenamientos/recordatorios", Destinations.SESSION_REMINDERS)
        assertEquals("nutrition.session.reminders", entry.capability)
        assertEquals(MenuGroup.NUTRITION, entry.group)
        assertEquals(230, entry.order)
        assertTrue(entry.enabled)
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES)).none { it.hu == "HU038" })
        assertTrue(NavigationRegistry.athleteMenu(athlete(INTERMEDIATE_CAPABILITIES, SubscriptionStatus.PROVISIONING)).none { it.hu == "HU038" })
    }

    // ------------------------------------------------------------- HU043

    @Test
    fun `HU043 Mi alimentacion es opcion base sin capacidad de plan`() {
        val entry = NavigationRegistry.athlete.single { it.hu == "HU043" }

        assertEquals("nutrition", entry.id)
        assertEquals(Destinations.NUTRITION, entry.route)
        assertEquals("nutrition", Destinations.NUTRITION)
        assertEquals(null, entry.capability)
        assertEquals(MenuGroup.MAIN, entry.group)
        assertEquals(60, entry.order)
        assertTrue(entry.enabled)
        // Visible sin capacidades, con suscripción no activa y para cualquier plan (planes/05, orden 60).
        assertTrue(NavigationRegistry.athleteMenu(null).any { it.hu == "HU043" })
        assertTrue(NavigationRegistry.athleteMenu(athlete(FREE_CAPABILITIES, SubscriptionStatus.PROVISIONING)).any { it.hu == "HU043" })
        assertTrue(NavigationRegistry.athleteMenu(athlete(PREMIUM_CAPABILITIES)).any { it.hu == "HU043" })
    }
}
