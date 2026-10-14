# Contratos consumidos por el cliente móvil

Copias de solo lectura de los contratos publicados por `sport-app-backend`. Las pruebas de
contrato (`com.lfnj.sport_app.contract`) verifican que los modelos y operaciones del cliente
correspondan exactamente a esta versión. No se editan a mano: para adoptar una versión nueva se
reemplaza el archivo desde el commit del backend, se actualiza esta tabla y
`BFF_MOBILE_CONTRACT_VERSION` en `app/build.gradle.kts`.

| Archivo | Versión | Origen | SHA-256 (LF) |
| --- | --- | --- | --- |
| `bff-mobile.openapi.json` | 0.8.0 | `sport-app-backend` `contracts/openapi/bff-mobile.openapi.json` en `develop` `eb745b7` (incluye HU032 PR #15 y HU035 PR #16); igual al OpenAPI desplegado en la composición local el 2026-10-14 | `8ac7a59b4d116be186c01305425b7332e1b9d86821c1383a77abaf91975481a4` |
