package com.lfnj.sport_app.testing

import com.lfnj.sport_app.core.session.SecureTokenStore
import okhttp3.mockwebserver.MockResponse

/** Datos sintéticos de las respuestas del contrato `bff-mobile` 0.3.0. */
object SessionFixtures {
    const val ACCESS_TOKEN = "access-token-sintetico-1"
    const val REFRESH_TOKEN = "refresh-token-sintetico-000000000001"
    const val ROTATED_REFRESH_TOKEN = "refresh-token-sintetico-000000000002"

    /** Capacidades del plan `FREE` (`planes/04-matriz-capacidades.md`). */
    val FREE_CAPABILITIES = listOf(
        "training.plan.manage", "training.routes.suggest", "events.suggest", "training.plans.suggest",
        "services.support.suggest", "nutrition.routines.suggest", "integrations.strava.connect",
        "training.session.record", "training.dashboard.view", "training.metrics.ftp_vo2max", "calendar.sport.view",
    )
    val INTERMEDIATE_CAPABILITIES = FREE_CAPABILITIES +
        listOf("training.realtime.monitor", "devices.measurement.connect", "nutrition.session.reminders")
    val PREMIUM_CAPABILITIES = INTERMEDIATE_CAPABILITIES +
        listOf("services.sports_medicine.access", "services.virtual_sessions.schedule")

    fun sessionBody(
        actorType: String? = "athlete",
        accessToken: String = ACCESS_TOKEN,
        refreshToken: String = REFRESH_TOKEN,
        expiresIn: Long = 900,
    ): String {
        val actor = actorType?.let { ""","actor_type":"$it"""" } ?: ""
        return """{"token_type":"Bearer","access_token":"$accessToken","expires_in":$expiresIn,""" +
            """"refresh_token":"$refreshToken","refresh_expires_in":604800,""" +
            """"user":{"user_id":"7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b","full_name":"Ana Deportista",""" +
            """"email":"ana@example.com"$actor}}"""
    }

    fun capabilitiesBody(
        actorType: String = "athlete",
        status: String = "ACTIVE",
        planCode: String? = "FREE",
        capabilities: List<String> = FREE_CAPABILITIES,
        permissions: List<String> = emptyList(),
    ): String {
        val plan = planCode?.let { "\"$it\"" } ?: "null"
        return """{"actor_type":"$actorType","subscription_status":"$status","plan_code":$plan,"plan_version":1,""" +
            """"catalog_version":"plans-2026-10-11.v1","authorization_policy_version":"actor-permissions.v1",""" +
            """"permissions":${permissions.toJson()},"capabilities":${capabilities.toJson()},""" +
            """"evaluated_at":"2026-10-12T12:00:00Z"}"""
    }

    fun error(status: Int, code: String, retryAfter: Long? = null): MockResponse = MockResponse()
        .setResponseCode(status)
        .setBody("""{"code":"$code","message":"mensaje","correlation_id":"8c1f2d3e-0000-4000-8000-000000000001"}""")
        .apply { retryAfter?.let { setHeader("Retry-After", it) } }

    fun ok(body: String): MockResponse = MockResponse().setBody(body)

    private fun List<String>.toJson(): String = joinToString(",", "[", "]") { "\"$it\"" }
}

/** Almacenamiento en memoria para pruebas JVM; el mecanismo real se prueba en el dispositivo. */
class FakeTokenStore(var value: String? = null) : SecureTokenStore {
    var writes = 0
    override fun read(): String? = value
    override fun write(value: String) {
        writes++
        this.value = value
    }
    override fun clear() {
        value = null
    }
}
