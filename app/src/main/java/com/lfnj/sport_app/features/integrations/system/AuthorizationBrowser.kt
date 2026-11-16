package com.lfnj.sport_app.features.integrations.system

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Abre la URL del consentimiento oficial de Strava; devuelve `false` si no hay navegador disponible. */
fun interface AuthorizationBrowser {
    fun open(context: Context, url: String): Boolean
}

/**
 * Abre el consentimiento en Custom Tabs (ADR-042 §5): la pestaña pertenece al navegador, de modo que
 * la app nunca ve ni captura las credenciales de Strava. Usa los extras públicos del protocolo de
 * Custom Tabs, sin agregar dependencias; un navegador sin soporte abre la misma URL normalmente.
 * Se lanza desde la actividad para que la pestaña quede en la tarea de la app y el retorno por el
 * enlace profundo la cierre.
 */
object CustomTabsAuthorizationBrowser : AuthorizationBrowser {
    /** `CustomTabsIntent.EXTRA_SESSION`: su presencia (aunque sea nula) solicita una Custom Tab. */
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"

    /** `CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE` con `SHOW_PAGE_TITLE`: el deportista ve el dominio y el título. */
    private const val EXTRA_TITLE_VISIBILITY = "android.support.customtabs.extra.TITLE_VISIBILITY"
    private const val SHOW_PAGE_TITLE = 1

    override fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
            putExtra(EXTRA_TITLE_VISIBILITY, SHOW_PAGE_TITLE)
        }
        val activity = context.findActivity()
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (activity ?: context).startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
