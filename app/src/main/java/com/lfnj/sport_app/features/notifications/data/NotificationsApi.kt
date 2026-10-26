package com.lfnj.sport_app.features.notifications.data

import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.NotificationIds
import com.lfnj.sport_app.features.notifications.model.NotificationPreferences
import com.lfnj.sport_app.features.notifications.model.NotificationPriority
import com.lfnj.sport_app.features.notifications.model.NotificationType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Operaciones de HU039 en `bff-mobile` (ADR-045 §11, tag `notifications`). La identidad sale del
 * token: ninguna solicitud lleva `recipient_id`. `PUT /v1/me/push-tokens` no se consume: FCM
 * requiere un SDK y un proyecto Firebase que no están autorizados (ADR-045 §2, pendiente).
 */
interface NotificationsApi {
    @GET("v1/me/notifications")
    suspend fun inbox(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String? = null,
    ): Response<NotificationsPageDto>

    @POST("v1/me/notifications/{notification_id}/read")
    suspend fun markRead(
        @Header("Authorization") authorization: String,
        @Path("notification_id") notificationId: String,
    ): Response<NotificationDto>

    @GET("v1/me/notification-preferences")
    suspend fun preferences(@Header("Authorization") authorization: String): Response<NotificationPreferencesDto>

    @PUT("v1/me/notification-preferences")
    suspend fun replacePreferences(
        @Header("Authorization") authorization: String,
        @Body body: NotificationPreferencesRequestDto,
    ): Response<NotificationPreferencesDto>
}

/** `NotificationResponse`. */
@Serializable
data class NotificationDto(
    @SerialName("notification_id") val notificationId: String,
    val type: String,
    val priority: String,
    @SerialName("event_id") val eventId: String,
    val title: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
) {
    override fun toString(): String = "NotificationDto(<oculto>)"
}

/** `NotificationsPageResponse`. */
@Serializable
data class NotificationsPageDto(
    val items: List<NotificationDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
) {
    override fun toString(): String = "NotificationsPageDto(items=${items.size})"
}

/** `NotificationPreferencesResponse`. */
@Serializable
data class NotificationPreferencesDto(
    @SerialName("event_matches") val eventMatches: Boolean,
    @SerialName("event_reminders") val eventReminders: Boolean,
    @SerialName("push_enabled") val pushEnabled: Boolean,
    val version: Int,
)

/** `NotificationPreferencesRequest`: reemplazo completo con control optimista (`expected_version`). */
@Serializable
data class NotificationPreferencesRequestDto(
    @SerialName("event_matches") val eventMatches: Boolean,
    @SerialName("event_reminders") val eventReminders: Boolean,
    @SerialName("push_enabled") val pushEnabled: Boolean,
    @SerialName("expected_version") val expectedVersion: Int,
)

/** Error de contrato: la respuesta no corresponde a ADR-045 y no se muestra parcialmente. */
class InvalidNotificationResponse : Exception()

/** Solo elementos con tipo, prioridad, identificadores e instantes válidos; nunca se completan datos. */
fun NotificationDto.toNotification(): InboxNotification {
    if (!NotificationIds.isValid(notificationId) || !NotificationIds.isValid(eventId)) throw InvalidNotificationResponse()
    val type = NotificationType.entries.firstOrNull { it.name == type } ?: throw InvalidNotificationResponse()
    val priority = NotificationPriority.entries.firstOrNull { it.name == priority } ?: throw InvalidNotificationResponse()
    return InboxNotification(
        notificationId = notificationId,
        type = type,
        priority = priority,
        eventId = eventId,
        title = title,
        body = body,
        createdAt = instant(createdAt),
        readAt = readAt?.let(::instant),
    )
}

fun NotificationPreferencesDto.toPreferences() = NotificationPreferences(eventMatches, eventReminders, pushEnabled, version)

private fun instant(value: String): Instant = try {
    OffsetDateTime.parse(value).toInstant()
} catch (_: DateTimeParseException) {
    throw InvalidNotificationResponse()
}
