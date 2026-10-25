package com.lfnj.sport_app.navigation

import androidx.annotation.StringRes
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.calendar.model.CALENDAR_CAPABILITY
import com.lfnj.sport_app.features.recommendations.model.TRAINING_PLANS_SUGGEST_CAPABILITY
import com.lfnj.sport_app.features.recommendations.model.TRAINING_ROUTES_SUGGEST_CAPABILITY
import com.lfnj.sport_app.features.training.dashboard.model.TRAINING_DASHBOARD_CAPABILITY
import com.lfnj.sport_app.features.training.metrics.model.TRAINING_METRICS_CAPABILITY
import com.lfnj.sport_app.features.training.session.model.TRAINING_SESSION_CAPABILITY

enum class MenuGroup(@param:StringRes val label: Int?) {
    MAIN(null),
    TRAINING(R.string.menu_group_training),
    EVENTS(R.string.menu_group_events),
    SERVICES(R.string.menu_group_services),
    NUTRITION(R.string.menu_group_nutrition),
    INTEGRATIONS(R.string.menu_group_integrations),
    CALENDAR(R.string.menu_group_calendar),
    DEVICES(R.string.menu_group_devices),
    ACCOUNT(R.string.menu_group_account),
}

/** Acción del menú que no navega a un destino. */
enum class MenuAction { LOGOUT }

/**
 * Opción del menú lateral del deportista. [capability] es `null` para las opciones base.
 * Una opción sin [route] ni [action] corresponde a una HU aún no disponible en el canal móvil:
 * se muestra deshabilitada como «Próximamente», sin enlace ni acción.
 */
data class MenuEntry(
    val id: String,
    @param:StringRes val label: Int,
    val group: MenuGroup,
    val order: Int,
    val hu: String,
    val capability: String? = null,
    val route: String? = null,
    val action: MenuAction? = null,
) {
    val enabled: Boolean get() = route != null || action != null
}

/**
 * Destino navegable que no es opción de menú: se abre desde otra pantalla ([origins], HU de
 * origen). [capability] es `null` cuando el destino no exige capacidad de plan.
 */
data class ContextualDestination(
    val route: String,
    val hu: String,
    val origins: List<String>,
    val capability: String? = null,
)

/**
 * Registro único de navegación móvil (ADR-025 y `planes/05-menu-lateral-por-plan.md`). La
 * aplicación es exclusiva para deportistas, por lo que solo existe el menú de `athlete`, con las
 * capacidades de plan cuyas HU están aprobadas para el canal móvil (HU026–HU038 y HU040) y
 * Cerrar sesión (HU004). No aparecen opciones web (HU010, HU017) ni de cuenta sin canal móvil
 * aprobado (Mi perfil HU002, Mi plan HU016).
 */
object NavigationRegistry {
    private fun capability(code: String, label: Int, group: MenuGroup, order: Int, hu: String, route: String? = null) =
        MenuEntry(code, label, group, order, hu, capability = code, route = route)

    val athlete: List<MenuEntry> = listOf(
        MenuEntry("home", R.string.menu_home, MenuGroup.MAIN, 10, "HU003", route = Destinations.ATHLETE_HOME),
        capability(TRAINING_ROUTES_SUGGEST_CAPABILITY, R.string.menu_athlete_routes, MenuGroup.TRAINING, 110, "HU026", Destinations.ROUTE_SUGGESTIONS),
        capability("events.suggest", R.string.menu_athlete_events, MenuGroup.EVENTS, 120, "HU027"),
        capability(TRAINING_PLANS_SUGGEST_CAPABILITY, R.string.menu_athlete_training_suggestions, MenuGroup.TRAINING, 130, "HU028", Destinations.PLAN_SUGGESTIONS),
        capability("services.support.suggest", R.string.menu_athlete_support, MenuGroup.SERVICES, 140, "HU029"),
        capability("nutrition.routines.suggest", R.string.menu_athlete_nutrition_routines, MenuGroup.NUTRITION, 150, "HU030"),
        capability("integrations.strava.connect", R.string.menu_athlete_strava, MenuGroup.INTEGRATIONS, 160, "HU031"),
        capability(TRAINING_SESSION_CAPABILITY, R.string.menu_athlete_session, MenuGroup.TRAINING, 170, "HU032", Destinations.TRAINING_SESSION),
        capability(TRAINING_DASHBOARD_CAPABILITY, R.string.menu_athlete_dashboard, MenuGroup.TRAINING, 180, "HU033", Destinations.TRAINING_DASHBOARD),
        capability(TRAINING_METRICS_CAPABILITY, R.string.menu_athlete_metrics, MenuGroup.TRAINING, 190, "HU034", Destinations.TRAINING_METRICS),
        capability(CALENDAR_CAPABILITY, R.string.menu_athlete_calendar, MenuGroup.CALENDAR, 200, "HU035", Destinations.CALENDAR),
        capability("training.realtime.monitor", R.string.menu_athlete_realtime, MenuGroup.TRAINING, 210, "HU036"),
        capability("devices.measurement.connect", R.string.menu_athlete_devices, MenuGroup.DEVICES, 220, "HU037"),
        capability("nutrition.session.reminders", R.string.menu_athlete_reminders, MenuGroup.NUTRITION, 230, "HU038"),
        capability("services.virtual_sessions.schedule", R.string.menu_athlete_virtual_sessions, MenuGroup.SERVICES, 250, "HU040"),
        MenuEntry("logout", R.string.menu_logout, MenuGroup.ACCOUNT, 900, "HU004"),
    )

    /**
     * Destinos del deportista sin opción de menú. HU041 registra el detalle del evento publicado y
     * «Mis inscripciones»: se llega desde el calendario (HU035) o las sugerencias (HU027) y la
     * inscripción no exige capacidad de plan (ADR-032 §1).
     */
    val contextual: List<ContextualDestination> = listOf(
        ContextualDestination(Destinations.EVENT_DETAIL, "HU041", origins = listOf("HU035", "HU027", "HU041")),
        ContextualDestination(Destinations.MY_EVENT_REGISTRATIONS, "HU041", origins = listOf("HU035", "HU041")),
    )

    /**
     * opciones visibles = deportista ∩ canal móvil ∩ capacidades vigentes (HU003 CA7).
     * Sin [access] (no cargado o con error), con suscripción distinta de `ACTIVE` o con un actor
     * que no sea deportista, solo quedan las opciones base. «Cerrar sesión» solo se habilita con
     * [logoutAvailable], es decir, cuando el contenedor provee la cola de revocación (HU004).
     * Con [openTrainingSession] se muestra «Registrar entrenamiento» aunque la capacidad ya no esté
     * vigente: finalizar una sesión propia no la exige (HU032, ADR-029 §1).
     */
    fun athleteMenu(access: AccessSnapshot?, logoutAvailable: Boolean = false, openTrainingSession: Boolean = false): List<MenuEntry> {
        val capabilities = access
            ?.takeIf { it.actorType == ActorType.ATHLETE && it.subscriptionStatus == SubscriptionStatus.ACTIVE }
            ?.capabilities
            .orEmpty()
            .let { if (openTrainingSession) it + TRAINING_SESSION_CAPABILITY else it }
        return athlete
            .filter { it.capability == null || it.capability in capabilities }
            .map { if (it.id == "logout" && logoutAvailable) it.copy(action = MenuAction.LOGOUT) else it }
            .sortedBy { it.order }
    }

    /** Agrupa conservando el orden de la primera opción de cada grupo. */
    fun grouped(entries: List<MenuEntry>): List<Pair<MenuGroup, List<MenuEntry>>> =
        entries.sortedBy { it.order }.groupBy { it.group }.toList()
}
