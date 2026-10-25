package com.lfnj.sport_app.features.services.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.SubscriptionStatus
import java.math.BigDecimal
import java.security.MessageDigest
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Capacidad `PREMIUM` que habilita «Sesiones virtuales» (HU040, ADR-046). */
const val VIRTUAL_SESSIONS_CAPABILITY = "services.virtual_sessions.schedule"

/**
 * Guarda visual de la ruta: solo un deportista con suscripción activa y la capacidad vigente.
 * Mejora la experiencia, pero no reemplaza la verificación ante Afiliación que hace el backend.
 */
object VirtualSessionsAccess {
    fun canView(access: AccessSnapshot?): Boolean =
        access != null && access.subscriptionStatus == SubscriptionStatus.ACTIVE && VIRTUAL_SESSIONS_CAPABILITY in access.capabilities
}

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (patrón ADR-031). */
object VirtualSessionsOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Reglas de ADR-046 que el cliente explica antes de enviar; el backend sigue siendo la autoridad. */
object VirtualSessionsRules {
    /** Un intervalo se reserva si empieza por lo menos 1 h después (`422 availability_not_bookable`). */
    val BOOKING_LEAD: Duration = Duration.of(1, ChronoUnit.HOURS)

    /** Una cita se cancela si faltan 24 h o más (`422 cancellation_window_closed`). */
    val CANCELLATION_WINDOW: Duration = Duration.of(24, ChronoUnit.HOURS)

    /** La última lectura confirmada se marca desactualizada a las 24 h (ADR-046 §8). */
    val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)

    /** Rango consultado de disponibilidad: dentro del máximo de 31 días del contrato. */
    val CATALOG_RANGE: Duration = Duration.of(30, ChronoUnit.DAYS)

    fun isBookable(startsAt: Instant, now: Instant): Boolean = !Duration.between(now, startsAt).minus(BOOKING_LEAD).isNegative

    fun canCancel(startsAt: Instant, now: Instant): Boolean = !Duration.between(now, startsAt).minus(CANCELLATION_WINDOW).isNegative

    /** Último instante en que la cita todavía puede cancelarse. */
    fun cancellationDeadline(startsAt: Instant): Instant = startsAt.minus(CANCELLATION_WINDOW)
}

/** Modalidades reservables (`VIRTUAL`, `HYBRID`): la consulta pide solo estas. */
enum class ServiceModality(val wire: String) {
    VIRTUAL("VIRTUAL"),
    HYBRID("HYBRID");

    companion object {
        fun fromWire(value: String): ServiceModality? = entries.firstOrNull { it.wire == value }
    }
}

/** Unidad de cobro de la tarifa informativa (`BillingUnit`); un código desconocido se muestra tal cual. */
enum class BillingUnit(val wire: String) {
    SERVICE("SERVICE"),
    SESSION("SESSION"),
    HOUR("HOUR"),
    DAY("DAY");

    companion object {
        fun fromWire(value: String): BillingUnit? = entries.firstOrNull { it.wire == value }
    }
}

/** Estados de la cita en ADR-046 §1. */
enum class AppointmentStatus(val wire: String) {
    BOOKED("BOOKED"),
    CANCELLED_BY_ATHLETE("CANCELLED_BY_ATHLETE");

    companion object {
        fun fromWire(value: String): AppointmentStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Tarifa informativa (ADR-027): reservar no cobra. [amount] conserva la cadena decimal recibida. */
data class ServiceFee(val amount: String, val currency: String, val billingUnit: String)

/** Intervalo `[startsAt, endsAt)` en UTC con la zona IANA declarada por el profesional. */
data class AvailabilitySlot(
    val availabilityId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val timeZone: ZoneId,
    val capacity: Int,
)

/**
 * Servicio `COACHING` virtual o híbrido con disponibilidad. El contrato no publica el nombre del
 * profesional, solo [providerId]: la pantalla muestra el nombre del servicio.
 */
data class CoachingService(
    val serviceId: String,
    val providerId: String,
    val name: String,
    val description: String,
    val modality: ServiceModality,
    val fee: ServiceFee,
    val slots: List<AvailabilitySlot>,
)

/** Última lista de servicios confirmada por el BFF, con su `last_synced_at` ([syncedAt]). */
data class CatalogSnapshot(
    val services: List<CoachingService>,
    /** El BFF tenía más páginas que las consultadas: la lista no está completa. */
    val truncated: Boolean,
    val syncedAt: Instant,
) {
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > VirtualSessionsRules.STALE_AFTER

    fun find(serviceId: String): CoachingService? = services.firstOrNull { it.serviceId == serviceId }
}

/**
 * Cita confirmada por el backend. `meeting_url` es siempre `null` en el MVP (ADR-046 §6): no se
 * guarda y la pantalla explica que el profesional coordinará el enlace.
 */
data class Appointment(
    val appointmentId: String,
    val serviceId: String,
    val availabilityId: String,
    val providerId: String,
    val serviceName: String,
    val modality: String,
    val fee: ServiceFee,
    val startsAt: Instant,
    val endsAt: Instant,
    val timeZone: ZoneId,
    val status: AppointmentStatus,
    val bookedAt: Instant,
    val cancelledAt: Instant?,
    val version: Int,
) {
    /** Reservada y sin terminar: aparece en «Próximas». */
    fun isUpcoming(now: Instant): Boolean = status == AppointmentStatus.BOOKED && endsAt.isAfter(now)

    fun canCancel(now: Instant): Boolean = status == AppointmentStatus.BOOKED && VirtualSessionsRules.canCancel(startsAt, now)

    val cancellationDeadline: Instant get() = VirtualSessionsRules.cancellationDeadline(startsAt)
}

/** Últimas citas confirmadas del deportista, en el orden del BFF (`starts_at` ascendente). */
data class AppointmentsSnapshot(val items: List<Appointment>, val syncedAt: Instant) {
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > VirtualSessionsRules.STALE_AFTER

    fun upcoming(now: Instant): List<Appointment> = items.filter { it.isUpcoming(now) }

    /** Canceladas o ya terminadas, la más reciente primero; nada se elimina silenciosamente (CA6). */
    fun history(now: Instant): List<Appointment> = items.filterNot { it.isUpcoming(now) }.sortedByDescending { it.startsAt }

    /** Cita activa del deportista en el intervalo, si la hay. */
    fun activeIn(availabilityId: String): Appointment? =
        items.firstOrNull { it.availabilityId == availabilityId && it.status == AppointmentStatus.BOOKED }

    /** Reemplaza (o agrega) la cita confirmada y conserva el orden por inicio. */
    fun upsert(appointment: Appointment, syncedAt: Instant): AppointmentsSnapshot =
        AppointmentsSnapshot(
            (items.filterNot { it.appointmentId == appointment.appointmentId } + appointment)
                .sortedWith(compareBy<Appointment> { it.startsAt }.thenBy { it.appointmentId }),
            syncedAt,
        )
}

/**
 * Presentación de un intervalo en la zona del dispositivo y en la del profesional (ADR-046 §8).
 * Solo cambia la representación: los instantes se conservan en UTC.
 */
object SessionTimes {
    fun range(startsAt: Instant, endsAt: Instant, zone: ZoneId, locale: Locale): String {
        val start = startsAt.atZone(zone)
        val end = endsAt.atZone(zone)
        val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val endText = if (start.toLocalDate() == end.toLocalDate()) time.format(end) else dateTime.format(end)
        return "${dateTime.format(start)} – $endText"
    }

    fun dateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

    /** `true` si en ese instante la zona del profesional tiene el mismo desfase que la del dispositivo. */
    fun sameOffset(instant: Instant, device: ZoneId, provider: ZoneId): Boolean =
        device.rules.getOffset(instant) == provider.rules.getOffset(instant)
}

/**
 * Tarifa informativa con el código de moneda del contrato («120.000 COP» en español). Los
 * decimales solo se muestran si no son cero; un importe ilegible se muestra tal cual.
 */
object FeeFormat {
    fun amount(fee: ServiceFee, locale: Locale): String {
        val value = runCatching { BigDecimal(fee.amount) }.getOrNull() ?: return "${fee.amount} ${fee.currency}"
        val decimals = if (value.stripTrailingZeros().scale() > 0) 2 else 0
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(value)
        return "$number ${fee.currency}"
    }
}
