package com.lfnj.sport_app.testing

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.metrics.data.MetricsLocalState
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsApi
import com.lfnj.sport_app.features.training.metrics.data.TrainingMetricsRepository
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsCodec
import com.lfnj.sport_app.features.training.metrics.sync.MetricsSyncEngine
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/**
 * Repositorio y motor reales de HU034 sobre la fuente local en memoria y un `bff-mobile` simulado
 * con MockWebServer. Contrato simulado: **no** es un E2E.
 */
class MetricsHarness {
    val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    val tokenStore = FakeTokenStore()
    val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), tokenStore, BffMobileClient.json, clock = { 1_000_000L })
    val dao = InMemoryTrainingMetricsDao()
    val cipher = FakeCipher()
    val codec = TrainingMetricsCodec(cipher, BffMobileClient.json)
    val clock = MetricsClock()
    val scheduler = FakeScheduler()
    val api: TrainingMetricsApi = retrofit.create(TrainingMetricsApi::class.java)
    val owner = TrainingOwner.keyOf(TrainingFixtures.USER_ID)
    private var ids = 0
    val repository = TrainingMetricsRepository(
        dao, SerialTransactionRunner(), codec, api, sessionManager, scheduler, BffMobileClient.json, clock,
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )
    val engine = MetricsSyncEngine(dao, SerialTransactionRunner(), codec, api, sessionManager, BffMobileClient.json, clock)

    suspend fun login(manager: SessionManager = sessionManager) {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        manager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    /** Catálogo confirmado en caché (respuesta real de la composición local). */
    suspend fun cacheCatalog(body: String = MetricsFixtures.CATALOG) {
        server.enqueue(MockResponse().setBody(body).setHeader("ETag", MetricsFixtures.ETAG))
        repository.refreshCatalog(owner)
        takeRequest()
    }

    suspend fun local(owner: String = this.owner): MetricsLocalState = repository.observe(owner).first()

    fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    fun shutdown() = server.shutdown()
}
