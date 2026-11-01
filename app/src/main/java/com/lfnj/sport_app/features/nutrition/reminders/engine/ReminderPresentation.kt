package com.lfnj.sport_app.features.nutrition.reminders.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.features.nutrition.reminders.model.SessionReminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Notificación local de un recordatorio; la implementación Android usa el canal `session_reminders`. */
interface ReminderNotifier {
    /** Estado del permiso, de las notificaciones de la aplicación y del canal de HU038. */
    fun status(): SystemNotificationsStatus

    /** `false` si el sistema no permitió publicarla (permiso revocado entre la comprobación y la publicación). */
    fun notify(reminder: SessionReminder): Boolean
}

/** Banner en pantalla de un recordatorio; [systemNotificationShown] es `false` cuando solo existe el banner (CA4). */
data class ReminderBanner(
    val sessionId: String,
    val type: ReminderType,
    val n: Int,
    val message: String,
    val disclaimer: String,
    val systemNotificationShown: Boolean,
)

/** Banner vigente para la interfaz; lo publica el motor y lo descarta el deportista o el fin de la sesión. */
class ReminderBannerCenter {
    private val _current = MutableStateFlow<ReminderBanner?>(null)
    val current: StateFlow<ReminderBanner?> = _current.asStateFlow()

    fun show(banner: ReminderBanner) {
        _current.value = banner
    }

    fun dismiss() {
        _current.value = null
    }

    /** Retira el banner de una sesión que terminó. */
    fun clearSession(sessionId: String) {
        if (_current.value?.sessionId == sessionId) _current.value = null
    }
}

fun ReminderBanner.presentation(): ReminderPresentation =
    if (systemNotificationShown) ReminderPresentation.NOTIFICATION_AND_BANNER else ReminderPresentation.BANNER_ONLY

/**
 * Notificaciones locales de HU038 en el canal `session_reminders`. Una ocurrencia se identifica
 * con la etiqueta `session_id:TYPE:n`, por lo que nunca se publica dos veces la misma. El permiso
 * `POST_NOTIFICATIONS` lo solicita la bandeja de HU039; aquí solo se lee su estado.
 */
class AndroidReminderNotifier(context: Context) : ReminderNotifier {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Crea el canal; es idempotente y respeta los ajustes que el usuario haya cambiado. */
    fun ensureChannel() {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.reminders_channel_description) }
        )
    }

    override fun status(): SystemNotificationsStatus {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return SystemNotificationsStatus.PERMISSION_REQUIRED
        if (!manager.areNotificationsEnabled()) return SystemNotificationsStatus.DISABLED
        val blocked = manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        return if (blocked) SystemNotificationsStatus.DISABLED else SystemNotificationsStatus.ENABLED
    }

    override fun notify(reminder: SessionReminder): Boolean {
        if (status() != SystemNotificationsStatus.ENABLED) return false
        ensureChannel()
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleOf(reminder.type)))
            .setContentText(reminder.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${reminder.message}\n${reminder.disclaimer}"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { open?.let(::setContentIntent) }
            .build()
        return try {
            manager.notify(tag(reminder), NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "session_reminders"
        const val NOTIFICATION_ID = 38

        fun tag(reminder: SessionReminder): String = "${reminder.sessionId}:${reminder.type.name}:${reminder.n}"

        fun titleOf(type: ReminderType): Int = when (type) {
            ReminderType.HYDRATION -> R.string.reminders_type_hydration
            ReminderType.NUTRITION -> R.string.reminders_type_nutrition
        }
    }
}
