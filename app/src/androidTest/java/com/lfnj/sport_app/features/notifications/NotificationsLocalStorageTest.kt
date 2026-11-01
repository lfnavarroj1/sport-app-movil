package com.lfnj.sport_app.features.notifications

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lfnj.sport_app.R
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.ActorType
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.core.session.SessionUser
import com.lfnj.sport_app.features.notifications.data.NotificationsCacheJanitor
import com.lfnj.sport_app.features.notifications.data.NotificationsRepository
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCacheEntity
import com.lfnj.sport_app.features.notifications.data.local.NotificationsCodec
import com.lfnj.sport_app.features.notifications.data.local.NotificationsDatabase
import com.lfnj.sport_app.features.notifications.model.InboxNotification
import com.lfnj.sport_app.features.notifications.model.InboxSnapshot
import com.lfnj.sport_app.features.notifications.model.NotificationPriority
import com.lfnj.sport_app.features.notifications.model.NotificationType
import com.lfnj.sport_app.features.notifications.model.NotificationsOwner
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.RecordingNotificationAnnouncer
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.time.Instant

/**
 * HU039 en el dispositivo: Room real, cifrado AES-GCM con Android Keystore, partición por
 * deportista, reinicio del proceso, limpieza al cerrar sesión, esquema exportado y exclusión del
 * respaldo.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = SessionUser("7d8f0b8e-3c2a-4b8f-9a51-0c1d2e3f4a5b", "Ana Deportista", "ana@example.com", ActorType.ATHLETE)
    private val owner = NotificationsOwner.keyOf(ana.userId)
    private val otherOwner = NotificationsOwner.keyOf("0f0e0d0c-0b0a-4908-8706-050403020100")
    private var database = openDatabase()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NotificationsDatabase::class.java)

    private fun openDatabase() = Room.databaseBuilder(context, NotificationsDatabase::class.java, DB_NAME).build()

    private fun codec() = NotificationsCodec(KeystoreTokenCipher(TestAppContainer.NOTIFICATIONS_TEST_KEY_ALIAS), BffMobileClient.json)

    /** Un repositorio nuevo equivale a un proceso nuevo: no conserva nada en memoria. */
    private fun repository(db: NotificationsDatabase = database) = NotificationsRepository(
        dao = db.notificationsCacheDao(),
        codec = codec(),
        api = null,
        sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json),
        json = BffMobileClient.json,
        announcer = RecordingNotificationAnnouncer(),
    )

    private val snapshot = InboxSnapshot(
        items = listOf(
            InboxNotification(
                notificationId = "5b0e7c1a-2d3f-4a5b-8c6d-7e8f9a0b1c2d",
                type = NotificationType.EVENT_REMINDER_24H,
                priority = NotificationPriority.HIGH,
                eventId = "683f4e38-6bb3-4c47-a6dc-9bb1588c67ef",
                title = "Tu evento empieza mañana",
                body = "Carrera 10K Bogotá el 15 de noviembre.",
                createdAt = Instant.parse("2026-10-25T13:00:00Z"),
                readAt = null,
            ),
        ),
        nextCursor = "cursor-opaco",
        syncedAt = Instant.parse("2026-10-25T13:01:00Z"),
        announced = setOf("5b0e7c1a-2d3f-4a5b-8c6d-7e8f9a0b1c2d"),
    )

    private suspend fun save(target: String = owner, db: NotificationsDatabase = database) =
        db.notificationsCacheDao().replace(NotificationsCacheEntity(target, codec().encode(snapshot)))

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(MIGRATION_DB)
        TestAppContainer.deleteTestKey(TestAppContainer.NOTIFICATIONS_TEST_KEY_ALIAS)
    }

    @Test
    fun laBandejaSoloGuardaTextoCifradoYLaParticion() = runBlocking {
        save()

        val db = database.openHelper.readableDatabase
        val columns = db.query("SELECT * FROM notification_inbox_cache").use { it.columnNames.toSet() }
        assertEquals(setOf("owner_key", "payload"), columns)
        val blob = db.query("SELECT payload FROM notification_inbox_cache").use { it.moveToFirst(); it.getBlob(0) }
        val text = String(blob, Charsets.ISO_8859_1)
        listOf("Carrera", "evento", "Bogot", "683f4e38", "5b0e7c1a", "EVENT_", "cursor", "last_synced_at").forEach {
            assertFalse("«$it» en claro", text.contains(it))
        }
        assertEquals(snapshot, repository().observe(owner).first())
    }

    @Test
    fun laBandejaYLaDeduplicacionSobrevivenAlReinicio() = runBlocking {
        save()
        database.close()

        database = openDatabase()
        val restored = checkNotNull(repository().observe(owner).first())

        assertEquals(snapshot.syncedAt, restored.syncedAt)
        assertEquals(snapshot.items, restored.items)
        assertEquals(snapshot.announced, restored.announced)
        assertEquals("cursor-opaco", restored.nextCursor)
    }

    @Test
    fun cadaDeportistaSoloVeSuParticion() = runBlocking {
        save()

        assertNull(repository().observe(otherOwner).first())
    }

    @Test
    fun conLaClaveInvalidadaLaBandejaSeIgnoraSinInventarNotificaciones() = runBlocking {
        save()
        TestAppContainer.deleteTestKey(TestAppContainer.NOTIFICATIONS_TEST_KEY_ALIAS)

        assertNull(repository().observe(owner).first())
    }

    @Test
    fun cerrarSesionEliminaLaBandejaYOtroDeportistaNoReutilizaLaAnterior() = runBlocking {
        save(owner)
        save(otherOwner)
        val user = MutableStateFlow<SessionUser?>(ana)
        val scope = CoroutineScope(Dispatchers.Default)
        NotificationsCacheJanitor(user, repository()).start(scope)
        val dao = database.notificationsCacheDao()

        withTimeout(5_000) { while (dao.observe(otherOwner).first() != null) delay(50) }
        assertTrue(dao.observe(owner).first() != null)

        user.value = null
        withTimeout(5_000) { while (dao.observe(owner).first() != null) delay(50) }
        scope.cancel()
    }

    @Test
    fun elEsquemaCreadoCoincideConElExportadoDeLaVersion1() {
        migrationHelper.createDatabase(MIGRATION_DB, 1).close()

        migrationHelper.runMigrationsAndValidate(MIGRATION_DB, 1, true).close()
    }

    @Test
    fun laBandejaSeExcluyeDelRespaldoYLaTransferencia() {
        val backup = excludedPaths(R.xml.backup_rules)
        val extraction = excludedPaths(R.xml.data_extraction_rules)
        val name = NotificationsDatabase.NAME

        listOf(name, "$name-wal", "$name-shm", "$name-journal").forEach { file ->
            assertTrue("$file en backup_rules", "database:$file" in backup)
            assertEquals("$file en nube y transferencia", 2, extraction.count { it == "database:$file" })
        }
    }

    private fun excludedPaths(xml: Int): List<String> {
        val paths = mutableListOf<String>()
        context.resources.getXml(xml).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    paths += "${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
                }
            }
        }
        return paths
    }

    private companion object {
        const val DB_NAME = "sportapp_notifications_test.db"
        const val MIGRATION_DB = "sportapp_notifications_migration_test.db"
    }
}
