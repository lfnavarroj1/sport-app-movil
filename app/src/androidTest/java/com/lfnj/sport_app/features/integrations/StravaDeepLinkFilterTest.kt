package com.lfnj.sport_app.features.integrations

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.features.integrations.system.StravaRedirectActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HU031: el enlace profundo del callback de `bff-mobile` (ADR-042 §6) llega solo a la actividad
 * trampolín de la app y ningún otro esquema o ruta la abre. Consulta el registro de paquetes del
 * teléfono sin lanzar actividades ni tocar datos.
 */
@RunWith(AndroidJUnit4::class)
class StravaDeepLinkFilterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun handlers(link: String): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.name }
    }

    @Test
    fun elRetornoDeStravaLoRecibeLaActividadTrampolin() {
        listOf("LINKED", "DENIED", "INVALID_STATE", "ERROR").forEach { result ->
            assertEquals(listOf(StravaRedirectActivity::class.java.name), handlers("sportapp://integrations/strava?result=$result"))
        }
    }

    @Test
    fun otrasRutasOEsquemasNoAbrenLaApp() {
        assertTrue(handlers("sportapp://integrations/garmin?result=LINKED").isEmpty())
        assertTrue(handlers("sportapp://otra/strava?result=LINKED").isEmpty())
        assertTrue(handlers("https://integrations/strava?result=LINKED").isEmpty())
    }
}
