# Contratos consumidos por el cliente móvil

Copias de solo lectura de los contratos publicados por `sport-app-backend`. Las pruebas de
contrato (`com.lfnj.sport_app.contract`) verifican que los modelos y operaciones del cliente
correspondan exactamente a esta versión. No se editan a mano: para adoptar una versión nueva se
reemplaza el archivo desde el commit del backend, se actualiza esta tabla y
`BFF_MOBILE_CONTRACT_VERSION` en `app/build.gradle.kts`.

| Archivo | Versión | Origen | SHA-256 (LF) |
| --- | --- | --- | --- |
| `bff-mobile.openapi.json` | 0.18.0 | `sport-app-backend` `contracts/openapi/bff-mobile.openapi.json` en `develop` `93e8b47` (incluye HU040 PR #60); adoptada por HU040. Frente a 0.15.0 es aditiva: 9 rutas y 19 esquemas nuevos, sin rutas ni esquemas eliminados o modificados; ninguna operación ya consumida (HU003, HU004, HU026, HU028, HU032–HU035) cambia | `5f6a5b2711f39187545b9d75f667b49218df0738680cb608291b83f11a721764` |
