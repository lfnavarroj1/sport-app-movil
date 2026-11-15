package com.lfnj.sport_app.features.training.monitoring.system

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
import com.lfnj.sport_app.features.training.monitoring.RiskAlertNotifier
import com.lfnj.sport_app.features.training.monitoring.model.RiskAlert

/**
 * Notificación local de las alertas de riesgo (ADR-044 §12) en el canal `monitoring_alerts` con
 * importancia alta. Muestra el texto fijo del backend y su instrucción; nunca el valor medido.
 */
class AndroidRiskAlertNotifier(context: Context) : RiskAlertNotifier {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Crea el canal; es idempotente y respeta los ajustes que el usuario haya cambiado. */
    fun ensureChannel() {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.monitoring_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.monitoring_channel_description) }
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

    override fun notify(alert: RiskAlert): Boolean {
        if (status() != SystemNotificationsStatus.ENABLED) return false
        ensureChannel()
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.monitoring_alert_title))
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${alert.message}\n${alert.instruction}"))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { open?.let(::setContentIntent) }
            .build()
        return try {
            manager.notify(alert.alertId, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "monitoring_alerts"
        const val NOTIFICATION_ID = 36
    }
}
