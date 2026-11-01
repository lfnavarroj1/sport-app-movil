package com.lfnj.sport_app.features.notifications.model

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (ADR-026 §9, como HU041). */
object NotificationsOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** `notification_id` y `event_id` del contrato: UUID. Cualquier otro valor no se envía al BFF. */
object NotificationIds {
    fun isValid(value: String?): Boolean =
        value != null && runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
}

/** Tipos del MVP (ADR-045 §7). */
enum class NotificationType { EVENT_PUBLISHED_MATCH, EVENT_REMINDER_24H }

/** Prioridad asignada por el backend: `EVENT_PUBLISHED_MATCH` → `NORMAL`, `EVENT_REMINDER_24H` → `HIGH`. */
enum class NotificationPriority { NORMAL, HIGH }

/**
 * Canales de Android de HU039: `event_alerts` (`HIGH`) y `event_updates` (`NORMAL`). El canal sale
 * de la prioridad que envía el backend; el cliente no la recalcula.
 */
object NotificationChannels {
    const val EVENT_ALERTS = "event_alerts"
    const val EVENT_UPDATES = "event_updates"

    fun forPriority(priority: NotificationPriority): String = when (priority) {
        NotificationPriority.HIGH -> EVENT_ALERTS
        NotificationPriority.NORMAL -> EVENT_UPDATES
    }
}

/** Elemento de `notification_inbox_view` tal como lo confirmó el BFF (`NotificationResponse`). */
data class InboxNotification(
    val notificationId: String,
    val type: NotificationType,
    val priority: NotificationPriority,
    /** Evento del detalle (HU041). */
    val eventId: String,
    val title: String,
    val body: String,
    val createdAt: Instant,
    /** `null` mientras no se lea. */
    val readAt: Instant?,
) {
    val unread: Boolean get() = readAt == null

    /** Sin título ni texto: un `toString` accidental no expone el contenido. */
    override fun toString(): String = "InboxNotification(id=$notificationId, type=$type, unread=$unread)"
}

/**
 * Bandeja confirmada por el BFF para un deportista con su `last_synced_at` ([syncedAt]); se guarda
 * cifrada en el dispositivo. [announced] son los `notification_id` que ya produjeron una
 * notificación del sistema: la deduplicación local evita repetirlas en cada sincronización.
 */
data class InboxSnapshot(
    val items: List<InboxNotification>,
    /** Cursor opaco de la página siguiente, o `null` si ya se cargó todo. */
    val nextCursor: String?,
    val syncedAt: Instant,
    val announced: Set<String> = emptySet(),
) {
    /** Orden del contrato: `created_at DESC, notification_id DESC`. */
    val ordered: List<InboxNotification>
        get() = items.sortedWith(compareByDescending<InboxNotification> { it.createdAt }.thenByDescending { it.notificationId })

    val unreadCount: Int get() = items.count { it.unread }

    /** Agrega una página más antigua sin repetir elementos; las páginas antiguas no se anuncian. */
    fun append(page: List<InboxNotification>, cursor: String?): InboxSnapshot {
        val known = items.map { it.notificationId }.toSet()
        return copy(items = items + page.filterNot { it.notificationId in known }, nextCursor = cursor)
    }

    /** Refleja la lectura confirmada por el BFF. */
    fun withRead(notification: InboxNotification): InboxSnapshot =
        copy(items = items.map { if (it.notificationId == notification.notificationId) notification else it })

    companion object {
        /**
         * Primera página confirmada que reemplaza la bandeja anterior ([previous]). Devuelve la
         * bandeja nueva y los elementos que deben anunciarse en el sistema: no leídos y nunca
         * anunciados en esta partición. Un elemento que sale de la primera página no vuelve a ella
         * (keyset por `created_at DESC`), por lo que [announced] se limita a la página vigente.
         */
        fun firstPage(
            previous: InboxSnapshot?,
            page: List<InboxNotification>,
            cursor: String?,
            now: Instant,
        ): Pair<InboxSnapshot, List<InboxNotification>> {
            val alreadyAnnounced = previous?.announced.orEmpty()
            val unique = page.distinctBy { it.notificationId }
            val fresh = unique.filter { it.unread && it.notificationId !in alreadyAnnounced }
            val snapshot = InboxSnapshot(
                items = unique,
                nextCursor = cursor,
                syncedAt = now,
                announced = unique.map { it.notificationId }.toSet(),
            )
            return snapshot to fresh
        }
    }
}

/** Preferencias de HU039 (`NotificationPreferencesResponse`); `version = 0` mientras rigen los valores por defecto. */
data class NotificationPreferences(
    val eventMatches: Boolean,
    val eventReminders: Boolean,
    val pushEnabled: Boolean,
    val version: Int,
)

/** Estado de las notificaciones del sistema para la aplicación. */
enum class SystemNotificationsStatus {
    ENABLED,
    /** Android 13+: falta conceder `POST_NOTIFICATIONS`. */
    PERMISSION_REQUIRED,
    /** Desactivadas en los ajustes del sistema o en los canales de HU039. */
    DISABLED,
}
