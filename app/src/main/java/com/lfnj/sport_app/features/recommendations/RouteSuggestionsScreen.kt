package com.lfnj.sport_app.features.recommendations

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.recommendations.model.RouteDifficulty
import com.lfnj.sport_app.features.recommendations.model.RouteFormat
import com.lfnj.sport_app.features.recommendations.model.RouteMaps
import com.lfnj.sport_app.features.recommendations.model.RouteMissingContext
import com.lfnj.sport_app.features.recommendations.model.RoutePoint
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestion
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionReason
import com.lfnj.sport_app.features.recommendations.model.RouteSuggestionsSnapshot
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import java.time.ZoneId
import java.util.Locale

/**
 * «Rutas sugeridas» (HU026): rutas publicadas por organizadores con distancia, dificultad, autor y
 * motivo; detalle con el punto de inicio, que se abre en la aplicación de mapas del teléfono
 * (`geo:`), y los puntos intermedios como lista. Indica si la lista no es personalizada y qué dato
 * falta, la antigüedad de la última lista confirmada y que una sugerencia no garantiza seguridad.
 * [onOpenMap] recibe la URI `geo:` y devuelve `false` si ninguna aplicación puede abrirla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSuggestionsScreen(
    state: RouteSuggestionsUiState,
    onOpenDetail: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onRetry: () -> Unit,
    onOpenMap: (String) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.selected
    BackHandler(enabled = detail != null, onBack = onCloseDetail)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (detail == null) R.string.route_suggestions_title else R.string.route_suggestions_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = if (detail == null) onBack else onCloseDetail, modifier = Modifier.testTag("route_suggestions_back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("route_suggestions_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val snapshot = state.snapshot
            when {
                state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.testTag("route_suggestions_loading"))
                    Text(stringResource(R.string.route_suggestions_loading))
                }
                snapshot == null -> WithoutSnapshot(state, onRetry)
                detail != null -> {
                    SyncStatus(state, snapshot, onRetry)
                    RouteDetail(detail, state.zone, onOpenMap)
                    SafetyNotice()
                }
                else -> {
                    SyncStatus(state, snapshot, onRetry)
                    Personalization(snapshot)
                    SafetyNotice()
                    if (state.empty) {
                        Text(
                            stringResource(if (snapshot.personalized) R.string.route_suggestions_empty_personalized else R.string.route_suggestions_empty),
                            Modifier.testTag("route_suggestions_empty"),
                        )
                    } else {
                        snapshot.items.forEach { RouteCard(it, onOpenDetail) }
                    }
                }
            }
        }
    }
}

/** Sin lista confirmada: nunca sincronizada sin red, error o acceso rechazado. */
@Composable
private fun WithoutSnapshot(state: RouteSuggestionsUiState, onRetry: () -> Unit) {
    if (state.neverSynced) {
        Notice(R.string.route_suggestions_never_synced, "route_suggestions_never_synced")
    } else {
        Notice(errorMessage(state.problem), "route_suggestions_error", MaterialTheme.colorScheme.error)
    }
    Button(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("route_suggestions_retry")) {
        Text(stringResource(R.string.action_retry))
    }
}

private fun errorMessage(problem: RouteSuggestionsProblem?): Int = when (problem) {
    RouteSuggestionsProblem.CAPABILITY_REQUIRED -> R.string.route_suggestions_error_capability_required
    RouteSuggestionsProblem.PERMISSION_REQUIRED -> R.string.route_suggestions_error_permission_required
    RouteSuggestionsProblem.INVALID_REQUEST -> R.string.route_suggestions_error_invalid_request
    RouteSuggestionsProblem.INVALID_RESPONSE -> R.string.route_suggestions_error_invalid_response
    RouteSuggestionsProblem.STORAGE -> R.string.route_suggestions_error_storage
    RouteSuggestionsProblem.NO_CONNECTION, RouteSuggestionsProblem.UNAVAILABLE, null -> R.string.route_suggestions_error_unavailable
}

/** Actualización en curso, antigüedad y conectividad de la lista mostrada. */
@Composable
private fun SyncStatus(state: RouteSuggestionsUiState, snapshot: RouteSuggestionsSnapshot, onRetry: () -> Unit) {
    if (state.refreshing) {
        val refreshing = stringResource(R.string.route_suggestions_refreshing)
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .testTag("route_suggestions_refreshing")
                .semantics { contentDescription = refreshing },
        )
    }
    Text(
        stringResource(R.string.route_suggestions_synced_at, SuggestionsFormat.dateTime(snapshot.syncedAt, state.zone, currentLocale())),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("route_suggestions_synced_at"),
    )
    if (state.stale) Notice(R.string.route_suggestions_stale, "route_suggestions_stale", MaterialTheme.colorScheme.error)
    when (state.problem) {
        null -> Unit
        RouteSuggestionsProblem.NO_CONNECTION -> Notice(R.string.route_suggestions_offline_with_cache, "route_suggestions_problem")
        else -> {
            Notice(R.string.route_suggestions_unavailable_with_cache, "route_suggestions_problem")
            OutlinedButton(onClick = onRetry, enabled = !state.refreshing, modifier = Modifier.testTag("route_suggestions_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * Cuándo la lista es «para ti» y cuándo no (CA3): nunca se presenta como personalizada una lista
 * con `personalized: false`, y se explica cada dato faltante.
 */
@Composable
private fun Personalization(snapshot: RouteSuggestionsSnapshot) {
    val missing = snapshot.missingContext
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.personalized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (snapshot.personalized) "route_suggestions_personalized" else "route_suggestions_not_personalized")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val headline = when {
                !snapshot.personalized -> R.string.route_suggestions_not_personalized
                RouteMissingContext.CITY.wire in missing -> R.string.route_suggestions_personalized_sport_only
                else -> R.string.route_suggestions_personalized_full
            }
            Text(stringResource(headline), style = MaterialTheme.typography.titleSmall)
            if (snapshot.contextUnavailable) {
                Text(stringResource(R.string.route_suggestions_context_unavailable), Modifier.testTag("route_suggestions_context_unavailable"))
            } else {
                missing.forEach { code ->
                    val text = when (RouteMissingContext.fromWire(code)) {
                        RouteMissingContext.SPORTS -> R.string.route_suggestions_missing_sports
                        RouteMissingContext.CITY -> R.string.route_suggestions_missing_city
                        null -> R.string.route_suggestions_missing_other
                    }
                    Text(stringResource(text), Modifier.testTag("route_suggestions_missing_$code"))
                }
            }
        }
    }
}

/** Texto fijo de HU026: sugerir una ruta no valora ni garantiza su seguridad. */
@Composable
private fun SafetyNotice() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("route_suggestions_safety")
            .semantics(mergeDescendants = true) { },
    ) {
        Text(stringResource(R.string.route_suggestions_safety), Modifier.padding(12.dp))
    }
}

/** Tarjeta de una ruta: nombre, autor, deporte, distancia, dificultad, lugar y motivo (CA3). */
@Composable
private fun RouteCard(item: RouteSuggestion, onOpenDetail: (String) -> Unit) {
    val openDescription = stringResource(R.string.route_suggestions_open_detail, item.title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = openDescription) { onOpenDetail(item.routeId) }
            .testTag("route_suggestion_${item.routeId}")
            .semantics(mergeDescendants = true) { },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Author(item)
            Text(
                stringResource(
                    R.string.route_suggestions_summary_line,
                    sportLabel(item.sport),
                    distanceLabel(item.distanceKm),
                    difficultyLabel(item.difficulty),
                )
            )
            Text(stringResource(R.string.route_suggestions_place, item.city, item.countryCode))
            Reasons(item)
        }
    }
}

@Composable
private fun Author(item: RouteSuggestion) {
    val author = item.authorName
    Text(
        if (author != null) stringResource(R.string.route_suggestions_author, author) else stringResource(R.string.route_suggestions_author_unknown),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Motivos de la sugerencia; en la lista no personalizada el BFF no envía ninguno. */
@Composable
private fun Reasons(item: RouteSuggestion) {
    item.knownReasons.forEach { reason ->
        Text(
            stringResource(
                when (reason) {
                    RouteSuggestionReason.SPORT_MATCH -> R.string.route_suggestions_reason_sport_match
                    RouteSuggestionReason.CITY_MATCH -> R.string.route_suggestions_reason_city_match
                }
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("route_suggestion_reason_${reason.wire}"),
        )
    }
}

/** Detalle de la ruta: punto de inicio con apertura en mapas y puntos intermedios como lista. */
@Composable
private fun RouteDetail(item: RouteSuggestion, zone: ZoneId, onOpenMap: (String) -> Boolean) {
    val locale = currentLocale()
    var mapUnavailable by remember(item.routeId) { mutableStateOf(false) }
    Column(Modifier.testTag("route_suggestion_detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Author(item)
        Text(stringResource(R.string.route_suggestions_field_sport, sportLabel(item.sport)))
        Text(stringResource(R.string.route_suggestions_field_distance, distanceLabel(item.distanceKm)), Modifier.testTag("route_suggestion_distance"))
        Text(stringResource(R.string.route_suggestions_field_difficulty, difficultyLabel(item.difficulty)), Modifier.testTag("route_suggestion_difficulty"))
        Text(stringResource(R.string.route_suggestions_field_place, item.city, item.countryCode))
        Text(
            stringResource(R.string.route_suggestions_field_published, SuggestionsFormat.date(item.publishedAt, zone, locale)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (item.knownReasons.isNotEmpty()) {
            SectionTitle(R.string.route_suggestions_reasons_title)
            Reasons(item)
        }
        item.summary?.let {
            SectionTitle(R.string.route_suggestions_description_title)
            Text(it, Modifier.testTag("route_suggestion_description"))
        }
        SectionTitle(R.string.route_suggestions_start_title)
        Text(pointLabel(item.startPoint, locale), Modifier.testTag("route_suggestion_start_point"))
        val openMap = stringResource(R.string.route_suggestions_open_map_description, item.title)
        Button(
            onClick = { mapUnavailable = !onOpenMap(RouteMaps.geoUri(item.startPoint, item.title)) },
            modifier = Modifier
                .testTag("route_suggestion_open_map")
                .semantics { contentDescription = openMap },
        ) {
            Text(stringResource(R.string.route_suggestions_open_map))
        }
        if (mapUnavailable) Notice(R.string.route_suggestions_map_unavailable, "route_suggestion_map_unavailable", MaterialTheme.colorScheme.error)
        SectionTitle(R.string.route_suggestions_waypoints_title)
        if (item.waypoints.isEmpty()) {
            Text(stringResource(R.string.route_suggestions_waypoints_empty), Modifier.testTag("route_suggestion_waypoints_empty"))
        } else {
            Text(pluralStringResource(R.plurals.route_suggestions_waypoints_count, item.waypoints.size, item.waypoints.size))
            item.waypoints.forEachIndexed { index, point ->
                Text(
                    stringResource(R.string.route_suggestions_waypoint, index + 1, pointLabel(point, locale)),
                    Modifier.testTag("route_suggestion_waypoint_$index"),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Notice(message: Int, tag: String, color: Color = Color.Unspecified) {
    Text(
        stringResource(message),
        color = color,
        modifier = Modifier
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Un código que esta versión no conoce se muestra tal cual: nunca se reinterpreta como otro valor. */
@Composable
private fun sportLabel(code: String): String = TrainingSport.fromWire(code)?.let { stringResource(it.label) } ?: code

@Composable
private fun difficultyLabel(code: String): String = when (RouteDifficulty.fromWire(code)) {
    RouteDifficulty.LOW -> stringResource(R.string.route_suggestions_difficulty_low)
    RouteDifficulty.MODERATE -> stringResource(R.string.route_suggestions_difficulty_moderate)
    RouteDifficulty.HIGH -> stringResource(R.string.route_suggestions_difficulty_high)
    null -> code
}

@Composable
private fun distanceLabel(distanceKm: String): String = stringResource(R.string.route_suggestions_distance, RouteFormat.distance(distanceKm, currentLocale()))

@Composable
private fun pointLabel(point: RoutePoint, locale: Locale): String =
    stringResource(R.string.route_suggestions_point, RouteFormat.coordinate(point.lat, locale), RouteFormat.coordinate(point.lng, locale))

/** Idioma vigente de la configuración: observable, a diferencia de `Locale.getDefault()`. */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * Abre la URI `geo:` en la aplicación de mapas que elija el sistema. No hay proveedor cartográfico
 * propio ni se registra la ubicación: solo se entrega el punto público de la ruta al intent.
 */
object RouteMapLauncher {
    fun intent(geoUri: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))

    /** `false` si ninguna aplicación del teléfono puede abrir la URI. */
    fun open(context: Context, geoUri: String): Boolean = try {
        val intent = intent(geoUri).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
