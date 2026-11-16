package com.lfnj.sport_app.features.integrations.data.local

import com.lfnj.sport_app.core.security.TokenCipher
import com.lfnj.sport_app.features.integrations.model.StravaIntegration
import com.lfnj.sport_app.features.integrations.model.StravaLastSync
import com.lfnj.sport_app.features.integrations.model.StravaLinkStatus
import com.lfnj.sport_app.features.integrations.model.StravaSyncError
import com.lfnj.sport_app.features.integrations.model.StravaSyncStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.DateTimeException
import java.time.Instant

/** Contenido cifrado de [StravaCacheEntity.payload]. Instantes en segundos UTC. Sin tokens. */
@Serializable
internal data class StravaPayload(
    val status: String,
    @SerialName("linked_at") val linkedAt: Long? = null,
    val scopes: List<String>,
    @SerialName("last_sync") val lastSync: CachedSync? = null,
    @SerialName("synced_at") val syncedAt: Long,
)

@Serializable
internal data class CachedSync(
    val status: String,
    @SerialName("requested_at") val requestedAt: Long,
    @SerialName("finished_at") val finishedAt: Long? = null,
    @SerialName("imported_count") val importedCount: Long,
    @SerialName("error_code") val errorCode: String? = null,
)

/**
 * Cifra y descifra el estado de vinculación confirmado con AES-GCM y una clave no exportable de
 * Android Keystore propia de HU031. Una caché que no puede descifrarse se trata como inexistente:
 * nunca se muestra un estado inventado.
 */
class StravaCodec(private val cipher: TokenCipher, private val json: Json) {

    fun encode(integration: StravaIntegration): ByteArray {
        val payload = StravaPayload(
            status = integration.status.name,
            linkedAt = integration.linkedAt?.epochSecond,
            scopes = integration.scopes,
            lastSync = integration.lastSync?.let {
                CachedSync(it.status.name, it.requestedAt.epochSecond, it.finishedAt?.epochSecond, it.importedCount, it.error?.wire)
            },
            syncedAt = integration.syncedAt.epochSecond,
        )
        return cipher.encrypt(json.encodeToString(StravaPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
    }

    /** `null` si la caché no puede descifrarse o no corresponde al formato esperado. */
    fun decode(entity: StravaCacheEntity): StravaIntegration? = try {
        val payload = json.decodeFromString(StravaPayload.serializer(), cipher.decrypt(entity.payload).toString(Charsets.UTF_8))
        StravaIntegration(
            status = StravaLinkStatus.valueOf(payload.status),
            linkedAt = payload.linkedAt?.let(Instant::ofEpochSecond),
            scopes = payload.scopes,
            lastSync = payload.lastSync?.let {
                StravaLastSync(
                    status = StravaSyncStatus.valueOf(it.status),
                    requestedAt = Instant.ofEpochSecond(it.requestedAt),
                    finishedAt = it.finishedAt?.let(Instant::ofEpochSecond),
                    importedCount = it.importedCount,
                    error = it.errorCode?.let { code -> StravaSyncError.fromWire(code) ?: error("error_code desconocido") },
                )
            },
            syncedAt = Instant.ofEpochSecond(payload.syncedAt),
        )
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

    companion object {
        /** Alias de la clave no exportable de Android Keystore para la caché de HU031. */
        const val KEY_ALIAS = "sportapp.strava.v1"
    }
}
