package com.lfnj.sport_app.features.integrations.system

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.lfnj.sport_app.MainActivity
import com.lfnj.sport_app.SportApp

/**
 * Recibe el enlace profundo `sportapp://integrations/strava?result=…` con el que el callback de
 * `bff-mobile` devuelve el control tras el consentimiento en Custom Tabs (HU031, ADR-042 §6). Entrega
 * solo el resultado a [StravaLinkLaunch] y regresa a la actividad única existente, cerrando la pestaña
 * de Strava que quedó encima, sin crear otra instancia de la aplicación. No registra el enlace.
 */
class StravaRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as? SportApp)?.container?.stravaLinkLaunch?.offer(intent?.dataString)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
