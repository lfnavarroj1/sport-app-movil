package com.lfnj.sport_app.features.notifications.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.notifications.data.NotificationAnnouncer
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.NotificationChannels
import com.lfnj.sport_app.features.notifications.model.NotificationIds
import com.lfnj.sport_app.features.notifications.model.NotificationPriority
import com.lfnj.sport_app.features.notifications.model.NotificationType
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lectura del estado de las notificaciones del sistema para la bandeja. */
fun interface SystemNotificationsProbe {
    fun status(): SystemNotificationsStatus
}

/**
 * Notificaciones del sistema de HU039: una por elemento nuevo, en el canal de su prioridad
 * (`event_alerts` para `HIGH`, `event_updates` para `NORMAL`). La etiqueta es el
 * `notification_id`, por lo que repetir el anuncio reemplaza la notificación en lugar de duplicarla.
 * Sin permiso o con las notificaciones desactivadas no publica nada: la bandeja sigue siendo el
 * canal base (ADR-045 §1). El contenido no se muestra completo en la pantalla de bloqueo.
 */
class AndroidNotificationAnnouncer(context: Context) : NotificationAnnouncer, SystemNotificationsProbe {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Crea los canales; es idempotente y respeta los ajustes que el usuario haya cambiado. */
    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(NotificationChannels.EVENT_ALERTS, context.getString(R.string.notifications_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.notifications_channel_alerts_description) }
        )
        system.createNotificationChannel(
            NotificationChannel(NotificationChannels.EVENT_UPDATES, context.getString(R.string.notifications_channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notifications_channel_updates_description) }
        )
    }

    override fun status(): SystemNotificationsStatus {
        if (!permissionGranted()) return SystemNotificationsStatus.PERMISSION_REQUIRED
        if (!manager.areNotificationsEnabled()) return SystemNotificationsStatus.DISABLED
        val blocked = listOf(NotificationChannels.EVENT_ALERTS, NotificationChannels.EVENT_UPDATES)
            .mapNotNull { manager.getNotificationChannel(it) }
            .any { it.importance == NotificationManager.IMPORTANCE_NONE }
        return if (blocked) SystemNotificationsStatus.DISABLED else SystemNotificationsStatus.ENABLED
    }

    override fun announce(items: List<InboxNotification>) {
        if (status() != SystemNotificationsStatus.ENABLED) return
        ensureChannels()
        items.forEach(::post)
    }

    override fun dismiss(notificationId: String) = manager.cancel(notificationId, NOTIFICATION_ID)

    private fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(item: InboxNotification) {
        val publicVersion = NotificationCompat.Builder(context, NotificationChannels.forPriority(item.priority))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notifications_public_title))
            .build()
        val notification = NotificationCompat.Builder(context, NotificationChannels.forPriority(item.priority))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.title)
            .setContentText(item.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setPriority(if (item.priority == NotificationPriority.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (item.type == NotificationType.EVENT_REMINDER_24H) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_RECOMMENDATION)
            .setWhen(item.createdAt.toEpochMilli())
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(openEventIntent(item))
            .build()
        try {
            manager.notify(item.notificationId, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // El permiso se revocó entre la comprobación y la publicación: la bandeja conserva el elemento.
        }
    }

    private fun openEventIntent(item: InboxNotification): PendingIntent {
        val intent = NotificationLaunch.intent(context, item.eventId, item.notificationId)
        return PendingIntent.getActivity(
            context,
            item.notificationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Identificador numérico común; cada notificación se distingue por su etiqueta (`notification_id`). */
        const val NOTIFICATION_ID = 39
    }
}

/** Destino pendiente al tocar una notificación del sistema: detalle del evento (HU041). */
data class NotificationOpen(val eventId: String, val notificationId: String)

/**
 * Traslada el toque de una notificación del sistema a la navegación. La actividad entrega el
 * intent y el inicio del deportista lo consume una sola vez, después de validar la sesión.
 */
class NotificationLaunch {
    private val _pending = MutableStateFlow<NotificationOpen?>(null)
    val pending: StateFlow<NotificationOpen?> = _pending.asStateFlow()

    /** Registra el destino si el intent proviene de una notificación de HU039 con identificadores válidos. */
    fun offer(intent: Intent?) {
        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID)
        val notificationId = intent?.getStringExtra(EXTRA_NOTIFICATION_ID)
        if (intent?.action == ACTION_OPEN && NotificationIds.isValid(eventId) && NotificationIds.isValid(notificationId)) {
            _pending.value = NotificationOpen(eventId!!, notificationId!!)
        }
    }

    /** Devuelve y elimina el destino pendiente. */
    fun consume(): NotificationOpen? = _pending.value.also { _pending.value = null }

    companion object {
        const val ACTION_OPEN = "com.lfnj.sport_app.action.OPEN_EVENT_NOTIFICATION"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /**
         * Con la aplicación abierta reutiliza la actividad única (`onNewIntent`) sin reiniciar la
         * tarea, de modo que una sesión de entrenamiento o un formulario en curso no se pierden; sin
         * ella, el arranque valida la sesión antes de abrir el evento.
         */
        fun intent(context: Context, eventId: String, notificationId: String): Intent =
            Intent(ACTION_OPEN).setPackage(context.packageName)
                .setClassName(context.packageName, "com.lfnj.sport_app.MainActivity")
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
