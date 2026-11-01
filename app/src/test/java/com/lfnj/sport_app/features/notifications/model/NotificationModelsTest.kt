package com.lfnj.sport_app.features.notifications.model

import com.lfnj.sport_app.testing.NotificationFixtures
import com.lfnj.sport_app.testing.NotificationFixtures.NOTIFICATION_ID
import com.lfnj.sport_app.testing.NotificationFixtures.OTHER_NOTIFICATION_ID
import com.lfnj.sport_app.testing.NotificationFixtures.THIRD_NOTIFICATION_ID
import com.lfnj.sport_app.testing.TrainingFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** HU039: deduplicación local por `notification_id`, prioridad → canal y orden de la bandeja. */
class NotificationModelsTest {
    private val now = Instant.parse("2026-10-25T15:00:00Z")

    @Test
    fun `la prioridad del backend define el canal del sistema`() {
        assertEquals("event_alerts", NotificationChannels.forPriority(NotificationPriority.HIGH))
        assertEquals("event_updates", NotificationChannels.forPriority(NotificationPriority.NORMAL))
    }

    @Test
    fun `la primera sincronizacion anuncia solo las no leidas`() {
        val unread = NotificationFixtures.notification()
        val read = NotificationFixtures.notification(OTHER_NOTIFICATION_ID, readAt = now)

        val (snapshot, fresh) = InboxSnapshot.firstPage(null, listOf(unread, read), "c2", now)

        assertEquals(listOf(unread), fresh)
        assertEquals(setOf(NOTIFICATION_ID, OTHER_NOTIFICATION_ID), snapshot.announced)
        assertEquals("c2", snapshot.nextCursor)
        assertEquals(now, snapshot.syncedAt)
    }

    @Test
    fun `una notificacion ya anunciada no se vuelve a anunciar y solo se recuerdan las de la pagina vigente`() {
        val first = NotificationFixtures.notification()
        val (previous, _) = InboxSnapshot.firstPage(null, listOf(first), null, now)
        val newer = NotificationFixtures.notification(THIRD_NOTIFICATION_ID, createdAt = now)

        val (snapshot, fresh) = InboxSnapshot.firstPage(previous, listOf(newer, first), null, now.plusSeconds(900))

        assertEquals(listOf(newer), fresh)
        assertEquals(setOf(NOTIFICATION_ID, THIRD_NOTIFICATION_ID), snapshot.announced)
    }

    @Test
    fun `los duplicados de una respuesta se descartan por notification_id`() {
        val item = NotificationFixtures.notification()

        val (snapshot, fresh) = InboxSnapshot.firstPage(null, listOf(item, item), null, now)

        assertEquals(1, snapshot.items.size)
        assertEquals(1, fresh.size)
    }

    @Test
    fun `el orden es created_at descendente y luego notification_id descendente`() {
        val sameTime = Instant.parse("2026-10-25T13:00:00Z")
        val snapshot = InboxSnapshot(
            listOf(
                NotificationFixtures.notification(THIRD_NOTIFICATION_ID, createdAt = sameTime),
                NotificationFixtures.notification(NOTIFICATION_ID, createdAt = sameTime),
                NotificationFixtures.notification(OTHER_NOTIFICATION_ID, createdAt = now),
            ),
            null,
            now,
        )

        assertEquals(listOf(OTHER_NOTIFICATION_ID, NOTIFICATION_ID, THIRD_NOTIFICATION_ID), snapshot.ordered.map { it.notificationId })
        assertEquals(3, snapshot.unreadCount)
    }

    @Test
    fun `agregar una pagina no repite elementos y marcar leida reemplaza solo el elemento`() {
        val item = NotificationFixtures.notification()
        val base = InboxSnapshot(listOf(item), "c2", now)
        val other = NotificationFixtures.notification(OTHER_NOTIFICATION_ID)

        val appended = base.append(listOf(item, other), null)
        assertEquals(2, appended.items.size)
        assertEquals(null, appended.nextCursor)

        val read = appended.withRead(item.copy(readAt = now))
        assertFalse(read.items.first { it.notificationId == NOTIFICATION_ID }.unread)
        assertTrue(read.items.first { it.notificationId == OTHER_NOTIFICATION_ID }.unread)
    }

    @Test
    fun `la particion es una huella del usuario y el toString no expone contenido`() {
        val key = NotificationsOwner.keyOf(TrainingFixtures.USER_ID)
        assertEquals(64, key.length)
        assertFalse(key.contains(TrainingFixtures.USER_ID))
        assertNotEquals(key, NotificationsOwner.keyOf(TrainingFixtures.OTHER_USER_ID))
        assertFalse(NotificationFixtures.notification().toString().contains("Bogotá"))
    }

    @Test
    fun `solo se aceptan identificadores UUID`() {
        assertTrue(NotificationIds.isValid(NOTIFICATION_ID))
        assertFalse(NotificationIds.isValid("../v1/me"))
        assertFalse(NotificationIds.isValid(null))
    }
}
