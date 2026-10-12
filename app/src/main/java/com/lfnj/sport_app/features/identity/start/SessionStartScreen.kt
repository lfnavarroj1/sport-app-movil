package com.lfnj.sport_app.features.identity.start

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.session.RestoreOutcome
import com.lfnj.sport_app.ui.theme.BrandNight
import com.lfnj.sport_app.ui.theme.OnBrandNight

@Composable
fun SessionStartScreen(
    state: SessionStartUiState,
    onRetry: () -> Unit,
    onContinueToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BrandNight)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = OnBrandNight)
        val failure = state.failure
        if (failure == null) {
            CircularProgressIndicator(Modifier.testTag("start_loading"), color = OnBrandNight)
            Text(stringResource(R.string.start_restoring), color = OnBrandNight)
        } else {
            Text(
                text = when (failure) {
                    is RestoreOutcome.Unavailable ->
                        failure.correlationId?.let { stringResource(R.string.start_unavailable_reference, it) }
                            ?: stringResource(R.string.start_unavailable)
                    else -> stringResource(R.string.start_no_connection)
                },
                color = OnBrandNight,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .testTag("start_error")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            TextButton(onClick = onContinueToLogin) {
                Text(stringResource(R.string.start_go_to_login), color = OnBrandNight)
            }
        }
    }
}
