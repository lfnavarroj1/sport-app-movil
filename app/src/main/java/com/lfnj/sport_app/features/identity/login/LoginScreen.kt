package com.lfnj.sport_app.features.identity.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lfnj.sport_app.R
import com.lfnj.sport_app.ui.theme.BrandNight
import com.lfnj.sport_app.ui.theme.OnBrandNight
import com.lfnj.sport_app.ui.theme.SportappTheme

@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSubmit: () -> Unit,
    onOpenStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BrandNight)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = OnBrandNight)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium)
                    if (state.sessionExpired) {
                        Notice(stringResource(R.string.login_session_expired), tag = "login_notice")
                    }
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = { Text(stringResource(R.string.login_email)) },
                        singleLine = true,
                        enabled = !state.submitting,
                        isError = state.emailError != null,
                        supportingText = state.emailError?.let { { Text(stringResource(it.emailMessage())) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_email")
                            .semantics { contentType = ContentType.EmailAddress },
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.login_password)) },
                        singleLine = true,
                        enabled = !state.submitting,
                        isError = state.passwordError != null,
                        supportingText = state.passwordError?.let { { Text(stringResource(it.passwordMessage())) } },
                        visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                        trailingIcon = {
                            TextButton(onClick = onTogglePasswordVisibility, enabled = !state.submitting) {
                                Text(stringResource(if (state.passwordVisible) R.string.login_hide_password else R.string.login_show_password))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_password")
                            .semantics { contentType = ContentType.Password },
                    )
                    state.error?.let { Notice(it.message(), tag = "login_error", isError = true) }
                    Button(
                        onClick = onSubmit,
                        enabled = !state.submitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_submit"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.submitting) {
                                CircularProgressIndicator(Modifier.size(18.dp).testTag("login_loading"), strokeWidth = 2.dp)
                                Text(stringResource(R.string.login_submitting))
                            } else {
                                Text(stringResource(R.string.login_submit))
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onOpenStatus) {
                Text(stringResource(R.string.login_service_status), color = OnBrandNight)
            }
        }
    }
}

@Composable
private fun Notice(text: String, tag: String, isError: Boolean = false) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun FieldError.emailMessage(): Int = when (this) {
    FieldError.EMPTY -> R.string.login_email_required
    FieldError.TOO_LONG -> R.string.login_email_too_long
}

private fun FieldError.passwordMessage(): Int = when (this) {
    FieldError.EMPTY -> R.string.login_password_required
    FieldError.TOO_LONG -> R.string.login_password_too_long
}

@Composable
private fun LoginError.message(): String = when (this) {
    LoginError.InvalidCredentials -> stringResource(R.string.login_error_invalid_credentials)
    LoginError.ActorNotAllowed -> stringResource(R.string.login_error_actor_not_allowed)
    is LoginError.TooManyAttempts ->
        if (waitMinutes == null) stringResource(R.string.login_error_too_many_attempts)
        else pluralStringResource(
            R.plurals.login_error_too_many_attempts_wait, waitMinutes.toInt(), waitMinutes.toInt()
        )
    LoginError.InvalidInput -> stringResource(R.string.login_error_invalid_input)
    LoginError.NoConnection -> stringResource(R.string.login_error_no_connection)
    is LoginError.Unavailable ->
        if (correlationId == null) stringResource(R.string.login_error_unavailable)
        else stringResource(R.string.login_error_unavailable_reference, correlationId)
    LoginError.NotConfigured -> stringResource(R.string.login_error_not_configured)
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    SportappTheme {
        LoginScreen(
            state = LoginUiState(email = "deportista@example.com", error = LoginError.InvalidCredentials),
            onEmailChange = {}, onPasswordChange = {}, onTogglePasswordVisibility = {}, onSubmit = {}, onOpenStatus = {},
        )
    }
}
