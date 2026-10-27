package com.lfnj.sport_app.testing

import android.os.SystemClock
import com.lfnj.sport_app.features.training.session.data.TrainingClock
import java.time.Instant

/**
 * Reloj del motor de HU038 solo para pruebas: a partir de [anchor] (marca monotónica del inicio de
 * la sesión) el tiempo transcurre [speed] veces más rápido, de modo que una sesión de 70 min se
 * recorre en segundos. Antes de anclarlo se comporta como el reloj del sistema. Nunca se usa en
 * producción (el contenedor real usa el reloj de HU032).
 */
class AcceleratedTrainingClock(private val speed: Long) : TrainingClock {
    @Volatile
    private var anchorMillis: Long? = null

    fun anchor(monotonicStartMillis: Long) {
        anchorMillis = monotonicStartMillis
    }

    /** Milisegundos acelerados que se suman al tiempo real desde el ancla. */
    private fun extraMillis(): Long {
        val anchor = anchorMillis ?: return 0
        return (SystemClock.elapsedRealtime() - anchor).coerceAtLeast(0) * (speed - 1)
    }

    override fun now(): Instant = Instant.now().plusMillis(extraMillis())

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime() + extraMillis()

    /** Tiempo real equivalente a [acceleratedMillis] de sesión. */
    fun realMillis(acceleratedMillis: Long): Long = acceleratedMillis / speed
}
