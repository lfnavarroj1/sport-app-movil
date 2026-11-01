package com.lfnj.sport_app.features.events.model

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Partición local por identidad técnica: huella SHA-256 del `user_id` (ADR-032 §9, como HU035). */
object EventRegistrationsOwner {
    fun keyOf(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** `event_id` del contrato: UUID. Cualquier otro valor no se envía al BFF. */
object EventIds {
    fun isValid(value: String?): Boolean =
        value != null && runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
}

data class EventPlace(val name: String, val city: String, val countryCode: String)

/** Tarifa informativa (`{amount, currency}`, ADR-040): no es un cobro y SportApp no procesa pagos. */
data class EventPrice(val amount: String, val currency: String)

/** Inscripción vigente del deportista en el detalle (`my_registration`). */
data class MyRegistration(val registrationId: String, val registeredAt: Instant)

/** Detalle de un evento `PUBLISHED` tal como lo confirmó el BFF (ADR-032 «Detalle del evento publicado»). */
data class EventDetail(
    val eventId: String,
    val name: String,
    val description: String,
    val discipline: String,
    val location: EventPlace,
    val startsAt: Instant,
    val endsAt: Instant,
    val timeZone: String,
    /** Cupo declarado por el organizador; la disponibilidad solo la decide el backend al inscribir. */
    val capacity: Int,
    val version: Int,
    val fee: EventPrice?,
    val myRegistration: MyRegistration?,
) {
    /** El evento ya inició: el backend respondería `409 event_registration_closed` (CA3). */
    fun hasStarted(now: Instant): Boolean = !now.isBefore(startsAt)
}

/** Datos del evento que acompañan cada inscripción (`RegistrationEvent`). */
data class RegisteredEvent(
    val name: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val timeZone: String,
    val location: EventPlace,
)

/** Inscripción confirmada por el backend (`RegistrationResponse`): nunca se crea localmente. */
data class EventRegistration(
    val registrationId: String,
    val eventId: String,
    val registeredAt: Instant,
    val appliedFee: EventPrice?,
    val version: Int,
    val event: RegisteredEvent,
)

/**
 * «Mis inscripciones» confirmadas por el BFF para un deportista con su `last_synced_at`
 * ([syncedAt]); es lo que se guarda cifrado en el dispositivo (ADR-032 §9).
 */
data class RegistrationsSnapshot(
    val items: List<EventRegistration>,
    /** Cursor opaco de la página siguiente, o `null` si ya se cargó todo. */
    val nextCursor: String?,
    val syncedAt: Instant,
) {
    /** Orden del contrato: fecha del evento y, ante empate, `registration_id` (CA8). */
    val ordered: List<EventRegistration>
        get() = items.sortedWith(compareBy({ it.event.startsAt }, { it.registrationId }))

    /** Confirmado hace más de 24 horas (ADR-032 §9, igual que HU035). */
    fun isStale(now: Instant): Boolean = Duration.between(syncedAt, now) > STALE_AFTER

    /** Agrega una página sin repetir inscripciones ya cargadas. */
    fun append(page: List<EventRegistration>, cursor: String?): RegistrationsSnapshot {
        val known = items.map { it.registrationId }.toSet()
        return copy(items = items + page.filterNot { it.registrationId in known }, nextCursor = cursor)
    }

    companion object {
        val STALE_AFTER: Duration = Duration.of(24, ChronoUnit.HOURS)
    }
}
