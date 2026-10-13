package com.lfnj.sport_app.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiCallTest {
    private val server = MockWebServer()
    private lateinit var api: HealthApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient.Builder()
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .addInterceptor(CorrelationIdInterceptor())
            .build()
        api = BffMobileClient.retrofit(server.url("/").toString(), client).create(HealthApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun call() = apiCall(BffMobileClient.json) { api.ready() }

    @Test
    fun `una respuesta exitosa entrega el cuerpo del contrato`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","checks":{"users":"ok"}}"""))

        val result = call()

        assertEquals(ApiResult.Success(HealthStatusDto("ok", mapOf("users" to "ok"))), result)
    }

    @Test
    fun `cada solicitud lleva un identificador de correlacion UUID`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        call()

        val header = server.takeRequest().getHeader(CORRELATION_HEADER)
        assertEquals(header, UUID.fromString(header).toString())
    }

    @Test
    fun `un error del contrato conserva codigo, correlacion y Retry-After`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
                .setBody("""{"code":"too_many_attempts","message":"m","correlation_id":"c-1"}""")
        )

        val result = call()

        assertEquals(ApiResult.HttpFailure(429, "too_many_attempts", "c-1", 120), result)
    }

    @Test
    fun `un error sin cuerpo del contrato usa la correlacion del encabezado`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setHeader(CORRELATION_HEADER, "c-2").setBody("<html/>"))

        val result = call()

        assertEquals(ApiResult.HttpFailure(502, null, "c-2", null), result)
    }

    @Test
    fun `un Retry-After invalido se ignora`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "-5"))

        val result = call() as ApiResult.HttpFailure

        assertEquals(null, result.retryAfterSeconds)
    }

    @Test
    fun `un cuerpo exitoso fuera del contrato es una respuesta invalida`() = runTest {
        server.enqueue(MockResponse().setBody("""{"inesperado":true}"""))

        assertEquals(ApiResult.InvalidResponse, call())
    }

    @Test
    fun `sin respuesta a tiempo es Timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(ApiResult.Timeout, call())
    }

    @Test
    fun `una conexion cortada es NoConnection`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertTrue(call() is ApiResult.NoConnection)
    }

    @Test
    fun `un servidor apagado es NoConnection`() = runTest {
        server.shutdown()

        assertEquals(ApiResult.NoConnection, call())
    }

    @Test
    fun `un 422 conserva solo las causas de validacion`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"code":"validation_error","message":"m","details":[{"field":"started_at","issue":"too_old"},{"issue":"required"}]}"""
            )
        )

        val result = call() as ApiResult.HttpFailure

        assertEquals(listOf("too_old", "required"), result.issues)
    }

    @Test
    fun `204 sin cuerpo es exito vacio solo cuando la operacion lo admite`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(ApiResult.Success(null), apiCallAllowingNoContent(BffMobileClient.json) { api.ready() })
        assertEquals(ApiResult.InvalidResponse, call())
    }
}
