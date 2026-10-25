package com.lfnj.sport_app.features.services.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.services.model.Appointment
import com.lfnj.sport_app.features.services.model.AppointmentStatus
import com.lfnj.sport_app.features.services.model.AppointmentsSnapshot
import com.lfnj.sport_app.features.services.model.AvailabilitySlot
import com.lfnj.sport_app.features.services.model.CatalogSnapshot
import com.lfnj.sport_app.features.services.model.CoachingService
import com.lfnj.sport_app.features.services.model.ServiceFee
import com.lfnj.sport_app.features.services.model.ServiceModality
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/** Contenido cifrado de [VirtualSessionsCatalogEntity.payload]. Instantes en segundos UTC. */
@Serializable
internal data class CatalogPayload(
    val services: List<CachedService>,
    val truncated: Boolean = false,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedService(
    @SerialName("service_id") val serviceId: String,
    @SerialName("provider_id") val providerId: String,
    val name: String,
    val description: String,
    val modality: String,
    val fee: CachedFee,
    val slots: List<CachedSlot>,
)

@Serializable
internal data class CachedSlot(
    @SerialName("availability_id") val availabilityId: String,
    @SerialName("starts_at") val startsAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("time_zone") val timeZone: String,
    val capacity: Int,
)

@Serializable
internal data class CachedFee(
    val amount: String,
    val currency: String,
    @SerialName("billing_unit") val billingUnit: String,
)

/** Contenido cifrado de [VirtualSessionsAppointmentsEntity.payload]. */
@Serializable
internal data class AppointmentsPayload(
    val items: List<CachedAppointment>,
    @SerialName("last_synced_at") val lastSyncedAt: Long,
)

@Serializable
internal data class CachedAppointment(
    @SerialName("appointment_id") val appointmentId: String,
    @SerialName("service_id") val serviceId: String,
    @SerialName("availability_id") val availabilityId: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("service_name") val serviceName: String,
    val modality: String,
    val fee: CachedFee,
    @SerialName("starts_at") val startsAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    @SerialName("time_zone") val timeZone: String,
    val status: String,
    @SerialName("booked_at") val bookedAt: Long,
    @SerialName("cancelled_at") val cancelledAt: Long? = null,
    val version: Int,
)

/**
 * Cifra y descifra el catálogo y las citas confirmadas con AES-GCM y una clave no exportable de
 * Android Keystore propia de HU040. Una caché que no puede descifrarse se trata como inexistente:
 * nunca se muestran servicios ni citas inventados.
 */
class VirtualSessionsCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encodeCatalog(snapshot: CatalogSnapshot): ByteArray = encrypt(
        CatalogPayload.serializer(),
        CatalogPayload(snapshot.services.map(::cached), snapshot.truncated, snapshot.syncedAt.epochSecond),
    )

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decodeCatalog(entity: VirtualSessionsCatalogEntity): CatalogSnapshot? = safely {
        val payload = json.decodeFromString(CatalogPayload.serializer(), decrypt(entity.payload))
        CatalogSnapshot(
            services = payload.services.map { it.toService() ?: return@safely null },
            truncated = payload.truncated,
            syncedAt = Instant.ofEpochSecond(payload.lastSyncedAt),
        )
    }

    fun encodeAppointments(snapshot: AppointmentsSnapshot): ByteArray = encrypt(
        AppointmentsPayload.serializer(),
        AppointmentsPayload(snapshot.items.map(::cached), snapshot.syncedAt.epochSecond),
    )

    fun decodeAppointments(entity: VirtualSessionsAppointmentsEntity): AppointmentsSnapshot? = safely {
        val payload = json.decodeFromString(AppointmentsPayload.serializer(), decrypt(entity.payload))
        AppointmentsSnapshot(
            items = payload.items.map { it.toAppointment() ?: return@safely null },
            syncedAt = Instant.ofEpochSecond(payload.lastSyncedAt),
        )
    }

    private fun <T> encrypt(serializer: kotlinx.serialization.KSerializer<T>, payload: T): ByteArray =
        cipher.encrypt(json.encodeToString(serializer, payload).toByteArray(Charsets.UTF_8))

    private fun decrypt(payload: ByteArray): String = cipher.decrypt(payload).toString(Charsets.UTF_8)

    private inline fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: DateTimeException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun cached(fee: ServiceFee) = CachedFee(fee.amount, fee.currency, fee.billingUnit)

    private fun cached(service: CoachingService) = CachedService(
        serviceId = service.serviceId,
        providerId = service.providerId,
        name = service.name,
        description = service.description,
        modality = service.modality.wire,
        fee = cached(service.fee),
        slots = service.slots.map {
            CachedSlot(it.availabilityId, it.startsAt.epochSecond, it.endsAt.epochSecond, it.timeZone.id, it.capacity)
        },
    )

    private fun cached(item: Appointment) = CachedAppointment(
        appointmentId = item.appointmentId,
        serviceId = item.serviceId,
        availabilityId = item.availabilityId,
        providerId = item.providerId,
        serviceName = item.serviceName,
        modality = item.modality,
        fee = cached(item.fee),
        startsAt = item.startsAt.epochSecond,
        endsAt = item.endsAt.epochSecond,
        timeZone = item.timeZone.id,
        status = item.status.wire,
        bookedAt = item.bookedAt.epochSecond,
        cancelledAt = item.cancelledAt?.epochSecond,
        version = item.version,
    )

    private fun CachedFee.toFee() = ServiceFee(amount, currency, billingUnit)

    /** `null` si un código guardado no es reconocible: no se reinterpreta como otro valor. */
    private fun CachedService.toService(): CoachingService? = CoachingService(
        serviceId = serviceId,
        providerId = providerId,
        name = name,
        description = description,
        modality = ServiceModality.fromWire(modality) ?: return null,
        fee = fee.toFee(),
        slots = slots.map {
            AvailabilitySlot(it.availabilityId, Instant.ofEpochSecond(it.startsAt), Instant.ofEpochSecond(it.endsAt), ZoneId.of(it.timeZone), it.capacity)
        },
    )

    private fun CachedAppointment.toAppointment(): Appointment? = Appointment(
        appointmentId = appointmentId,
        serviceId = serviceId,
        availabilityId = availabilityId,
        providerId = providerId,
        serviceName = serviceName,
        modality = modality,
        fee = fee.toFee(),
        startsAt = Instant.ofEpochSecond(startsAt),
        endsAt = Instant.ofEpochSecond(endsAt),
        timeZone = ZoneId.of(timeZone),
        status = AppointmentStatus.fromWire(status) ?: return null,
        bookedAt = Instant.ofEpochSecond(bookedAt),
        cancelledAt = cancelledAt?.let(Instant::ofEpochSecond),
        version = version,
    )

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU040. */
        const val KEY_ALIAS = "sportapp.virtual_sessions.v1"
    }
}
