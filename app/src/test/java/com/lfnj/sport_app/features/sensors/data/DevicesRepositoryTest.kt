package com.lfnj.sport_app.features.sensors.data

import com.lfnj.sport_app.core.network.ApiResult
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.sensors.data.local.SensorDeviceEntity
import com.lfnj.sport_app.features.sensors.data.local.SensorsCodec
import com.lfnj.sport_app.features.sensors.data.local.StoredDevice
import com.lfnj.sport_app.features.sensors.model.DeviceStatus
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemorySensorsDao
import com.lfnj.sport_app.testing.SensorsFixtures
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * HU037 contra un `bff-mobile` simulado con MockWebServer y la base en memoria (cifrado de
 * prueba). Contrato simulado según `bff-mobile` 0.21.0: **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemorySensorsDao()
    private val codec = SensorsCodec(FakeCipher(), BffMobileClient.json)
    private val repository = DevicesRepository(dao, codec, retrofit.create(DevicesApi::class.java), sessionManager, BffMobileClient.json)
    private val samples = SensorSamples(dao, codec)
    private val owner = SensorsFixtures.OWNER
    private val user = SensorsFixtures.user()
    private val monitor = SensorsFixtures.monitor()
    private val deviceId = SensorsFixtures.deviceId()
    private val idempotencyKeyFixture = UUID.nameUUIDFromBytes("hu037-vincular".toByteArray()).toString()

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        sessionManager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    private fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    private fun disconnect() = server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

    private suspend fun link(): DevicesResult<com.lfnj.sport_app.features.sensors.model.LinkedDevice> =
        repository.link(user, monitor, SensorsFixtures.DISPLAY_NAME, idempotencyKeyFixture)

    private suspend fun stored(): List<StoredDevice> = dao.devices(owner).mapNotNull(codec::decodeDevice)

    /** Vincula el monitor de prueba (consume la solicitud). */
    private suspend fun linkFixture() {
        respond(SensorsFixtures.deviceBody(), 201)
        assertTrue(link() is DevicesResult.Done)
        takeRequest()
    }

    // ----------------------------------------------------------------- vincular

    @Test
    fun `vincular envia el hash, el nombre, el tipo y el protocolo con clave de idempotencia y sin la direccion BLE`() = runTest {
        login()
        respond(SensorsFixtures.deviceBody(), 201)

        val result = link()

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/devices", request.requestUrl?.encodedPath)
        assertEquals(idempotencyKeyFixture, request.getHeader("Idempotency-Key"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))
        val raw = request.body.readUtf8()
        val body = Json.parseToJsonElement(raw).jsonObject
        assertEquals(setOf("device_id", "display_name", "device_type", "protocol"), body.keys)
        assertEquals(deviceId, body.getValue("device_id").jsonPrimitive.content)
        assertEquals(SensorsFixtures.DISPLAY_NAME, body.getValue("display_name").jsonPrimitive.content)
        assertEquals("HEART_RATE_MONITOR", body.getValue("device_type").jsonPrimitive.content)
        assertEquals("BLE_HRS", body.getValue("protocol").jsonPrimitive.content)
        assertFalse(raw.contains(SensorsFixtures.ADDRESS, ignoreCase = true))
        assertFalse(raw.contains(SensorsFixtures.ADVERTISED_NAME))

        val linked = (result as DevicesResult.Done).value
        assertEquals(deviceId, linked.deviceId)
        assertEquals(DeviceStatus.ACTIVE, linked.status)
        assertTrue(linked.hasLocalAddress)
        assertTrue(linked.connectable)
    }

    @Test
    fun `la vinculacion confirmada se guarda cifrada con la direccion BLE`() = runTest {
        login()
        linkFixture()

        val entity = dao.devices(owner).single()
        assertEquals(deviceId, entity.deviceId)
        assertFalse(String(entity.payload, Charsets.ISO_8859_1).contains(SensorsFixtures.ADDRESS))
        assertEquals(SensorsFixtures.ADDRESS, stored().single().address)
        val observed = repository.observe(owner).first().single()
        assertEquals(SensorsFixtures.DISPLAY_NAME, observed.displayName)
        assertTrue(observed.hasLocalAddress)
        assertEquals(Instant.parse("2026-10-24T12:00:00Z"), observed.createdAt)
    }

    @Test
    fun `409 device_already_registered no guarda nada`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(409, "device_already_registered"))

        assertEquals(DevicesResult.Failed(DevicesProblem.ALREADY_REGISTERED), link())
        assertTrue(dao.devices(owner).isEmpty())
    }

    @Test
    fun `409 idempotency_key_reused pide una clave nueva`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(409, "idempotency_key_reused"))

        assertEquals(DevicesResult.Failed(DevicesProblem.KEY_REUSED), link())
        assertTrue(dao.devices(owner).isEmpty())
    }

    @Test
    fun `403 capability_required explica el plan y 403 permission_required se distingue`() = runTest {
        login()
        server.enqueue(SessionFixtures.error(403, "capability_required"))
        server.enqueue(SessionFixtures.error(403, "permission_required"))

        assertEquals(DevicesResult.Failed(DevicesProblem.CAPABILITY_REQUIRED), link())
        assertEquals(DevicesResult.Failed(DevicesProblem.PERMISSION_REQUIRED), link())
        assertTrue(dao.devices(owner).isEmpty())
    }

    @Test
    fun `sin red vincular falla OFFLINE y no se guarda ni se encola nada`() = runTest {
        login()
        disconnect()

        assertEquals(DevicesResult.Failed(DevicesProblem.OFFLINE), link())
        assertTrue(dao.devices(owner).isEmpty())
        assertTrue(dao.sampleRows.isEmpty())
    }

    @Test
    fun `una respuesta con otro device_id o fuera del contrato no se guarda`() = runTest {
        login()
        respond(SensorsFixtures.deviceBody(deviceId = SensorsFixtures.deviceId(SensorsFixtures.OTHER_ADDRESS)), 201)
        respond(SensorsFixtures.deviceBody(status = "LOST"), 201)
        respond(SensorsFixtures.deviceBody(createdAt = "ayer"), 201)

        assertEquals(DevicesResult.Failed(DevicesProblem.INVALID), link())
        assertEquals(DevicesResult.Failed(DevicesProblem.INVALID), link())
        assertEquals(DevicesResult.Failed(DevicesProblem.INVALID), link())
        assertTrue(dao.devices(owner).isEmpty())
    }

    @Test
    fun `sin sesion vincular devuelve SIGNED_OUT sin llamar al BFF`() = runTest {
        assertEquals(DevicesResult.Failed(DevicesProblem.SIGNED_OUT), link())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sin BFF configurado las operaciones quedan no disponibles`() = runTest {
        val unconfigured = DevicesRepository(dao, codec, null, sessionManager, BffMobileClient.json)

        assertEquals(DevicesResult.Failed(DevicesProblem.UNAVAILABLE), unconfigured.refresh(owner))
        assertEquals(DevicesResult.Failed(DevicesProblem.UNAVAILABLE), unconfigured.link(user, monitor, "x", idempotencyKeyFixture))
        assertEquals(DevicesResult.Failed(DevicesProblem.UNAVAILABLE), unconfigured.revoke(owner, deviceId))
    }

    // ----------------------------------------------------------------- listar

    @Test
    fun `refresh conserva la direccion de los conocidos, agrega los nuevos y elimina los que ya no vienen`() = runTest {
        login()
        linkFixture()
        val gone = SensorsFixtures.deviceId("C0:FF:EE:00:00:09")
        dao.upsertDevice(
            SensorDeviceEntity(
                owner, gone,
                codec.encodeDevice(StoredDevice(gone, "Viejo", DeviceStatus.ACTIVE, 1, Instant.EPOCH, Instant.EPOCH, null, null)),
            )
        )
        val other = SensorsFixtures.deviceId(SensorsFixtures.OTHER_ADDRESS)
        respond(
            SensorsFixtures.devicesBody(
                SensorsFixtures.deviceBody(deviceId = other, displayName = "Banda de otro telefono", createdAt = "2026-10-24T12:10:00Z"),
                SensorsFixtures.deviceBody(displayName = "Renombrado", version = 3, updatedAt = "2026-10-24T12:20:00Z"),
            )
        )

        assertEquals(DevicesResult.Done(Unit), repository.refresh(owner))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/devices", request.requestUrl?.encodedPath)
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))
        val devices = repository.observe(owner).first()
        assertEquals(listOf(other, deviceId), devices.map { it.deviceId })
        assertFalse(devices[0].hasLocalAddress)
        assertTrue(devices[1].hasLocalAddress)
        assertEquals("Renombrado", devices[1].displayName)
        assertEquals(3, devices[1].version)
        assertEquals(SensorsFixtures.ADDRESS, stored().single { it.deviceId == deviceId }.address)
    }

    @Test
    fun `refresh sin red o con respuesta invalida conserva lo guardado`() = runTest {
        login()
        linkFixture()
        disconnect()
        respond(SensorsFixtures.devicesBody(SensorsFixtures.deviceBody(status = "LOST")))

        assertEquals(DevicesResult.Failed(DevicesProblem.OFFLINE), repository.refresh(owner))
        assertEquals(DevicesResult.Failed(DevicesProblem.INVALID), repository.refresh(owner))
        assertEquals(listOf(deviceId), repository.observe(owner).first().map { it.deviceId })
    }

    // ----------------------------------------------------------------- revocar

    @Test
    fun `revocar marca REVOKED y borra las muestras pendientes del dispositivo`() = runTest {
        login()
        linkFixture()
        samples.record(owner, SensorsFixtures.SESSION_ID, deviceId, SensorsFixtures.START.epochSecond + 1, 120)
        respond(SensorsFixtures.deviceBody(status = "REVOKED", version = 2, updatedAt = "2026-10-24T13:00:00Z", revokedAt = "2026-10-24T13:00:00Z"))

        val result = repository.revoke(owner, deviceId)

        val request = takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/me/devices/$deviceId", request.requestUrl?.encodedPath)
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))
        val revoked = (result as DevicesResult.Done).value
        assertEquals(DeviceStatus.REVOKED, revoked.status)
        assertEquals(Instant.parse("2026-10-24T13:00:00Z"), revoked.revokedAt)
        assertFalse(revoked.connectable)
        assertTrue(dao.sampleRows.isEmpty())
        assertEquals(DeviceStatus.REVOKED, repository.observe(owner).first().single().status)
        assertNull(repository.observeConnectable(owner).first())
    }

    @Test
    fun `revocar un dispositivo inexistente devuelve NOT_FOUND y sin red OFFLINE`() = runTest {
        login()
        linkFixture()
        server.enqueue(SessionFixtures.error(404, "device_not_found"))
        disconnect()

        assertEquals(DevicesResult.Failed(DevicesProblem.NOT_FOUND), repository.revoke(owner, deviceId))
        assertEquals(DevicesResult.Failed(DevicesProblem.OFFLINE), repository.revoke(owner, deviceId))
        assertEquals(DeviceStatus.ACTIVE, repository.observe(owner).first().single().status)
    }

    // ----------------------------------------------------------------- ingestión

    @Test
    fun `observeConnectable entrega el monitor activo con direccion y nada para los demas`() = runTest {
        assertNull(repository.observeConnectable(owner).first())
        login()
        linkFixture()
        val other = SensorsFixtures.deviceId(SensorsFixtures.OTHER_ADDRESS)
        dao.upsertDevice(
            SensorDeviceEntity(
                owner, other,
                codec.encodeDevice(StoredDevice(other, "Sin direccion", DeviceStatus.ACTIVE, 1, Instant.EPOCH, Instant.parse("2026-10-25T00:00:00Z"), null, null)),
            )
        )

        assertEquals(ConnectableDevice(deviceId, SensorsFixtures.ADDRESS), repository.observeConnectable(owner).first())
        assertNull(repository.observeConnectable(SensorsFixtures.OTHER_OWNER).first())
    }

    @Test
    fun `markRevoked refleja la revocacion local y borra las muestras del dispositivo`() = runTest {
        login()
        linkFixture()
        samples.record(owner, SensorsFixtures.SESSION_ID, deviceId, SensorsFixtures.START.epochSecond + 1, 120)

        repository.markRevoked(owner, deviceId)

        assertEquals(DeviceStatus.REVOKED, repository.observe(owner).first().single().status)
        assertTrue(dao.sampleRows.isEmpty())
        assertNull(repository.observeConnectable(owner).first())
        // Un dispositivo desconocido no se inventa.
        repository.markRevoked(owner, SensorsFixtures.deviceId(SensorsFixtures.OTHER_ADDRESS))
        assertEquals(1, dao.devices(owner).size)
    }

    @Test
    fun `problemOf traduce las fallas del BFF`() {
        fun http(status: Int, code: String?) = ApiResult.HttpFailure(status, code, null, null)

        assertEquals(DevicesProblem.OFFLINE, problemOf(ApiResult.NoConnection))
        assertEquals(DevicesProblem.UNAVAILABLE, problemOf(ApiResult.Timeout))
        assertEquals(DevicesProblem.UNAVAILABLE, problemOf(ApiResult.NotConfigured))
        assertEquals(DevicesProblem.INVALID, problemOf(ApiResult.InvalidResponse))
        assertEquals(DevicesProblem.CAPABILITY_REQUIRED, problemOf(http(403, "capability_required")))
        assertEquals(DevicesProblem.PERMISSION_REQUIRED, problemOf(http(403, "permission_required")))
        assertEquals(DevicesProblem.ALREADY_REGISTERED, problemOf(http(409, "device_already_registered")))
        assertEquals(DevicesProblem.KEY_REUSED, problemOf(http(409, "idempotency_key_reused")))
        assertEquals(DevicesProblem.NOT_FOUND, problemOf(http(404, "device_not_found")))
        assertEquals(DevicesProblem.INVALID, problemOf(http(422, "validation_error")))
        assertEquals(DevicesProblem.UNAVAILABLE, problemOf(http(503, "upstream_unavailable")))
        assertEquals(DevicesProblem.UNAVAILABLE, problemOf(http(504, "upstream_timeout")))
    }

    // ----------------------------------------------------------------- limpieza

    @Test
    fun `SensorsJanitor borra lo de otros deportistas, y todo al cerrar sesion o cambiar de deportista`() = runTest {
        val other = SensorsFixtures.OTHER_OWNER
        val otherId = SensorsFixtures.deviceId(userId = TrainingFixtures.OTHER_USER_ID)
        fun device(id: String) = codec.encodeDevice(StoredDevice(id, "x", DeviceStatus.ACTIVE, 1, Instant.EPOCH, Instant.EPOCH, null, SensorsFixtures.ADDRESS))
        dao.upsertDevice(SensorDeviceEntity(owner, deviceId, device(deviceId)))
        dao.upsertDevice(SensorDeviceEntity(other, otherId, device(otherId)))
        samples.record(owner, SensorsFixtures.SESSION_ID, deviceId, 100L, 120)
        samples.record(other, "otra-sesion", otherId, 100L, 130)
        val currentUser = MutableStateFlow<SessionUser?>(user)

        val job = SensorsJanitor(currentUser, repository).start(backgroundScope)
        runCurrent()

        assertEquals(listOf(owner), dao.deviceRows.value.map { it.ownerKey })
        assertEquals(listOf(owner), dao.sampleRows.map { it.ownerKey })

        currentUser.value = null
        runCurrent()

        assertTrue(dao.deviceRows.value.isEmpty())
        assertTrue(dao.sampleRows.isEmpty())

        dao.upsertDevice(SensorDeviceEntity(owner, deviceId, device(deviceId)))
        currentUser.value = user
        runCurrent()
        dao.upsertDevice(SensorDeviceEntity(other, otherId, device(otherId)))
        currentUser.value = SensorsFixtures.user(TrainingFixtures.OTHER_USER_ID)
        runCurrent()

        assertEquals(listOf(other), dao.deviceRows.value.map { it.ownerKey })
        job.cancel()
    }
}
