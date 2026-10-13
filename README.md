# sport-app-movil

Aplicación Android/Kotlin de SportApp. Una sola aplicación organizada por *features* MVVM que
consume únicamente el **BFF móvil** (`bff-mobile`); nunca llama microservicios internos, bases de
datos ni Pub/Sub. La arquitectura, las HU y los contratos se gobiernan desde la documentación
central (`documentacion/arquitectura`), no desde este repositorio.

## Herramientas

| Elemento | Versión |
| --- | --- |
| JDK (daemon y CI) | 25 (`gradle/gradle-daemon-jvm.properties`; Temurin en CI, JBR de Android Studio en local) |
| Gradle | 9.6.0 (wrapper con `distributionSha256Sum`) |
| Android Gradle Plugin | 9.4.1 (Kotlin integrado) |
| Kotlin / plugins Compose y serialización | 2.2.10 |
| SDK | `compileSdk` y `targetSdk` 37, `minSdk` 28 |
| UI | Jetpack Compose (BOM 2026.02.01) y Material 3 |
| Navegación / estado | Navigation Compose 2.10.2, Lifecycle ViewModel 2.11.0 |
| Red | Retrofit 3.0.0, OkHttp 4.12.0, kotlinx.serialization 1.9.0 |
| Inyección de dependencias | Manual (`di/AppContainer`) |
| Persistencia local y sincronización (HU032, ADR-029) | Room 2.8.5 (procesador con KSP 2.3.12), WorkManager 2.12.0 |

Las dependencias se declaran en `gradle/libs.versions.toml`. Agregar o actualizar una librería
requiere autorización (instrucciones del agente móvil, §2 y §10). Room, WorkManager y KSP los
autoriza `T-HU032-MOB`; KSP 2.3.x ya no sigue la versión de Kotlin y la línea `2.2.10-2.0.x` no
admite el Kotlin integrado de AGP 9 (`kotlin.sourceSets` prohibido). El plugin `androidx.room`
exporta el esquema a `app/schemas/`, base de las pruebas de migración.

## Estructura

```text
app/src/main/java/com/lfnj/sport_app/
├── SportApp.kt            Application: crea el AppContainer, configura WorkManager e inicia la coordinación de HU032
├── MainActivity.kt        única Activity; aloja la navegación Compose
├── di/                    AppContainer (DI manual)
├── core/
│   ├── config/            AppConfig por variante (URL del BFF, ambiente, versión de contrato)
│   ├── network/           cliente Retrofit/OkHttp, ApiResult, errores del contrato, correlación
│   ├── security/          Android Keystore (AES-256-GCM) y almacenamiento cifrado del refresh token
│   └── session/           API y modelos de sesión, SessionManager (HU003)
├── navigation/            NavHost y destinos
├── features/<feature>/    pantalla (Compose) → ViewModel → repositorio → API del BFF
│   ├── identity/          arranque/recuperación, acceso y acceso no autorizado (HU003)
│   ├── home/              inicio del deportista con menú por capacidades (HU003)
│   ├── training/session/  registrar entrenamiento offline first (HU032): pantalla, ViewModel,
│   │   ├── data/          API, repositorio y Room (payload cifrado con Keystore)
│   │   ├── model/         deportes, estados de sincronización y causas de rechazo
│   │   └── sync/          motor de sincronización, WorkManager y coordinación con la sesión
│   └── status/            pantalla técnica de estado del BFF
└── ui/theme/              tema Material 3 de marca (ADR-012)
```

- La UI no accede a red ni a almacenamiento; el ViewModel coordina el estado de pantalla; los
  repositorios encapsulan las llamadas al BFF.
- La aplicación es **exclusiva para deportistas** (HU003 CA11, ADR-025): solo existe `athlete_home`
  y un `organizer` o `service_provider` no abre sesión.
- `core/network` no registra solicitudes, respuestas ni encabezados (pueden contener credenciales).
  Cada solicitud lleva un `X-Correlation-ID` UUID que el BFF propaga.

### Registro de entrenamiento offline first (HU032, ADR-029)

- La pantalla `entrenamientos/sesion` lee la fuente local (Room). Iniciar y finalizar guardan
  primero la sesión y su comando (`START`/`FINISH`) en una transacción y luego programan la
  sincronización; funcionan sin red y muestran `LOCAL`, `PENDING_SYNC`, `SYNCED` o `SYNC_FAILED`.
- `session_id` e `Idempotency-Key` son UUID v4 generados y persistidos en el dispositivo. Deporte,
  plan e instantes se guardan cifrados con AES-GCM (clave `sportapp.training.v1` de Android
  Keystore); en claro solo quedan identificadores y metadatos técnicos. La base
  `sportapp_training.db` se excluye del respaldo y de la transferencia entre dispositivos.
- WorkManager ejecuta un trabajo único por deportista (huella SHA-256 del `user_id`), con red y
  reintento exponencial solo ante pérdida de red, `502`, `503` o `504`. El fin nunca se envía antes
  del inicio; un rechazo funcional deja la cadena en `SYNC_FAILED` con su causa y los pendientes
  vencen a los 30 días. Al cerrar sesión se cancela; solo el mismo deportista la reanuda.
- El cronómetro usa el reloj monotónico mientras vive el proceso y se reconstruye desde
  `started_at` tras reiniciar; la duración oficial la calcula el backend.

## Configuración por variante

| Variante | `APP_ENVIRONMENT` | `BFF_MOBILE_BASE_URL` |
| --- | --- | --- |
| `debug` | `local` | `http://10.0.2.2:18081/` (BFF móvil de la composición local vista desde el emulador). Texto claro permitido solo hacia `10.0.2.2`, `localhost` y `127.0.0.1`. |
| `release` | `production` | Sin valor aprobado mientras producción GCP esté pendiente: la app informa «no tiene configurado el servicio». Solo HTTPS. |

Se sobrescriben sin tocar el código con `-Psportapp.bffMobileBaseUrl.debug=<url>` o
`-Psportapp.bffMobileBaseUrl.release=<url>` (la URL debe terminar en `/`). Para un dispositivo
físico con la composición local: `adb reverse tcp:18081 tcp:18081` y
`-Psportapp.bffMobileBaseUrl.debug=http://localhost:18081/`.

## Comandos

| Objetivo | Comando |
| --- | --- |
| Puerta local (lint + unidad/contrato + APK debug) | `./gradlew ciCheck` |
| Lint | `./gradlew :app:lintDebug` |
| Unidad y contrato | `./gradlew :app:testDebugUnitTest` |
| Cobertura de unidad (JaCoCo) | `./gradlew :app:createDebugUnitTestCoverageReport` |
| Instrumentadas (Espresso/Compose) | `./gradlew :app:connectedDebugAndroidTest` (emulador o dispositivo conectado; `ANDROID_SERIAL` elige uno) |
| E2E HU003 contra la composición local (emulador → `http://10.0.2.2:18081`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu003IntegratedE2eTest -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> -Pandroid.testInstrumentationRunnerArguments.e2eOrganizerEmail=<correo> -Pandroid.testInstrumentationRunnerArguments.e2eServiceProviderEmail=<correo> -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña>` — las cuentas sintéticas se crean antes con `POST http://localhost:18080/v1/users` (`bff-web`, HU001), porque el móvil no tiene registro. Sin `e2ePassword` el paquete `e2e` se excluye. |
| APK de depuración | `./gradlew :app:assembleDebug` |
| Candidato de release sin firmar | `./gradlew :app:assembleRelease :app:bundleRelease` |

### Ruta con tildes en Windows

El proyecto vive bajo una ruta con caracteres no ASCII (`Mestría`, `Ingeniería`). Gradle pasa el
classpath de las pruebas al proceso de prueba en un archivo de argumentos que el lanzador de Java
lee, en Windows, con la página de códigos del sistema. Por eso `gradle.properties` fija el daemon en
`-Dfile.encoding=COMPAT` (codificación nativa: Windows-1252 en Windows, UTF-8 en Linux/macOS y CI).
Con UTF-8 la ruta llega corrupta y las pruebas fallan con `ClassNotFoundException` ("Test process
encountered an unexpected problem"). No cambie ese valor; ya no hace falta `subst`.

`android.overridePathCheck=true` solo omite la advertencia de AGP sobre rutas no ASCII.

## Pruebas

- `app/src/test`: ViewModels, repositorios con MockWebServer, traducción de errores y
  **contrato**: `contract/` compara modelos `@Serializable` y operaciones Retrofit con la copia de
  `bff-mobile.openapi.json` en `app/src/test/resources/contracts/` (origen y checksum en su README).
- `app/src/androidTest`: pantallas Compose (carga, éxito, error, sin conexión, reintento) y
  componentes que requieren Android.
- Las pruebas instrumentadas y de contrato usan respuestas simuladas: **no son E2E**. El E2E
  exige la aplicación contra un `bff-mobile` real.
- HU032: las pruebas JVM usan una fuente local en memoria con la semántica del DAO; el cifrado
  real, Room, el esquema exportado, el reinicio del proceso y WorkManager se prueban en el
  dispositivo (`features/training/session/`). Mientras `bff-mobile` 0.5.0 no esté publicado,
  `TrainingSessionAdrContractTest` fija operaciones y cuerpos con los ejemplos de ADR-029.

## CI y artefactos (GitHub Actions)

| Workflow | Disparador | Jobs |
| --- | --- | --- |
| `ci-mobile.yml` | push a `feature/**`, `fix/**`, `chore/**`, `release/**`, `hotfix/**`, `develop`, `main` | `quality` (lint), `unit-tests` (unidad, contrato y cobertura), `build` (APK debug con SHA-256 y metadatos de commit y contrato) |
| `ci-mobile.yml` | pull request a `develop`/`main` | lo anterior más `secrets` (Gitleaks fijado por digest) e `instrumented-tests` (emulador API 35) |
| `release-mobile.yml` | tag `vX.Y.Z` en `main` | `candidate` (verificaciones, APK y AAB sin firmar, `SHA256SUMS`); `sign` solo con `MOBILE_SIGNING_ENABLED=true` en el ambiente protegido `mobile-signing` |

Permisos mínimos (`contents: read`) y acciones fijadas a SHA. Firma, distribución y tienda están
pendientes de decisión; ningún material de firma vive en el repositorio.

## Ramas

GitFlow del proyecto: `feature/HUxxx-descripcion`, `fix/...` y `chore/...` desde `develop`, PR a
`develop`; `release/vX.Y.Z` y `hotfix/...` hacia `main`. La rama principal remota todavía se llama
`master`; su renombrado a `main` está pendiente.
