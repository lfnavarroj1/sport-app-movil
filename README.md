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
├── SportApp.kt            Application: crea el AppContainer, configura WorkManager e inicia la coordinación de HU032, HU034, HU035, HU028, HU033, HU026, HU040, HU041, HU029, HU038, HU037, HU043 y HU004
├── MainActivity.kt        única Activity; aloja la navegación Compose y el banner global de recordatorios (HU038)
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
│   ├── events/            detalle de evento, inscripción y «Mis inscripciones» (HU041): pantallas y ViewModels,
│   │   ├── data/          API, repositorio, conectividad, limpieza al cerrar sesión y caché Room cifrada
│   │   └── model/         detalle, inscripción confirmada, orden y antigüedad de la lista
│   ├── home/              inicio del deportista con menú por capacidades (HU003)
│   ├── nutrition/intake/  «Mi alimentación» (HU043): pantalla, ViewModel y nombres del catálogo,
│   │   ├── data/          API, repositorio y Room (catálogo y consumos cifrados)
│   │   ├── model/         catálogo, unidades, comidas, validadores y estados de sincronización
│   │   └── sync/          cola durable con WorkManager, fábrica propia y coordinación con la sesión
│   ├── nutrition/reminders/ recordatorios durante la sesión (HU038): pantalla, ViewModel y banner global,
│   │   ├── data/          API, repositorio, limpieza al cerrar sesión y Room (configuración y entregas cifradas)
│   │   ├── engine/        cálculo de ocurrencias, motor local, notificación local y centro de banners
│   │   └── model/         reglas, preferencias, configuración confirmada y capacidad
│   ├── sensors/           dispositivos de medición (HU037): pantalla, ViewModel y permisos Bluetooth,
│   │   ├── ble/           escaneo `0x180D`, conexión GATT, lectura de `0x2A37`/batería y conexión compartida
│   │   ├── data/          API, repositorio, limpieza al cerrar sesión y Room (vinculaciones y muestras cifradas)
│   │   ├── model/         dispositivo, identidad lógica (hash), estados de conexión e ingestión
│   │   └── sync/          tarea del servicio en primer plano: muestras de 1 Hz y lotes cada 5 s
│   ├── recommendations/   entrenamientos sugeridos (HU028) y rutas sugeridas (HU026): pantallas de lista y detalle, ViewModels,
│   │   ├── data/          API, repositorios, limpieza al cerrar sesión y cachés Room cifradas (una por HU)
│   │   └── model/         plantillas y rutas, motivos, contexto faltante, antigüedad y URI `geo:`
│   ├── services/          sesiones virtuales con entrenadores (HU040) y acompañamiento y logística sugeridos (HU029): pantallas y ViewModels,
│   │   ├── data/          API, repositorio (lecturas cacheadas y comandos solo en línea), limpieza y caché Room cifrada (una por HU)
│   │   └── model/         servicios, intervalos, citas, ventana de 24 h, antelación de 1 h, zonas y tarifa; sugerencias, motivos, fuentes y antigüedad
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
│   │   ├── foreground/    servicio en primer plano de la sesión activa (creado por HU038, reutilizable)
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

### Rutas sugeridas (HU026, ADR-041)

- La opción 110 «Rutas sugeridas» (capacidad `training.routes.suggest`; sin ella la opción no aparece y
  la guarda devuelve al inicio) abre `entrenamientos/rutas`, que consulta
  `GET /v1/me/suggestions/routes?limit=20` y muestra las rutas en el orden del BFF (primero las de la
  ciudad del deportista y luego por distancia) con autor (o «Autor no disponible»), deporte, distancia
  con un decimal, dificultad, ciudad y país, y motivos (`SPORT_MATCH`, `CITY_MATCH`). El detalle
  añade la descripción, la fecha de publicación, el punto de inicio y los puntos intermedios como lista.
- «Abrir en mapas» entrega `geo:lat,lng?q=lat,lng(nombre)` a la aplicación de mapas del teléfono
  (`Intent.ACTION_VIEW`); si no hay ninguna lo informa. No hay mapa embebido, proveedor cartográfico,
  trazado GPX ni navegación, y la ubicación del teléfono no se consulta. Un texto fijo avisa que una
  sugerencia no garantiza la seguridad de la ruta.
- Con `personalized: false` la lista se presenta como no personalizada (últimas publicadas) y explica
  cada `missing_context` o que Usuarios no respondió; con `missing_context: ["city"]` sigue
  personalizada por deporte. Una respuesta con coordenadas fuera de rango, distancia sin un decimal,
  `source` distinto de `ORGANIZER` o referencia distinta de `route` se rechaza completa.
- Solo lectura, sin WorkManager: una fila por deportista en `sportapp_route_suggestions.db`, payload
  AES-GCM con la clave `sportapp.route_suggestions.v1`, excluida del respaldo; cada `200` la
  reemplaza (una ruta retirada desaparece), sin red se muestra con `last_synced_at` y se marca
  desactualizada a las 24 h. Al terminar la sesión se elimina como en HU035.

### Sesiones virtuales con entrenadores (HU040, ADR-046)

- La opción `PREMIUM` 250 «Sesiones virtuales» (capacidad `services.virtual_sessions.schedule`; sin
  ella la opción no aparece y la guarda devuelve al inicio) abre `servicios/sesiones-virtuales` con dos
  pestañas. «Entrenadores» consulta `GET /v1/services/availability` (`category=COACHING`,
  `modality=VIRTUAL&modality=HYBRID`, 30 días desde ahora, `limit=50`, hasta 5 páginas) y muestra
  cada servicio con su modalidad, tarifa informativa y horarios. El contrato no publica el nombre
  del profesional: se muestra el del servicio.
- Cada intervalo se presenta en la zona del dispositivo y, además, en la del profesional
  (`time_zone`). Un intervalo que empieza en menos de 1 h o que el deportista ya reservó no ofrece
  «Reservar». La confirmación explica la tarifa (sin cobro), que el profesional coordinará el enlace
  (`meeting_url` nulo) y hasta cuándo se puede cancelar.
- `POST /v1/me/appointments` solo se envía con conectividad (no se encola): cada intento genera una
  `Idempotency-Key` UUID que se conserva en los reintentos automáticos (red, `502`–`504`, hasta 3) y
  en el reintento manual tras un resultado desconocido. `409 availability_full`,
  `409 appointment_exists`, `403 capability_required` (plan requerido), `422 service_not_bookable` y
  `422 availability_not_bookable` tienen mensajes propios; los conflictos vuelven a consultar.
- «Mis sesiones» (`GET /v1/me/appointments`, `limit=100`, hasta 5 páginas) separa próximas e
  historial (canceladas y pasadas, sin eliminar ninguna). `DELETE /v1/me/appointments/{appointment_id}`
  solo se ofrece con 24 h o más de antelación; con menos se explica la ventana, y
  `422 cancellation_window_closed` también se informa. No hay reprogramación.
- Lectura offline first sin WorkManager: `sportapp_virtual_sessions.db` (tablas de catálogo y de citas,
  una fila por deportista), payload AES-GCM con la clave `sportapp.virtual_sessions.v1`, excluida del
  respaldo; sin red se muestra con `last_synced_at`, se marca desactualizada a las 24 h y no se
  reserva ni cancela. Al terminar la sesión se elimina como en HU035.

### Acompañamiento y logística sugeridos (HU029, ADR-041)

- La opción `FREE` 140 «Acompañamiento y logística» (capacidad `services.support.suggest`; sin ella la
  opción no aparece y la guarda devuelve al inicio) abre `servicios/acompanamiento` y consulta
  `GET /v1/me/suggestions/services?limit=20` (copia 0.18.0). Cada servicio muestra proveedor
  (`author_name`; «no disponible» si Usuarios no respondió), categoría, modalidad, tarifa informativa,
  próximo intervalo en la zona del dispositivo y motivo (`EVENT_LOGISTICS`, `UPCOMING_AVAILABILITY`), en
  el orden del BFF. El detalle añade la hora en la zona del proveedor y la descripción; es informativo:
  no contrata, reserva ni paga.
- `personalized: false` con `missing_context: ["event_registrations"]` explica que no hay inscripciones
  vigentes; `sources` con `events` `UNAVAILABLE` explica que faltó el motivo logístico (CA5). Una lista
  vacía se explica según haya o no inscripciones. `503`/`504` muestran error y reintento sin borrar la
  caché; `403 capability_required` y `permission_required` ocultan los datos guardados.
- Lectura offline first sin WorkManager: `sportapp_support_suggestions.db` (una fila por deportista),
  payload AES-GCM con la clave `sportapp.support_suggestions.v1`, excluida del respaldo; cada `200` la
  reemplaza (un servicio despublicado desaparece), sin red se muestra con `last_synced_at` y se marca
  desactualizada a las 24 h. Al terminar la sesión se elimina como en HU035.

### Recordatorios durante la sesión (HU038, ADR-045)

- La opción `INTERMEDIATE` 230 «Recordatorios durante sesión» (capacidad `nutrition.session.reminders`;
  sin ella la opción no aparece y la guarda devuelve al inicio) abre `entrenamientos/recordatorios`,
  descarga `GET /v1/session-reminder-rules` y `GET /v1/me/session-reminder-preferences` (copia 0.18.0) y
  permite ajustar hidratación (10–60 min) y alimentación (30–120 min) dentro de los rangos del ruleset;
  guardar usa `PUT` con `expected_version` y exige red (no hay cola offline). `409 version_conflict`
  recarga las vigentes; `422` conserva el borrador. Mensajes y aviso se muestran sin modificar.
- Sin ruleset descargado nunca hay recordatorios y la pantalla declara «información insuficiente» (CA5).
  La configuración confirmada se guarda en `sportapp_session_reminders.db` cifrada con
  `sportapp.session_reminders.v1` (excluida del respaldo) y se elimina al terminar la sesión.
- Durante una sesión `ACTIVE` de HU032 corre `ActiveSessionService` (primer plano, tipo `specialUse`,
  canal `training_session` con cronómetro): `health` exigiría permisos de sensores fuera del alcance;
  publicar en Google Play exigiría justificar el tipo en la consola (el APK se distribuye por GitHub
  Release). El servicio toma un `PARTIAL_WAKE_LOCK` con tiempo máximo de 10 min, renovado cada 5 min solo
  mientras la sesión sigue activa y liberado al terminar, al detener el servicio y en `onDestroy`
  (`WAKE_LOCK` llega fusionado desde WorkManager). Si Android no permite iniciarlo en segundo plano, se
  reintenta al volver la aplicación al primer plano; un puntero (`sportapp_active_session.xml`, solo
  huella y `session_id`, excluido del respaldo) permite continuar tras un reinicio del sistema.
- El motor evalúa sin red, cada segundo como máximo, `HYDRATION` cada intervalo desde el inicio y
  `NUTRITION` solo en ocurrencias posteriores a 60 min; reclama `(session_id, type, n)` en el registro
  local antes de presentarla, por lo que un reinicio nunca la repite, y tras un reinicio solo presenta la
  vigente. Presenta notificación local (canal `session_reminders`) y banner global; sin permiso o con las
  notificaciones desactivadas solo banner, y lo declara (CA4). El permiso `POST_NOTIFICATIONS` lo
  solicita el mecanismo de HU039. `delivered_at` queda solo en el dispositivo.

### Registro de consumo de alimentos (HU043, ADR-037)

- La opción base 60 «Mi alimentación» (sin capacidad de plan, visible con cualquier plan y durante el
  aprovisionamiento) abre `nutrition`. La pantalla descarga `GET /v1/food-catalog` (copia 0.21.0) con
  `If-None-Match`; un `304` renueva `last_synced_at` sin reescribir alimentos. El catálogo se guarda
  en `sportapp_food_intakes.db` cifrado con `sportapp.food-intakes.v1` (excluida del respaldo) y pasa a
  `STALE` a las 24 h. Sin catálogo en caché la pantalla pide conexión y no ofrece alimentos (CA5).
- El formulario elige comida, momento (≤ 5 min en el futuro, ≤ 30 días), alimentos del buscador local
  (solo la caché; sin texto libre), cantidad como texto decimal con un decimal entre 0,1 y 5000 (se acepta
  coma y se normaliza a `150.0`) y una de las `allowed_units` del alimento; 1–20 alimentos. Sin
  calorías, nutrientes ni juicios nutricionales. Los nombres salen del `label_key` con los textos de la
  web (`public/i18n/es.json`, frontend `develop` 7e40434); una clave desconocida muestra el `food_code`.
- Registrar guarda primero el consumo cifrado como `PENDING_SYNC` con `intake_id` e `Idempotency-Key`
  (UUID) y programa un trabajo único por deportista con red y reintento exponencial (`food-intakes-sync`).
  `POST /v1/me/food-intakes` solo se reintenta ante red, `502`, `503` y `504`; un `201` que no
  corresponde a lo enviado o cualquier `4xx` deja `SYNC_FAILED` con causa explicable (catálogo cambiado,
  validación, permiso, conflicto `intake_id_reused`), y un pendiente con más de 30 días vence. La lista
  muestra los consumos de hoy y cualquier pendiente o fallido; solo un fallido puede retirarse.
- Al terminar la sesión se detiene la cola, se borran la caché del catálogo y los consumos resueltos, y
  los pendientes quedan cifrados para el mismo deportista (ADR-029 offline §4, misma decisión de HU042).

### Dispositivos de medición (HU037, ADR-044)

- La opción `INTERMEDIATE` 220 «Dispositivos de medición» (capacidad `devices.measurement.connect`; sin
  ella no aparece y la guarda devuelve al inicio) abre `dispositivos`. Solo se admite un monitor de
  frecuencia cardiaca Bluetooth LE con el *Heart Rate Service* estándar (`0x180D`/`0x2A37`): cualquier
  banda o reloj que transmita la frecuencia cardiaca por BLE; sin SDK de fabricante ni otros sensores.
- Android 12+: `BLUETOOTH_SCAN` (`neverForLocation`) y `BLUETOOTH_CONNECT` se piden al buscar. En
  Android 9–11 la pantalla declara que requiere Android 12 y no pide ubicación (decisión pendiente del
  responsable, aislada en `BluetoothPermissions`). Sin permiso, sin Bluetooth o sin monitores
  compatibles se explica la causa y no se vincula; antes de vincular se verifica por GATT que el
  dispositivo expone `0x180D` (CA2) y la pantalla declara qué se captura.
- Vincular (`POST /v1/me/devices` con `Idempotency-Key`), consultar (`GET`) y revocar (`DELETE`) usan la
  copia 0.21.0 y exigen red (no se encolan). `device_id` = SHA-256 de la dirección BLE en mayúsculas +
  `user_id`; la dirección solo se guarda cifrada (`sportapp_sensors.db`, clave `sportapp.sensors.v1`,
  excluida del respaldo) para reconectar, y se borra con todo lo demás al terminar la sesión.
- Durante una sesión `ACTIVE` de HU032, `MeasurementStreamTask` corre dentro de `ActiveSessionService`
  (junto a los recordatorios de HU038): guarda cifrada una muestra por segundo de `0x2A37` y cada 5 s
  envía las ≤ 60 más antiguas a `POST /v1/training-sessions/{session_id}/measurements`. Solo un `200`
  las resuelve (aceptadas, `duplicates` o `rejected[]`); sin respuesta se reenvían en el siguiente ciclo
  (idempotencia natural). `409 device_revoked` marca el dispositivo revocado y detiene el envío;
  `409 training_session_already_finished` descarta; las muestras vencen a los 15 min y con la sesión.
  La desconexión se detecta localmente y se reintenta cada 5 s; no se muestra una lectura anterior como
  actual. La evaluación de riesgo de la respuesta pertenece a HU036 y no se presenta.
- El emulador no tiene BLE (R-20): las pruebas instrumentadas usan una fuente simulada marcada
  (`SimulatedHeartRateSource`, aviso visible en pantalla); la evidencia con monitor o reloj físico la
  documenta el responsable.

### Inscripción a un evento publicado (HU041, ADR-032)

- No hay opción de menú: el detalle `eventos/detalle/{event_id}` se abre desde la tarjeta de un evento
  del calendario (HU035, con el `event_id` de su `reference`) o desde las sugerencias (HU027, con
  `Destinations.eventDetail`), y «Mis inscripciones» (`eventos/inscripciones`) desde el calendario o el
  detalle. Ambos destinos están en `NavigationRegistry.contextual` y no exigen capacidad de plan.
- El detalle (`GET /v1/events/{event_id}`) muestra fecha y hora en la zona del dispositivo, ubicación,
  cupo declarado y la tarifa informativa con el aviso «El pago no se procesa en SportApp». «Inscribirme»
  se deshabilita, con explicación, si ya existe `my_registration`, el evento inició o no hay conexión.
- La inscripción (`POST /v1/events/{event_id}/registrations`, sin cuerpo) exige confirmación explícita
  y solo se muestra con el `201`. Una `Idempotency-Key` UUID por intento lógico se conserva en hasta
  tres reintentos automáticos (red, `502`, `503`, `504`) y en el reintento manual, y se renueva tras el
  éxito o `idempotency_key_reused`; un `4xx` nunca se repite solo. `event_registration_exists` muestra
  «ya inscrito» y recarga el detalle; `event_full` y `event_registration_closed` tienen mensaje propio.
- No hay cola offline del comando (ADR-032 §8). «Mis inscripciones» (`GET /v1/me/event-registrations`,
  páginas de 20 con cursor) se guarda en `sportapp_event_registrations.db` (una fila por deportista,
  payload AES-GCM con la clave `sportapp.event_registrations.v1`, excluida del respaldo); sin red se
  muestra con `last_synced_at` y se marca desactualizada a las 24 h. Al terminar la sesión se elimina
  como en HU035.

## Configuración por variante

| Variante | `APP_ENVIRONMENT` | `BFF_MOBILE_BASE_URL` |
| --- | --- | --- |
| `debug` | `local` | `http://10.0.2.2:18081/` (BFF móvil de la composición local vista desde el emulador). Texto claro permitido solo hacia `10.0.2.2`, `localhost` y `127.0.0.1`. |
| `release` | `production` | URL de `bff-mobile` en Cloud Run, que pasa el script de release de la plataforma al compilar el APK de producción. Sin ella (CI) la app informa «no tiene configurado el servicio». Solo HTTPS. |

Se sobrescriben sin tocar el código con `-Psportapp.bffMobileBaseUrl.debug=<url>` o
`-Psportapp.bffMobileBaseUrl.release=<url>` (la URL debe terminar en `/`). Para un dispositivo
físico con la composición local: `adb reverse tcp:18081 tcp:18081` y
`-Psportapp.bffMobileBaseUrl.debug=http://localhost:18081/`.

HU042 añade `HEALTH_DATA_CONSENT_VERSION`, la versión del consentimiento de datos de salud que el
móvil envía en `consent.version` (ADR-034/ADR-037): `-Psportapp.healthDataConsentVersion.debug`
(por defecto `local-dev`, el valor del overlay local del backend) y
`-Psportapp.healthDataConsentVersion.release` (por defecto vacía). Vacía, «Mi salud» deshabilita el
formulario y no captura datos; un `422 unsupported_consent_version` deja el reporte `SYNC_FAILED`.

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
| E2E HU026 contra la composición local (teléfono con `adb reverse tcp:18081 tcp:18081`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu026IntegratedE2eTest -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña> -Pandroid.testInstrumentationRunnerArguments.e2eRouteSuffix=<sufijo>` — antes, por `bff-web` (`localhost:18080`, con `Origin` permitido): deportista con su ciudad (`PUT /v1/me/demographic-profile`) y un deporte (`PUT /v1/me/sport-habits`), ambos con `Idempotency-Key`, y organizador que publica con `POST /v1/routes` «E2E HU026 Ciudad <sufijo>» (ciudad del deportista, más larga), «E2E HU026 Corta <sufijo>» (otra ciudad, más corta) y «E2E HU026 Retirada <sufijo>», que retira con `POST /v1/routes/{route_id}/retire`. Datos sintéticos. |
| E2E HU027 contra la composición local (teléfono con `adb reverse tcp:18081 tcp:18081`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu027IntegratedE2eTest -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña> -Pandroid.testInstrumentationRunnerArguments.e2eFeeEventId=<event_id> -Pandroid.testInstrumentationRunnerArguments.e2eRegisterEventId=<event_id> -Pandroid.testInstrumentationRunnerArguments.e2eIncompleteAthleteEmail=<correo>` — antes, por `bff-web` (`localhost:18080`, con `Origin` permitido): deportista con una ciudad propia (`PUT /v1/me/demographic-profile`) y el deporte `running` (`PUT /v1/me/sport-habits`); otro deportista sin perfil deportivo; y organizador que crea y publica (`POST /v1/events`, `POST /v1/events/{event_id}/publish`) dos eventos en esa ciudad con disciplinas alias de `running` («Atletismo» con tarifa `PUT /v1/events/{event_id}/fee` `45000.00 COP`, y «Media Maratón» sin tarifa ni inscripciones, que la prueba inscribe desde el detalle de HU041). Datos sintéticos. |
| E2E HU029 contra la composición local (teléfono con `adb reverse tcp:18081 tcp:18081`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu029IntegratedE2eTest -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo con inscripción> -Pandroid.testInstrumentationRunnerArguments.e2eUnregisteredEmail=<correo sin inscripciones> -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña> -Pandroid.testInstrumentationRunnerArguments.e2eServiceSuffix=<sufijo>` — antes: un organizador publica un evento dentro de 31 días y el primer deportista se inscribe (HU041); un profesional publica por `bff-web` «E2E HU029 Transporte <sufijo>» (`TRANSPORT`) con un intervalo disponible ese día y «E2E HU029 Entrenamiento <sufijo>» (`COACHING`) con un intervalo en 31 días. El segundo deportista no tiene inscripciones. Datos sintéticos. |
| E2E HU038 contra la composición local (teléfono con `adb reverse tcp:18081 tcp:18081`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu038IntegratedE2eTest -Pandroid.testInstrumentationRunnerArguments.e2eIntermediateEmail=<correo INTERMEDIATE> -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña>` — deportista `INTERMEDIATE` sintético con `nutrition.session.reminders`; la sesión de HU032 es real y solo el reloj del motor se acelera ×60 en la prueba. |
| E2E HU043 contra la composición local (teléfono con `adb reverse tcp:18081 tcp:18081`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lfnj.sport_app.e2e.Hu043IntegratedE2eTest -Pandroid.testInstrumentationRunnerArguments.e2eAthleteEmail=<correo> -Pandroid.testInstrumentationRunnerArguments.e2ePassword=<contraseña>` — deportista sintético; la composición local debe servir `bff-mobile` ≥ 0.19.0 y `training-management` 0.16.0 (migraciones 0012). |
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
- HU026: `RouteSuggestionsModelsTest` (URI `geo:`, formatos, guarda y partición),
  `RouteSuggestionsRepositoryTest` (MockWebServer y caché en memoria) y `RouteSuggestionsViewModelTest`
  en la JVM; `RouteSuggestionsContractTest` verifica la operación y sus esquemas en la copia 0.15.0.
  Room, Keystore, reinicio, esquema, respaldo, limpieza, pantalla (incluido el intent `geo:`) y
  recorrido se prueban en el dispositivo (`features/recommendations/Route*` y
  `navigation/RouteSuggestionsFlowTest`); `e2e/Hu026IntegratedE2eTest` usa la composición local.
- HU040: `VirtualSessionsModelsTest` (antelación, ventana de 24 h, zonas, tarifa, guarda y partición),
  `VirtualSessionsRepositoryTest` (MockWebServer: `201`, reintentos con la misma clave, `409`, `403`,
  `422`, `404`, sin red, paginación, caché y limpieza) y `VirtualSessionsViewModelTest` en la JVM;
  `VirtualSessionsContractTest` verifica las cuatro operaciones y sus esquemas en la copia 0.18.0.
  Room, Keystore, reinicio, esquema, respaldo, pantalla y recorrido se prueban en el dispositivo
  (`features/services/` y `navigation/VirtualSessionsFlowTest`); `e2e/Hu040IntegratedE2eTest` requiere
  la composición local con `bff-mobile` ≥ 0.18.0 y `complementary-services` 0.6.0.
- HU029: `SupportSuggestionsModelsTest` (guarda, partición, códigos, fuentes y `STALE`),
  `SupportSuggestionsRepositoryTest` (MockWebServer: `200`, vacía explicada, Eventos o Usuarios caídos,
  `403`, `422`, `503`/`504`, `401`, sin red, respuesta inválida, cifrado, partición y limpieza) y
  `SupportSuggestionsViewModelTest` en la JVM; `SupportSuggestionsContractTest` verifica la operación y sus
  esquemas en la copia 0.18.0. Room, Keystore, reinicio, esquema, respaldo, limpieza, pantalla y recorrido
  se prueban en el dispositivo (`features/services/Support*` y `navigation/SupportSuggestionsFlowTest`);
  `e2e/Hu029IntegratedE2eTest` usa la composición local.
- HU038: `ReminderScheduleTest` (ocurrencias con tiempo simulado, regla de 60 min, rangos),
  `SessionReminderEngineTest` (70 min, < 3 s, reinicio sin duplicados, notificaciones deshabilitadas, tipo
  deshabilitado, sin ruleset, capacidad), `SessionRemindersRepositoryTest` (MockWebServer: `200`, `403`,
  `409`, `422`, `503`/`504`, sin red, cifrado y limpieza), `SessionRemindersViewModelTest`,
  `ActiveSessionTrackerTest` y `ActiveSessionRunnerTest` (wake lock) en la JVM;
  `SessionRemindersContractTest` verifica las tres operaciones y sus esquemas en la copia 0.18.0. En el
  dispositivo: Room y Keystore, pantalla y banner, `ActiveSessionServiceTest` (servicio real con sesión de
  70 min acelerada, reinicio, solo banner y liberación del wake lock) y
  `navigation/SessionRemindersFlowTest`; `e2e/Hu038IntegratedE2eTest` usa la composición local.
- HU043: `FoodIntakeModelsTest` (cantidad decimal, fechas, unidades permitidas, `STALE`, buscador),
  `FoodIntakeRepositoryTest` (MockWebServer: `ETag`/`304`, sin red con y sin caché, `403`, `503`,
  encolado cifrado, alimentos ausentes, limpieza), `FoodIntakeSyncEngineTest` (`201` versión 1, respuesta
  perdida, `502`/`503`/`504`, `409`, `422`, orden, vencimiento, partición) y `FoodIntakeViewModelTest` en
  la JVM; `FoodIntakeContractTest` verifica las dos operaciones y sus esquemas en la copia 0.21.0. En el
  dispositivo: Room y Keystore, reinicio, vencimiento, limpieza, esquema y respaldo
  (`FoodIntakeLocalStorageTest`), `FoodIntakeWorkerTest` (incluida la no captura entre fábricas),
  pantalla y `navigation/FoodIntakeFlowTest`; `e2e/Hu043IntegratedE2eTest` usa la composición local.
- HU033: `TrainingDashboardModelsTest`, `TrainingDashboardRepositoryTest` (MockWebServer: `200`,
  vacío, fuente caída, `403`, `422`, `503`/`504`, sin red, respuesta inválida, partición y limpieza) y
  `TrainingDashboardViewModelTest` en la JVM; `TrainingDashboardContractTest` verifica la operación y
  sus esquemas en la copia de `bff-mobile`. Room, Keystore, reinicio, esquema, respaldo, pantalla y
  recorrido se prueban en el dispositivo (`features/training/dashboard/` y
  `navigation/TrainingDashboardFlowTest`).
- HU041: `EventRegistrationRepositoryTest` (MockWebServer: detalle `200`/`404`/`403`/`503`, comando
  `201`, los cuatro `409`, `404`/`403`/`422`, reintentos con la misma clave, lista con y sin cursor,
  sin red, partición y limpieza), `EventDetailViewModelTest`, `MyRegistrationsViewModelTest` y
  `EventRegistrationModelsTest` en la JVM; `EventRegistrationContractTest` verifica las tres operaciones
  en la copia 0.15.0 y `CalendarCacheCodecCompatibilityTest`, la lectura de cachés de HU035 anteriores.
  Room, Keystore, reinicio, esquema, respaldo, pantallas y recorrido se prueban en el dispositivo
  (`features/events/` y `navigation/EventRegistrationFlowTest`); el E2E es `e2e/Hu041IntegratedE2eTest`.
- HU027: `EventSuggestionsModelsTest` (guarda, partición, motivos, tarifa gratuita y `STALE`),
  `EventSuggestionsRepositoryTest` (MockWebServer: `200` personalizado y no personalizado, Usuarios
  caído, «Ya inscrito», `403` por capacidad o permiso, `422`, `503`/`504`, sin red, respuesta inválida,
  partición y limpieza) y `EventSuggestionsViewModelTest` en la JVM; `EventSuggestionsContractTest`
  verifica la operación y sus esquemas en la copia versionada (sin cambios desde 0.15.0). Room, Keystore,
  reinicio, esquema, respaldo, limpieza, pantalla y recorrido hasta el detalle de HU041 se prueban en el dispositivo
  (`features/events/EventSuggestions*` y `navigation/EventSuggestionsFlowTest`); el E2E es
  `e2e/Hu027IntegratedE2eTest`.
- HU042: `HealthReportModelsTest` (catálogos de ADR-037, fecha, días de recuperación, sesiones
  confirmadas), `HealthReportsRepositoryTest` (cola cifrada, identificadores, consentimiento ausente,
  partición, retiro y limpieza al cerrar sesión), `HealthReportsSyncEngineTest` (MockWebServer: `201`,
  reintento con la misma clave, `409 report_id_reused`, `422`/`404`/`403`, `502`/`503`/`504`, sin red,
  vencimiento a 30 días, otra partición, reinicio del proceso), `HealthReportsCoordinatorTest` y
  `HealthReportViewModelTest` en la JVM; `HealthReportsContractTest` verifica la operación en la copia
  0.21.0. Room, Keystore, reinicio, esquema, respaldo, WorkManager, pantalla y recorrido se prueban en
  el dispositivo (`features/health/` y `navigation/HealthReportFlowTest`); el E2E es
  `e2e/Hu042IntegratedE2eTest`.
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
