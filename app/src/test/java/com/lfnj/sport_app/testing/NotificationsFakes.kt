package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.notifications.data.NotificationAnnouncer
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCacheDao
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCacheEntity
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.NotificationPriority
import com.lfnj.sport_app.features.notifications.model.NotificationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.mockwebserver.MockResponse
import java.time.Instant

/** Datos sintéticos de HU039 alineados con `NotificationResponse` de `bff-mobile`. */
object NotificationFixtures {
    const val NOTIFICATION_ID = "5b0e7c1a-2d3f-4a5b-8c6d-7e8f9a0b1c2d"
    const val OTHER_NOTIFICATION_ID = "4a9d6b09-1c2e-4f4a-9b5c-6d7e8f9a0b1c"
    const val THIRD_NOTIFICATION_ID = "3f8c5af8-0b1d-4e39-8a4b-5c6d7e8f9a0b"
    const val EVENT_ID = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef"

    fun item(
        notificationId: String = NOTIFICATION_ID,
        type: String = "EVENT_PUBLISHED_MATCH",
        priority: String = "NORMAL",
        eventId: String = EVENT_ID,
        createdAt: String = "2026-10-25T13:00:00Z",
        readAt: String? = null,
        title: String = "Nuevo evento de running en Bogotá",
    ): String =
        """{"notification_id":"$notificationId","type":"$type","priority":"$priority","event_id":"$eventId",""" +
            """"title":"$title","body":"Carrera 10K Bogotá el 15 de noviembre.","created_at":"$createdAt",""" +
            """"read_at":${readAt?.let { "\"$it\"" } ?: "null"}}"""

    fun reminder(notificationId: String = OTHER_NOTIFICATION_ID, createdAt: String = "2026-10-25T14:00:00Z", readAt: String? = null) =
        item(notificationId, "EVENT_REMINDER_24H", "HIGH", createdAt = createdAt, readAt = readAt, title = "Tu evento empieza mañana")

    fun page(items: List<String>, nextCursor: String? = null): String =
        """{"items":${items.joinToString(",", "[", "]")},"next_cursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}"""

    fun preferences(eventMatches: Boolean = true, eventReminders: Boolean = true, pushEnabled: Boolean = true, version: Int = 0) =
        """{"event_matches":$eventMatches,"event_reminders":$eventReminders,"push_enabled":$pushEnabled,"version":$version}"""

    fun error(status: Int, code: String, details: String = "[]"): MockResponse = MockResponse()
        .setResponseCode(status)
        .setBody("""{"code":"$code","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000039","details":$details}""")

    fun notification(
        notificationId: String = NOTIFICATION_ID,
        priority: NotificationPriority = NotificationPriority.NORMAL,
        createdAt: Instant = Instant.parse("2026-10-25T13:00:00Z"),
        readAt: Instant? = null,
    ) = InboxNotification(
        notificationId = notificationId,
        type = if (priority == NotificationPriority.HIGH) NotificationType.EVENT_REMINDER_24H else NotificationType.EVENT_PUBLISHED_MATCH,
        priority = priority,
        eventId = EVENT_ID,
        title = "Nuevo evento de running en Bogotá",
        body = "Carrera 10K Bogotá el 15 de noviembre.",
        createdAt = createdAt,
        readAt = readAt,
    )
}

/** Bandeja en memoria con la misma semántica que el DAO de Room (una fila por deportista). */
class InMemoryNotificationsDao : NotificationsCacheDao {
    val rows = MutableStateFlow<Map<String, NotificationsCacheEntity>>(emptyMap())
    var failWrites = false

    override fun observe(owner: String): Flow<NotificationsCacheEntity?> = rows.map { it[owner] }

    override suspend fun read(owner: String): NotificationsCacheEntity? = rows.value[owner]

    override suspend fun replace(entry: NotificationsCacheEntity) {
        check(!failWrites) { "disco lleno" }
        rows.value = rows.value + (entry.ownerKey to entry)
    }

    override suspend fun delete(owner: String) {
        rows.value = rows.value - owner
    }

    override suspend fun deleteAllExcept(owner: String) {
        rows.value = rows.value.filterKeys { it == owner }
    }
}

/** Registra los anuncios en lugar de publicarlos en Android. */
class RecordingAnnouncer : NotificationAnnouncer {
    val announced = mutableListOf<InboxNotification>()
    val dismissed = mutableListOf<String>()
    var fail = false

    override fun announce(items: List<InboxNotification>) {
        check(!fail) { "sistema de notificaciones no disponible" }
        announced += items
    }

    override fun dismiss(notificationId: String) {
        dismissed += notificationId
    }
}
