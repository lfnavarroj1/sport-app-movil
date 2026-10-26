package com.lfnj.sport_app.features.notifications.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.InboxSnapshot
import com.lfnj.sport_app.features.notifications.model.NotificationPriority
import com.lfnj.sport_app.features.notifications.model.NotificationType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.Instant

/** Contenido cifrado de [NotificationsCacheEntity.payload]. Instantes en milisegundos UTC. */
@Serializable
internal data class InboxPayload(
    val items: List<CachedNotification>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
    val announced: List<String> = emptyList(),
)

@Serializable
internal data class CachedNotification(
    @SerialName("notification_id") val notificationId: String,
    val type: String,
    val priority: String,
    @SerialName("event_id") val eventId: String,
    val title: String,
    val body: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("read_at") val readAt: Long? = null,
)

/**
 * Cifra y descifra la bandeja con AES-GCM y una clave no exportable de Android Keystore propia de
 * HU039. Una bandeja que no puede descifrarse se trata como inexistente: nunca se muestran
 * notificaciones inventadas.
 */
class NotificationsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(snapshot: InboxSnapshot): ByteArray {
        val payload = InboxPayload(
            items = snapshot.items.map(::cached),
            nextCursor = snapshot.nextCursor,
            lastSyncedAt = snapshot.syncedAt.toEpochMilli(),
            announced = snapshot.announced.sorted(),
        )
        return cipher.encrypt(json.encodeToString(InboxPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la bandeja no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: NotificationsCacheEntity): InboxSnapshot? = try {
        val payload = json.decodeFromString(InboxPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        InboxSnapshot(
            items = payload.items.map { it.toNotification() },
            nextCursor = payload.nextCursor,
            syncedAt = Instant.ofEpochMilli(payload.lastSyncedAt),
            announced = payload.announced.toSet(),
        )
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun cached(item: InboxNotification) = CachedNotification(
        notificationId = item.notificationId,
        type = item.type.name,
        priority = item.priority.name,
        eventId = item.eventId,
        title = item.title,
        body = item.body,
        createdAt = item.createdAt.toEpochMilli(),
        readAt = item.readAt?.toEpochMilli(),
    )

    /** `valueOf` lanza `IllegalArgumentException` ante un valor desconocido: la bandeja se descarta. */
    private fun CachedNotification.toNotification() = InboxNotification(
        notificationId = notificationId,
        type = NotificationType.valueOf(type),
        priority = NotificationPriority.valueOf(priority),
        eventId = eventId,
        title = title,
        body = body,
        createdAt = Instant.ofEpochMilli(createdAt),
        readAt = readAt?.let(Instant::ofEpochMilli),
    )

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la bandeja de HU039. */
        const val KEY_ALIAS = "sportapp.notifications.v1"
    }
}
