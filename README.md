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

Las dependencias se declaran en `gradle/libs.versions.toml`. Agregar o actualizar una librería
requiere autorización (instrucciones del agente móvil, §2 y §10).

## Estructura

```text
app/src/main/java/com/lfnj/sport_app/
├── SportApp.kt            Application: crea el AppContainer
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
│   └── status/            pantalla técnica de estado del BFF
└── ui/theme/              tema Material 3 de marca (ADR-012)
```

- La UI no accede a red ni a almacenamiento; el ViewModel coordina el estado de pantalla; los
  repositorios encapsulan las llamadas al BFF.
- La aplicación es **exclusiva para deportistas** (HU003 CA11, ADR-025): solo existe `athlete_home`
  y un `organizer` o `service_provider` no abre sesión.
- `core/network` no registra solicitudes, respuestas ni encabezados (pueden contener credenciales).
  Cada solicitud lleva un `X-Correlation-ID` UUID que el BFF propaga.

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

Con el proyecto bajo una ruta con caracteres no ASCII (`Mestría`, `Ingeniería`), el proceso de
pruebas de Gradle no encuentra las clases (`ClassNotFoundException`). Ejecute Gradle desde una
unidad virtual con ruta ASCII:

```powershell
subst S: "D:\04 - Mestría en Ingeniería de Software\08 - Ciclo 8\Proyecto final II\aplicacion_proyecto_final"
cd S:\movil
.\gradlew.bat ciCheck
```

`android.overridePathCheck=true` en `gradle.properties` solo omite la advertencia de AGP; no corrige
las pruebas. En CI (Linux) no aplica.

## Pruebas

- `app/src/test`: ViewModels, repositorios con MockWebServer, traducción de errores y
  **contrato**: `contract/` compara modelos `@Serializable` y operaciones Retrofit con la copia de
  `bff-mobile.openapi.json` en `app/src/test/resources/contracts/` (origen y checksum en su README).
- `app/src/androidTest`: pantallas Compose (carga, éxito, error, sin conexión, reintento) y
  componentes que requieren Android.
- Las pruebas instrumentadas y de contrato usan respuestas simuladas: **no son E2E**. El E2E
  exige la aplicación contra un `bff-mobile` real.

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
