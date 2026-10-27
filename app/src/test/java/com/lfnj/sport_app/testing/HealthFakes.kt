package com.lfnj.sport_app.testing

import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.session.SessionApi
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.health.data.HealthReportsApi
import com.lfnj.sport_app.features.health.data.HealthReportsRepository
import com.lfnj.sport_app.features.health.data.local.HealthReportEntity
import com.lfnj.sport_app.features.health.data.local.HealthReportsCodec
import com.lfnj.sport_app.features.health.data.local.HealthReportsDao
import com.lfnj.sport_app.features.health.model.BodyArea
import com.lfnj.sport_app.features.health.model.HealthOwner
import com.lfnj.sport_app.features.health.model.HealthReportDetails
import com.lfnj.sport_app.features.health.model.HealthReportType
import com.lfnj.sport_app.features.health.model.HealthSeverity
import com.lfnj.sport_app.features.health.model.LocalHealthReport
import com.lfnj.sport_app.features.health.sync.HealthReportsSyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Datos sintéticos de HU042 conforme a ADR-037 y la copia de `bff-mobile` 0.21.0. */
object HealthFixtures {
    val NOW: Instant = Instant.parse("2026-10-24T18:35:00Z")
    const val CONSENT_VERSION = "local-dev"
    const val SESSION_ID = "4c3b2a19-0f1e-4d2c-8b3a-293847566574"

    fun details(
        severity: HealthSeverity = HealthSeverity.MODERATE,
        occurredAt: Instant = Instant.parse("2026-10-24T18:30:00Z"),
        sessionId: String? = null,
        recoveryDays: Int? = null,
    ) = HealthReportDetails(HealthReportType.DISCOMFORT, BodyArea.KNEE, severity, occurredAt, sessionId, recoveryDays)

    /** `HealthReportResponse` tal como lo devuelve la composición local para el cuerpo enviado. */
    fun response(
        reportId: String,
        type: String = "DISCOMFORT",
        bodyArea: String = "KNEE",
        severity: String = "MODERATE",
        occurredAt: String = "2026-10-24T18:30:00Z",
        sessionId: String? = null,
        recoveryDays: Int? = null,
        status: String = "OPEN",
    ) = """{"report_id":"$reportId","athlete_id":"${TrainingFixtures.USER_ID}","type":"$type","body_area":"$bodyArea",""" +
        """"severity":"$severity","occurred_at":"$occurredAt","session_id":${sessionId?.let { "\"$it\"" } ?: "null"},""" +
        """"expected_recovery_days":${recoveryDays ?: "null"},"consent":{"version":"$CONSENT_VERSION"},"status":"$status",""" +
        """"reported_at":"2026-10-24T18:35:02Z","version":1}"""

    fun error(code: String, field: String? = null, issue: String? = null): String {
        val details = if (issue != null) ""","details":[{"field":${field?.let { "\"$it\"" } ?: "null"},"issue":"$issue"}]""" else ""
        return """{"code":"$code","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000042"$details}"""
    }
}

/** Fuente local en memoria con la misma semántica de consultas que el DAO de Room de HU042. */
class InMemoryHealthReportsDao : HealthReportsDao {
    val reports = linkedMapOf<String, HealthReportEntity>()
    var failWrites = false
    private val changes = MutableStateFlow(0)

    private fun changed() {
        changes.value++
    }

    private fun checkWritable() {
        if (failWrites) throw IllegalStateException("almacenamiento no disponible")
    }

    override fun observeReports(owner: String): Flow<List<HealthReportEntity>> =
        changes.map { reports.values.filter { it.ownerKey == owner }.sortedByDescending { it.createdAtMs } }

    override suspend fun pendingReports(owner: String) =
        reports.values.filter { it.ownerKey == owner && it.syncState == HealthReportEntity.PENDING_SYNC }.sortedBy { it.createdAtMs }

    override suspend fun countPending(owner: String) = pendingReports(owner).size

    override suspend fun report(owner: String, reportId: String) = reports[reportId]?.takeIf { it.ownerKey == owner }

    override suspend fun insertReport(report: HealthReportEntity) {
        checkWritable()
        require(report.reportId !in reports && reports.values.none { it.idempotencyKey == report.idempotencyKey })
        reports[report.reportId] = report
        changed()
    }

    override suspend fun replaceReport(report: HealthReportEntity) {
        checkWritable()
        reports[report.reportId] = report
        changed()
    }

    override suspend fun markAttempt(reportId: String, now: Long) {
        val r = reports[reportId] ?: return
        reports[reportId] = HealthReportEntity(
            r.reportId, r.ownerKey, r.idempotencyKey, r.syncState, r.failureReason, r.failureField, r.failureIssue,
            r.createdAtMs, r.attempts + 1, now, r.payload,
        )
        changed()
    }

    override suspend fun deleteReport(owner: String, reportId: String) {
        if (reports[reportId]?.ownerKey == owner) reports.remove(reportId)
        changed()
    }

    override suspend fun deleteSettled(owner: String) {
        reports.values.removeAll { it.ownerKey == owner && it.syncState != HealthReportEntity.PENDING_SYNC }
        changed()
    }

    override suspend fun pendingCreatedBefore(threshold: Long) =
        reports.values.filter { it.syncState == HealthReportEntity.PENDING_SYNC && it.createdAtMs < threshold }
}

/**
 * Repositorio y motor reales de HU042 sobre la fuente local en memoria y un `bff-mobile` simulado
 * con MockWebServer. Contrato simulado: **no** es un E2E.
 */
class HealthHarness(consentVersion: String? = HealthFixtures.CONSENT_VERSION) {
    val server = MockWebServer().apply { start() }
    private val retrofit = BffMobileClient.retrofit(
        server.url("/").toString(),
        BffMobileClient.okHttp().newBuilder().retryOnConnectionFailure(false).build(),
    )
    val tokenStore = FakeTokenStore()
    val sessionManager = SessionManager(retrofit.create(SessionApi::class.java), tokenStore, BffMobileClient.json, clock = { 1_000_000L })
    val dao = InMemoryHealthReportsDao()
    val cipher = FakeCipher()
    val codec = HealthReportsCodec(cipher, BffMobileClient.json)
    val clock = MetricsClock(HealthFixtures.NOW)
    val scheduler = FakeScheduler()
    val api: HealthReportsApi = retrofit.create(HealthReportsApi::class.java)
    val owner = HealthOwner.keyOf(TrainingFixtures.USER_ID)
    val otherOwner = HealthOwner.keyOf(TrainingFixtures.OTHER_USER_ID)
    private var ids = 0
    val repository = HealthReportsRepository(
        dao, SerialTransactionRunner(), codec, scheduler, consentVersion, clock,
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )
    val engine = HealthReportsSyncEngine(dao, SerialTransactionRunner(), codec, api, sessionManager, BffMobileClient.json, clock)

    suspend fun login(manager: SessionManager = sessionManager) {
        server.enqueue(SessionFixtures.ok(SessionFixtures.sessionBody()))
        manager.login("ana@example.com", "Secreta-123")
        server.takeRequest()
    }

    suspend fun local(owner: String = this.owner): List<LocalHealthReport> = repository.observe(owner).first()

    fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "No llegó la solicitud esperada" }

    fun shutdown() = server.shutdown()
}
