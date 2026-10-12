package com.lfnj.sport_app.features.identity.unauthorized

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R

/**
 * Acceso no autorizado (ADR-025, HU003 CA10): actor ausente, desconocido o manipulado, o intento
 * de abrir `athlete_home` sin una sesión válida de deportista. No presenta menú de negocio ni
 * asume un rol; la sesión local ya fue eliminada.
 */
@Composable
fun UnauthorizedScreen(onGoToLogin: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("unauthorized"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.unauthorized_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.unauthorized_message), textAlign = TextAlign.Center)
        Button(onClick = onGoToLogin) { Text(stringResource(R.string.unauthorized_go_login)) }
    }
}
