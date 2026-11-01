package com.lfnj.sport_app.contract

import com.lfnj.sport_app.features.training.session.data.ActiveTrainingSessionDto
import com.lfnj.sport_app.features.training.session.data.FinishTrainingSessionRequestDto
import com.lfnj.sport_app.features.training.session.data.StartTrainingSessionRequestDto
import com.lfnj.sport_app.features.training.session.data.TrainingSessionApi
import com.lfnj.sport_app.features.training.session.data.TrainingSessionDto
import com.lfnj.sport_app.features.training.session.model.TrainingSport
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU032 en el cliente móvil frente a la copia exacta de `bff-mobile` (ADR-029). */
class TrainingSessionContractTest {
    private val contract = OpenApiContract.load()

    @Test
    fun `las operaciones de sesion de entrenamiento existen en el contrato`() {
        contract.assertApiMatches(TrainingSessionApi::class.java)
    }

    @Test
    fun `iniciar y finalizar documentan Idempotency-Key`() {
        listOf("/v1/training-sessions", "/v1/training-sessions/{session_id}/finish").forEach { path ->
            assertTrue(path, "idempotency-key" in contract.parameters("post", path, "header"))
        }
    }

    @Test
    fun `los cuerpos y respuestas corresponden al contrato`() {
        contract.assertRequestBodyMatches("post", "/v1/training-sessions", StartTrainingSessionRequestDto.serializer())
        contract.assertRequestBodyMatches("post", "/v1/training-sessions/{session_id}/finish", FinishTrainingSessionRequestDto.serializer())
        contract.assertModelMatches("TrainingSessionResponse", TrainingSessionDto.serializer())
        contract.assertModelMatches("ActiveTrainingSessionResponse", ActiveTrainingSessionDto.serializer())
    }

    @Test
    fun `los catalogos de deporte y estado coinciden con el contrato`() {
        OpenApiContract.assertSameElements(
            "TrainingSessionResponse.sport",
            contract.enumValues("TrainingSessionResponse", "sport"),
            TrainingSport.entries.map { it.wire }.toSet(),
        )
        OpenApiContract.assertSameElements(
            "TrainingSessionResponse.status",
            contract.enumValues("TrainingSessionResponse", "status"),
            setOf(TrainingSessionDto.STATUS_ACTIVE, TrainingSessionDto.STATUS_FINISHED),
        )
    }

    @Test
    fun `el cliente trata todas las respuestas documentadas`() {
        assertTrue(contract.responseCodes("post", "/v1/training-sessions").containsAll(setOf("201", "401", "403", "404", "409", "422", "503", "504")))
        assertTrue(contract.responseCodes("post", "/v1/training-sessions/{session_id}/finish").containsAll(setOf("200", "404", "409", "422")))
        assertTrue(contract.responseCodes("get", "/v1/training-sessions/active").containsAll(setOf("200", "204", "401", "403")))
    }
}
