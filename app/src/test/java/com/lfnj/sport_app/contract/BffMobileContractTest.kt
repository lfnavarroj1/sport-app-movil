package com.lfnj.sport_app.contract

import com.lfnj.sport_app.BuildConfig
import com.lfnj.sport_app.core.network.ErrorDetailDto
import com.lfnj.sport_app.core.network.ErrorResponseDto
import com.lfnj.sport_app.core.network.HealthApi
import com.lfnj.sport_app.core.network.HealthStatusDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compatibilidad del andamiaje con la copia del OpenAPI `bff-mobile`. */
class BffMobileContractTest {
    private val contract = OpenApiContract.load()

    @Test
    fun `la copia del contrato es la version declarada por el cliente`() {
        assertEquals(BuildConfig.BFF_MOBILE_CONTRACT_VERSION, contract.version)
    }

    @Test
    fun `los modelos comunes corresponden al contrato`() {
        contract.assertModelMatches("ErrorResponse", ErrorResponseDto.serializer())
        contract.assertModelMatches("ErrorDetail", ErrorDetailDto.serializer())
        contract.assertModelMatches("HealthStatus", HealthStatusDto.serializer())
    }

    @Test
    fun `las operaciones de salud existen en el contrato`() {
        contract.assertApiMatches(HealthApi::class.java)
    }
}
