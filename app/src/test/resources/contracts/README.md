# Contratos consumidos por el cliente móvil

Copias de solo lectura de los contratos publicados por `sport-app-backend`. Las pruebas de
contrato (`com.lfnj.sport_app.contract`) verifican que los modelos y operaciones del cliente
correspondan exactamente a esta versión. No se editan a mano: para adoptar una versión nueva se
reemplaza el archivo desde el commit del backend, se actualiza esta tabla y
`BFF_MOBILE_CONTRACT_VERSION` en `app/build.gradle.kts`.

| Archivo | Versión | Origen | SHA-256 (LF) |
| --- | --- | --- | --- |
| `bff-mobile.openapi.json` | 0.3.0 | `sport-app-backend` `contracts/openapi/bff-mobile.openapi.json` en `bafa8a5` (PR #8) | `a5cc9274963af558699f93138f3548ca1c8b18107e874f35ad5eeea81d4c72e5` |
