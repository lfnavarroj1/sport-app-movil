# Contratos consumidos por el cliente móvil

Copias de solo lectura de los contratos publicados por `sport-app-backend`. Las pruebas de
contrato (`com.lfnj.sport_app.contract`) verifican que los modelos y operaciones del cliente
correspondan exactamente a esta versión. No se editan a mano: para adoptar una versión nueva se
reemplaza el archivo desde el commit del backend, se actualiza esta tabla y
`BFF_MOBILE_CONTRACT_VERSION` en `app/build.gradle.kts`.

| Archivo | Versión | Origen | SHA-256 (LF) |
| --- | --- | --- | --- |
| `bff-mobile.openapi.json` | 0.15.0 | `sport-app-backend` `contracts/openapi/bff-mobile.openapi.json` en `develop` `acfff0f` (incluye HU028 PR #43 y HU033 PR #40); adoptada por HU028. Frente a 0.8.0, las operaciones ya consumidas (HU003, HU004, HU032, HU034, HU035) solo ganan campos opcionales | `b0fb9ca1e6378524211f5e6379b4fea4382061c1611b2c19d800cdbeba95a57d` |
