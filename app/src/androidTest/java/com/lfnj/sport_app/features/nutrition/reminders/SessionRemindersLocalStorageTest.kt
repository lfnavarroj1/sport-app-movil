package com.lfnj.sport_app.features.nutrition.reminders

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lfnj.sport_app.core.network.BffMobileClient
import com.lfnj.sport_app.core.security.KeystoreTokenCipher
import com.lfnj.sport_app.core.session.SessionManager
import com.lfnj.sport_app.features.nutrition.reminders.data.SessionRemindersRepository
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionReminderConfigEntity
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersCodec
import com.lfnj.sport_app.features.nutrition.reminders.data.local.SessionRemindersDatabase
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderConfig
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPreferences
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderPresentation
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRule
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderRuleset
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderSetting
import com.lfnj.sport_app.features.nutrition.reminders.model.ReminderType
import com.lfnj.sport_app.testing.InMemoryTokenStore
import com.lfnj.sport_app.testing.TestAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * HU038 en el dispositivo: Room real (base en memoria) y cifrado AES-GCM con una clave de prueba de
 * Android Keystore. Configuración y registro de entregas no quedan en claro y la inserción única
 * evita duplicados (CA3).
 */
@RunWith(AndroidJUnit4::class)
class SessionRemindersLocalStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, SessionRemindersDatabase::class.java).build()
    private val dao = database.sessionRemindersDao()
    private val codec = SessionRemindersCodec(KeystoreTokenCipher(ALIAS), BffMobileClient.json)
    private val repository = SessionRemindersRepository(
        dao, codec, api = null, sessionManager = SessionManager(null, InMemoryTokenStore(), BffMobileClient.json), json = BffMobileClient.json,
    )

    @After
    fun tearDown() {
        database.close()
        TestAppContainer.deleteTestKey(ALIAS)
        TestAppContainer.deleteTestKey(OTHER_ALIAS)
    }

    @Test
    fun laConfiguracionSeGuardaCifradaYSeRecupera() = runBlocking {
        dao.replaceConfig(SessionReminderConfigEntity(OWNER, codec.encodeConfig(CONFIG)))

        assertEquals(CONFIG, repository.observe(OWNER).first())
        val raw = dao.config(OWNER)!!.payload.toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("Hidr"))
        assertFalse(raw.contains("HYDRATION"))
    }

    @Test
    fun conOtraClaveLaConfiguracionEsIlegibleYNoSeEvalua() = runBlocking {
        val other = SessionRemindersCodec(KeystoreTokenCipher(OTHER_ALIAS), BffMobileClient.json)
        dao.replaceConfig(SessionReminderConfigEntity(OWNER, other.encodeConfig(CONFIG)))

        assertNull(repository.observe(OWNER).first())
    }

    @Test
    fun cadaOcurrenciaSeRegistraUnaSolaVezConDeliveredAtCifrado() = runBlocking {
        val at = Instant.parse("2026-10-26T13:15:00Z")
        assertTrue(repository.claim(OWNER, SESSION, ReminderType.HYDRATION, 1, at, ReminderPresentation.NOTIFICATION_AND_BANNER))
        assertFalse(repository.claim(OWNER, SESSION, ReminderType.HYDRATION, 1, at.plusSeconds(1), ReminderPresentation.BANNER_ONLY))

        val delivery = repository.deliveries(OWNER, SESSION).single()
        assertEquals(at, delivery.deliveredAt)
        assertEquals(ReminderPresentation.NOTIFICATION_AND_BANNER, delivery.presentation)
        assertFalse(dao.deliveries(OWNER, SESSION).single().payload.toString(Charsets.ISO_8859_1).contains("NOTIFICATION"))
    }

    @Test
    fun laRetencionYLaLimpiezaBorranLoQueNoCorresponde() = runBlocking {
        repository.claim(OWNER, "anterior", ReminderType.HYDRATION, 1, Instant.EPOCH, ReminderPresentation.BANNER_ONLY)
        repository.claim(OWNER, SESSION, ReminderType.HYDRATION, 1, Instant.EPOCH, ReminderPresentation.BANNER_ONLY)
        repository.claim("otro", SESSION, ReminderType.HYDRATION, 1, Instant.EPOCH, ReminderPresentation.BANNER_ONLY)
        dao.replaceConfig(SessionReminderConfigEntity("otro", codec.encodeConfig(CONFIG)))

        repository.keepOnly(OWNER, SESSION)
        assertTrue(repository.deliveries(OWNER, "anterior").isEmpty())
        repository.clearAllExcept(OWNER)
        assertTrue(dao.deliveries("otro", SESSION).isEmpty())
        assertNull(dao.config("otro"))
        repository.clear(OWNER)
        assertTrue(dao.deliveries(OWNER, SESSION).isEmpty())
    }

    private companion object {
        const val ALIAS = "sportapp.session_reminders.storage.test"
        const val OTHER_ALIAS = "sportapp.session_reminders.other.test"
        const val OWNER = "huella-sintetica"
        const val SESSION = "5b4a3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d"
        const val DISCLAIMER = "Recordatorio general: no es un diagnóstico médico ni prescribe cantidades."
        val CONFIG = ReminderConfig(
            ReminderRuleset(
                "session-reminders-2026-10-13.v1",
                listOf(
                    ReminderRule(ReminderType.HYDRATION, 15, 10, 60, 0, "Hidrátate: bebe agua o bebida isotónica", DISCLAIMER),
                    ReminderRule(ReminderType.NUTRITION, 45, 30, 120, 60, "Consume un alimento ligero según tu plan", DISCLAIMER),
                ),
            ),
            ReminderPreferences(ReminderSetting(true, 10), ReminderSetting(false, 30), 2),
            Instant.parse("2026-10-26T12:00:00Z"),
        )
    }
}
