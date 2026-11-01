package com.lfnj.sport_app.features.nutrition.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lfnj.sport_app.R
import com.lfnj.sport_app.features.nutrition.reminders.engine.AndroidReminderNotifier
import com.lfnj.sport_app.features.nutrition.reminders.engine.ReminderBanner
import com.lfnj.sport_app.features.nutrition.reminders.engine.ReminderBannerCenter

/** Banner global de HU038 sobre cualquier pantalla: observa el centro de banners del motor local. */
@Composable
fun ReminderBannerHost(center: ReminderBannerCenter, modifier: Modifier = Modifier) {
    val banner by center.current.collectAsStateWithLifecycle()
    banner?.let { ReminderBannerCard(it, onDismiss = center::dismiss, modifier = modifier) }
}

/**
 * Recordatorio en pantalla con el mensaje y el aviso aprobados del ruleset, sin modificarlos. Si
 * las notificaciones del sistema están desactivadas lo declara: este banner es el único aviso (CA4).
 */
@Composable
fun ReminderBannerCard(banner: ReminderBanner, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(AndroidReminderNotifier.titleOf(banner.type))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag("reminder_banner")
            .semantics {
                paneTitle = title
                liveRegion = LiveRegionMode.Assertive
            },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(banner.message, modifier = Modifier.testTag("reminder_banner_message"))
            Text(banner.disclaimer, style = MaterialTheme.typography.bodySmall)
            if (!banner.systemNotificationShown) {
                Text(
                    stringResource(R.string.reminders_banner_only),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("reminder_banner_only"),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("reminder_banner_dismiss")) {
                    Text(stringResource(R.string.reminders_banner_dismiss))
                }
            }
        }
    }
}
