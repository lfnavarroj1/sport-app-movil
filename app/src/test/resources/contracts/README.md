# Contratos consumidos por el cliente móvil

Copias de solo lectura de los contratos publicados por `sport-app-backend`. Las pruebas de
contrato (`com.lfnj.sport_app.contract`) verifican que los modelos y operaciones del cliente
correspondan exactamente a esta versión. No se editan a mano: para adoptar una versión nueva se
reemplaza el archivo desde el commit del backend, se actualiza esta tabla y
`BFF_MOBILE_CONTRACT_VERSION` en `app/build.gradle.kts`.

| Archivo | Versión | Origen | SHA-256 (LF) |
| --- | --- | --- | --- |
| `bff-mobile.openapi.json` | 0.22.0 | `sport-app-backend` `contracts/openapi/bff-mobile.openapi.json` en `develop` `e046220` (merge del PR #72 de HU031); adoptada por HU031. Frente a 0.21.0 es aditiva: 4 rutas (`/v1/me/integrations/strava/authorization`, `/v1/me/integrations/strava`, `/v1/me/integrations/strava/syncs` y el callback público `/v1/integrations/strava/callback`, que la app no llama: solo recibe su enlace profundo) y 4 esquemas nuevos (`StravaAuthorizationResponse`, `StravaIntegrationResponse`, `StravaSyncResponse`, `StravaSyncSummary`), sin rutas ni esquemas eliminados o modificados; ninguna operación ya consumida cambia | `1f838220a8a0c77f73c999bf7830c3342fbd3b6815ac60a5a65bcc6754e42ef1` |
