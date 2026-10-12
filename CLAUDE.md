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

Estructura y comandos completos en `README.md`.

## 5. Entorno local (Windows)

- La ruta contiene tildes y rompe las pruebas de Gradle (`ClassNotFoundException`). Ejecuta Gradle
  desde una unidad ASCII: `subst S: "<ruta>\aplicacion_proyecto_final"` y trabaja en `S:\movil`.
- `JAVA_HOME` = JBR de Android Studio (`C:\Program Files\Android\Android Studio2\jbr`).
- Puerta local: `./gradlew ciCheck`; instrumentadas: `./gradlew :app:connectedDebugAndroidTest`.
  Usa el AVD `sportapp_api33` y fija `ANDROID_SERIAL=emulator-5554` para no instalar en un teléfono personal conectado.
- BFF móvil local: `http://10.0.2.2:18081/` desde el emulador (composición Kubernetes `sportapp-local`
  del repositorio `platform`); dispositivo físico con `adb reverse tcp:18081 tcp:18081`.
