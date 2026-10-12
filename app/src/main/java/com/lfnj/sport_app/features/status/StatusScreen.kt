package com.lfnj.sport_app.features.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.ui.theme.SportappTheme

@Composable
fun StatusScreen(state: StatusUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.status_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.status_app_version, state.appVersion))
        Text(stringResource(R.string.status_environment, state.environment))
        Text(stringResource(R.string.status_contract_version, state.contractVersion))
        if (state.checking) {
            CircularProgressIndicator(Modifier.testTag("status_loading"))
            Text(stringResource(R.string.status_checking))
        } else {
            state.availability?.let { availability ->
                Text(
                    text = stringResource(availability.messageRes()),
                    modifier = Modifier
                        .testTag("status_result")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (availability != BffAvailability.READY && availability != BffAvailability.NOT_CONFIGURED) {
                    Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

private fun BffAvailability.messageRes(): Int = when (this) {
    BffAvailability.READY -> R.string.status_bff_ready
    BffAvailability.UNAVAILABLE -> R.string.status_bff_unavailable
    BffAvailability.NO_CONNECTION -> R.string.status_bff_no_connection
    BffAvailability.NOT_CONFIGURED -> R.string.status_bff_not_configured
    BffAvailability.UNEXPECTED -> R.string.status_bff_unexpected
}

@Preview(showBackground = true)
@Composable
private fun StatusScreenPreview() {
    SportappTheme {
        StatusScreen(
            state = StatusUiState("0.1.0", "local", "0.3.0", availability = BffAvailability.NO_CONNECTION),
            onRetry = {},
        )
    }
}
