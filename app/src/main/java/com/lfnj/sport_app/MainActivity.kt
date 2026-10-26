package com.lfnj.sport_app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.lfnj.sport_app.navigation.AppNavHost
import com.lfnj.sport_app.ui.theme.SportappTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SportApp).container
        // HU039: el toque de una notificación del sistema abre el detalle del evento tras validar la sesión.
        // Solo en la creación inicial: una recreación conserva la navegación y no repite el destino.
        if (savedInstanceState == null) container.notificationLaunch.offer(intent)
        setContent {
            SportappTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavHost(container = container, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    /** HU039: con la aplicación abierta el toque llega aquí; la navegación vigente y su estado se conservan. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as SportApp).container.notificationLaunch.offer(intent)
    }
}
