# Estado del Proyecto — Alquileres Roveda SA

> [!IMPORTANT] Estado al 18/11/2026
> Todo el sistema está integrado en `develop`: la auditoría integral (PR #17) y la ronda de arreglos de la revisión independiente posterior (18/11). El CI (GitHub Actions, Java 21) corre **729 tests en verde**. La colección Postman, de **132 requests y 460 assertions**, pasa completa con Newman sobre bases limpias.
> El [Alcance](./Alcance.md) entregado a la cátedra no se modificó. Las diferencias entre lo implementado y lo que dice el Alcance están listadas en la [sección 3](#3-decisiones-que-difieren-del-alcance-entregado).

**Documentos relacionados:** [Alcance](./Alcance.md) · [Proceso Principal](./ProcesoPrincipal.md) · [BCRA y Resiliencia](./BCRA-Resiliencia.md) · [Diagramas — Entrega Final](./Diagramas-EntregaFinal.md) · [Configuración de Keycloak](./ConfiguracionKeycloak.md) · [Plan de Trabajo](./PlanDeTrabajo.md)

---

## 1. Qué está hecho

### Funcionalidades del Alcance (§4)

| ID | Funcionalidad | Estado | Dónde |
| :--- | :--- | :--- | :--- |
| F-01 | Autenticar y autorizar acceso | ✅ | Gateway (JWT por JWKS, métodos mutables solo para ADMINISTRADOR) y cada servicio como Resource Server |
| F-02 | Registrar persona | ✅ | `POST /api/personas`. Valida el dígito verificador del CUIT y la unicidad. `keycloakUserId` (UUID) es obligatorio para PROPIETARIO e INQUILINO y se guarda siempre en minúsculas |
| F-03 | Consultar personas y gestionar roles | ✅ | `GET /api/personas`, `GET /api/personas/{id}`, `PATCH /api/personas/{id}/roles`. El PATCH de roles acepta un `keycloakUserId` opcional para pasar un GARANTE a INQUILINO o PROPIETARIO (409 si ya lo usa otra persona) |
| F-04 | Registrar propiedad con normalización y tasación | ✅ | `POST /api/propiedades`: GeoRef AR + zona más cercana + canon sugerido. La altura (`numero`) tiene que ser un entero positivo sin ceros a la izquierda; si GeoRef no devuelve altura, 400 "dirección no encontrada" |
| F-05 | Consultar propiedades | ✅ | `GET /api/propiedades` (admin), `GET /api/propiedades/propias` (propietario, por `sub`) |
| F-06 | Modificar y dar de baja una propiedad | ✅ | `PUT /api/propiedades/{id}`, `PATCH /api/propiedades/{id}/baja` (solo desde DISPONIBLE) |
| F-07 | Actualizar estado de ocupación | ✅ | `PATCH /api/propiedades/{id}/estado` con `estadoEsperado` opcional (409 si no coincide), más `PATCH /api/propiedades/{id}/estado/compensacion` para la Saga. En los dos, si el body trae un `inquilinoId` distinto del de la propiedad RESERVADA o ALQUILADA, 409 "La propiedad está tomada por otro contrato" |
| F-08 | Evaluar solvencia del garante | ✅ | `POST /api/personas/{id}/evaluacion-solvencia`, Central de Deudores del BCRA (RN-07) |
| F-09 | Crear contrato | ✅ | `POST /api/contratos`. Valida montos (`@Digits`) y que la frecuencia de ajuste no supere la duración |
| F-10 | Señar contrato | ✅ | `POST /api/contratos/{id}/senia` |
| F-11 | Activar contrato | ✅ | `POST /api/contratos/{id}/activacion`: seña vigente, garante solvente, cancela borradores competidores y genera el tramo 0 de cuotas |
| F-12 | Cancelar contrato o seña | ✅ | `POST /api/contratos/{id}/cancelacion` con motivo y retención de la seña |
| F-13 | Aplicar ajuste por índice | ✅ | `POST /api/contratos/{id}/ajustes`, `POST /api/contratos/ajustes-periodicos` y `@Scheduled`. ICL, y también IPC (agregado) |
| F-14 | Rescindir contrato | ✅ | `POST /api/contratos/{id}/rescision`: penalización de 1,5 cánones antes de los 6 meses (`inicio + 6 meses`) o 1 canon después, sobre el canon vigente a la fecha de corte; anula las cuotas posteriores al corte. La fecha va entre el inicio y hoy, y exige no tener ajustes pendientes |
| F-15 | Finalizar contrato | ✅ | `POST /api/contratos/{id}/finalizacion`: desde la fecha de fin, sin ajustes pendientes y sin anular cuotas ya devengadas |
| F-16 | Consultar contratos, ajustes y cuotas | ✅ | `GET /api/contratos`, `/{id}`, `/{id}/ajustes`, `/{id}/cuotas`, `GET /api/cuotas/{id}`. Filtra por rol y responde 404 ante un recurso ajeno |
| F-17 | Registrar pago de cuota | ✅ | `PATCH /api/cuotas/{id}/pago` (`@Version` evita el doble pago concurrente) |

### Transversales

- **Seguridad:**
  - Roles ADMINISTRADOR, PROPIETARIO e INQUILINO en Keycloak 24.
  - El Gateway pasa el `Authorization` tal cual (no hay filtro TokenRelay) y cada servicio vuelve a validar el token.
  - Actuator está protegido (solo `/actuator/health` es público).
  - El perfil `local` tiene la misma seguridad que los demás.
  - Los 401 y 403 salen con `ApiError`, y el 401 lleva `WWW-Authenticate: Bearer`.
- **Token de servicio:** ms-contratos llama al catálogo con la cuenta `backend-services` por **client credentials**. El token se cachea hasta poco antes de vencer y se renueva ante un 401. El secreto sale de la variable `KEYCLOAK_CLIENT_SECRET`.
- **Errores:**
  - Todos los servicios responden con el formato `ApiError` (`timestamp`, `status`, `error`, `message`, `path`, `correlationId`) y el manejo común está en `inmobiliaria-commons` (400, 401, 403, 404, 405, 406, 409, 415 y 500). Un dato que la base rechaza por rango o formato da 400; 409 queda solo para conflictos con datos ya registrados.
  - Los datos de entrada se validan antes de llegar a la base: montos con `@Digits`, textos con `@Size`, altura de la dirección entera y positiva, fechas de contrato entre 2000 y 2100.
  - El Gateway responde con `ApiError` también en una ruta inexistente (404), un servicio caído (503), una conexión cortada a mitad del pedido (502), Keycloak sin responder (503) y un timeout (504). Sus logs incluyen el `correlationId`.
- **Trazabilidad:**
  - `X-Correlation-ID` se valida (`[A-Za-z0-9._-]{1,64}`); si no cumple, se genera uno nuevo.
  - Se propaga del Gateway a los servicios y de contratos al catálogo, y sale una sola vez en la respuesta.
  - Los logs se centralizan con Loki, Promtail y Grafana.
- **Saga contratos–catálogo:**
  - Cada cambio de estado de la propiedad lleva el estado esperado.
  - Si falla el guardado local, o el catálogo no responde con claridad (timeout o 5xx), se compensa con `/estado/compensacion`.
  - Las llamadas HTTP van fuera de las transacciones de base (`TransactionTemplate`).
  - Contrato, propiedad y cuota tienen `@Version`.
- **BCRA y contingencia:**
  - Hay caché de índices `indices_cache` (ICL e IPC) que se guarda en su propia transacción y tolera pedidos concurrentes.
  - Con el BCRA caído, se usa un valor del caché solo si tiene como mucho 7 días.
  - El CUIT sale enmascarado en los logs.
  - Detalle en [BCRA y Resiliencia](./BCRA-Resiliencia.md).
- **Perfil `demo`:** simula el BCRA (garantes con situación 2 y 3, garante con cheques rechazados, ICL e IPC) y permite simular caídas del BCRA y de GeoRef desde `/api/demo/**`, solo con rol ADMINISTRADOR. No se activa fuera de ese perfil.
- **Infraestructura:**
  - `docker compose up -d --build` con imágenes multi-etapa y usuario no root.
  - Contraseñas, secreto del cliente y puertos del host se configuran con variables de entorno (ver [`.env.example`](../.env.example)).
  - Los contenedores corren con la hora de Argentina (`TZ=America/Argentina/Cordoba`), así que "hoy" para mora, señas y ajustes es el día local.
  - El CI usa `./mvnw clean verify` en cada PR.

### Matriz de trazabilidad: regla → código → tests → colección

Cada regla del Alcance (§4.2), dónde está implementada, qué tests la prueban y qué requests de la colección la demuestran. Los IDs de la colección (E-, N-, S-, I-, T-, C-) son los de la §12 y la §14 del Alcance y aparecen al principio del nombre de cada request.

| Regla | Implementación | Tests | Colección |
| :--- | :--- | :--- | :--- |
| RN-01 Alta de persona (CUIT, unicidad) | `ValidadorCuit`, `PersonaService` | `ValidadorCuitTest`, `CuitValidoValidatorTest`, `PersonaServiceTest`, `PersonaControllerAbmTest` | E-02, N-01 |
| RN-02 Asignación de personas (roles) | `PropiedadService`, `ContratoService` | `PropiedadServiceTest`, `ContratoServiceTest` | E-02, N-03 |
| RN-03 Normalización con GeoRef | `GeoRefClient`, `PropiedadService` | `GeoRefClientTest`, `PropiedadServiceTest` | E-03, N-02, I-01 |
| RN-04 Tasación por zona | `TasacionService`, `Propiedad` | `TasacionServiceTest`, `PrecioReferenciaZonaTest` | C-01 |
| RN-05 Estados de la propiedad | `TransicionesEstadoPropiedad`, `Propiedad` (`@Version`), `PropiedadService` | `TransicionesEstadoPropiedadTest`, `PropiedadServiceTest`, `CatalogoRepositoriesTest` | N-11, `estadoEsperado` → 409, compensación inválida → 400 |
| RN-06 Señar | `EstadoCreado`, `ContratoService` | `EstadoContratoTest`, `ContratoServiceTest` | E-05, C-02 |
| RN-07 Solvencia del garante | `EvaluadorSolvencia`, `SolvenciaGaranteService` | `EvaluadorSolvenciaTest`, `SolvenciaGaranteServiceTest`, `EvaluacionGaranteServiceTest` | C-04 / N-06, I-02 |
| RN-08 Activar | `EstadoSeniado`, `ContratoService` (Saga) | `EstadoContratoTest`, `ContratoServiceTest`, `ContratoServiceSagaIntegrationTest` | E-06 / C-03, I-02 |
| RN-09 Cancelar | `EstadoCreado`, `EstadoSeniado` | `EstadoContratoTest`, `ContratoServiceTest` | C-04 / N-07, C-03 (competidor cancelado) |
| RN-10 Máquina de estados | `EstadoContrato` y sus seis beans, `EstadoContratoFactory` | `EstadoContratoTest` | N-05, N-09 |
| RN-11 Ajuste por índice | `EstadoActivo`, `CalendarioContrato`, `AjusteProgramadoService`, `IndiceService`, `CalculadoraAjuste` | `EstadoContratoTest`, `CalendarioContratoTest`, `GeneradorCuotasTramoTest`, `AjusteProgramadoServiceTest`, `CalculadoraAjusteTest` | C-05 / E-10, N-08, ajuste sin body, ajuste periódico (contrato J), A-01 (IPC) |
| RN-12 Contingencia del índice | `IndiceService`, `IndiceCacheService`, `IndiceCacheRepository` | `IndiceServiceTest`, `IndiceCacheServiceTest`, `IndiceCacheRepositoryTest` | I-03, I-04 |
| RN-13 Mora | `Cuota`, `CuotaService` | `CuotaTest`, `CuotaServiceTest` | C-06 / E-08, cuota emitida tarde |
| RN-14 Rescindir | `EstadoActivo`, `ContratoService` | `EstadoContratoTest`, `ContratoServiceTest` | C-07 / E-11, rescisión futura → 400 |
| RN-15 Finalizar | `EstadoActivo` | `EstadoContratoTest`, `ContratoServiceTest` | N-09 |
| RN-16 Control de acceso | `GWConfig`, `SecurityConfig` (catálogo y contratos), `ErroresSeguridadHandler` | `GWConfigTest`, `GatewayRuteoErroresTest`, `SecurityConfigTest`, `PropiedadControllerTest`, `IndiceControllerSecurityTest` | S-01, S-02, S-03, actuator → 403 |
| RN-17 Aislamiento de datos | `ContratoService`, `CuotaService`, `PropiedadRepository.findByPropietario_KeycloakUserId` | `ContratoServiceTest`, `CuotaServiceTest`, `CuotaControllerTest`, `CatalogoRepositoriesTest` | S-04, C-08a / E-07, E-07 |
| RN-18 Registrar pago | `Cuota`, `CuotaService` | `CuotaTest`, `CuotaServiceTest`, `CuotaVersionadoJpaTest`, `CuotaControllerTest` | E-09, N-10, `fechaPago` futura o anterior al inicio → 400 |

---

## 2. Cómo se verificó

| Verificación | Resultado |
| :--- | :--- |
| `./mvnw verify` (CI, Java 21) | **729 tests** en verde: commons 39, gateway 34, catálogo 381, contratos 275. |
| Colección Postman con Newman, perfil `demo`, bases limpias | **132 requests, 460 assertions, 0 fallas**, en 3 corridas seguidas. Carpetas: 00 Autenticación (5), 01 Datos iniciales (15), 02 Proceso principal (12), 03 Excepciones y reglas (28), 04 Ciclo posterior (33), 05 Seguridad (8), 06 Integraciones y contingencia (31). |
| Colección con el reloj adelantado (`app.reloj.fecha-fija`) | 0 fallas al 20/01/2027, al 10/09/2027 y al 15/08/2028. Las fechas de la colección son relativas a hoy. |
| Comprobaciones C-01 a C-08 (§14 del Alcance) | Dan los números exactos (canon 625.000, mora 5.000 / total 505.000, penalización 750.000, etc.). |
| `docker compose up -d --build` desde cero (sin `.env`, con `PERFIL_SERVICIOS=docker,demo`) | Las 3 imágenes se construyen y los 9 contenedores quedan sanos: bases, Keycloak, los 3 servicios, Loki, Promtail y Grafana. La colección completa contra los contenedores da **132 requests, 460 assertions, 0 fallas**. Loki recibe los logs de los 3 servicios. Con el perfil `docker` solo, `/api/demo/**` da 404 y el ICL sale del BCRA real. |
| Sin perfil `demo`, contra el BCRA real | Funciona el flujo completo: persona, propiedad con GeoRef, contrato, seña, evaluación del garante y activación. El ICL y el IPC se obtienen de la API real. |
| Token de servicio | Las llamadas de contratos al catálogo llegan con la identidad de `backend-services` y el `X-Correlation-ID` propagado. Con un secreto inválido responde 503 con un mensaje claro. |
| Revisión independiente (18/11) y sus arreglos, reproducidos en vivo | Altura inválida, ingresos enormes, motivo de 501 caracteres y fechas absurdas → 400. `keycloakUserId` en mayúsculas → se guarda en minúsculas. Garante que pasa a inquilino cargando su `keycloakUserId` → 200. Cambio de estado o compensación con otro inquilino → 409 y la propiedad no se toca. Rescindir con un ajuste pendiente → 400, y después de aplicarlo → 200. Tres ajustes periódicos en paralelo → sin 500 y sin SQL en la respuesta. Colección: 0 fallas en 2 corridas con bases limpias. |
| Casos borde reproducidos en vivo | Contrato de 01/01 a 31/12: 12 cuotas. Contrato atrasado: se pone al día con el ajuste periódico. Montos fuera de rango: 400. Pagos y ajustes concurrentes: sin errores 500. Rescisión futura y pago con fecha absurda: 400. Timeout del catálogo al activar: se compensa y el reintento funciona. |

---

## 3. Decisiones que difieren del Alcance entregado

El Alcance (fuente de verdad entregada) no se modificó. Estas decisiones surgieron al implementar y probar. Hay que avisarlas a la cátedra o defenderlas en el coloquio:

| Tema | Alcance | Implementado | Por qué |
| :--- | :--- | :--- | :--- |
| **Índice IPC** (agregado) | Solo ICL (§4.1 excluye otros índices). | El contrato puede pactar ICL (por defecto) o IPC. El IPC usa los meses de la ventana con un mes de rezago por la publicación. | Pedido de negocio. Reusa el cliente BCRA y el caché. |
| **RN-11 Ajuste** | "Meses transcurridos ≥ frecuencia". | Hitos fijos desde el inicio: `H_k = inicio + k × frecuencia`, ventana `H_(k-1) → H_k`. Las fechas del request son opcionales y se validan. El ajuste periódico pone al día todos los ajustes pendientes. | Con la regla anterior, el período se desplazaba a la fecha en que se aplicaba el ajuste, se perdían tramos del índice y quedaban meses sin cuotas. |
| **Generación de cuotas** | Cuotas 1..N por tramo. | Una cuota por mes del contrato. Vence el primer día de vencimiento a partir del inicio de su período, así que ninguna nace vencida. | Antes la primera cuota podía vencer antes del inicio, y en contratos de 1/1 a 31/12 faltaba la última. |
| **RN-13 Mora** | Días desde el vencimiento. PAGADA no genera mora. | Días desde `max(vencimiento, emisión)`. Una cuota pagada tarde muestra la mora fija a la fecha de pago (RN-18). | Una cuota emitida tarde (ajuste demorado) no puede nacer con mora retroactiva. RN-18 pide calcular la mora hasta la fecha de pago. |
| **RN-12 Contingencia** | Último valor publicado anterior a la fecha. | Con el BCRA caído, se usa el caché solo si el valor tiene como mucho 7 días. Si no hay, 503 y el ajuste queda pendiente. | Un valor de meses atrás aplicaba un ajuste parcial y cerraba el período. |
| **RN-14 Rescisión** | Fecha de corte libre. | Fecha entre el inicio y hoy (no puede ser futura). | La rescisión libera la propiedad en el acto. |
| **RN-14 y RN-15 Ajustes al día** | Sin mención de los ajustes. | Rescindir/finalizar exigen tener los ajustes al día (400 si hay un ajuste pendiente). | Con un ajuste pendiente, las cuotas y la multa quedarían calculadas con un canon que ya debió ajustarse. |
| **RN-14 Multa de rescisión** | 1,5 o 1 canon vigente según pasen menos de 6 meses o 6 o más desde el inicio. | La multa de rescisión usa el canon vigente a la fecha de corte y el umbral de 6 meses con la misma convención del calendario. | Que la multa no dependa del día en que se procesa la rescisión y cuente los meses igual que las cuotas y los ajustes. |
| **RN-15 Finalizar** | Sin mención de cuotas. | No anula las cuotas ya devengadas (las de períodos que empezaron antes del fin). | Son deuda real del inquilino. |
| **§7 Endpoints** | No lista compensación. `/ajustes-periodicos` sin respuesta. | Nuevo `PATCH /api/propiedades/{id}/estado/compensacion` (ADMINISTRADOR) y `estadoEsperado` en el PATCH de estado. `/ajustes-periodicos` devuelve `{contratosRevisados, ajustesAplicados, pendientes}`. | Saga real con compensación. Hace visible el resultado del proceso masivo. |
| **§8 Persistencia** | `indices_icl_cache` (UK fecha). `@Version` en propiedades y contratos. | `indices_cache` (UK `tipo_indice, fecha`). `@Version` también en `cuotas`. Columnas `cuotas.fecha_emision` y `ajustes.fecha_valor_indice_desde/hasta`. Coeficiente con 8 decimales. | IPC, pagos concurrentes, mora sin retroactividad y auditoría del dato usado. |
| **§11 Perfil `local`** | Solo para desarrollo del catálogo. | Existe en los tres módulos. Solo cambia el nivel de log y muestra el SQL; la seguridad es la misma. | Comodidad para desarrollar sin abrir la seguridad. |

---

## 4. Pendientes

| # | Pendiente | Responsable sugerido |
| :--- | :--- | :--- |
| 1 | Repetir `docker compose up -d --build` en una máquina del equipo antes de la defensa. El despliegue completo ya se verificó (ver sección 2), pero en el entorno de prueba Maven no podía descargar dependencias dentro del contenedor y se usó un repositorio local precargado; además, los contenedores necesitaron el certificado del proxy de ese entorno para llegar a GeoRef y al BCRA. En una máquina con conexión normal no hace falta ninguna de las dos cosas, y lo único que falta comprobar es la descarga de Maven. | Dev 1 |
| 2 | Pasar `develop` a `main` con un PR, si la cátedra evalúa `main`. `main` sigue con el esqueleto inicial (23/10). | Dev 1 |
| 3 | Alcance §15: tildar "Perfil demo" y "Colección Postman" (hechos), completar el apellido de Esteban y tildar "repositorio visible" (es público). | Equipo |
| 4 | Avisar a la cátedra el agregado del IPC y las decisiones de la sección 3, o prepararlas para el coloquio. | Equipo |
| 5 | Swagger: los dos bodies que pasaron a commons (`ActualizarEstadoPropiedadDTO` y `CompensarEstadoPropiedadDTO`) no tienen descripción `@Schema`, porque commons no depende de springdoc. | Opcional |
| 6 | Límite conocido de la Saga: si ms-contratos se cae entre el PATCH al catálogo y su guardado local, o si un PATCH llega al catálogo después de la compensación, las dos bases quedan distintas y se corrigen a mano con `/estado/compensacion`; resolverlo requiere un outbox o un proceso de reconciliación, fuera del alcance del TPI. | Defensa |
| 7 | Límite conocido de Keycloak: un usuario se trata con un solo rol de negocio. Si tiene PROPIETARIO e INQUILINO, ve como propietario. | Defensa |

**Para correr los tests en una máquina propia:** usar **Java 21**, la versión del proyecto, del CI y de los Dockerfiles. Con Java 25, Mockito no puede instrumentar las clases y fallan los tests aunque el código esté bien. Antes de levantar con Docker una base creada con una versión anterior, hacer `docker compose down -v`: hay columnas nuevas y cambió la escala de otras, y `ddl-auto: update` no las modifica.

---

## 5. Temas para la defensa

- **Patrón State:**
  - Beans stateless resueltos por `EstadoContratoFactory`.
  - Cada estado valida sus operaciones.
  - `CalendarioContrato` calcula meses, vencimientos e hitos.
- **Saga contratos–catálogo:**
  - Uso de `estadoEsperado`.
  - Compensación por el endpoint específico.
  - Manejo del timeout ambiguo.
  - El límite conocido (pendiente 6).
- **Transacciones y concurrencia:**
  - HTTP fuera de las transacciones.
  - `REQUIRES_NEW` en el caché.
  - `@Version` en contrato, propiedad y cuota.
  - Upsert del caché con `ON CONFLICT`.
- **Seguridad:**
  - El Gateway como perímetro.
  - Los servicios como Resource Servers.
  - El token de servicio por client credentials.
  - Aislamiento por `sub` con 404 ante recursos ajenos.
  - Solo se valida la firma del JWT por JWKS, no `iss` ni `aud` (decisión del Alcance §11).
- **Resiliencia BCRA:**
  - Clasificación de errores.
  - Caché con límite de antigüedad.
  - IPC con rezago.
  - Perfil `demo` para mostrar las caídas.
