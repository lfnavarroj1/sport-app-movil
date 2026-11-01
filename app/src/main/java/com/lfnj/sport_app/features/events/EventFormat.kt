package com.lfnj.sport_app.features.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Formatos localizados de HU041; fechas y horas siempre en la zona del dispositivo. */
internal object EventFormat {
    /** Idioma vigente de la configuración: observable, a diferencia de `Locale.getDefault()`. */
    @Composable
    @ReadOnlyComposable
    fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

    fun dateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

    /** Inicio y fin en [zone]; si terminan el mismo día, el fin muestra solo la hora. */
    fun range(startsAt: Instant, endsAt: Instant, zone: ZoneId, locale: Locale): Array<String> {
        val start = startsAt.atZone(zone)
        val end = endsAt.atZone(zone)
        val endText = if (start.toLocalDate() == end.toLocalDate()) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(end)
        } else {
            dateTime(endsAt, zone, locale)
        }
        return arrayOf(dateTime(startsAt, zone, locale), endText)
    }
}
