# Contratos consumidos por el cliente móvil

Copias de solo lectura de los contratos publicados por `sport-app-backend`. Las pruebas de
contrato (`com.lfnj.sport_app.contract`) verifican que los modelos y operaciones del cliente
correspondan exactamente a esta versión. No se editan a mano: para adoptar una versión nueva se
reemplaza el archivo desde el commit del backend, se actualiza esta tabla y
`BFF_MOBILE_CONTRACT_VERSION` en `app/build.gradle.kts`.

| Archivo | Versión | Origen | SHA-256 (LF) |
| --- | --- | --- | --- |
| `bff-mobile.openapi.json` | 0.21.0 | `sport-app-backend` `contracts/openapi/bff-mobile.openapi.json` en `develop` `372989e` (incluye HU042 PR #63, merge `e3a9f0a`); adoptada por HU042. Frente a 0.18.0 es aditiva: 4 rutas (`/v1/food-catalog`, `/v1/me/food-intakes`, `/v1/me/health-reports`, `/v1/me/suggestions/nutrition-routines`) y 11 esquemas nuevos, sin rutas ni esquemas eliminados o modificados; ninguna operación ya consumida cambia | `fbe7090774c0cf9ebf06986a858582b1c01e9cc6381125632d98546a0b003754` |
