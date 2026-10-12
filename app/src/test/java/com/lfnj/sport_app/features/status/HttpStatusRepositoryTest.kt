package com.lfnj.sport_app.features.status

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.network.HealthApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpStatusRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val repository = HttpStatusRepository(
        BffMobileClient.retrofit(server.url("/").toString()).create(HealthApi::class.java),
        BffMobileClient.json,
    )

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `BFF listo`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        assertEquals(BffAvailability.READY, repository.bffAvailability())
        assertEquals("/health/ready", server.takeRequest().path)
    }

    @Test
    fun `BFF con dependencias caidas responde 503`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"status":"unavailable","checks":{"db":"failed"}}"""))

        assertEquals(BffAvailability.UNAVAILABLE, repository.bffAvailability())
    }

    @Test
    fun `respuesta HTTP inesperada`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(BffAvailability.UNEXPECTED, repository.bffAvailability())
    }

    @Test
    fun `variante sin URL configurada`() = runTest {
        assertEquals(BffAvailability.NOT_CONFIGURED, HttpStatusRepository(null, BffMobileClient.json).bffAvailability())
    }
}
