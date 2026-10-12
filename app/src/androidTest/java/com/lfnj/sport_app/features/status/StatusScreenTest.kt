package com.lfnj.sport_app.features.status

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.ui.theme.SportappTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val base = StatusUiState(appVersion = "0.1.0", environment = "local", contractVersion = "0.3.0")

    private fun show(state: StatusUiState, onRetry: () -> Unit = {}) =
        composeRule.setContent { SportappTheme { StatusScreen(state = state, onRetry = onRetry) } }

    @Test
    fun carga() {
        show(base.copy(checking = true))

        composeRule.onNodeWithTag("status_loading").assertIsDisplayed()
        composeRule.onNodeWithText("Verificando el servicio…").assertIsDisplayed()
    }

    @Test
    fun disponibleSinReintento() {
        show(base.copy(availability = BffAvailability.READY))

        composeRule.onNodeWithText("El servicio está disponible.").assertIsDisplayed()
        composeRule.onNodeWithText("Reintentar").assertDoesNotExist()
    }

    @Test
    fun sinConexionPermiteReintentar() {
        var retries = 0
        show(base.copy(availability = BffAvailability.NO_CONNECTION)) { retries++ }

        composeRule.onNodeWithText("No hay conexión con el servicio.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Reintentar").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun sinConfiguracionNoOfreceReintento() {
        show(base.copy(availability = BffAvailability.NOT_CONFIGURED))

        composeRule.onNodeWithText("Esta versión de la aplicación no tiene configurado el servicio.").assertIsDisplayed()
        composeRule.onNodeWithText("Reintentar").assertDoesNotExist()
    }

    @Test
    fun muestraVersionYContrato() {
        show(base)

        composeRule.onNodeWithText("Contrato BFF móvil: 0.3.0").assertIsDisplayed()
        composeRule.onNodeWithText("Ambiente: local").assertIsDisplayed()
    }
}
