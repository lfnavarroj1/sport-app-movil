package com.lfnj.sport_app.features.notifications.system

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lfnj.sport_app.features.notifications.model.SystemNotificationsStatus

/**
 * Acción «activar notificaciones» de la bandeja: en Android 13+ solicita `POST_NOTIFICATIONS` la
 * primera vez, cuando el deportista lo pide desde la explicación de la pantalla; si ya se rechazó o
 * las notificaciones están desactivadas, abre los ajustes de notificaciones de la aplicación.
 * [onResult] se invoca al volver para releer el estado real.
 */
@Composable
fun rememberSystemNotificationsEnabler(onResult: () -> Unit): (SystemNotificationsStatus) -> Unit {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    return remember(context, launcher, asked) {
        { status ->
            if (status == SystemNotificationsStatus.PERMISSION_REQUIRED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
                asked = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings(context)
            }
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Dispositivo sin pantalla de ajustes de notificaciones: la bandeja sigue disponible.
    }
}
