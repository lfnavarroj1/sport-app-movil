package com.lfnj.sport_app.testing

import com.lfnj.sport_app.features.training.metrics.data.local.MetricCatalogCacheEntity
import com.lfnj.sport_app.features.training.metrics.data.local.MetricCommandEntity
import com.lfnj.sport_app.features.training.metrics.data.local.MetricResultCacheEntity
import com.lfnj.sport_app.features.training.metrics.data.local.TrainingMetricsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Datos sintéticos de HU034 conforme a ADR-030 y la copia de `bff-mobile` 0.8.0. */
object MetricsFixtures {
    val NOW: Instant = Instant.parse("2026-10-12T15:35:00Z")
    const val CALCULATION_ID = "7a2fef74-b7bb-43dd-b670-ad8d40aa11d2"
    const val ETAG = "\"training-metrics-2026-10-12.v1\""
    const val FTP_ID = "FTP_20_MIN_95_V1@1"
    const val COOPER_ID = "COOPER_12_MIN_V1@1"

    private const val FTP_METHOD =
        """{"metric_code":"FTP_ESTIMATED","label_key":"training_metric.ftp_estimated","result_unit":"W","result_scale":0,""" +
            """"method_code":"FTP_20_MIN_95_V1","method_version":1,"method_label_key":"training_metric.method.ftp_20_min_95_v1",""" +
            """"disclaimer_key":"estimated_not_diagnostic","inputs":[{"code":"mean_power_watts",""" +
            """"label_key":"training_metric.input.mean_power_watts","value_type":"INTEGER","unit":"W","minimum":1,""" +
            """"maximum":2500,"scale":0,"required":true}]}"""

    private const val COOPER_METHOD =
        """{"metric_code":"VO2MAX_ESTIMATED","label_key":"training_metric.vo2max_estimated","result_unit":"mL/kg/min",""" +
            """"result_scale":1,"method_code":"COOPER_12_MIN_V1","method_version":1,""" +
            """"method_label_key":"training_metric.method.cooper_12_min_v1","disclaimer_key":"estimated_not_diagnostic",""" +
            """"inputs":[{"code":"distance_meters","label_key":"training_metric.input.distance_meters","value_type":"INTEGER",""" +
            """"unit":"m","minimum":600,"maximum":6000,"scale":0,"required":true}]}"""

    /**
     * Tercer indicador numérico escalar **solo de prueba** (HA014, CA7): claves de texto que la app
     * no conoce y dos entradas. Debe renderizarse sin cambiar el código.
     */
    const val THIRD_METHOD =
        """{"metric_code":"TEST_SCALAR_ESTIMATED","label_key":"training_metric.test_scalar_estimated","result_unit":"pts",""" +
            """"result_scale":2,"method_code":"TEST_SCALAR_V1","method_version":3,"method_label_key":"training_metric.method.test_scalar_v1",""" +
            """"disclaimer_key":"estimated_not_diagnostic","inputs":[""" +
            """{"code":"repetitions","label_key":"training_metric.input.repetitions","value_type":"INTEGER","unit":"rep","minimum":1,"maximum":500,"scale":0,"required":true},""" +
            """{"code":"heart_rate_bpm","label_key":"training_metric.input.heart_rate_bpm","value_type":"INTEGER","unit":"bpm","minimum":30,"maximum":250,"scale":0,"required":false}]}"""

    /** Respuesta real del catálogo de la composición local (2026-10-18). */
    val CATALOG = """{"catalog_version":"training-metrics-2026-10-12.v1","methods":[$FTP_METHOD,$COOPER_METHOD]}"""

    val CATALOG_WITH_THIRD = """{"catalog_version":"training-metrics-2026-10-19.test","methods":[$FTP_METHOD,$COOPER_METHOD,$THIRD_METHOD]}"""

    fun ftpResult(
        calculationId: String = CALCULATION_ID,
        measuredAt: String = "2026-10-12T15:30:00Z",
        calculatedAt: String = "2026-10-12T15:30:02Z",
    ) = """{"calculation_id":"$calculationId","athlete_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b",""" +
        """"metric_code":"FTP_ESTIMATED","method_code":"FTP_20_MIN_95_V1","method_version":1,"measured_at":"$measuredAt",""" +
        """"inputs":[{"code":"mean_power_watts","value":250,"unit":"W"}],"result":{"value":238,"unit":"W","scale":0},""" +
        """"status":"CONFIRMED","calculated_at":"$calculatedAt","disclaimer_key":"estimated_not_diagnostic","version":1}"""

    fun cooperResult(calculationId: String, measuredAt: String = "2026-10-11T08:00:00Z") =
        """{"calculation_id":"$calculationId","athlete_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b",""" +
            """"metric_code":"VO2MAX_ESTIMATED","method_code":"COOPER_12_MIN_V1","method_version":1,"measured_at":"$measuredAt",""" +
            """"inputs":[{"code":"distance_meters","value":2400,"unit":"m"}],"result":{"value":42.4,"unit":"mL/kg/min","scale":1},""" +
            """"status":"CONFIRMED","calculated_at":"2026-10-11T08:00:05Z","disclaimer_key":"estimated_not_diagnostic","version":1}"""

    fun page(vararg items: String, nextCursor: String? = null) =
        """{"items":[${items.joinToString(",")}],"next_cursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}"""

    fun validationError(field: String, issue: String) =
        """{"code":"validation_error","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000002",""" +
            """"details":[{"field":"$field","issue":"$issue"}]}"""
}

/** Reloj controlable para vigencias, vencimientos e instantes de prueba. */
class MetricsClock(var instant: Instant = MetricsFixtures.NOW) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = instant

    fun advanceHours(hours: Long) {
        instant = instant.plusSeconds(hours * 3600)
    }
}

/** Fuente local en memoria con la misma semántica de consultas que el DAO de Room de HU034. */
class InMemoryTrainingMetricsDao : TrainingMetricsDao {
    val catalogs = linkedMapOf<String, MetricCatalogCacheEntity>()
    val results = linkedMapOf<String, MetricResultCacheEntity>()
    val commands = linkedMapOf<String, MetricCommandEntity>()
    var failWrites = false
    private val changes = MutableStateFlow(0)

    private fun changed() {
        changes.value++
    }

    private fun checkWritable() {
        if (failWrites) throw IllegalStateException("almacenamiento no disponible")
    }

    override fun observeCatalog(owner: String): Flow<MetricCatalogCacheEntity?> = changes.map { catalogs[owner] }

    override suspend fun catalog(owner: String) = catalogs[owner]

    override suspend fun replaceCatalog(entry: MetricCatalogCacheEntity) {
        checkWritable()
        catalogs[entry.ownerKey] = entry
        changed()
    }

    override fun observeResults(owner: String): Flow<MetricResultCacheEntity?> = changes.map { results[owner] }

    override suspend fun results(owner: String) = results[owner]

    override suspend fun replaceResults(entry: MetricResultCacheEntity) {
        checkWritable()
        results[entry.ownerKey] = entry
        changed()
    }

    override fun observeCommands(owner: String): Flow<List<MetricCommandEntity>> =
        changes.map { commands.values.filter { it.ownerKey == owner }.sortedByDescending { it.createdAtMs } }

    override suspend fun pendingCommands(owner: String) =
        commands.values.filter { it.ownerKey == owner && it.syncState == MetricCommandEntity.PENDING_SYNC }.sortedBy { it.createdAtMs }

    override suspend fun countPending(owner: String) = pendingCommands(owner).size

    override suspend fun command(owner: String, calculationId: String) = commands[calculationId]?.takeIf { it.ownerKey == owner }

    override suspend fun insertCommand(command: MetricCommandEntity) {
        checkWritable()
        require(command.calculationId !in commands && commands.values.none { it.idempotencyKey == command.idempotencyKey })
        commands[command.calculationId] = command
        changed()
    }

    override suspend fun replaceCommand(command: MetricCommandEntity) {
        checkWritable()
        commands[command.calculationId] = command
        changed()
    }

    override suspend fun markAttempt(calculationId: String, now: Long) {
        val command = commands[calculationId] ?: return
        commands[calculationId] = MetricCommandEntity(
            command.calculationId, command.ownerKey, command.idempotencyKey, command.syncState, command.failureReason,
            command.failureField, command.failureIssue, command.createdAtMs, command.attempts + 1, now, command.payload,
        )
        changed()
    }

    override suspend fun deleteCommand(owner: String, calculationId: String) {
        if (commands[calculationId]?.ownerKey == owner) commands.remove(calculationId)
        changed()
    }

    override suspend fun pendingCreatedBefore(threshold: Long) =
        commands.values.filter { it.syncState == MetricCommandEntity.PENDING_SYNC && it.createdAtMs < threshold }
}
