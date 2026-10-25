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
}
