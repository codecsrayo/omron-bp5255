# BP

App Android para leer un tensiómetro Omron BP5255 (HEM-716BT2) por Bluetooth, sin la app oficial.

- Sincroniza las 30 mediciones de la memoria del tensiómetro (solo lectura; opcionalmente pone el reloj en hora).
- Gráfica interactiva (toca o arrastra para ver cada medición; rangos 7/30/90 días).
- Hallazgos y notificaciones: crisis hipertensiva, presión alta/baja, pulso fuera de rango, latido irregular, tendencias.
- Exporta a CSV (Excel) y a Health Connect.

Orientativo según la guía ACC/AHA 2017; no sustituye la valoración de un profesional de salud.

## Protocolo

Verificado con capturas HCI (ver `app/src/test/.../ProtocolTest.kt`):

| Elemento | Valor |
|---|---|
| Servicio | `0xFE4A` (en modelos más antiguos `ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b`) |
| TX (write sin respuesta) | `db5b55e0-aee7-11e1-965e-0002a5d5c51b` |
| RX (notify) | `49123040-aee8-11e1-a74d-0002a5d5c51b` |
| Desbloqueo (write + notify) | `b305b680-aee7-11e1-a730-0002a5d5c51b`: `0x11`+4 bytes aleatorios → `0x91 0x00`+eco |
| Registros | 30 × 14 bytes desde `0x02E8`, little endian |

Requiere que el tensiómetro esté vinculado (bond) con el móvil; puede convivir con OMRON connect.

## Compilar

```
gradlew testDebugUnitTest assembleDebug
```

Gradle 8.10.2, AGP 8.8.0, Kotlin 2.0.21, JDK 17 (el de Android Studio).
