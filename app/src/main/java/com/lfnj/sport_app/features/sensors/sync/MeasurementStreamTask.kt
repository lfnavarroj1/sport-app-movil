package com.lfnj.sport_app.features.sensors.sync

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.apiCall
import com.lfnj.sport_app.core.session.AuthorizedResult
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.sensors.data.ConnectableDevice
import com.lfnj.sport_app.features.sensors.data.ConnectableDevices
import com.lfnj.sport_app.features.sensors.data.DevicesApi
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchRequestDto
import com.lfnj.sport_app.features.sensors.data.MeasurementBatchResponseDto
import com.lfnj.sport_app.features.sensors.data.MeasurementSampleDto
import com.lfnj.sport_app.features.sensors.data.SensorSamples
import com.lfnj.sport_app.features.sensors.model.BatchReport
import com.lfnj.sport_app.features.sensors.model.HeartRateReading
import com.lfnj.sport_app.features.sensors.model.IngestionState
import com.lfnj.sport_app.features.sensors.model.IngestionStatus
import com.lfnj.sport_app.features.sensors.model.MonitorConnection
import com.lfnj.sport_app.features.sensors.model.MonitorSnapshot
import com.lfnj.sport_app.features.training.session.foreground.ActiveSession
import com.lfnj.sport_app.features.training.session.foreground.ActiveSessionTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Resultado de un ciclo de envío. */
enum class FlushResult { NOTHING_PENDING, CONFIRMED, RETRY_NEXT_CYCLE, STOP }

/**
 * Envía las muestras pendientes de una sesión (CA3–CA6, ADR-044 §5–§6, §12). Cada ciclo toma las
 * ≤ 60 más antiguas y las envía en un lote; solo una respuesta `200` las resuelve (aceptadas,
 * duplicadas o rechazadas por muestra). Sin confirmación, el mismo lote se reenvía en el siguiente
 * ciclo: la idempotencia natural `(session_id, device_id, measured_at)` del backend descarta lo ya
 * recibido. Las muestras vencen con la sesión o a los 15 min (el backend las rechazaría `too_old`).
 */
class MeasurementUploader(
    private val samples: SensorSamples,
    private val devices: ConnectableDevices,
    private val api: DevicesApi?,
    private val sessionManager: SessionManager,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _status = MutableStateFlow(IngestionStatus())

    /** Estado visible de la ingestión de la sesión activa (no persiste: los datos son las muestras). */
    val status: StateFlow<IngestionStatus> = _status.asStateFlow()

    suspend fun flush(session: ActiveSession, device: ConnectableDevice): FlushResult {
        samples.expireBefore(clock.instant().minus(MAX_SAMPLE_AGE).epochSecond)
        val batch = samples.oldest(session.owner, session.sessionId, device.deviceId, DevicesApi.MAX_SAMPLES_PER_BATCH)
        if (batch.isEmpty()) {
            publish(_status.value.state.takeIf { it != IngestionState.PENDING_RETRY } ?: IngestionState.SENDING, session)
            return FlushResult.NOTHING_PENDING
        }
        val api = api ?: return retry(session)
        val body = MeasurementBatchRequestDto(
            deviceId = device.deviceId,
            samples = batch.map { (second, bpm) ->
                MeasurementSampleDto(WIRE.format(Instant.ofEpochSecond(second)), DevicesApi.SAMPLE_TYPE_HEART_RATE, bpm, DevicesApi.SAMPLE_UNIT_BPM)
            },
        )
        val outcome = sessionManager.authorized { auth -> apiCall(json) { api.ingest(auth, session.sessionId, body) } }
        val result = when (outcome) {
            AuthorizedResult.SignedOut -> return FlushResult.STOP
            AuthorizedResult.RenewalUnavailable -> return retry(session)
            is AuthorizedResult.Completed -> outcome.result
        }
        return when (result) {
            is ApiResult.Success -> confirmed(session, device, batch.map { it.first }, result.value)
            ApiResult.NoConnection, ApiResult.Timeout, ApiResult.NotConfigured -> retry(session)
            ApiResult.InvalidResponse -> retry(session)
            is ApiResult.HttpFailure -> when {
                result.status == 409 && result.code == "device_revoked" -> {
                    // Marcar la revocación hace que la tarea deje de observar este dispositivo: se completa sin cancelarse.
                    withContext(NonCancellable) {
                        stop(IngestionState.DEVICE_REVOKED, session).also { devices.markRevoked(session.owner, device.deviceId) }
                    }
                }
                result.status == 409 && result.code == "training_session_already_finished" -> {
                    samples.dropSession(session.owner, session.sessionId)
                    stop(IngestionState.SESSION_FINISHED, session)
                }
                // El inicio de la sesión puede seguir en la cola de HU032: se reintenta hasta que venza.
                result.status == 404 && result.code == "training_session_not_found" -> retry(session)
                result.status == 403 && result.code == "capability_required" -> {
                    samples.dropSession(session.owner, session.sessionId)
                    stop(IngestionState.CAPABILITY_REQUIRED, session)
                }
                result.status == 404 || result.status == 403 -> {
                    samples.dropSession(session.owner, session.sessionId)
                    stop(IngestionState.REJECTED, session)
                }
                result.status == 422 -> {
                    // Un lote que el backend no puede validar no se reenvía igual: se descarta solo ese lote.
                    samples.remove(session.sessionId, device.deviceId, batch.map { it.first })
                    publish(IngestionState.REJECTED, session)
                    FlushResult.CONFIRMED
                }
                else -> retry(session)
            }
        }
    }

    /** Descarta lo pendiente de una sesión que terminó: las muestras vencen con ella. */
    suspend fun expire(session: ActiveSession) {
        samples.dropSession(session.owner, session.sessionId)
        _status.value = IngestionStatus()
    }

    /** Sin monitor conectable; conserva la causa de una detención (p. ej. `device_revoked`) para mostrarla. */
    fun reset() {
        if (_status.value.state !in STOPPED) _status.value = IngestionStatus()
    }

    private suspend fun confirmed(
        session: ActiveSession,
        device: ConnectableDevice,
        sent: List<Long>,
        response: MeasurementBatchResponseDto,
    ): FlushResult {
        samples.remove(session.sessionId, device.deviceId, sent)
        val report = BatchReport(clock.instant(), response.received, response.accepted, response.duplicates, response.rejected.size)
        _status.value = IngestionStatus(IngestionState.SENDING, samples.count(session.owner, session.sessionId), report)
        return FlushResult.CONFIRMED
    }

    private suspend fun retry(session: ActiveSession): FlushResult {
        publish(IngestionState.PENDING_RETRY, session)
        return FlushResult.RETRY_NEXT_CYCLE
    }

    private suspend fun stop(state: IngestionState, session: ActiveSession): FlushResult {
        publish(state, session)
        return FlushResult.STOP
    }

    private suspend fun publish(state: IngestionState, session: ActiveSession) {
        _status.value = _status.value.copy(state = state, pendingSamples = samples.count(session.owner, session.sessionId))
    }

    companion object {
        /** HA008: un lote cada 5 s con las muestras de 1 Hz del intervalo. */
        const val BATCH_INTERVAL_MILLIS = 5_000L

        /** Antigüedad máxima que acepta el backend (ADR-044 §6). */
        val MAX_SAMPLE_AGE: Duration = Duration.ofMinutes(15)

        /** `YYYY-MM-DDTHH:MM:SSZ`, precisión de segundos. */
        private val WIRE: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        private val STOPPED = setOf(
            IngestionState.DEVICE_REVOKED,
            IngestionState.SESSION_FINISHED,
            IngestionState.CAPABILITY_REQUIRED,
            IngestionState.REJECTED,
        )
    }
}

/**
 * Tarea de HU037 en el servicio en primer plano de la sesión (compartido con HU038/HU036): mientras
 * la sesión está `ACTIVE` y hay un monitor activo vinculado en este teléfono, guarda cifrada una
 * muestra por segundo de `0x2A37` y envía un lote cada 5 s. Sin red sigue capturando y reenvía en el
 * siguiente ciclo. Al terminar la sesión, lo pendiente vence.
 */
class MeasurementStreamTask(
    private val devices: ConnectableDevices,
    private val link: (address: String) -> Flow<MonitorSnapshot>,
    private val samples: SensorSamples,
    private val uploader: MeasurementUploader,
    private val batchIntervalMillis: Long = MeasurementUploader.BATCH_INTERVAL_MILLIS,
) : ActiveSessionTask {

    override suspend fun run(session: ActiveSession) {
        try {
            samples.dropOtherSessions(session.owner, session.sessionId)
            devices.observeConnectable(session.owner).distinctUntilChanged().collectLatest { device ->
                if (device == null) {
                    uploader.reset()
                    return@collectLatest
                }
                stream(session, device)
            }
        } finally {
            withContext(NonCancellable) { uploader.expire(session) }
        }
    }

    private suspend fun stream(session: ActiveSession, device: ConnectableDevice) = coroutineScope {
        val capture = launch {
            link(device.address)
                .mapNotNull { snapshot -> snapshot.lastReading?.takeIf { snapshot.connection == MonitorConnection.CONNECTED } }
                .distinctUntilChanged()
                .collect { reading -> record(session, device, reading) }
        }
        try {
            while (true) {
                delay(batchIntervalMillis)
                if (uploader.flush(session, device) == FlushResult.STOP) break
            }
        } finally {
            capture.cancel()
        }
    }

    private suspend fun record(session: ActiveSession, device: ConnectableDevice, reading: HeartRateReading) {
        // Nada anterior al inicio de la sesión: el backend lo rechazaría y no pertenece a ella.
        if (reading.receivedAt.isBefore(session.startedAt)) return
        try {
            samples.record(session.owner, session.sessionId, device.deviceId, reading.receivedAt.truncatedTo(ChronoUnit.SECONDS).epochSecond, reading.bpm)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sin almacenamiento disponible la muestra se pierde; nunca se registra su valor.
        }
    }
}
