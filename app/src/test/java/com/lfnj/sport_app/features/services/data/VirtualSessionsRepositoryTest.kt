package com.lfnj.sport_app.features.services.data

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCatalogEntity
import com.lfnj.sport_app.features.services.data.local.VirtualSessionsCodec
import com.lfnj.sport_app.features.services.model.AppointmentStatus
import com.lfnj.sport_app.features.services.model.ServiceModality
import com.lfnj.sport_app.features.services.model.VirtualSessionsOwner
import com.lfnj.sport_app.testing.FakeCipher
import com.lfnj.sport_app.testing.FakeTokenStore
import com.lfnj.sport_app.testing.InMemoryVirtualSessionsDao
import com.lfnj.sport_app.testing.MutableClock
import com.lfnj.sport_app.testing.SessionFixtures
import com.lfnj.sport_app.testing.TrainingFixtures
import com.lfnj.sport_app.testing.VirtualSessionsFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HU040 contra un `bff-mobile` simulado con MockWebServer y la caché en memoria (cifrado de
 * prueba). Contrato simulado según `bff-mobile` 0.18.0: **no** es un E2E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualSessionsRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), FakeTokenStore(), BffMobileClient.json, clock = { 1_000_000L })
    private val dao = InMemoryVirtualSessionsDao()
    private val cipher = FakeCipher()
    private val clock = MutableClock(Instant.parse("2026-10-25T15:00:00Z"), ZoneId.of("America/Bogota"))
    private val retryDelays = mutableListOf<Int>()
    private val repository = VirtualSessionsRepository(
        dao, VirtualSessionsCodec(cipher, BffMobileClient.json), retrofit.create(VirtualSessionsApi::class.java),
        sessionManager, BffMobileClient.json, clock, retryDelay = { retryDelays += it },
    )
    private val owner = VirtualSessionsOwner.keyOf(TrainingFixtures.USER_ID)
    private val key = "5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e"

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

    private suspend fun book() = repository.book(owner, VirtualSessionsFixtures.SERVICE_ID, VirtualSessionsFixtures.SLOT_ID, key)

    // ----------------------------------------------------------------- catálogo

    @Test
    fun `consulta servicios COACHING virtuales e hibridos en un rango de 30 dias y guarda la lista`() = runTest {
        login()
        respond(VirtualSessionsFixtures.catalog())

        assertEquals(VirtualSessionsRefresh.Updated, repository.refreshCatalog(owner))

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/services/availability", request.requestUrl?.encodedPath)
        assertEquals(listOf("COACHING"), request.requestUrl?.queryParameterValues("category"))
        assertEquals(listOf("VIRTUAL", "HYBRID"), request.requestUrl?.queryParameterValues("modality"))
        assertEquals("2026-10-25T10:00:00-05:00", request.requestUrl?.queryParameter("from"))
        assertEquals("2026-11-24T10:00:00-05:00", request.requestUrl?.queryParameter("to"))
        assertEquals("50", request.requestUrl?.queryParameter("limit"))
        assertNull(request.requestUrl?.queryParameter("cursor"))
        assertEquals("Bearer ${SessionFixtures.ACCESS_TOKEN}", request.getHeader("Authorization"))

        val cached = checkNotNull(repository.observeCatalog(owner).first())
        val service = cached.services.single()
        assertEquals(VirtualSessionsFixtures.SERVICE_ID, service.serviceId)
        assertEquals(ServiceModality.VIRTUAL, service.modality)
        assertEquals("120000.00", service.fee.amount)
        assertEquals(listOf(VirtualSessionsFixtures.SLOT_ID, VirtualSessionsFixtures.LATER_SLOT_ID), service.slots.map { it.availabilityId })
        assertEquals(ZoneId.of("Europe/Madrid"), service.slots[1].timeZone)
        assertEquals(Instant.parse("2026-10-28T15:00:00Z"), service.slots[0].startsAt)
        assertEquals(clock.instant, cached.syncedAt)
        assertEquals(false, cached.truncated)
    }

    @Test
    fun `sigue el cursor del catalogo y omite servicios que no son reservables`() = runTest {
        login()
        respond(VirtualSessionsFixtures.catalog(nextCursor = "c2"))
        respond(
            VirtualSessionsFixtures.catalog(
                listOf(
                    VirtualSessionsFixtures.service(VirtualSessionsFixtures.OTHER_SERVICE_ID, "Plan híbrido", modality = "HYBRID"),
                    VirtualSessionsFixtures.service("5c4d3e2f-1a0b-4c9d-8e7f-6a5b4c3d2e1f", "Presencial", modality = "IN_PERSON"),
                )
            )
        )

        assertEquals(VirtualSessionsRefresh.Updated, repository.refreshCatalog(owner))

        takeRequest()
        assertEquals("c2", takeRequest().requestUrl?.queryParameter("cursor"))
        val services = checkNotNull(repository.observeCatalog(owner).first()).services
        assertEquals(listOf(VirtualSessionsFixtures.SERVICE_ID, VirtualSessionsFixtures.OTHER_SERVICE_ID), services.map { it.serviceId })
    }

    @Test
    fun `un catalogo con mas paginas que el maximo se marca incompleto`() = runTest {
        login()
        repeat(VirtualSessionsRepository.MAX_PAGES) { respond(VirtualSessionsFixtures.catalog(nextCursor = "c$it")) }

        assertEquals(VirtualSessionsRefresh.Updated, repository.refreshCatalog(owner))
        assertEquals(true, checkNotNull(repository.observeCatalog(owner).first()).truncated)
    }

    @Test
    fun `sin red conserva el ultimo catalogo confirmado`() = runTest {
        login()
        respond(VirtualSessionsFixtures.catalog())
        repository.refreshCatalog(owner)
        takeRequest()
        disconnect()

        assertEquals(VirtualSessionsRefresh.NoConnection, repository.refreshCatalog(owner))
        assertEquals(1, checkNotNull(repository.observeCatalog(owner).first()).services.size)
    }

    @Test
    fun `una zona o fecha invalida invalida toda la respuesta sin tocar la cache`() = runTest {
        login()
        respond(VirtualSessionsFixtures.catalog(listOf(VirtualSessionsFixtures.service(intervals = listOf(VirtualSessionsFixtures.interval(timeZone = "Mars/Olympus"))))))
        assertEquals(VirtualSessionsRefresh.InvalidResponse, repository.refreshCatalog(owner))
        respond(VirtualSessionsFixtures.catalog(listOf(VirtualSessionsFixtures.service(intervals = listOf(VirtualSessionsFixtures.interval(startsAt = "mañana"))))))
        assertEquals(VirtualSessionsRefresh.InvalidResponse, repository.refreshCatalog(owner))
        assertNull(repository.observeCatalog(owner).first())
    }

    @Test
    fun `errores de lectura del catalogo`() = runTest {
        login()
        respond(VirtualSessionsFixtures.error("permission_required"), 403)
        assertEquals(VirtualSessionsRefresh.PermissionRequired, repository.refreshCatalog(owner))
        respond(VirtualSessionsFixtures.error("validation_error"), 422)
        assertEquals(VirtualSessionsRefresh.InvalidRequest, repository.refreshCatalog(owner))
        respond(VirtualSessionsFixtures.error("upstream_unavailable"), 503)
        assertEquals(VirtualSessionsRefresh.Unavailable(VirtualSessionsFixtures.CORRELATION_ID), repository.refreshCatalog(owner))
    }

    @Test
    fun `si no puede guardar informa el fallo de almacenamiento`() = runTest {
        login()
        dao.failWrites = true
        respond(VirtualSessionsFixtures.catalog())
        assertEquals(VirtualSessionsRefresh.StorageFailed, repository.refreshCatalog(owner))
    }

    // ----------------------------------------------------------------- mis sesiones

    @Test
    fun `consulta las citas propias, reservadas y canceladas, sin filtros y siguiendo el cursor`() = runTest {
        login()
        respond(VirtualSessionsFixtures.appointments(nextCursor = "p2"))
        respond(
            VirtualSessionsFixtures.appointments(
                listOf(VirtualSessionsFixtures.appointment("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff", "CANCELLED_BY_ATHLETE", cancelledAt = "2026-10-25T14:30:00Z", version = 2))
            )
        )

        assertEquals(VirtualSessionsRefresh.Updated, repository.refreshAppointments(owner))

        val first = takeRequest()
        assertEquals("/v1/me/appointments", first.requestUrl?.encodedPath)
        assertEquals("100", first.requestUrl?.queryParameter("limit"))
        assertNull(first.requestUrl?.queryParameter("status"))
        assertNull(first.requestUrl?.queryParameter("from"))
        assertEquals("p2", takeRequest().requestUrl?.queryParameter("cursor"))
        val items = checkNotNull(repository.observeAppointments(owner).first()).items
        assertEquals(listOf(AppointmentStatus.BOOKED, AppointmentStatus.CANCELLED_BY_ATHLETE), items.map { it.status })
        assertEquals(Instant.parse("2026-10-25T14:30:00Z"), items[1].cancelledAt)
    }

    // ----------------------------------------------------------------- reservar (CA1, CA2, CA3, CA5)

    @Test
    fun `reserva con la clave del intento y agrega la cita confirmada a Mis sesiones (CA1)`() = runTest {
        login()
        respond(VirtualSessionsFixtures.appointment(), 201)

        val result = book()

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/appointments", request.requestUrl?.encodedPath)
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals(
            """{"service_id":"${VirtualSessionsFixtures.SERVICE_ID}","availability_id":"${VirtualSessionsFixtures.SLOT_ID}"}""",
            request.body.readUtf8(),
        )
        val booked = (result as BookResult.Booked).appointment
        assertEquals(AppointmentStatus.BOOKED, booked.status)
        assertEquals(VirtualSessionsFixtures.PROVIDER_ID, booked.providerId)
        assertEquals(ZoneId.of("America/Bogota"), booked.timeZone)
        assertEquals(listOf(VirtualSessionsFixtures.APPOINTMENT_ID), checkNotNull(repository.observeAppointments(owner).first()).items.map { it.appointmentId })
    }

    @Test
    fun `los fallos transitorios se reintentan con la misma clave y devuelven la cita original (CA5)`() = runTest {
        login()
        disconnect()
        respond(VirtualSessionsFixtures.error("upstream_unavailable"), 503)
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Idempotent-Replayed", "true").setBody(VirtualSessionsFixtures.appointment()))

        val result = book()

        assertTrue(result is BookResult.Booked)
        repeat(3) { assertEquals(key, takeRequest().getHeader("Idempotency-Key")) }
        assertEquals(listOf(1, 2), retryDelays)
    }

    @Test
    fun `sin red tras los reintentos el resultado es desconocido y no se guarda nada`() = runTest {
        login()
        repeat(VirtualSessionsGateway.MAX_AUTOMATIC_RETRIES + 1) { disconnect() }

        assertEquals(BookResult.NoConnection, book())
        assertEquals(listOf(1, 2, 3), retryDelays)
        assertNull(repository.observeAppointments(owner).first())
    }

    @Test
    fun `los conflictos y rechazos no se reintentan automaticamente (CA2, CA3)`() = runTest {
        login()
        val cases = listOf(
            Triple(409, VirtualSessionsFixtures.error("availability_full"), BookResult.Full),
            Triple(
                409,
                VirtualSessionsFixtures.error("appointment_exists", """[{"field":"appointment_id","issue":"${VirtualSessionsFixtures.APPOINTMENT_ID}"}]"""),
                BookResult.AlreadyBooked(VirtualSessionsFixtures.APPOINTMENT_ID),
            ),
            Triple(409, VirtualSessionsFixtures.error("idempotency_key_reused"), BookResult.KeyReused),
            Triple(403, VirtualSessionsFixtures.error("capability_required"), BookResult.CapabilityRequired),
            Triple(403, VirtualSessionsFixtures.error("permission_required"), BookResult.PermissionRequired),
            Triple(422, VirtualSessionsFixtures.error("service_not_bookable"), BookResult.ServiceNotBookable),
            Triple(422, VirtualSessionsFixtures.error("availability_not_bookable"), BookResult.SlotNotBookable),
            Triple(422, VirtualSessionsFixtures.error("validation_error"), BookResult.Invalid),
        )
        cases.forEach { (status, body, expected) ->
            respond(body, status)
            assertEquals(expected, book())
            takeRequest()
        }
        assertTrue(retryDelays.isEmpty())
        assertNull(repository.observeAppointments(owner).first())
    }

    // ----------------------------------------------------------------- cancelar (CA4)

    @Test
    fun `cancela la cita propia y la reemplaza en Mis sesiones (CA4)`() = runTest {
        login()
        respond(VirtualSessionsFixtures.appointments())
        repository.refreshAppointments(owner)
        takeRequest()
        respond(VirtualSessionsFixtures.appointment(status = "CANCELLED_BY_ATHLETE", cancelledAt = "2026-10-25T15:00:00Z", version = 2))

        val result = repository.cancel(owner, VirtualSessionsFixtures.APPOINTMENT_ID)

        val request = takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/me/appointments/${VirtualSessionsFixtures.APPOINTMENT_ID}", request.requestUrl?.encodedPath)
        assertNull(request.getHeader("Idempotency-Key"))
        assertEquals(AppointmentStatus.CANCELLED_BY_ATHLETE, (result as CancelResult.Cancelled).appointment.status)
        val cached = checkNotNull(repository.observeAppointments(owner).first()).items.single()
        assertEquals(AppointmentStatus.CANCELLED_BY_ATHLETE, cached.status)
        assertEquals(2, cached.version)
    }

    @Test
    fun `fuera de la ventana de 24 h recibe cancellation_window_closed y la cita sigue reservada (CA4)`() = runTest {
        login()
        respond(VirtualSessionsFixtures.appointments())
        repository.refreshAppointments(owner)
        takeRequest()
        respond(VirtualSessionsFixtures.error("cancellation_window_closed"), 422)

        assertEquals(CancelResult.WindowClosed, repository.cancel(owner, VirtualSessionsFixtures.APPOINTMENT_ID))
        assertEquals(AppointmentStatus.BOOKED, checkNotNull(repository.observeAppointments(owner).first()).items.single().status)
    }

    @Test
    fun `errores de cancelacion`() = runTest {
        login()
        respond(VirtualSessionsFixtures.error("appointment_not_found"), 404)
        assertEquals(CancelResult.NotFound, repository.cancel(owner, VirtualSessionsFixtures.APPOINTMENT_ID))
        respond(VirtualSessionsFixtures.error("permission_required"), 403)
        assertEquals(CancelResult.PermissionRequired, repository.cancel(owner, VirtualSessionsFixtures.APPOINTMENT_ID))
        respond(VirtualSessionsFixtures.error("validation_error"), 422)
        assertEquals(CancelResult.Invalid, repository.cancel(owner, VirtualSessionsFixtures.APPOINTMENT_ID))
        repeat(VirtualSessionsGateway.MAX_AUTOMATIC_RETRIES + 1) { disconnect() }
        assertEquals(CancelResult.NoConnection, repository.cancel(owner, VirtualSessionsFixtures.APPOINTMENT_ID))
    }

    @Test
    fun `sin sesion no envia comandos`() = runTest {
        assertEquals(BookResult.SignedOut, book())
        assertEquals(CancelResult.SignedOut, repository.cancel(owner, VirtualSessionsFixtures.APPOINTMENT_ID))
        assertEquals(VirtualSessionsRefresh.SignedOut, repository.refreshCatalog(owner))
        assertEquals(0, server.requestCount)
    }

    // ----------------------------------------------------------------- caché y limpieza

    @Test
    fun `una cache alterada se trata como inexistente`() = runTest {
        dao.replaceCatalog(VirtualSessionsCatalogEntity(owner, "texto".toByteArray()))
        assertNull(repository.observeCatalog(owner).first())
    }

    @Test
    fun `la cache cifrada no contiene los datos en claro`() = runTest {
        login()
        respond(VirtualSessionsFixtures.catalog())
        repository.refreshCatalog(owner)
        val payload = checkNotNull(dao.catalogs.value[owner]).payload.toString(Charsets.UTF_8)
        assertTrue("Asesoría" !in payload)
    }

    @Test
    fun `el conserje limpia la cache al cerrar sesion y la de otros deportistas al entrar`() = runTest {
        val user = MutableStateFlow<SessionUser?>(SessionUser(TrainingFixtures.USER_ID, "Ana", "ana@example.com", ActorType.ATHLETE))
        val other = VirtualSessionsOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
        dao.replaceCatalog(VirtualSessionsCatalogEntity(other, byteArrayOf(1)))
        dao.replaceCatalog(VirtualSessionsCatalogEntity(owner, byteArrayOf(2)))
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val job = VirtualSessionsCacheJanitor(user, repository).start(scope)

        assertEquals(setOf(owner), dao.catalogs.value.keys)
        user.value = null
        assertTrue(dao.catalogs.value.isEmpty())
        job.cancelAndJoin()
    }
}
