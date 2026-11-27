<!--
SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
-->

# Licencias de terceros

Estado: aún no hay dependencias integradas. Esta tabla se rellena al añadir cada una.

Las licencias indicadas en "Candidatas" son de memoria y **hay que verificarlas en el repositorio original** antes de integrar.

## Integradas

| Nombre | Versión | Licencia | Uso | Enlace |
|---|---|---|---|---|
| (ninguna) | | | | |

## Candidatas

| Nombre | Licencia esperada | Posible uso |
|---|---|---|
| Xenia / Xenia Canary / Xenia Edge | BSD-3-Clause | Referencia y posible reutilización de loader XEX, kernel HLE, traductor de shaders |
| XenonRecomp / XenosRecomp | MIT | Referencia para recompilación y conversión de shaders |
| FEX-Emu | MIT | Referencia de JIT ARM64 |
| Dynarmic | 0BSD | Posible base de emisión ARM64 |
| Vulkan headers, SPIRV-Cross, glslang | Apache-2.0 / MIT / BSD | GPU |
| FFmpeg (build LGPL) | LGPL-2.1+ | Decodificación XMA, solo dinámico |
| zstd | BSD-3-Clause / GPL (doble) | Compresión de caché, usar bajo BSD |

## Avisos de copyright

Al integrar código BSD/MIT se copia aquí su aviso de copyright y texto de licencia completos, y se muestran en la pantalla "Acerca de" de la app.
