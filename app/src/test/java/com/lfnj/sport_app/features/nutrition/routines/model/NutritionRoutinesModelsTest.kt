package com.lfnj.sport_app.features.nutrition.routines.model

import com.lfnj.sport_app.core.session.AccessSnapshot
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SubscriptionStatus
import com.lfnj.sport_app.testing.TrainingFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU030: guarda visual, partición, catálogos de códigos y reglas de la lista confirmada. */
class NutritionRoutinesModelsTest {
    private val now = Instant.parse("2026-10-27T15:00:00Z")

    private fun access(status: SubscriptionStatus = SubscriptionStatus.ACTIVE, capabilities: Set<String> = setOf(NUTRITION_ROUTINES_SUGGEST_CAPABILITY)) =
        AccessSnapshot(ActorType.ATHLETE, status, "FREE", setOf(), capabilities)

    private fun snapshot(personalized: Boolean = true, notices: List<String> = listOf("NOT_MEDICAL_ADVICE"), users: RoutineSourceStatus? = RoutineSourceStatus.OK) =
        NutritionRoutinesSnapshot(emptyList(), personalized, emptyList(), notices, now, users, now)

    @Test
    fun `solo un deportista con suscripcion activa y la capacidad ve la opcion`() {
        assertTrue(NutritionRoutinesAccess.canView(access()))
        assertFalse(NutritionRoutinesAccess.canView(null))
        assertFalse(NutritionRoutinesAccess.canView(access(capabilities = emptySet())))
        assertFalse(NutritionRoutinesAccess.canView(access(status = SubscriptionStatus.entries.first { it != SubscriptionStatus.ACTIVE })))
        assertEquals("nutrition.routines.suggest", NUTRITION_ROUTINES_SUGGEST_CAPABILITY)
    }

    @Test
    fun `la particion es una huella estable que no contiene el user_id`() {
        val key = NutritionRoutinesOwner.keyOf(TrainingFixtures.USER_ID)
        assertEquals(64, key.length)
        assertEquals(key, NutritionRoutinesOwner.keyOf(TrainingFixtures.USER_ID))
        assertNotEquals(key, NutritionRoutinesOwner.keyOf(TrainingFixtures.OTHER_USER_ID))
        assertFalse(TrainingFixtures.USER_ID in key)
    }

    @Test
    fun `los codigos conocidos se interpretan y los desconocidos no`() {
        assertEquals(RoutineReason.RESTRICTION_COMPATIBLE, RoutineReason.fromWire("RESTRICTION_COMPATIBLE"))
        assertNull(RoutineReason.fromWire("SEASON_MATCH"))
        assertEquals(RoutineMissingContext.NUTRITION_HABITS, RoutineMissingContext.fromWire("nutrition_habits"))
        assertNull(RoutineMissingContext.fromWire("city"))
        assertEquals(RoutineNotice.RESTRICTIONS_NOT_VERIFIED, RoutineNotice.fromWire("RESTRICTIONS_NOT_VERIFIED"))
        assertNull(RoutineNotice.fromWire("OTRO"))
        assertEquals(RoutineSourceStatus.UNAVAILABLE, RoutineSourceStatus.fromWire("UNAVAILABLE"))
        assertNull(RoutineSourceStatus.fromWire("DOWN"))
    }

    @Test
    fun `las restricciones solo se dan por verificadas en una lista personalizada sin aviso (CA3)`() {
        assertFalse(snapshot().restrictionsNotVerified)
        assertTrue(snapshot(notices = listOf("RESTRICTIONS_NOT_VERIFIED", "NOT_MEDICAL_ADVICE")).restrictionsNotVerified)
        assertTrue(snapshot(personalized = false).restrictionsNotVerified)
        assertTrue(snapshot(personalized = true, notices = listOf("RESTRICTIONS_NOT_VERIFIED")).restrictionsNotVerified)
    }

    @Test
    fun `Usuarios no disponible se reconoce por su fuente`() {
        assertTrue(snapshot(users = RoutineSourceStatus.UNAVAILABLE).contextUnavailable)
        assertFalse(snapshot(users = null).contextUnavailable)
    }

    @Test
    fun `la lista pasa a STALE despues de 24 horas`() {
        val list = snapshot()
        assertFalse(list.isStale(now.plusSeconds(24 * 3600)))
        assertTrue(list.isStale(now.plusSeconds(24 * 3600 + 1)))
    }
}
