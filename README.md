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
├── SportApp.kt            Application: crea el AppContainer, configura WorkManager e inicia la coordinación de HU032, HU034, HU035, HU028, HU033 y HU004
├── MainActivity.kt        única Activity; aloja la navegación Compose
├── di/                    AppContainer (DI manual)
├── core/
│   ├── config/            AppConfig por variante (URL del BFF, ambiente, versión de contrato)
│   ├── network/           cliente Retrofit/OkHttp, ApiResult, errores del contrato, correlación
│   ├── security/          Android Keystore (AES-256-GCM) y almacenamiento cifrado del refresh token
│   └── session/           API y modelos de sesión, SessionManager (HU003) y cierre (HU004)
│       └── revocation/    cola cifrada de revocación en Room y su trabajo de WorkManager (HU004)
├── navigation/            NavHost y destinos
├── features/<feature>/    pantalla (Compose) → ViewModel → repositorio → API del BFF
│   ├── identity/          arranque/recuperación, acceso y acceso no autorizado (HU003)
│   ├── calendar/          calendario deportivo (HU035): pantalla de mes o lista, ViewModel,
│   │   ├── data/          API, repositorio, limpieza al cerrar sesión y caché Room cifrada
│   │   └── model/         periodo por zona, elementos, fuentes y agrupación por día
│   ├── home/              inicio del deportista con menú por capacidades (HU003)
│   ├── recommendations/   entrenamientos sugeridos (HU028): pantalla de lista y detalle, ViewModel,
│   │   ├── data/          API, repositorio, limpieza al cerrar sesión y caché Room cifrada
│   │   └── model/         plantillas, motivos, contexto faltante y antigüedad
│   ├── training/dashboard/ tablero de entrenamientos (HU033): pantalla con selector de periodo y ViewModel,
│   │   ├── data/          API, repositorio, limpieza al cerrar sesión y caché Room cifrada por opción
│   │   └── model/         periodos con desfase, agregados, fuentes y barras
│   ├── training/metrics/  FTP y VO₂max estimados (HU034): pantalla y ViewModel dirigidos por el catálogo,
│   │   ├── data/          API, repositorio y Room (catálogo, historial y cola cifrados con Keystore)
│   │   ├── model/         catálogo, entradas, resultados, validación de experiencia y formato
│   │   └── sync/          motor de sincronización y WorkManager
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

### Cierre de sesión offline first (HU004, ADR-026)

- «Cerrar sesión» elimina primero la sesión local (access token en memoria, identidad y refresh
  token del almacén seguro), limpia la pila de navegación y abre el acceso como raíz; la sesión
  nunca puede restaurarse aunque el proceso termine durante el cierre.
- La credencial renovable pasa a una cola cifrada **separada** del almacén de sesión
  (`sportapp_revocation.db`, AES-GCM con la clave `sportapp.revocation.v1`, excluida del respaldo):
  cada entrada guarda solo id local, credencial cifrada, vencimiento (`refresh_expires_in`),
  `Idempotency-Key`, intentos y próxima ejecución. El flujo de restauración de HU003 no la lee.
- Se intenta `POST /v1/sessions/revoke` de inmediato. `204` confirma (también una sesión ya
  cerrada); red, timeout y `5xx` (`503`/`504`) dejan la entrada pendiente y programan un trabajo
  único de WorkManager con red y espera exponencial (30 s hasta 5 h) que repite la misma clave hasta
  confirmar o hasta que la credencial venza, momento en que se descarta sin enviarla. Un `422` se
  descarta. La etiqueta propia evita que la cancelación de HU032 al cerrar sesión lo detenga.
- El acceso informa «Cerraste sesión en este dispositivo» y, si corresponde, que el cierre en el
  servidor quedó pendiente. Un nuevo inicio de sesión no reutiliza ni mezcla la credencial pendiente.

### Indicadores FTP y VO₂max (HU034, ADR-030)

- La pantalla `entrenamientos/metricas` (capacidad `training.metrics.ftp_vo2max`; sin ella la opción no
  aparece y la guarda devuelve al inicio) se construye solo con los metadatos del catálogo
  (`GET /v1/training-metrics/catalog`, con `ETag`/`If-None-Match` y `304`): métodos, entradas enteras
  con unidad y rango, y claves de texto resueltas desde recursos locales. Una clave desconocida —p. ej.
  de un indicador agregado después en el backend— muestra el código técnico: un tercer indicador
  escalar se captura y presenta sin cambiar el APK.
- El móvil **no aplica fórmulas**: valida tipo, obligatoriedad, rango y `measured_at` (5 min a futuro,
  365 días) solo para la experiencia, muestra el aviso `estimated_not_diagnostic` antes de enviar y
  guarda el cálculo cifrado como `PENDING_SYNC` con `calculation_id` UUID v4 e `Idempotency-Key`
  distintos. El resultado (`201`, o `200` si el `calculation_id` ya existía) siempre lo da el backend.
- `sportapp_training_metrics.db` (clave `sportapp.training-metrics.v1`, excluida del respaldo) guarda por
  deportista el catálogo (`STALE` a las 24 h), la primera página del historial con `last_synced_at`,
  los resultados confirmados que la proyección aún no devuelve y la cola. WorkManager reintenta solo
  red, `502`, `503` y `504`; un `4xx` deja `SYNC_FAILED` con el campo y la causa; los pendientes vencen a
  los 30 días. Al cerrar sesión se detiene y solo el mismo deportista la reanuda.

### Tablero de entrenamientos (HU033, ADR-043)

- La pantalla `entrenamientos/tablero` (capacidad `training.dashboard.view`; sin ella la opción no
  aparece y la guarda devuelve al inicio) consulta `GET /v1/me/training-dashboard?from&to` para 7, 30
  o 90 días (días completos hasta hoy) o un rango personalizado de hasta 366 días. `from`/`to` viajan
  con el desfase del dispositivo; el periodo es `[from, to)` y las semanas ISO las calcula el BFF.
- Muestra totales (sesiones, minutos totales y promedio, distancia en metros o «no disponible» si
  llega `null`), barras por semana y por deporte con alternativa textual accesible, el origen de las
  sesiones (`SPORTAPP`/`STRAVA`) y el último resultado por indicador con unidad, método, versión, fecha
  y el aviso `estimated_not_diagnostic`. Números y fechas usan el formato regional sin alterar el
  valor. Sin datos (`has_data: false` con todas las fuentes `OK`) muestra el estado vacío con
  «Registrar entrenamiento»; una consulta interna de `sources[]` distinta de `OK` (o ausente) se
  informa y sus secciones no se muestran: nunca se presentan ceros como confirmados.
- Solo lectura: no hay cola de comandos ni WorkManager. `sportapp_training_dashboard.db` guarda una
  fila por deportista y opción del selector (payload AES-GCM con la clave
  `sportapp.training_dashboard.v1`, excluida del respaldo); cada `200` la reemplaza. Sin red muestra
  el último tablero de esa opción con sus propias fechas y `last_synced_at`, y lo marca desactualizado
  a las 24 h; un rango personalizado solo reutiliza la caché de exactamente las mismas fechas. Al
  terminar la sesión se elimina la caché del deportista y, al autenticarse uno, la de cualquier otro.

### Calendario deportivo (HU035, ADR-031)

- La pantalla `calendario` (capacidad `calendar.sport.view`; sin ella la opción no aparece y la
  guarda devuelve al inicio) muestra el mes visible por mes o en lista. El periodo es el mes completo
  en la zona del dispositivo (`from`/`to` con desfase, siempre ≤ 92 días) y fechas y horas se
  presentan en esa zona sin alterar el instante ni la `time_zone` declarada del evento.
- Solo lectura: no hay cola de comandos ni WorkManager. La pantalla observa la caché del último
  periodo confirmado (una fila por deportista en `sportapp_calendar.db`, payload AES-GCM con la
  clave `sportapp.calendar.v1`, excluida del respaldo) y cada `200` del BFF la reemplaza.
- Sin red muestra ese periodo con `last_synced_at` y lo marca desactualizado a las 24 h; sin
  caché informa que aún no se ha sincronizado. Una fuente de `sources[]` distinta de `OK` (o
  ausente) se informa como incompleta y nunca como ausencia de actividades; `truncated` sugiere
  acotar el periodo.
- Al terminar la sesión (cierre, vencimiento o cambio de deportista) se elimina la caché del
  deportista; al autenticarse uno se eliminan las de cualquier otro.

### Entrenamientos sugeridos (HU028, ADR-041)

- La opción 130 «Entrenamientos sugeridos» (capacidad `training.plans.suggest`; sin ella la opción no
  aparece y la guarda devuelve al inicio) abre `entrenamientos/sugeridos`, que consulta
  `GET /v1/me/suggestions/training-plan-templates?limit=20` y muestra las plantillas en el orden del BFF
  con autor (o «Autor no disponible»), deporte, nivel, semanas, sesiones por semana y motivos. El
  detalle añade objetivo, fecha de publicación, objetivos y sesiones semanales.
- Con `personalized: false` la lista se presenta como no personalizada y explica cada
  `missing_context` o que Usuarios no respondió; con `missing_context: ["primary_goal"]` sigue
  personalizada por deporte. «Autorizar a este profesional» es solo un texto que remite a la web
  (HU010): la app no asigna, copia ni inicia planes.
- Solo lectura, sin WorkManager: la pantalla observa la última lista confirmada (una fila por
  deportista en `sportapp_plan_suggestions.db`, payload AES-GCM con la clave
  `sportapp.plan_suggestions.v1`, excluida del respaldo) y cada `200` la reemplaza; sin red la muestra
  con `last_synced_at` y la marca desactualizada a las 24 h. Al terminar la sesión se elimina como en HU035.

## Configuración por variante

| Variante | `APP_ENVIRONMENT` | `BFF_MOBILE_BASE_URL` |
| --- | --- | --- |
| `debug` | `local` | `http://10.0.2.2:18081/` (BFF móvil de la composición local vista desde el emulador). Texto claro permitido solo hacia `10.0.2.2`, `localhost` y `127.0.0.1`. |
| `release` | `production` | URL de `bff-mobile` en Cloud Run, que pasa el script de release de la plataforma al compilar el APK de producción. Sin ella (CI) la app informa «no tiene configurado el servicio». Solo HTTPS. |

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
| E2E HU028 contra la composición local (teléfono con `adb reverse tcp:18081 tcp:18081`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu028IntegratedE2eTest -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña> -Pandroid.testInstrumentationRunnerArguments.e2eTemplateSuffix=<sufijo>` — antes, por `bff-web` (`localhost:18080`, con `Origin` permitido): deportista con `PUT /v1/me/sport-habits` (deporte y `primary_goal`) y profesional que publica «E2E HU028 Objetivo <sufijo>» (ese objetivo) y «E2E HU028 Deporte <sufijo>» (otro objetivo) con `POST /v1/training-plan-templates`. Datos sintéticos. |
| APK de depuración | `./gradlew :app:assembleDebug` |
| Candidato de release sin firmar | `./gradlew :app:assembleRelease :app:bundleRelease` |

### Versión y firma del APK de producción

El APK de producción no lo genera GitHub Actions: lo compila y lo firma
`sport-app-platform/scripts/sportapp-release-movil.ps1` en la estación del operador, desde el commit del
tag, y lo adjunta a la GitHub Release del móvil. `app/build.gradle.kts` recibe:

| Entrada | Uso | Sin valor |
| --- | --- | --- |
| `-Psportapp.versionName=X.Y.Z` | `versionName`: versión del tag del móvil | `0.1.0` |
| `-Psportapp.versionCode=N` | `versionCode` = `MAJOR*1000000 + MINOR*1000 + PATCH` | `1` |
| `-Psportapp.systemVersion=vX.Y.Z` | `BuildConfig.SYSTEM_VERSION`: versión de plataforma | `dev` |
| `SPORTAPP_SIGNING_STORE_FILE`, `SPORTAPP_SIGNING_STORE_PASSWORD`, `SPORTAPP_SIGNING_KEY_ALIAS`, `SPORTAPP_SIGNING_KEY_PASSWORD` (entorno) | firma de release | APK sin firmar, como en CI |

El almacén de claves vive fuera del repositorio. El APK se nombra
`sportapp-movil-vX.Y.Z-sistema-vA.B.C.apk` y va con `SHA256SUMS` y `artifact.txt`.

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
  dispositivo (`features/training/session/`). `TrainingSessionContractTest` verifica la copia de
  `bff-mobile` 0.15.0 y `TrainingSessionAdrContractTest`, los cuerpos y ejemplos de ADR-029.
- HU035: la caché en memoria y el cifrado de prueba cubren la lógica en la JVM; Room, Keystore, el
  esquema exportado, la limpieza al cerrar sesión y el respaldo se prueban en el dispositivo
  (`features/calendar/`). `CalendarContractTest` verifica la copia de `bff-mobile` 0.15.0 y
  `CalendarAdrContractTest`, el ejemplo de ADR-031.
- HU034: `TrainingMetricsRepositoryTest`, `MetricsSyncEngineTest`, `TrainingMetricsViewModelTest` y
  `TrainingMetricsModelsTest` en la JVM; `TrainingMetricsContractTest` verifica la copia 0.15.0, los
  ejemplos de ADR-030 y un catálogo con un tercer indicador de prueba. Room, Keystore, reinicio,
  esquema, respaldo, WorkManager, pantalla y recorrido se prueban en el dispositivo
  (`features/training/metrics/` y `navigation/TrainingMetricsFlowTest`).
- HU028: `PlanSuggestionsRepositoryTest` (MockWebServer y caché en memoria) y
  `PlanSuggestionsViewModelTest` en la JVM; `PlanSuggestionsContractTest` verifica la copia 0.15.0. Room,
  Keystore, reinicio, esquema, respaldo, limpieza, pantalla y recorrido se prueban en el dispositivo
  (`features/recommendations/` y `navigation/PlanSuggestionsFlowTest`).
- HU033: `TrainingDashboardModelsTest`, `TrainingDashboardRepositoryTest` (MockWebServer: `200`,
  vacío, fuente caída, `403`, `422`, `503`/`504`, sin red, respuesta inválida, partición y limpieza) y
  `TrainingDashboardViewModelTest` en la JVM; `TrainingDashboardContractTest` verifica la operación y
  sus esquemas en la copia de `bff-mobile`. Room, Keystore, reinicio, esquema, respaldo, pantalla y
  recorrido se prueban en el dispositivo (`features/training/dashboard/` y
  `navigation/TrainingDashboardFlowTest`).
- HU004: `RevocationQueueTest` cubre la cola con MockWebServer (204, 422, 503/504, sin red,
  vencimiento, clave estable) y `SessionContractTest`, `POST /v1/sessions/revoke`; Room, Keystore,
  la separación del almacén de sesión, el reinicio del proceso, el esquema, el respaldo y
  WorkManager se prueban en el dispositivo (`core/session/revocation/`), y el recorrido en
  `navigation/LoginFlowTest`.

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
`master`; `sportapp-release-movil.ps1 prepare` de la plataforma la renombra a `main`, que es la rama que
exige `release-mobile.yml`.
