package com.lfnj.sport_app.contract

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.features.training.session.data.FinishTrainingSessionRequestDto
import com.lfnj.sport_app.features.training.session.data.IDEMPOTENCY_HEADER
import com.lfnj.sport_app.features.training.session.data.StartTrainingSessionRequestDto
import com.lfnj.sport_app.features.training.session.data.TrainingSessionApi
import com.lfnj.sport_app.features.training.session.data.TrainingSessionDto
import com.lfnj.sport_app.features.training.session.model.SyncFailure
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * HU032 frente a la sección «Contrato HTTP» de ADR-029 (aceptado el 2026-10-12).
 *
 * `BLOQUEADO`: `bff-mobile` 0.5.0 con estas operaciones aún no está publicado en el backend
 * (`T-HU032-BE`), por lo que no existe la copia OpenAPI para [OpenApiContract]. Cuando se publique,
 * se copia en `src/test/resources/contracts/` y se agrega la verificación con `assertApiMatches` y
 * `assertModelMatches`; estas pruebas fijan mientras tanto los nombres y ejemplos del ADR.
 */
class TrainingSessionAdrContractTest {
    private val json = BffMobileClient.json

    @Test
    fun `las operaciones y metodos son los de ADR-029`() {
        val operations = TrainingSessionApi::class.java.declaredMethods.associate { method ->
            method.name to (method.getAnnotation(POST::class.java)?.let { "POST /${it.value}" }
                ?: method.getAnnotation(GET::class.java)?.let { "GET /${it.value}" })
        }

        assertEquals(
            mapOf(
                "start" to "POST /v1/training-sessions",
                "finish" to "POST /v1/training-sessions/{session_id}/finish",
                "active" to "GET /v1/training-sessions/active",
            ),
            operations,
        )
    }

    @Test
    fun `iniciar y finalizar envian Idempotency-Key`() {
        listOf("start", "finish").forEach { name ->
            val method = TrainingSessionApi::class.java.declaredMethods.single { it.name == name }
            val headers = method.parameterAnnotations.flatten().filterIsInstance<Header>().map { it.value }
            assertTrue("$name sin $IDEMPOTENCY_HEADER", IDEMPOTENCY_HEADER in headers)
            assertTrue("$name sin Authorization", "Authorization" in headers)
        }
    }

    @Test
    fun `el cuerpo de inicio coincide con el ejemplo de ADR-029 y omite campos prohibidos`() {
        val body = json.encodeToString(
            StartTrainingSessionRequestDto.serializer(),
            StartTrainingSessionRequestDto("8b89aca8-bb50-4dc9-8501-61353967de48", null, "running", "2026-10-12T12:30:00Z"),
        )

        assertEquals("""{"session_id":"8b89aca8-bb50-4dc9-8501-61353967de48","sport":"running","started_at":"2026-10-12T12:30:00Z"}""", body)
        val withPlan = json.parseToJsonElement(
            json.encodeToString(StartTrainingSessionRequestDto.serializer(), StartTrainingSessionRequestDto("s", "p-1", "cycling", "t"))
        ).jsonObject
        assertEquals(setOf("session_id", "plan_id", "sport", "started_at"), withPlan.keys)
        FORBIDDEN_IN_BODIES.forEach { assertTrue("Campo prohibido: $it", it !in withPlan.keys) }
    }

    @Test
    fun `el cuerpo de fin solo lleva ended_at`() {
        val body = json.encodeToString(FinishTrainingSessionRequestDto.serializer(), FinishTrainingSessionRequestDto("2026-10-12T13:15:00Z"))

        assertEquals("""{"ended_at":"2026-10-12T13:15:00Z"}""", body)
    }

    @Test
    fun `las respuestas de inicio y fin de ADR-029 se interpretan`() {
        val started = json.decodeFromString(
            TrainingSessionDto.serializer(),
            """{"session_id":"8b89aca8-bb50-4dc9-8501-61353967de48","athlete_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b",""" +
                """"plan_id":null,"sport":"running","started_at":"2026-10-12T12:30:00Z","status":"ACTIVE","version":1,""" +
                """"created_at":"2026-10-12T12:30:01Z","updated_at":"2026-10-12T12:30:01Z"}""",
        )
        val finished = json.decodeFromString(
            TrainingSessionDto.serializer(),
            """{"session_id":"8b89aca8-bb50-4dc9-8501-61353967de48","athlete_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b",""" +
                """"plan_id":null,"sport":"running","started_at":"2026-10-12T12:30:00Z","ended_at":"2026-10-12T13:15:00Z",""" +
                """"duration_seconds":2700,"status":"FINISHED","version":2,""" +
                """"created_at":"2026-10-12T12:30:01Z","updated_at":"2026-10-12T13:15:02Z"}""",
        )

        assertEquals(TrainingSessionDto.STATUS_ACTIVE to 1, started.status to started.version)
        assertEquals(TrainingSessionDto.STATUS_FINISHED to 2, finished.status to finished.version)
        assertEquals(2_700L, finished.durationSeconds)
    }

    @Test
    fun `el catalogo de deportes es el de ADR-029`() {
        assertEquals(setOf("running", "cycling", "swimming", "strength", "other"), TrainingSport.entries.map { it.wire }.toSet())
    }

    @Test
    fun `cada codigo de error funcional de ADR-029 tiene una causa explicable propia`() {
        val codes = listOf(
            "permission_required", "capability_required", "training_plan_not_found", "training_session_not_found",
            "active_session_exists", "session_id_reused", "training_session_not_active",
            "training_session_already_finished", "idempotency_key_reused",
        )
        codes.forEach { assertNotEquals(it, SyncFailure.REJECTED, SyncFailure.fromHttp(it, emptyList())) }
        listOf("too_far_in_future", "ends_before_starts").forEach {
            assertEquals(SyncFailure.DEVICE_CLOCK, SyncFailure.fromHttp("validation_error", listOf(it)))
        }
        assertEquals(SyncFailure.EXPIRED, SyncFailure.fromHttp("validation_error", listOf("too_old")))
        assertEquals(SyncFailure.REJECTED, SyncFailure.fromHttp("validation_error", listOf("unknown_value")))
    }

    @Test
    fun `los modelos de entrenamiento no exponen datos al convertirse en texto`() {
        val text = listOf(
            StartTrainingSessionRequestDto("8b89aca8", null, "running", "2026-10-12T12:30:00Z"),
            FinishTrainingSessionRequestDto("2026-10-12T13:15:00Z"),
        ).joinToString()

        listOf("running", "2026-10-12", "8b89aca8").forEach { assertTrue("Se expone «$it»", !text.contains(it)) }
    }

    private companion object {
        val FORBIDDEN_IN_BODIES = setOf("athlete_id", "status", "version", "duration_seconds")
    }
}
