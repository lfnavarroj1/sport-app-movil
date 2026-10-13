package com.lfnj.sport_app.testing

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.training.session.data.TrainingSessionApi
import com.lfnj.sport_app.features.training.session.data.TrainingSessionRepository
import com.lfnj.sport_app.features.training.session.data.local.TrainingRecordCodec
import com.lfnj.sport_app.features.training.session.model.LocalTrainingSession
import com.lfnj.sport_app.features.training.session.model.TrainingOwner
import com.lfnj.sport_app.features.training.session.sync.TrainingSyncEngine
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Repositorio y motor de sincronización reales de HU032 sobre la fuente local en memoria y un
 * `bff-mobile` simulado con MockWebServer. Contrato simulado: **no** es un E2E.
 */
class TrainingHarness {
    val server = MockWebServer().apply { start() }
    /**
     * Sin el reintento automático de OkHttp ante una conexión fallida: así un corte simulado llega
     * al motor como pérdida de red. En producción ese reintento es seguro por la `Idempotency-Key`.
     */
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    val tokenStore = FakeTokenStore()
    val sessionManager = newSessionManager()
    val dao = InMemoryTrainingDao()
    val transactions = SerialTransactionRunner()
    val cipher = FakeCipher()
    val codec = TrainingRecordCodec(cipher, BffMobileClient.json)
    val clock = FakeTrainingClock()
    val scheduler = FakeScheduler()
    val api: TrainingSessionApi = retrofit.create(TrainingSessionApi::class.java)
    val owner = TrainingOwner.keyOf(TrainingFixtures.USER_ID)
    val repository = TrainingSessionRepository(dao, transactions, codec, scheduler, api, sessionManager, BffMobileClient.json, clock)
    val engine = newEngine(sessionManager)

    /** El access token sintético dura 900 s; el reloj de la sesión queda fijo para no forzar renovaciones. */
    fun newSessionManager() = SessionManager(retrofit.create(SessionApi::class.java), tokenStore, BffMobileClient.json, clock = { 1_000_000L })

    fun newEngine(manager: SessionManager) = TrainingSyncEngine(dao, transactions, codec, api, manager, BffMobileClient.json, clock)

    suspend fun login(manager: SessionManager = sessionManager) {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        manager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    suspend fun sessions(owner: String = this.owner): List<LocalTrainingSession> = repository.observe(owner).first()

    suspend fun session(sessionId: String): LocalTrainingSession = sessions().single { it.sessionId == sessionId }

    fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    fun shutdown() = server.shutdown()

    companion object {
        fun isUuidV4(value: String): Boolean = runCatching { UUID.fromString(value).version() == 4 }.getOrDefault(false)
    }
}
