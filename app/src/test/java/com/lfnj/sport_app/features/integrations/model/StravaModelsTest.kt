package com.lfnj.sport_app.features.integrations.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.features.integrations.system.StravaLinkLaunch
import com.lfnj.sport_app.testing.StravaFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** HU031: parseo del enlace profundo (CA2, CA3), vigencia `STALE` de 24 h y guarda visual por capacidad. */
class StravaModelsTest {

    @Test
    fun `los cuatro resultados del callback se reconocen (CA2, CA3)`() {
        StravaLinkResult.entries.forEach { result ->
            assertEquals(result, StravaLinkResult.fromDeepLink("sportapp://integrations/strava?result=${result.name}"))
        }
    }

    @Test
    fun `un resultado ausente o desconocido se trata como ERROR sin detalles`() {
        assertEquals(StravaLinkResult.ERROR, StravaLinkResult.fromDeepLink("sportapp://integrations/strava"))
        assertEquals(StravaLinkResult.ERROR, StravaLinkResult.fromDeepLink("sportapp://integrations/strava?result=OK"))
        assertEquals(StravaLinkResult.ERROR, StravaLinkResult.fromDeepLink("sportapp://integrations/strava?result=linked"))
        assertEquals(StravaLinkResult.ERROR, StravaLinkResult.fromDeepLink("sportapp://integrations/strava?result="))
    }

    @Test
    fun `solo el retorno de Strava se acepta`() {
        assertNull(StravaLinkResult.fromDeepLink(null))
        assertNull(StravaLinkResult.fromDeepLink("https://integrations/strava?result=LINKED"))
        assertNull(StravaLinkResult.fromDeepLink("sportapp://otra/strava?result=LINKED"))
        assertNull(StravaLinkResult.fromDeepLink("sportapp://integrations/garmin?result=LINKED"))
        assertNull(StravaLinkResult.fromDeepLink("sportapp://integrations/strava/extra?result=LINKED"))
        assertNull(StravaLinkResult.fromDeepLink("sportapp://integrations/strava?result=LINKED&x=%%"))
    }

    @Test
    fun `parametros ajenos no alteran el resultado y no se conservan`() {
        val launch = StravaLinkLaunch()

        assertTrue(launch.offer("sportapp://integrations/strava?code=no-debe-llegar&result=DENIED&state=tampoco"))

        assertEquals(StravaLinkResult.DENIED, launch.pending.value)
        assertEquals(StravaLinkResult.DENIED, launch.consume())
        assertNull(launch.pending.value)
        assertNull(launch.consume())
    }

    @Test
    fun `un enlace que no es de Strava no deja nada pendiente`() {
        val launch = StravaLinkLaunch()

        assertFalse(launch.offer("sportapp://integrations/otra?result=LINKED"))
        assertFalse(launch.offer(null))

        assertNull(launch.pending.value)
    }

    @Test
    fun `el estado confirmado pasa a STALE despues de 24 horas`() {
        val syncedAt = Instant.parse("2026-11-15T15:00:00Z")
        val integration = StravaFixtures.integration(syncedAt = syncedAt)

        assertFalse(integration.isStale(syncedAt.plus(Duration.ofHours(24))))
        assertTrue(integration.isStale(syncedAt.plus(Duration.ofHours(24)).plusSeconds(1)))
    }

    @Test
    fun `vinculado no significa sincronizado`() {
        val linkedNeverSynced = StravaFixtures.integration(lastSync = null)
        val syncing = StravaFixtures.integration(lastSync = StravaFixtures.sync(StravaSyncStatus.RUNNING))

        assertEquals(StravaLinkStatus.ACTIVE, linkedNeverSynced.status)
        assertNull(linkedNeverSynced.lastSync)
        assertFalse(linkedNeverSynced.syncInProgress)
        assertTrue(syncing.syncInProgress)
        assertTrue(StravaSyncStatus.QUEUED.inProgress)
        assertFalse(StravaSyncStatus.RATE_LIMITED.inProgress)
    }

    @Test
    fun `los errores que exigen volver a vincular son los de autorizacion`() {
        assertEquals(
            setOf(StravaSyncError.TOKEN_EXPIRED, StravaSyncError.INSUFFICIENT_SCOPE, StravaSyncError.INTEGRATION_REVOKED),
            StravaSyncError.entries.filter { it.requiresRelink }.toSet(),
        )
        assertEquals(StravaSyncError.CIRCUIT_OPEN, StravaSyncError.fromWire("circuit_open"))
        assertNull(StravaSyncError.fromWire("desconocido"))
    }

    @Test
    fun `solo un deportista con plan activo y la capacidad abre Strava`() {
        fun access(actor: ActorType = ActorType.ATHLETE, status: SubscriptionStatus = SubscriptionStatus.ACTIVE, caps: Set<String> = setOf(STRAVA_CAPABILITY)) =
            AccessSnapshot(actor, status, "FREE", emptySet(), caps)

        assertTrue(StravaAccess.canUse(access()))
        assertFalse(StravaAccess.canUse(null))
        assertFalse(StravaAccess.canUse(access(caps = emptySet())))
        assertFalse(StravaAccess.canUse(access(status = SubscriptionStatus.PROVISIONING)))
        assertFalse(StravaAccess.canUse(access(actor = ActorType.ORGANIZER)))
    }
}
