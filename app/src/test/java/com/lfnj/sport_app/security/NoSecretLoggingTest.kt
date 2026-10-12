package com.lfnj.sport_app.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Seguridad (HU003 y agente móvil §5): el código de producción no registra información. Sin
 * registros no puede filtrarse una contraseña, token o respuesta del BFF a Logcat.
 */
class NoSecretLoggingTest {
    private val forbidden = listOf(
        Regex("""\bandroid\.util\.Log\b"""),
        Regex("""\bLog\.[vdiwe]\("""),
        Regex("""\bLog\.wtf\("""),
        Regex("""\bprintln\("""),
        Regex("""\bprintStackTrace\("""),
        Regex("""HttpLoggingInterceptor"""),
        Regex("""\bTimber\b"""),
    )

    @Test
    fun `el codigo de produccion no escribe registros`() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("No se encontraron fuentes en ${File("src/main/java").absolutePath}", sources.isNotEmpty())

        val findings = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                forbidden.firstOrNull { it.containsMatchIn(line) }?.let { "${file.name}:${index + 1}: ${line.trim()}" }
            }
        }

        assertTrue("Registros prohibidos:\n${findings.joinToString("\n")}", findings.isEmpty())
    }
}
