# Instrucciones fundamentales para agentes — SportApp móvil

Este repositorio es el cliente **Android/Kotlin** de SportApp (`REPO_ROOT` móvil). La autoridad
sobre arquitectura, historias, contratos y reglas de trabajo está en la documentación central:

```text
DOC_ROOT = D:\04 - Mestría en Ingeniería de Software\08 - Ciclo 8\Proyecto final II\aplicacion_proyecto_final\documentacion
```

Responde y documenta en español.

## 1. Regla principal: consultar `guias-desarrollo` antes de cada instrucción

Ante **cualquier** instrucción (HU, corrección, mantenimiento, pregunta de diseño) lee primero,
en este orden, antes de proponer o modificar código:

1. `DOC_ROOT/arquitectura/guias-desarrollo/README.md` — protocolo de carga de contexto y precedencia documental.
2. `DOC_ROOT/arquitectura/guias-desarrollo/instrucciones-agentes/00-instrucciones-comunes.md` — obligatorias.
3. `DOC_ROOT/arquitectura/guias-desarrollo/instrucciones-agentes/03-agente-mobile.md` — obligatorias para este repositorio.
4. `DOC_ROOT/arquitectura/guias-desarrollo/configuracion-tareas/02-valores-proyecto.md` — rutas, estado y valores vigentes del móvil.
5. Si la tarea es una HU: `DOC_ROOT/arquitectura/guias-desarrollo/tareas-hu/HUxxx-*.md` (sección «Tarea para agente de aplicación móvil» y su estado verificado).
6. La guía del dominio (`guias-desarrollo/0N-<dominio>.md`) y, de la historia, **solo** la sección de la HU (`arquitectura/historias/…`).
7. El contrato afectado: `backend/contracts/openapi/bff-mobile.openapi.json` (y la copia en `app/src/test/resources/contracts/`).
8. Documentos de nivel 2 únicamente por activador (seguridad/JWT → `08-seguridad.md` y ADR-009; navegación por actor → ADR-025 y `navegacion/README.md`; Android/MVVM/i18n → ADR-012; CI → `integracion-continua/`).

No cargues toda la arquitectura, todas las HU ni las entregas históricas «por si acaso». Si
necesitas otro documento, explica qué decisión concreta requiere.

## 2. Reglas no negociables

- Implementa **solo** el alcance escrito en la tarea/HU; las mejoras se reportan como recomendación.
- La app llama **únicamente** al BFF móvil. Nunca a microservicios, PostgreSQL ni Pub/Sub.
- **No inventes** endpoints, campos, códigos de error, capacidades, permisos ni comportamientos offline.
  Si falta una definición que cambia negocio, datos, seguridad, contrato o experiencia: marca
  `BLOQUEADO`, cita archivo y sección, formula una pregunta concreta y continúa solo con lo independiente.
- **No agregues ni actualices** librerías, plugins, SDK ni versiones de Kotlin/AGP/Gradle sin autorización.
- Mantén MVVM: UI Compose sin red ni almacenamiento; ViewModel coordina estado; repositorios encapsulan el BFF.
- Seguridad: nunca registres contraseñas, tokens, respuestas OAuth, ubicación ni datos médicos
  (sin `Log`, `println` ni interceptores de registro HTTP). Tokens solo en memoria o en el
  almacenamiento seguro aprobado. Datos de prueba sintéticos.
- No elimines, omitas ni flexibilices pruebas o umbrales. No presentes mocks como E2E. No
  afirmes que una prueba pasó sin ejecutarla.
- No modifiques `DOC_ROOT`, backend, web ni plataforma desde aquí, salvo autorización expresa
  (el retorno documental del móvil se limita a lo indicado en la tarea, p. ej. `02-valores-proyecto.md`).
- Nada de cambios destructivos de Git. Push, PR y acciones remotas solo con confirmación del responsable.

## 3. Trazabilidad

- Ramas GitFlow: `feature/HUxxx-descripcion`, `fix/HUxxx-…`, `chore/…` desde `develop`; PR a `develop`.
- Commits `<tipo>(<alcance>): descripción` con `[HUxxx]` en los funcionales; PR que empiece con `[HUxxx]`.
- Sin atribución al asistente: los commits y PR **no** llevan `Co-Authored-By: Claude …` ni líneas «Generated with Claude Code» (decisión del responsable, 2026-10-12).
- El agente solo crea ramas y commits locales; el responsable publica (`push`) y abre los PR.
- No se fusiona un PR con `ci-mobile` en rojo. Si una rama se reescribió localmente, se publica con
  `push --force-with-lease` antes de abrir su PR; si aparecen conflictos con `develop` entre dos versiones
  de los mismos commits, se detiene la fusión y se compara contra el último tope verificado.
- Tareas técnicas `T-HUxxx-MOB`. Un agente puede proponer **En revisión**, nunca **Aceptada**.
- Al entregar, incluye el bloque «Actualización de HU» de las instrucciones comunes §6 y la
  correspondencia criterio → prueba.

## 4. Decisiones vigentes del cliente móvil

| Tema | Decisión (responsable, 2026-10-12) |
| --- | --- |
| Red y JSON | Retrofit 3 + OkHttp 4.12 + kotlinx.serialization; MockWebServer para pruebas |
| Navegación, ViewModel y DI | Navigation Compose + lifecycle-viewmodel-compose; DI manual (`di/AppContainer`), sin Hilt/Koin |
| Almacenamiento seguro | Android Keystore (AES-256-GCM, clave no exportable); solo texto cifrado en SharedPreferences privadas excluidas del backup; access token solo en memoria |
| Tema | Paleta Material 3 de la web «Energía deportiva» (ADR-012) |
| Contrato consumido | `bff-mobile` 0.3.0 (`BFF_MOBILE_CONTRACT_VERSION` en `app/build.gradle.kts`) |
| Actores admitidos | Solo `athlete` (HU003 CA11, ADR-025): único inicio `athlete_home`; organizador y profesional usan la web |
| Offline first (HU032, ADR-026/029) | Room 2.8.5 + KSP 2.3.12 (la línea `2.2.10-2.0.x` no admite el Kotlin integrado de AGP 9) y WorkManager 2.12.0; payload cifrado con su propia clave de Keystore; cola por deportista sin tokens; no hay cola HTTP genérica |

Estructura y comandos completos en `README.md`.

## 5. Entorno local (Windows)

- La ruta contiene tildes: `gradle.properties` fija el daemon en `-Dfile.encoding=COMPAT` para que las
  pruebas JVM encuentren sus clases desde la ruta original (también en Android Studio). No lo cambies
  a UTF-8; si reaparece `ClassNotFoundException` en las pruebas, revisa ese valor.
- `JAVA_HOME` = JBR de Android Studio (`C:\Program Files\Android\Android Studio2\jbr`).
- Puerta local: `./gradlew ciCheck`; instrumentadas: `./gradlew :app:connectedDebugAndroidTest`.
  Usa el AVD `sportapp_api33` y fija `ANDROID_SERIAL=emulator-5554` para no instalar en un teléfono personal conectado.
- BFF móvil local: `http://10.0.2.2:18081/` desde el emulador (composición Kubernetes `sportapp-local`
  del repositorio `platform`); dispositivo físico con `adb reverse tcp:18081 tcp:18081`.

## 6. Graphify (índice de contexto, solo lectura)

Fuente: `DOC_ROOT/graphify/instrucciones-agentes.md` (bloque común y «Aplicación móvil»). Las
integraciones de Graphify (hooks, `/graphify`) existen solo en `documentacion`; aquí rigen estas reglas.

- Grafo central de solo lectura: `DOC_ROOT/graphify-out/graph.json`. Pasa **siempre**
  `--graph "..\documentacion\graphify-out\graph.json"`; en este repositorio no hay grafo.
- **Prohibido** desde `movil`: `graphify update`, `graphify extract` y `/graphify` (crearían un
  `graphify-out` paralelo). Si una regla genérica pide actualizar el grafo tras editar código, no la
  cumplas: solicítalo al responsable en el cierre.
- Antes de editar una `T-HUxxx-MOB`, después de cargar las instrucciones de §1 (pasos 1–4):
  1. `graphify explain "<H1 del paquete de la HU>" --graph …`;
  2. `graphify query "HUxxx móvil Kotlin pantalla estados offline sincronización contrato bff-mobile" --seed "<H1 del paquete>" --graph … --budget 3000`;
  3. `graphify query "HUxxx offline first cola sincronización" --seed "<ADR del cliente móvil devuelto en el paso 2>" --graph … --budget 3000`.
  Si falta un aspecto, repite con otra semilla (ADR, guía del dominio o archivo devuelto), no con más
  palabras clave; sube el presupuesto solo si una consulta enfocada sigue truncada. `graphify path "<A>" "<B>" --undirected`
  solo para confirmar una relación concreta.
- **Etiqueta del paquete:** el H1 completo del archivo en `tareas-hu/`, con el sufijo:
  `HUxxx: <título> — paquete de asignación` (p. ej. `HU035: Ver calendario con eventos o salidas
  programadas — paquete de asignación`). Una etiqueta aproximada (sin el sufijo o con otro título)
  puede resolver sin aviso al nodo del catálogo: comprueba que `Source:` sea
  `…/guias-desarrollo/tareas-hu/HUxxx-….md`. Si Graphify responde `Ambiguous`, usa
  `<ruta>::<etiqueta>` en `explain`/`path` o el id del nodo en `--seed`; no corrijas el H1 del paquete.
- Abre y verifica solo las fuentes devueltas que ya estén en el contexto de §1; si el grafo muestra
  otro documento necesario, pide su activación. `EXTRACTED` es evidencia directa; `INFERRED` y
  `AMBIGUOUS` se verifican en la fuente. Si el grafo contradice una fuente vigente, prevalece la
  fuente y se reporta el grafo como desactualizado. Un nodo de código o prueba que cite la HU no
  prueba que esté integrada ni aceptada.
- Graphify no amplía alcance, rutas de escritura ni permisos. Incluye estas reglas en el prompt de
  todo subagente que explore contexto.
- Al entregar: indica qué fuentes recuperaste con Graphify y cuáles verificaste; no afirmes que
  Graphify ejecutó pruebas ni validó aceptación; pide al responsable actualizar el grafo central
  desde `documentacion` tras integrar código y documentación.
