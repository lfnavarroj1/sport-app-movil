package com.lfnj.sport_app.features.calendar.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.calendar.model.CalendarItem
import com.lfnj.sport_app.features.calendar.model.CalendarSnapshot
import com.lfnj.sport_app.features.calendar.model.CalendarSourceState
import com.lfnj.sport_app.features.calendar.model.EventLocation
import com.lfnj.sport_app.features.calendar.model.SourceStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Contenido cifrado de [CalendarCacheEntity.payload]. Instantes en segundos UTC; fechas ISO. */
@Serializable
internal data class CachePayload(
    val month: String,
    val from: Long,
    val to: Long,
    val items: List<CachedItem>,
    val sources: List<CachedSource>,
    @SerialName("evaluated_at") val evaluatedAt: Long,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedItem(
    val kind: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("occurs_on") val occursOn: String,
    val title: String,
    val sport: String? = null,
    @SerialName("starts_at") val startsAt: Long? = null,
    @SerialName("ends_at") val endsAt: Long? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    @SerialName("location_name") val locationName: String? = null,
    @SerialName("location_city") val locationCity: String? = null,
    @SerialName("location_country") val locationCountry: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    val rpe: Int? = null,
    val source: String? = null,
)

@Serializable
internal data class CachedSource(val name: String, val status: String? = null, val truncated: Boolean)

/**
 * Cifra y descifra el periodo confirmado con AES-GCM y una clave no exportable de Android
 * Keystore propia del calendario. Una caché que no puede descifrarse se trata como inexistente:
 * nunca se muestran elementos inventados (ADR-031, política offline §4).
 */
class CalendarCacheCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: CalendarSnapshot): ByteArray {
        val payload = CachePayload(
            month = snapshot.month.toString(),
            from = snapshot.from.epochSecond,
            to = snapshot.to.epochSecond,
            items = snapshot.items.map(::cached),
            sources = snapshot.sources.map { CachedSource(it.name, it.status?.name, it.truncated) },
            evaluatedAt = snapshot.evaluatedAt.epochSecond,
            lastSyncedAt = snapshot.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(CachePayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: CalendarCacheEntity): CalendarSnapshot? = try {
        val payload = json.decodeFromString(CachePayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        CalendarSnapshot(
            month = YearMonth.parse(payload.month),
            from = Instant.ofEpochSecond(payload.from),
            to = Instant.ofEpochSecond(payload.to),
            items = payload.items.map { it.toItem() ?: return null },
            sources = payload.sources.map { CalendarSourceState(it.name, it.status?.let(SourceStatus::fromWire), it.truncated) },
            evaluatedAt = Instant.ofEpochSecond(payload.evaluatedAt),
            syncedAt = Instant.ofEpochSecond(payload.lastSyncedAt),
        )
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: DateTimeException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun cached(item: CalendarItem): CachedItem = when (item) {
        is CalendarItem.Event -> CachedItem(
            kind = KIND_EVENT, itemId = item.itemId, occursOn = item.occursOn.toString(), title = item.title,
            sport = item.sport, startsAt = item.startsAt.epochSecond, endsAt = item.endsAt.epochSecond,
            timeZone = item.timeZone, locationName = item.location.name, locationCity = item.location.city,
            locationCountry = item.location.countryCode,
        )
        is CalendarItem.PlannedSession -> CachedItem(
            kind = KIND_SESSION, itemId = item.itemId, occursOn = item.occursOn.toString(), title = item.title,
            sport = item.sport, durationMinutes = item.durationMinutes, rpe = item.rpe,
        )
        is CalendarItem.Other -> CachedItem(
            kind = KIND_OTHER, itemId = item.itemId, occursOn = item.occursOn.toString(), title = item.title, source = item.source,
        )
    }

    private fun CachedItem.toItem(): CalendarItem? {
        val date = LocalDate.parse(occursOn)
        return when (kind) {
            KIND_EVENT -> CalendarItem.Event(
                itemId, date, title,
                sport = sport ?: return null,
                startsAt = Instant.ofEpochSecond(startsAt ?: return null),
                endsAt = Instant.ofEpochSecond(endsAt ?: return null),
                timeZone = timeZone ?: return null,
                location = EventLocation(locationName ?: return null, locationCity ?: return null, locationCountry ?: return null),
            )
            KIND_SESSION -> CalendarItem.PlannedSession(itemId, date, title, sport ?: return null, durationMinutes, rpe)
            KIND_OTHER -> CalendarItem.Other(itemId, date, title, source ?: return null)
            else -> null
        }
    }

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU035. */
        const val KEY_ALIAS = "sportapp.calendar.v1"
        private const val KIND_EVENT = "event"
        private const val KIND_SESSION = "session"
        private const val KIND_OTHER = "other"
    }
}
