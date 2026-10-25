package com.lfnj.sport_app.navigation

/**
 * Destinos de la aplicación. La aplicación móvil es exclusiva para deportistas: su único inicio
 * autenticado es `athlete_home` (ADR-025 §1 y §10).
 */
object Destinations {
    const val SESSION_START = "session_start"
    const val LOGIN = "login"
    const val UNAUTHORIZED = "unauthorized"
    const val STATUS = "status"
    const val ATHLETE_HOME = "athlete_home"

    /** HU032: registrar una sesión de entrenamiento (`/entrenamientos/sesion`). */
    const val TRAINING_SESSION = "entrenamientos/sesion"

    /** HU033: tablero de entrenamientos (`/entrenamientos/tablero`). */
    const val TRAINING_DASHBOARD = "entrenamientos/tablero"

    /** HU034: indicadores FTP y VO₂max estimados (`/entrenamientos/metricas`). */
    const val TRAINING_METRICS = "entrenamientos/metricas"

    /** HU035: calendario deportivo del deportista (`/calendario`). */
    const val CALENDAR = "calendario"

    /** HU028: plantillas de plan sugeridas al deportista (`/entrenamientos/sugeridos`). */
    const val PLAN_SUGGESTIONS = "entrenamientos/sugeridos"

    /** HU026: rutas sugeridas al deportista (`/entrenamientos/rutas`). */
    const val ROUTE_SUGGESTIONS = "entrenamientos/rutas"

    /** HU040: sesiones virtuales con entrenadores (`/servicios/sesiones-virtuales`, ADR-046 §8). */
    const val VIRTUAL_SESSIONS = "servicios/sesiones-virtuales"

    /** Argumento de [EVENT_DETAIL]: `event_id` (UUID) del evento publicado. */
    const val EVENT_ID_ARG = "event_id"

    /**
     * HU041: detalle de un evento publicado con la inscripción (`/eventos/detalle/{event_id}`). Se
     * llega desde el calendario (HU035) o las sugerencias (HU027) con [eventDetail]; no es opción de menú.
     */
    const val EVENT_DETAIL = "eventos/detalle/{$EVENT_ID_ARG}"

    /** HU041: «Mis inscripciones» (`/eventos/inscripciones`), desde el calendario o el detalle. */
    const val MY_EVENT_REGISTRATIONS = "eventos/inscripciones"

    /** Ruta concreta de [EVENT_DETAIL] para [eventId]. */
    fun eventDetail(eventId: String): String = "eventos/detalle/$eventId"
}
