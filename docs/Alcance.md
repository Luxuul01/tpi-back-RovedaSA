# Alcance Comprometido — TPI Backend 2026

> **Fuente de verdad del proyecto.** Este documento consolida lo declarado en el formulario de propuesta del TPI entregado a la cátedra. Es la base de la validación y de los criterios de corrección. Si otro documento del repositorio (`Dominio.md`, `PlanDeTrabajo.md`, diagramas o código) lo contradice, **prevalece este archivo**, y el otro documento se corrige. Cualquier cambio de alcance se acuerda primero con la cátedra y después se actualiza acá.

**Documentos relacionados:** [Dominio](./Dominio.md) · [Diagramas](./Diagramas.md) · [Proceso principal](./ProcesoPrincipal.md) · [Plan de Trabajo](./PlanDeTrabajo.md) · [Configuración de Keycloak](./ConfiguracionKeycloak.md)

---

## Índice

1. [Identificación](#1-identificación)
2. [Descripción breve](#2-descripción-breve)
3. [Problema y contexto](#3-problema-y-contexto)
4. [Alcance funcional y proceso principal](#4-alcance-funcional-y-proceso-principal)
5. [Arquitectura: Contexto (C4 nivel 1)](#5-arquitectura-contexto-c4-nivel-1)
6. [Arquitectura: Contenedores (C4 nivel 2)](#6-arquitectura-contenedores-c4-nivel-2)
7. [Arquitectura: APIs](#7-arquitectura-apis)
8. [Arquitectura: Persistencia](#8-arquitectura-persistencia)
9. [Arquitectura: Librería común](#9-arquitectura-librería-común)
10. [Seguridad y autorización](#10-seguridad-y-autorización)
11. [Ejecución y despliegue](#11-ejecución-y-despliegue)
12. [Colección de endpoints y escenarios de prueba](#12-colección-de-endpoints-y-escenarios-de-prueba)
13. [Compromiso del equipo, desafíos, exclusiones y riesgos](#13-compromiso-del-equipo-desafíos-exclusiones-y-riesgos)
14. [Comprobaciones observables](#14-comprobaciones-observables)
15. [Pendientes para cumplir el alcance](#15-pendientes-para-cumplir-el-alcance)

---

## 1. Identificación

| Campo | Valor |
|---|---|
| Proyecto | Alquileres Roveda SA — Sistema backend de administración de alquileres inmobiliarios basado en microservicios |
| Equipo | Grupo 50 |
| Comisión | 3K3 |
| Integrantes | Luciano Agüero, Levi Angelino, Santino Brenna, Lautaro Roveda, Esteban (apellido pendiente) |
| Responsable de contacto | Luciano Agüero |
| Repositorio | https://github.com/Luxuul01/tpi-back-RovedaSA |

---

## 2. Descripción breve

Alquileres Roveda SA es un sistema backend, basado en microservicios, para gestionar alquileres inmobiliarios. Está pensado para inmobiliarias y administradoras que manejan carteras de propiedades en alquiler. Lo usan tres perfiles: el administrador de la inmobiliaria, que hace toda la operación, y los propietarios e inquilinos, que solo pueden consultar la información que les corresponde.

La solución separa la oferta de inmuebles de la gestión de contratos en dos servicios independientes, cada uno con su propia base de datos, a los que se accede a través de un API Gateway que centraliza la autenticación y autorización con Keycloak (OAuth2/OIDC). El servicio de catálogo registra personas y propiedades: normaliza los domicilios y obtiene sus coordenadas con la API GeoRef AR, estima un canon sugerido según la zona y la tipología, y evalúa la solvencia de los garantes consultando la Central de Deudores del BCRA. El servicio de contratos maneja el ciclo de vida del alquiler con el patrón State (creado, señado, activo, cancelado, rescindido y finalizado) y sincroniza el estado de ocupación de cada propiedad con el catálogo.

El principal aporte es automatizar las reglas críticas del negocio que hoy suelen resolverse a mano: evitar que una misma propiedad se reserve dos veces, verificar el riesgo crediticio del garante antes de activar un contrato, actualizar el canon con el Índice de Contratos de Locación (ICL) oficial del BCRA, con un respaldo local ante fallas del servicio externo, y calcular la mora de cada cuota.

Como resultado concreto, la inmobiliaria podrá registrar propiedades, formalizar y administrar contratos de punta a punta con ajustes auditables, y cada propietario e inquilino podrá consultar en forma segura solo sus propios inmuebles, contratos y cuotas.

---

## 3. Problema y contexto

En una inmobiliaria que administra alquileres, la gestión diaria de propiedades, contratos y actualizaciones de canon suele hacerse con planillas, registros manuales y consultas sueltas a fuentes externas. Esto genera errores, demoras y poca trazabilidad, justo en procesos con impacto económico y legal directo.

**Causas y dificultades principales:**

- **Actualización de alquileres:** en un contexto inflacionario, cada contrato se actualiza con una periodicidad pactada y un índice oficial (como el ICL que publica el BCRA). Calcularlo a mano, contrato por contrato, es lento, propenso a errores y difícil de auditar ante un reclamo.
- **Reservas concurrentes:** cuando varios interesados quieren la misma propiedad, la falta de un estado de ocupación confiable puede llevar a comprometer el mismo inmueble dos veces, o a dejarlo bloqueado por una seña que nunca se concretó.
- **Evaluación de garantes:** verificar la situación crediticia del garante depende de consultas manuales que no siempre quedan registradas ni se hacen antes de firmar.
- **Datos inconsistentes:** los domicilios se cargan con formatos distintos, lo que dificulta identificar las propiedades y estimar su valor según la zona.
- **Mora y cuotas:** los recargos por pago fuera de término se calculan de forma dispar y las cuotas de contratos rescindidos pueden seguir figurando como deuda.
- **Acceso a la información:** propietarios e inquilinos dependen de la inmobiliaria para saber el canon vigente, los aumentos aplicados o el estado de su contrato.

**Actores:** la inmobiliaria (administradores que operan la cartera), los propietarios (dueños de los inmuebles que cobran el canon), los inquilinos (que pagan el alquiler y sus ajustes) y los garantes (que respaldan el contrato). Como fuentes externas intervienen el BCRA, que publica el ICL y la situación crediticia de las personas, y el registro geográfico oficial de direcciones.

**Pertinencia:** son reglas de negocio claras, repetitivas y con consecuencias económicas, por eso conviene automatizarlas. Un sistema que las aplique de forma uniforme, se apoye en fuentes oficiales y deje registro de cada operación reduce errores y conflictos entre las partes, y le da transparencia a propietarios e inquilinos sin depender de la consulta manual.

---

## 4. Alcance funcional y proceso principal

### 4.1. Funcionalidades comprometidas en el MVP

Prioridad: **Alta** = obligatoria para el MVP. **Media** = comprometida, se implementa después de completar las de prioridad Alta.

| ID | Funcionalidad | Actor | Comportamiento esperado | Resultado observable | Prioridad |
|---|---|---|---|---|---|
| F-01 | Autenticar y autorizar acceso | Todos | El usuario se autentica en Keycloak y obtiene un JWT con su rol. El Gateway valida el token en cada petición, filtra por rol, propaga la identidad y agrega el `X-Correlation-ID`. | 401 sin token o con token inválido, 403 si el rol no tiene permiso. Las peticiones autorizadas llegan al servicio con su `X-Correlation-ID`. | Alta |
| F-02 | Registrar persona | Administrador | Alta con CUIT/CUIL, contacto, usuario de Keycloak y uno o más roles (propietario, inquilino, garante). | 201 con la persona creada, o rechazo por datos inválidos o duplicados. | Alta |
| F-03 | Consultar personas y gestionar roles | Administrador | Lista y consulta personas. Agrega un rol a una persona existente. | Persona con su conjunto de roles actualizado. | Media |
| F-04 | Registrar propiedad con normalización y tasación | Administrador | Normaliza la dirección con GeoRef AR, obtiene coordenadas, asigna la zona de referencia y calcula el precio sugerido por m² y el canon base sugerido. | 201 con la propiedad DISPONIBLE, dirección normalizada, coordenadas, zona y canon sugerido. | Alta |
| F-05 | Consultar propiedades | Administrador / Propietario | El administrador consulta todas. El propietario consulta solo las suyas. | Listado con características, tasación y estado de ocupación (lista vacía si no tiene). | Alta |
| F-06 | Modificar y dar de baja una propiedad | Administrador | Edita los datos descriptivos. La baja es lógica: pasa a INACTIVA y solo se permite si está DISPONIBLE. | Propiedad actualizada o INACTIVA. | Media |
| F-07 | Actualizar estado de ocupación | ms-contratos (cuenta de servicio) | Cambia el estado de la propiedad cuando cambia el estado del contrato. | Propiedad RESERVADA, ALQUILADA o DISPONIBLE, o rechazo si la transición es inválida. | Alta |
| F-08 | Evaluar solvencia del garante | Administrador / ms-contratos | Consulta la Central de Deudores del BCRA por CUIT, guarda el resultado y determina si el garante es solvente. | Resultado solvente o no solvente con los datos que lo justifican. | Alta |
| F-09 | Crear contrato (borrador) | Administrador | Registra propiedad, partes, vigencia, canon inicial, frecuencia de ajuste, % de mora diaria y día de vencimiento. | 201 con el contrato CREADO. | Alta |
| F-10 | Señar contrato | Administrador | Registra el monto y la fecha límite de la seña y reserva la propiedad. | Contrato SEÑADO y propiedad RESERVADA. | Alta |
| F-11 | Activar contrato | Administrador | Verifica el plazo de la seña y la solvencia del garante. Alquila la propiedad, cancela los otros borradores sobre ella y genera las cuotas del primer período. | Contrato ACTIVO, propiedad ALQUILADA y cuotas PENDIENTE. | Alta |
| F-12 | Cancelar contrato o seña | Administrador | Descarta un borrador o cancela una seña registrando el motivo. Libera la propiedad si estaba reservada. | Contrato CANCELADO y, si correspondía, propiedad DISPONIBLE. | Alta |
| F-13 | Aplicar ajuste por ICL | Proceso programado / Administrador | Detecta los contratos que alcanzaron su frecuencia de ajuste, obtiene el ICL (o lo toma del caché si el BCRA falla), recalcula el canon, registra el ajuste y genera las cuotas del nuevo período. | Ajuste registrado, canon actualizado y nuevas cuotas. | Alta |
| F-14 | Rescindir contrato | Administrador | Interrumpe un contrato activo, calcula la penalización, anula las cuotas futuras y libera la propiedad. | Contrato RESCINDIDO, cuotas posteriores ANULADAS y propiedad DISPONIBLE. | Alta |
| F-15 | Finalizar contrato | Administrador | Cierra un contrato activo cuya fecha de fin ya se alcanzó. | Contrato FINALIZADO y propiedad DISPONIBLE. | Media |
| F-16 | Consultar contratos, ajustes y cuotas | Inquilino / Propietario / Administrador | Cada usuario ve lo que le corresponde según su rol. Las cuotas se muestran con la mora calculada a la fecha. | Contratos con estado, canon vigente, ajustes y cuotas con monto base, mora y total. | Alta |
| F-17 | Registrar pago de cuota | Administrador | Marca una cuota pendiente como PAGADA con su fecha de pago. Es solo un registro: no procesa ni cobra dinero. | Cuota PAGADA, que deja de acumular mora. | Media |

**Fuera del alcance:**

- Cobro real de alquileres: pasarelas de pago, conciliación bancaria, facturación e impuestos.
- Interfaz de usuario. Se entrega solo la API REST, documentada con OpenAPI.
- Autoregistro y gestión de usuarios desde la API (se gestionan en Keycloak).
- Notificaciones.
- Generación del documento del contrato y firma digital.
- Índices distintos del ICL, moneda extranjera y renovación automática.
- Reemplazo del garante en un contrato señado.
- ABM de zonas de precio de referencia por API (se cargan como datos iniciales).

### 4.2. Reglas de negocio

| ID | Condición o disparador | Comportamiento esperado | Rechazos | Func. |
|---|---|---|---|---|
| RN-01 | Alta de persona | El CUIT/CUIL debe tener un dígito verificador válido. CUIT, email y usuario de Keycloak deben ser únicos. | 400 si el CUIT es inválido. 409 si ya existe. | F-02 |
| RN-02 | Asignación de personas | Una persona puede tener varios roles, pero participa solo con un rol que tenga asignado. El garante no puede ser el inquilino ni el propietario. | 400 | F-03, F-04, F-09, F-10 |
| RN-03 | Alta de propiedad | La dirección se normaliza con GeoRef antes de guardarse. Si no se encuentra, la propiedad no se registra. | 400 si no se encuentra. 503 si GeoRef no está disponible. | F-04 |
| RN-04 | Tasación | Precio por m² = valor del m² de la zona × factor de la tipología. Canon sugerido = precio por m² × superficie computable. Zona: la que contiene el punto con el centro más cercano; si no hay, la zona por defecto de la ciudad; si la ciudad no tiene cobertura, queda sin precio sugerido. | No bloquea el alta. | F-04 |
| RN-05 | Estados de la propiedad | DISPONIBLE → RESERVADA; RESERVADA → ALQUILADA o DISPONIBLE; ALQUILADA → DISPONIBLE. INACTIVA es la baja lógica. Los cambios concurrentes se resuelven con control optimista de versión. | 400 si la transición es inválida. 409 si hay conflicto de concurrencia. | F-06, F-07 |
| RN-06 | Señar | Solo con el contrato CREADO y la propiedad DISPONIBLE. Monto y fecha límite futura. La propiedad pasa a RESERVADA. | Rechazo | F-10, F-07 |
| RN-07 | Solvencia del garante | Es solvente si su situación crediticia en el BCRA es 1 o 2 y tiene 0 cheques rechazados. Si el BCRA no responde, no se lo califica como solvente. | 503 si el BCRA no responde. | F-08, F-11 |
| RN-08 | Activar | Requiere SEÑADO, seña vigente y garante solvente. Efectos: propiedad ALQUILADA, cancela los otros borradores CREADO de la propiedad y genera las cuotas 1..N (N = frecuencia de ajuste en meses). | Rechazo, o 503. El contrato sigue SEÑADO. | F-11, F-07, F-08 |
| RN-09 | Cancelar | Desde CREADO (descarte) o SEÑADO (desistimiento, seña vencida o garante rechazado). Registra el motivo y si la seña se retiene o se devuelve. Si estaba SEÑADO, la propiedad vuelve a DISPONIBLE. | Rechazo si está ACTIVO o en un estado terminal. | F-12, F-07 |
| RN-10 | Máquina de estados | Cada estado habilita solo ciertas operaciones (ver tabla). | Rechazo | F-09 a F-15 |
| RN-11 | Ajuste por ICL | Contrato ACTIVO con meses transcurridos ≥ frecuencia. Canon nuevo = canon anterior × ICL(hasta) / ICL(desde). Registra el ajuste, actualiza el canon y genera las cuotas del período. Un solo ajuste por período. | Rechazo si todavía no corresponde o si el período ya se ajustó. | F-13 |
| RN-12 | Contingencia ICL | Cada valor obtenido del ICL se guarda en un caché local. Si el BCRA falla, se usa el valor guardado. Si no hay valor, el ajuste queda pendiente para la próxima ejecución. | 503 | F-13 |
| RN-13 | Mora | Cuota PENDIENTE con fecha de consulta posterior al vencimiento: mora = monto base × (% diario / 100) × días de atraso, y total = base + mora. Se calcula en cada consulta y no se guarda. Las cuotas ANULADAS o PAGADAS no generan mora. | — | F-16, F-17 |
| RN-14 | Rescindir | Solo contratos ACTIVOS. Penalización de 1,5 cánones vigentes si pasaron menos de 6 meses desde el inicio y de 1 canon si pasaron 6 o más. Anula las cuotas que vencen después de la fecha de corte y libera la propiedad. | Rechazo | F-14, F-07 |
| RN-15 | Finalizar | Solo contratos ACTIVOS cuya fecha actual sea igual o posterior a la fecha de fin. Libera la propiedad. | Rechazo | F-15, F-07 |
| RN-16 | Control de acceso | Toda petición requiere un JWT válido. POST, PUT, PATCH y DELETE quedan reservados a ADMINISTRADOR (en el Gateway y en cada servicio). PROPIETARIO e INQUILINO solo pueden hacer GET. | 401 / 403 | F-01 y todas |
| RN-17 | Aislamiento de datos | La pertenencia se determina con el `sub` del token, nunca con ids enviados en la petición. | 404 ante un recurso ajeno. | F-05, F-16 |
| RN-18 | Registrar pago | Solo cuotas PENDIENTE: pasan a PAGADA con su fecha de pago. La mora se calcula hasta esa fecha. | Rechazo si la cuota ya está pagada o anulada. | F-17 |

**Estados del contrato (RN-10):**

| Estado | Operaciones permitidas | Transiciones |
|---|---|---|
| CREADO | señar, cancelar | → SEÑADO, → CANCELADO |
| SEÑADO | activar, cancelar | → ACTIVO, → CANCELADO |
| ACTIVO | aplicar ajuste, consultar mora, rescindir, finalizar | → ACTIVO, → RESCINDIDO, → FINALIZADO |
| CANCELADO / RESCINDIDO / FINALIZADO | solo consulta | terminales |

**Estados de la propiedad:** DISPONIBLE → RESERVADA → ALQUILADA → DISPONIBLE. Desde RESERVADA también puede volver a DISPONIBLE. INACTIVA es la baja lógica.

**Estados de la cuota:** PENDIENTE → PAGADA (RN-18) o PENDIENTE → ANULADA (RN-14).

### 4.3. Proceso principal: formalizar un contrato de alquiler

Diagrama de secuencia y definición Mermaid: **[ProcesoPrincipal.md](./ProcesoPrincipal.md)**.

- **Evento inicial:** un interesado quiere alquilar una propiedad del catálogo.
- **Participantes:** Administrador, API Gateway + Keycloak, ms-contratos, ms-catalogo-inmobiliario, BCRA (Central de Deudores) e Inquilino/Propietario.

| Paso | Descripción | Funcionalidades | Reglas |
|---|---|---|---|
| P1 | Autenticación del administrador | F-01 | RN-16 |
| P2 | Verificar o registrar inquilino y garante | F-02, F-03 | RN-01, RN-02 |
| P3 | Crear el borrador (CREADO) | F-09 | RN-02, RN-10 |
| P4 | Señar. ¿Propiedad DISPONIBLE? Sí: RESERVADA y contrato SEÑADO. No: rechazo. | F-10, F-07 | RN-05, RN-06, RN-10 |
| P5 | Activar. ¿Seña vigente? ¿Garante solvente? Sí: ALQUILADA, competidores cancelados, cuotas y contrato ACTIVO. No: rechazo. | F-11, F-08, F-07 | RN-07, RN-08, RN-10 |
| P6 | Excepción: cancelar la seña. La propiedad vuelve a DISPONIBLE. | F-12, F-07 | RN-09, RN-05 |
| P7 | Consulta del resultado por inquilino y propietario | F-16, F-05 | RN-13, RN-17 |
| Ciclo posterior | Ajustes, rescisión, finalización y pagos | F-13, F-14, F-15, F-17 | RN-11, RN-12, RN-14, RN-15, RN-18 |

**Excepciones:** dos señas simultáneas sobre la misma propiedad (solo una se confirma, por control optimista). GeoRef, BCRA o el catálogo no disponibles (503 sin cambio de estado; nunca se asume un resultado favorable).

**Resultado final:** contrato ACTIVO con las cuotas del primer período, propiedad ALQUILADA y la información visible para cada parte según su rol.

---

## 5. Arquitectura: Contexto (C4 nivel 1)

Imagen y definición C4-PlantUML: [Diagramas.md — C4 Contexto](./Diagramas.md#c4---contexto) · [`img/C4-Contexto.png`](./img/C4-Contexto.png)

- **Sistema:** Sistema de Administración de Alquileres (backend REST).
- **Actores:** Administrador (lectura y escritura), Propietario (solo lectura de lo propio) e Inquilino (solo lectura de lo propio).
- **Sistemas externos:** Keycloak (proveedor de identidad; se despliega con el sistema pero es un producto de terceros), API GeoRef AR, API BCRA - Central de Deudores y API BCRA - Estadísticas (ICL).
- **Relaciones:** los actores se autentican en Keycloak y consumen el sistema por HTTPS/REST con JWT. El sistema valida los tokens con JWKS, normaliza direcciones con GeoRef, evalúa garantes con la Central de Deudores y obtiene el ICL de Estadísticas.

---

## 6. Arquitectura: Contenedores (C4 nivel 2)

Imagen y definición: [Diagramas.md — C4 Contenedores](./Diagramas.md#c4---contenedores) · [`img/C4-Contenedores.png`](./img/C4-Contenedores.png)

| Contenedor | Tecnología | Puerto | Responsabilidad |
|---|---|---|---|
| api-gateway | Java 21, Spring Cloud Gateway (Netty) | 8080 | Punto único de entrada: valida el JWT, restringe métodos por rol, enruta y agrega el `X-Correlation-ID`. |
| ms-catalogo-inmobiliario | Java 21, Spring Boot 3 | 8081 | Personas, propiedades, tasación, estado de ocupación, GeoRef y BCRA Central de Deudores. |
| ms-contratos | Java 21, Spring Boot 3 | 8082 | Contratos (patrón State), cuotas, mora, rescisión, ajuste por ICL y caché del ICL. |
| db-catalogo | PostgreSQL 16 | 5432 | Base exclusiva del catálogo. |
| db-contratos | PostgreSQL 16 | 5433 | Base exclusiva de contratos. |
| Promtail / Loki / Grafana | Stack PLG | — / 3100 / 3000 | Recolección, almacenamiento y consulta de logs por `X-Correlation-ID`. |

**Comunicaciones clave:** el Gateway enruta `/api/propiedades/**` y `/api/personas/**` al catálogo, y `/api/contratos/**` y `/api/cuotas/**` a contratos (TokenRelay). ms-contratos llama al catálogo por REST (RestClient) con un token de servicio (client credentials). `inmobiliaria-commons` es una **librería**, no un contenedor.

---

## 7. Arquitectura: APIs

### ms-catalogo-inmobiliario

| Recurso | Método | Endpoint | Operación de negocio | Seguridad |
|---|---|---|---|---|
| Personas | POST | `/api/personas` | Registrar una persona con sus roles y su usuario de Keycloak | ADMINISTRADOR |
| Personas | GET | `/api/personas?rol={rol}` | Listar personas en forma paginada, con filtro opcional por rol | ADMINISTRADOR |
| Personas | GET | `/api/personas/{id}` | Consultar los datos de una persona | ADMINISTRADOR (incluye la cuenta de servicio) |
| Personas | PATCH | `/api/personas/{id}/roles` | Agregar un rol a una persona existente | ADMINISTRADOR |
| Personas | POST | `/api/personas/{id}/evaluacion-solvencia` | Evaluar la solvencia del garante en el BCRA y registrar el resultado | ADMINISTRADOR (incluye la cuenta de servicio) |
| Propiedades | POST | `/api/propiedades` | Registrar una propiedad normalizando su dirección y calculando su canon sugerido | ADMINISTRADOR |
| Propiedades | GET | `/api/propiedades?estado={estado}` | Listar propiedades en forma paginada, con filtro opcional por estado | ADMINISTRADOR |
| Propiedades | GET | `/api/propiedades/{id}` | Consultar el detalle y la tasación de una propiedad | ADMINISTRADOR (incluye la cuenta de servicio) |
| Propiedades | GET | `/api/propiedades/propias` | Consultar las propiedades del propietario autenticado | PROPIETARIO (filtra por el token) |
| Propiedades | PUT | `/api/propiedades/{id}` | Modificar los datos descriptivos de una propiedad | ADMINISTRADOR |
| Propiedades | PATCH | `/api/propiedades/{id}/baja` | Dar de baja lógica una propiedad disponible (pasa a INACTIVA) | ADMINISTRADOR |
| Propiedades | PATCH | `/api/propiedades/{id}/estado` | Cambiar el estado de ocupación a pedido de ms-contratos | ADMINISTRADOR (uso interno de la cuenta de servicio) |

### ms-contratos

| Recurso | Método | Endpoint | Operación de negocio | Seguridad |
|---|---|---|---|---|
| Contratos | POST | `/api/contratos` | Registrar un borrador de contrato | ADMINISTRADOR |
| Contratos | GET | `/api/contratos` | Listar los contratos visibles según el rol: todos (admin), los de sus propiedades (propietario) o los propios (inquilino) | ADMINISTRADOR / PROPIETARIO / INQUILINO (filtra por el token) |
| Contratos | GET | `/api/contratos/{id}` | Consultar el detalle, el estado y el canon vigente | ADMINISTRADOR / dueño del recurso (404 si es ajeno) |
| Contratos | POST | `/api/contratos/{id}/seniar` | Registrar la seña y reservar la propiedad | ADMINISTRADOR |
| Contratos | POST | `/api/contratos/{id}/activar` | Formalizar el contrato: verificar el garante, alquilar la propiedad y generar las cuotas iniciales | ADMINISTRADOR |
| Contratos | POST | `/api/contratos/{id}/cancelar` | Descartar el borrador o cancelar la seña, liberando la propiedad | ADMINISTRADOR |
| Contratos | POST | `/api/contratos/{id}/rescindir` | Rescindir anticipadamente: penalización, anulación de cuotas y liberación de la propiedad | ADMINISTRADOR |
| Contratos | POST | `/api/contratos/{id}/finalizar` | Cerrar un contrato que cumplió su plazo | ADMINISTRADOR |
| Ajustes | POST | `/api/contratos/{id}/ajustes` | Aplicar el ajuste por ICL a un contrato puntual | ADMINISTRADOR |
| Ajustes | POST | `/api/contratos/ajuste-periodico` | Ejecutar manualmente el proceso de ajuste masivo (también corre programado) | ADMINISTRADOR |
| Ajustes | GET | `/api/contratos/{id}/ajustes` | Consultar el historial de ajustes | ADMINISTRADOR / dueño del recurso |
| Cuotas | GET | `/api/contratos/{id}/cuotas?fechaReferencia={fecha}` | Consultar las cuotas con mora y total calculados a la fecha de referencia | ADMINISTRADOR / dueño del recurso |
| Cuotas | GET | `/api/cuotas/{id}?fechaReferencia={fecha}` | Consultar una cuota con su mora | ADMINISTRADOR / dueño del recurso |
| Cuotas | PATCH | `/api/cuotas/{id}/pago` | Registrar el pago de una cuota pendiente | ADMINISTRADOR |

**Notas:** el Gateway no tiene recursos propios. Todos los endpoints requieren JWT (401/403). Para PROPIETARIO e INQUILINO, la pertenencia sale del token. Cada servicio expone OpenAPI/Swagger UI.

---

## 8. Arquitectura: Persistencia

Imagen y definición PlantUML: [Diagramas.md — DER](./Diagramas.md#diagrama-entidad-relación-der) · [`img/DER.png`](./img/DER.png)

**catalogo_db (ms-catalogo-inmobiliario):** `personas`, `persona_roles` (PK compuesta persona_id + rol), `propiedades` (con `version`) y `precios_referencia_zona`.

- personas 1 — 1..N persona_roles.
- personas 1 — 0..N propiedades como propietario, y 0..1 — 0..N como inquilino y como garante.
- precios_referencia_zona 0..1 — 0..N propiedades.

**contratos_db (ms-contratos):** `contratos` (referencias lógicas a la propiedad y las personas, `propietario_keycloak_id`, `inquilino_keycloak_id`, `version`), `ajustes` (UK contrato_id + fecha_indice_hasta), `cuotas` (FK contrato, FK opcional al ajuste, estado PENDIENTE/PAGADA/ANULADA, `fecha_pago`, UK contrato_id + nro_cuota) e `indices_icl_cache` (UK fecha).

- contratos 1 — 0..N ajustes y 1 — 0..N cuotas.
- ajustes 0..1 — 0..N cuotas.

**Decisiones de persistencia:**

1. Database per Service: cada servicio es dueño exclusivo de su base.
2. Sin FK entre bases: las referencias van por id y se validan vía API al crear el contrato.
3. Desnormalización controlada: el contrato guarda los `keycloak_user_id` de propietario e inquilino para filtrar por el `sub` sin llamar al catálogo.
4. Roles como colección (`persona_roles`): un mismo CUIT puede tener varios roles.
5. Control optimista (`@Version`) en `propiedades` y `contratos`.
6. El estado del patrón State se persiste como texto y el objeto se reconstruye al cargar. Todos los enums se guardan como STRING.
7. Montos en NUMERIC(15,2) (BigDecimal) y valores del ICL en NUMERIC(19,6).
8. La mora se calcula y no se guarda. Sí se guardan los ajustes, la penalización y la evaluación del BCRA.
9. Baja lógica de propiedades y conservación del histórico de contratos y cuotas.
10. Caché del ICL con fecha única.
11. `fecha_alta` / `fecha_modificacion` en las entidades principales.

---

## 9. Arquitectura: Librería común

**`inmobiliaria-commons`** (`com.rovedasa:inmobiliaria-commons:1.0.0-SNAPSHOT`), módulo del proyecto Maven multi-módulo. La usan **ms-catalogo-inmobiliario y ms-contratos** como dependencia y la registran con `scanBasePackages`. **El Gateway no la usa** porque es reactivo (WebFlux) y la librería es servlet; tiene su propio `CorrelationIdGlobalFilter`.

**Contenido:**

- DTOs entre servicios: `PropiedadDTO`, `ActualizarEstadoPropiedadDTO` y `ValidacionGaranteDTO`.
- Enum compartido: `EstadoPropiedadEnum`.
- Manejo de errores: `ApiError`, `GlobalExceptionHandler`, `RecursoNoEncontradoException` y `OperacionInvalidaException`.
- Trazabilidad: `CorrelationIdFilter`.
- Seguridad: `KeycloakRoleConverter`.

**Criterio:** en la librería va solo lo que (a) cruza el límite entre servicios o (b) es un comportamiento transversal que tiene que ser idéntico en todos los servicios. Quedan en cada servicio las entidades, los repositorios, la lógica de negocio, los DTOs de su API pública, los clientes de APIs externas y los tipos que usa un solo servicio (`EstadoContratoEnum`, `EstadoCuotaEnum` y los DTOs de cuotas pertenecen a ms-contratos). Para modificar la librería se usa el protocolo "Commons First".

---

## 10. Seguridad y autorización

- **Autenticación:** OAuth2/OIDC con Keycloak 24 (realm `rovedasa-realm`) y JWT firmados con RS256. El Gateway y los servicios son Resource Servers y validan localmente con JWKS.
- **Clientes:** `rovedasa-backend` (público, para usuarios humanos) y `backend-services` (confidencial, client credentials, cuenta de servicio de ms-contratos con rol ADMINISTRADOR).
- **Roles:** ADMINISTRADOR (lectura y escritura), PROPIETARIO y INQUILINO (solo GET de lo propio).
- **Dos capas:** el Gateway exige token y bloquea las mutaciones de los roles que no son ADMINISTRADOR. Los servicios revalidan el token, aplican `@PreAuthorize` por endpoint y filtran por el `sub` (anti-IDOR).
- **Públicos:** solo Swagger/OpenAPI y `/actuator/health`.
- **Respuestas ante accesos no autorizados:** 401 (sin token, token inválido o vencido), 403 (rol sin permiso) y 404 (recurso ajeno, para no revelar que existe). Todas con formato `ApiError` y `X-Correlation-ID`.
- **Usuarios de prueba:** `admin`/`admin123`, `propietario1`/`prop123` e `inquilino1`/`inq123`, con UUIDs fijos.
- **Comprobación:** con admin, POST `/api/propiedades` da 201. Con propietario1, GET `/api/propiedades/propias` da 200 con solo lo suyo. Sin token da 401. Con inquilino1, un POST da 403 (Gateway). Con propietario1, GET `/api/propiedades` da 403 (servicio). Con inquilino1, un contrato ajeno da 404.

---

## 11. Ejecución y despliegue

**Versiones:**

| Componente | Versión |
|---|---|
| Java | 21 (Temurin) |
| Spring Boot | 3.3.4 |
| Spring Cloud | 2023.0.3 |
| Maven | 3.9.8 (wrapper) |
| springdoc-openapi | 2.6.0 |
| PostgreSQL | 16-alpine |
| Keycloak | 24.0.5 |
| Loki / Promtail | 2.9.4 |
| Grafana | 10.4.2 |
| CI | GitHub Actions |

**Configuración por entorno:**

- Perfil por defecto: todo apunta a `localhost`.
- Perfil `docker`: usa los nombres de servicio de la red interna.
- Perfil `local`: solo para desarrollo del catálogo; baja el nivel de log de seguridad. La seguridad es la misma que en los demás perfiles (exige JWT de Keycloak).
- Las variables de entorno sobrescriben la configuración.
- Se mantiene `jwk-set-uri` (no `issuer-uri`) para que los tokens obtenidos desde el host valgan dentro de Docker.

**Arranque:**

- **Completo (demo y corrección):** `docker compose up -d --build`, que construye las imágenes multi-etapa de los 3 servicios Java y levanta todo.
- **Desarrollo:** levantar la infraestructura con docker compose, compilar con `./mvnw clean install` y arrancar catálogo, contratos y gateway, en ese orden.

---

## 12. Colección de endpoints y escenarios de prueba

**Colección Postman v2.1 en `/coleccion`**, ejecutable con Newman:

```
npx newman run coleccion/RovedaSA.postman_collection.json -e coleccion/local.postman_environment.json
```

**Carpetas:** 00 Autenticación · 01 Datos iniciales · 02 Proceso principal · 03 Excepciones y reglas · 04 Ciclo posterior · 05 Seguridad · 06 Integraciones y contingencia.

**Funcionamiento:**

- Variables encadenadas con `pm.environment.set`.
- Bearer heredado con renovación automática del token.
- Se ejecuta sobre bases limpias (`docker compose down -v`).
- Hay un perfil `demo` con el BCRA simulado (CUIT solvente, CUIT no solvente, ICL fijo y simulación de caída), porque el repo no puede usar CUITs reales.

| Grupo | Escenarios |
|---|---|
| Positivos (proceso) | E-01 tokens · E-02 personas · E-03 propiedad + GeoRef · E-04 dos borradores · E-05 seña → RESERVADA · E-06 activación → ALQUILADA, competidor cancelado y cuotas · E-07 aislamiento por rol |
| Positivos (ciclo) | E-08 mora · E-09 pago · E-10 ajuste ICL · E-11 rescisión |
| Negativos (reglas) | N-01 CUIT inválido o duplicado · N-02 dirección inexistente · N-03 roles inválidos · N-04 seña sobre propiedad no disponible · N-05 operación inválida para el estado · N-06 garante no solvente · N-07 cancelación de la seña · N-08 ajuste prematuro o repetido · N-09 finalizar antes de término · N-10 pagar una cuota anulada o pagada · N-11 transición inválida de la propiedad |
| Seguridad | S-01 401 · S-02 403 en el Gateway · S-03 403 en el servicio · S-04 404 ante recurso ajeno |
| Integraciones | I-01 GeoRef caído · I-02 BCRA deudores caído · I-03 ICL desde el caché · I-04 ICL sin caché · T-01 trazabilidad por `X-Correlation-ID` |

Las reglas que dependen del paso del tiempo (seña vencida, finalización) se cubren además con tests unitarios con un `Clock` inyectable.

---

## 13. Compromiso del equipo, desafíos, exclusiones y riesgos

**Proceso central:** la formalización de un contrato de punta a punta (P1 a P7) a través del Gateway con Keycloak real, más el ajuste por ICL y la rescisión sobre el contrato activo.

**Obligatorias (Alta):** F-01, F-02, F-04, F-05, F-07, F-08, F-09, F-10, F-11, F-12, F-13, F-14 y F-16, con las reglas RN-01 a RN-14, RN-16 y RN-17.

**Comprometidas (Media):** F-03, F-06, F-15 y F-17 (RN-15 y RN-18).

**Integraciones:** Keycloak, GeoRef AR, BCRA Central de Deudores, BCRA Estadísticas y la comunicación contratos → catálogo con token de servicio.

**Entregables:**

- Código del multi-módulo.
- `docker compose up`.
- Swagger.
- Colección Postman + Newman.
- Tests en CI.
- Logs en Grafana.

**Desafíos:**

1. **Consistencia sin transacción distribuida:** validar antes de llamar al catálogo y **compensar** (devolver la propiedad a su estado anterior) si falla el guardado local. Es un Saga simple.
2. Concurrencia por control optimista.
3. Patrón State con JPA.
4. Resiliencia ante GeoRef y BCRA.
5. Seguridad en dos capas y anti-IDOR.
6. Cálculos con BigDecimal y reloj inyectable.
7. Contenerización y trazabilidad.

**Exclusiones:** las de la sección 4.1, más el despliegue en la nube.

**Riesgos:**

- Tiempo de ms-contratos (está en etapa inicial).
- Dependencia entre módulos (Commons First).
- APIs públicas: caídas, límites de uso, cambios de versión y **certificados SSL del BCRA en Java**.
- Permisos de la cuenta de servicio en Keycloak.
- Incorporación de un integrante nuevo.
- Diferencias entre entornos (mitigadas con wrapper, imágenes fijas, `.gitattributes` y CI).

---

## 14. Comprobaciones observables

| ID | Condición inicial | Acción | Resultado esperado | Relación |
|---|---|---|---|---|
| C-01 | Admin, propietario y zonas de Córdoba cargadas | POST `/api/propiedades` con una dirección real escrita de forma informal | 201, DISPONIBLE, dirección normalizada, coordenadas, zona y canon = valor m² × factor × superficie. Con una dirección inexistente: 400. | F-04, RN-03, RN-04 |
| C-02 | Propiedad DISPONIBLE y dos contratos CREADO sobre ella | Señar el 1 y después el 2 | El 1 queda SEÑADO y la propiedad RESERVADA. El 2 se rechaza y sigue CREADO. | F-10, F-07, RN-05, RN-06 |
| C-03 | Contrato 1 SEÑADO, garante solvente, canon 500.000, frecuencia 3 | POST `/activar` | ACTIVO, propiedad ALQUILADA, contrato 2 CANCELADO y 3 cuotas PENDIENTE de 500.000 | F-11, F-08, RN-07, RN-08 |
| C-04 | Contrato SEÑADO con garante en situación ≥ 3 (perfil demo) | Activar y después cancelar | Activación rechazada (sigue SEÑADO). Luego CANCELADO y propiedad DISPONIBLE. | F-08, F-12, RN-07, RN-09 |
| C-05 | ACTIVO, canon 500.000, frecuencia 3, inicio hace más de 3 meses, ICL 10,00 → 12,50 | POST `/ajustes`, repetirlo, y repetir con el BCRA caído y el valor en caché | +25 %, canon 625.000 y 3 cuotas nuevas. La repetición se rechaza. Con el BCRA caído, el ajuste se aplica usando el caché. | F-13, RN-11, RN-12 |
| C-06 | Cuota PENDIENTE de 500.000, mora 0,1 % diario | GET cuotas con fecha de referencia vencimiento + 10 días, después pagar y volver a consultar | Mora 5.000 y total 505.000. Después del pago queda PAGADA y sin mora. | F-16, F-17, RN-13, RN-18 |
| C-07 | ACTIVO, canon 500.000, 4 meses de antigüedad, cuotas futuras | POST `/rescindir` | RESCINDIDO, penalización 750.000, cuotas futuras ANULADAS y propiedad DISPONIBLE. Una segunda rescisión se rechaza. | F-14, RN-10, RN-14 |
| C-08 | Dos contratos de inquilinos distintos y tokens de los 3 usuarios | (a) listar con inquilino1 · (b) pedir el contrato ajeno · (c) POST con inquilino1 · (d) GET general con propietario1 · (e) sin token | (a) 200 solo lo suyo · (b) 404 · (c) 403 Gateway · (d) 403 servicio · (e) 401 | F-01, F-05, F-16, RN-16, RN-17 |

**Convenciones que fijan estas comprobaciones:**

- `porcentajeMoraDiaria = 0.1` significa 0,1 % y se divide por 100.
- Si no hay valor publicado del ICL para la fecha exacta, se toma el último valor publicado anterior a esa fecha.

---

## 15. Pendientes para cumplir el alcance

Estado al 03/11/2026. Ir tachando a medida que se resuelven.

### Infraestructura y Gateway (Dev 1)
- [x] Asignar el rol ADMINISTRADOR a la cuenta de servicio `service-account-backend-services` en Keycloak y reexportar `realm-export.json`. **Sin esto, ms-contratos recibe 403 al cambiar el estado de las propiedades.**
- [x] `SecurityWebFilterChain` del Gateway: POST/PUT/PATCH/DELETE solo para ADMINISTRADOR.
- [x] Dockerfiles multi-etapa de `api-gateway`, `ms-catalogo-inmobiliario` y `ms-contratos`, y agregarlos a `docker-compose.yml` con `depends_on` + `service_healthy`. Sin esto, Promtail no recolecta los logs de los servicios.
- [x] `application-docker.yml` en cada servicio (hostnames de la red Docker en lugar de `localhost`). Mantener `jwk-set-uri`.

### Catálogo (Dev 2)
- [x] `POST /api/personas/{id}/evaluacion-solvencia`, con el cliente BCRA Central de Deudores (coordinar con Dev 4).
- [x] `PUT /api/propiedades/{id}`.
- [x] `PATCH /api/propiedades/{id}/baja`.
- [ ] Opcional: devolver 409 en lugar de 400 ante transiciones de estado inválidas.

### Contratos (Dev 3 y Esteban — división de tareas a definir)
- [ ] Guardar `propietario_keycloak_id` e `inquilino_keycloak_id` en el contrato al crearlo (obtenidos de `GET /api/personas/{id}`).
- [ ] Un único `GET /api/contratos` que filtre según el rol (no implementar `/mis-contratos` ni `/propietario`).
- [ ] Cuota con estado `PAGADA` y `fecha_pago`, y `PATCH /api/cuotas/{id}/pago`.
- [ ] Inyectar `java.time.Clock` (no usar `LocalDate.now()` directo).
- [ ] Compensación si falla el guardado local después de llamar al catálogo.
- [ ] Mover `EstadoContratoEnum`, `EstadoCuotaEnum` y `CuotaResumenDTO` de commons a ms-contratos, y agregar `PAGADA` a `EstadoCuotaEnum`.
- [ ] Sugerencia de división (no acordada): Dev 3 se ocupa de Contrato, el patrón State, las transiciones y la integración con el catálogo. Esteban se ocupa de Cuota (generación, mora, pago y anulación), las consultas filtradas por rol y el `Clock`.

### BCRA, ICL y QA (Dev 4)
- [ ] Clientes BCRA Central de Deudores y Estadísticas (probar temprano los certificados SSL).
- [ ] Perfil `demo` con el BCRA simulado.
- [ ] Caché del ICL y regla de "último valor publicado anterior".
- [ ] Colección Postman en `/coleccion` (migrar desde `ms-catalogo-inmobiliario/http/catalogo.http`) con tests y Newman.

### Equipo
- [ ] Completar el apellido de Esteban (sección 1).
- [ ] Confirmar que el repositorio sea visible para la cátedra.
