package com.lfnj.sport_app.features.training.monitoring.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.monitoring.model.ThresholdSource
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.MonitoringFixtures
import com.lfnj.sport_app.testing.SessionFixtures
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * HU036: umbral de alerta contra un `bff-mobile` 0.21.0 simulado con MockWebServer. Contrato
 * simulado: **no** es un E2E.
 */
class MonitoringRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val repository = MonitoringRepository(retrofit.create(MonitoringApi::class.java), sessionManager, BffMobileClient.json)
    private val path = "/v1/me/monitoring-settings"

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    private fun error(status: Int, code: String) = respond("""{"code":"$code","message":"mensaje","correlation_id":"c0ffee00-0000-4000-8000-000000000036"}""", status)

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    @Test
    fun `consulta el umbral por defecto con el token y conserva su origen`() = runTest {
        login()
        respond(MonitoringFixtures.settingsBody(171, "FIXED_DEFAULT"))

        val result = repository.settings()

        assertEquals(SettingsResult.Done(MonitoringFixtures.settings(171, ThresholdSource.FIXED_DEFAULT)), result)
        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals(path, request.requestUrl?.encodedPath)
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))
    }

    @Test
    fun `cambia el umbral con el cuerpo cerrado del contrato`() = runTest {
        login()
        respond(MonitoringFixtures.settingsBody(120, "CUSTOM", "2026-10-24T12:00:00Z"))

        val result = repository.update(120)

        assertEquals(SettingsResult.Done(MonitoringFixtures.settings(120, ThresholdSource.CUSTOM, Instant.parse("2026-10-24T12:00:00Z"))), result)
        val request = takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(path, request.requestUrl?.encodedPath)
        assertEquals("""{"max_heart_rate_alert_bpm":120}""", request.body.readUtf8())
    }

    @Test
    fun `un valor fuera de 100 a 220 no se envia`() = runTest {
        login()

        assertEquals(SettingsResult.Failed(SettingsProblem.OUT_OF_RANGE), repository.update(99))
        assertEquals(SettingsResult.Failed(SettingsProblem.OUT_OF_RANGE), repository.update(221))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `403 capability_required se informa para explicar el plan (CA5)`() = runTest {
        login()
        error(403, "capability_required")

        assertEquals(SettingsResult.Failed(SettingsProblem.CAPABILITY_REQUIRED), repository.settings())
    }

    @Test
    fun `422 validation_error se informa como fuera de rango`() = runTest {
        login()
        error(422, "validation_error")

        assertEquals(SettingsResult.Failed(SettingsProblem.OUT_OF_RANGE), repository.update(150))
    }

    @Test
    fun `sin red, servicio caido o respuesta invalida no fabrican un umbral`() = runTest {
        login()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(SettingsResult.Failed(SettingsProblem.OFFLINE), repository.settings())

        error(503, "upstream_unavailable")
        assertEquals(SettingsResult.Failed(SettingsProblem.UNAVAILABLE), repository.settings())

        respond(MonitoringFixtures.settingsBody(171, "INVENTADO"))
        assertEquals(SettingsResult.Failed(SettingsProblem.INVALID), repository.settings())

        respond("""{"source":"CUSTOM"}""")
        assertEquals(SettingsResult.Failed(SettingsProblem.INVALID), repository.settings())
    }

    @Test
    fun `sin sesion se pide iniciar sesion de nuevo`() = runTest {
        assertEquals(SettingsResult.Failed(SettingsProblem.SIGNED_OUT), repository.settings())
    }

    @Test
    fun `sin BFF configurado el umbral no esta disponible`() = runTest {
        val unconfigured = MonitoringRepository(null, sessionManager, BffMobileClient.json)

        assertEquals(SettingsResult.Failed(SettingsProblem.UNAVAILABLE), unconfigured.settings())
    }
}
