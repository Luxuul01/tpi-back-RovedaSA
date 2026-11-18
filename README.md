# TPI Backend — Alquileres Roveda SA

Sistema Backend de Administración de Alquileres Inmobiliarios basado en microservicios, desarrollado para el **Trabajo Práctico Integrador 2026** (Backend de Aplicaciones — UTN FRC).

---

## 📚 Documentación del Proyecto

Toda la documentación teórica, de negocio, diseño y organización del equipo se encuentra versionada dentro de la carpeta [`/docs`](./docs/):

* 📌 **[Alcance Comprometido con la Cátedra](./docs/Alcance.md)**: Fuente de verdad del proyecto. Consolida lo declarado en el formulario de propuesta (funcionalidades, reglas, endpoints, persistencia, seguridad, pruebas y comprobaciones) y la lista de pendientes. Ante cualquier diferencia con otro documento, prevalece este.
* 📄 **[Especificación del Dominio y Reglas de Negocio](./docs/Dominio.md)**: Descripción detallada del problema, servicios, bases de datos por servicio, APIs externas, roles de acceso (aislamiento estricto para Inquilinos y Propietarios), Casos de Uso y Requerimientos No Funcionales (ISO 25010).
* 📐 **[Diagramas de Arquitectura — Entrega Final (Implementado)](./docs/Diagramas-EntregaFinal.md)**: Refleja la arquitectura y el modelo efectivamente implementados (Contexto C1, Contenedores C2, Clases del Dominio, DER y Máquina de Estados del Contrato `Creado` $\rightarrow$ `Señado` $\rightarrow$ `Activo` $\rightarrow$ `Cancelado` / `Rescindido` / `Finalizado`).
* 🗂️ **[Diagramas de Arquitectura — 1ra Entrega (Diseño Inicial)](./docs/Diagramas.md)**: Conserva el diseño PlantUML original de la 1ra Entrega para trazabilidad histórica y comparación frente a `Diagramas-EntregaFinal.md`.
* 🔁 **[Proceso Principal](./docs/ProcesoPrincipal.md)**: Formalización de un contrato de punta a punta (P1 a P7) a través del Gateway, con la Saga de compensación sobre el estado de la propiedad.
* 🏦 **[Integración con el BCRA y Resiliencia](./docs/BCRA-Resiliencia.md)**: Clientes de la Central de Deudores y de Estadísticas (ICL/IPC), timeouts, caché de índices y comportamiento ante caídas (503).
* 🔐 **[Configuración de Keycloak](./docs/ConfiguracionKeycloak.md)**: Realm, usuarios de prueba, clientes OIDC (incluida la cuenta de servicio `backend-services`) y cómo obtener tokens.
* 👥 **[Plan de Trabajo y Flujos de GitHub](./docs/PlanDeTrabajo.md)**: Matriz de responsabilidades de los 5 integrantes, temas de defensa oral, pautas de trabajo en Git (ramas, Commons First, PRs con Squash) y guía de resolución de emergencias.

---

## 🏗️ Arquitectura de la Solución

El sistema se compone de una arquitectura desacoplada en microservicios, punto de entrada único por API Gateway y aislamiento estricto de base de datos por servicio (*Database per Service*):

```
                                  ┌────────────────────────┐
                                  │   Keycloak IdP (8083)  │
                                  │   (OAuth2 / OIDC JWT)  │
                                  └───────────▲────────────┘
                                              │ Valida JWT
┌───────────────────────┐         ┌───────────┴────────────┐
│   Clientes / Front    ├────────►│   API Gateway (8080)   │
└───────────────────────┘         │ (Spring Cloud Gateway) │
                                  └─────┬────────────┬─────┘
                     /api/propiedades/**│            │/api/contratos/**
                     /api/personas/**   │            │/api/cuotas/**
                                        │            │/api/indices/**
                                        ▼            ▼
                   ┌────────────────────────┐   ┌────────────────────────┐
                   │ ms-catalogo-inmob (8081│   │   ms-contratos (8082)  │
                   │ (Propiedades, Personas)│◄──┤ (Contratos, Patrón     │
                   └───────────┬────────────┘   │  State, Aumento ICL)   │
                               │                └───────────┬────────────┘
                               ▼ RestClient                 ▼ RestClient
                   ┌────────────────────────┐   ┌────────────────────────┐
                   │     GeoRef AR API      │   │    BCRA API Pública    │
                   │ (Normaliza direcciones)│   │ (Deudores + Índice ICL)│
                   └────────────────────────┘   └────────────────────────┘
```

* **`api-gateway` (Puerto 8080)**: Enrutamiento perimetral (`/api/propiedades/**`, `/api/personas/**`, `/api/contratos/**`, `/api/cuotas/**`, `/api/indices/**`), validación de firma criptográfica JWT contra Keycloak (JWKS), bloqueo de `POST`/`PUT`/`PATCH`/`DELETE` para roles que no son `ADMINISTRADOR`, encabezado `X-Correlation-ID`, **CORS global** y **Swagger UI unificado** en `http://localhost:8080/swagger-ui.html`. El header `Authorization` del cliente pasa tal cual al microservicio (no hay un filtro `TokenRelay`: el Gateway es Resource Server, no cliente OAuth2), y cada servicio vuelve a validar el token. Sus propios errores salen con el mismo `ApiError` que los servicios: 401 (con `WWW-Authenticate: Bearer`), 403, 404 (ruta inexistente), 503 (servicio caído) y 504 (timeout de 20 s).
* **`ms-catalogo-inmobiliario` (Puerto 8081)**: Gestión de inmuebles, cálculo de valor de mercado por $m^2$ e integración con GeoRef AR. Base de datos `catalogo_db` (contenedor `db-catalogo`, PostgreSQL publicado en el puerto 5432 del host).
* **`ms-contratos` (Puerto 8082)**: Gestión del ciclo de vida contractual con **Patrón State** (`Creado`, `Señado`, `Activo`, `Cancelado`, `Rescindido`, `Finalizado`), indexación por ICL y resiliencia. Base de datos `contratos_db` (contenedor `db-contratos`, PostgreSQL publicado en el puerto 5433 del host). Llama al catálogo con el **token de la cuenta de servicio** `backend-services` (client credentials contra Keycloak, cacheado hasta poco antes de vencer), no con el token del usuario.
* **`inmobiliaria-commons`**: Artefacto Maven compartido con DTOs de comunicación inter-servicio, enums, `X-Correlation-ID` y manejo unificado de excepciones (`ApiError`).

Los puertos son los publicados en el host por defecto; todos se pueden cambiar (ver [Variables de entorno](#variables-de-entorno)).

---

## 👥 Organización del Equipo y Roles

| Integrante | Rol y Módulo | Responsabilidad Principal |
| :--- | :--- | :--- |
| **Dev 1** — Luciano Agüero | **Tech Lead & Gateway** | Gateway, seguridad perimetral JWT, Docker Compose, Keycloak Realm y estructura base de `inmobiliaria-commons`. |
| **Dev 2** — Levi Angelino | **Catálogo Inmobiliario** | Módulo `ms-catalogo-inmobiliario`, base `catalogo_db`, integración con GeoRef AR y filtro por rol Propietario. |
| **Dev 3** — Santino Brenna | **Contratos & Patrón State** | Módulo `ms-contratos`, base `contratos_db`, implementación del Patrón State y orquestación de señas y alquileres. |
| **Dev 4** — Lautaro Roveda | **BCRA, ICL & QA/Demo** | Clientes BCRA (Central de Deudores e ICL), resiliencia local `IndiceCache`, ajuste periódico y suite E2E en Postman. |
| **Dev 5** — Esteban | **Contratos (junto a Dev 3)** | Módulo `ms-contratos`. La división de tareas con Dev 3 está a definir (ver [Alcance §15](./docs/Alcance.md#15-pendientes-para-cumplir-el-alcance)). |

---

## 🚀 Puesta en Marcha Local

### Prerrequisitos
* **Docker y Docker Compose** (suficiente para levantar toda la solución contenerizada).
* **Java 21** (JDK Temurin o Corretto) y **Maven 3.9+** / `./mvnw` (solo si se desea compilar o ejecutar los microservicios Java fuera de Docker).

### Opción 1 (Recomendada): Levantar la Solución Integral con Docker Compose
Desde la raíz del repositorio:
```bash
docker compose up -d --build
```
Este comando construye y levanta la solución completa (bases de datos, Keycloak, los 3 microservicios Java + API Gateway y el stack PLG de observabilidad):
* `tpi-api-gateway`: API Gateway en `http://localhost:8080` — **Swagger UI unificado** en `http://localhost:8080/swagger-ui.html`.
* `tpi-ms-catalogo`: `ms-catalogo-inmobiliario` en `http://localhost:8081`.
* `tpi-ms-contratos`: `ms-contratos` en `http://localhost:8082`.
* `tpi-keycloak`: Keycloak en `http://localhost:8083` (Realm `rovedasa-realm` pre-configurado).
* `tpi-db-catalogo`: PostgreSQL en `localhost:5432` (`catalogo_db`).
* `tpi-db-contratos`: PostgreSQL en `localhost:5433` (`contratos_db`).
* `tpi-loki`: Motor de almacenamiento de logs en `localhost:3100`.
* `tpi-promtail`: Recolector de logs de Docker conectado a `/var/run/docker.sock`.
* `tpi-grafana`: Tablero web de observabilidad en `http://localhost:3000` (User: `admin` / Pass: `admin`).

No hace falta configurar nada: las contraseñas, el secreto de la cuenta de servicio y los puertos tienen valores de demo por defecto. Si algún puerto ya está ocupado en tu máquina (por ejemplo, un PostgreSQL local en el 5432), cambialo con las [variables de entorno](#variables-de-entorno).

### Opción 2 (Alternativa Local): Ejecutar los Servicios Java sin Contenerizar
Si preferís ejecutar los microservicios Java directamente desde tu IDE o consola (requiere **Java 21**):

1. Levantá únicamente la infraestructura auxiliar (bases de datos, Keycloak y observabilidad):
   ```bash
   docker compose up -d db-catalogo db-contratos keycloak loki promtail grafana
   ```
2. Compilá e instalá el proyecto multi-módulo desde la raíz del repositorio:
   ```bash
   ./mvnw clean install
   ```
3. Ejecutá cada servicio en una terminal independiente. ms-contratos necesita el secreto de la cuenta de servicio para llamar al catálogo (sin él, esas llamadas responden 503 indicando que falta `KEYCLOAK_CLIENT_SECRET`):
   ```bash
   # Terminal 1: Catálogo
   ./mvnw -pl ms-catalogo-inmobiliario spring-boot:run

   # Terminal 2: Contratos (bash)
   export KEYCLOAK_CLIENT_SECRET=backend-secret-rovedasa-2026
   ./mvnw -pl ms-contratos spring-boot:run
   # Terminal 2: Contratos (PowerShell)
   $env:KEYCLOAK_CLIENT_SECRET="backend-secret-rovedasa-2026"; ./mvnw -pl ms-contratos spring-boot:run

   # Terminal 3: Gateway
   ./mvnw -pl api-gateway spring-boot:run
   ```
   Fuera de Docker los servicios usan el perfil por defecto (todo en `localhost`). Si cambiaste puertos o contraseñas en el `.env`, exportá las mismas variables en estas terminales: Spring no lee el `.env`. Para ver el SQL y los logs en `DEBUG`, agregá `-Dspring-boot.run.profiles=local` (la seguridad es la misma en todos los perfiles).

---

<a id="variables-de-entorno"></a>

## ⚙️ Variables de entorno

Todas son opcionales. `docker-compose.yml` usa `${VARIABLE:-valor_demo}`, así que sin configurar nada se levanta con los valores de la tabla. Para cambiarlos, copiá [`.env.example`](./.env.example) a `.env` (Docker Compose lo lee solo; está en `.gitignore`) y editá lo que necesites.

| Variable | Valor por defecto | Para qué sirve |
| :--- | :--- | :--- |
| `CATALOGO_DB_PASSWORD` | `postgrespassword` | Contraseña de `catalogo_db` (la usan `db-catalogo` y `ms-catalogo-inmobiliario`). |
| `CONTRATOS_DB_PASSWORD` | `postgrespassword` | Contraseña de `contratos_db` (la usan `db-contratos` y `ms-contratos`). |
| `KEYCLOAK_CLIENT_SECRET` | `backend-secret-rovedasa-2026` | Secreto del cliente `backend-services`. Keycloak lo carga en el realm al importarlo y ms-contratos lo usa para pedir el token de servicio. |
| `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` | `admin` / `admin` | Administrador de la consola de Keycloak (`http://localhost:8083/admin`). |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | `admin` / `admin` | Administrador de Grafana. |
| `GATEWAY_PORT` | `8080` | Puerto del host del API Gateway. |
| `CATALOGO_PORT` / `CONTRATOS_PORT` | `8081` / `8082` | Puertos del host de los microservicios. |
| `KEYCLOAK_PORT` | `8083` | Puerto del host de Keycloak (también lo usan los servicios fuera de Docker). |
| `DB_CATALOGO_PORT` / `DB_CONTRATOS_PORT` | `5432` / `5433` | Puertos del host de las dos bases. |
| `GRAFANA_PORT` / `LOKI_PORT` | `3000` / `3100` | Puertos del host de Grafana y Loki. |
| `PERFIL_SERVICIOS` | `docker` | Perfil de Spring de los dos microservicios (`docker,demo` para el BCRA simulado). |

Notas:
* En el perfil `docker` las contraseñas y el secreto **no tienen valor por defecto en el código**: los pasa el compose. En el perfil por defecto (servicios corriendo en el host) las contraseñas de las bases conservan el valor de demo como default para que `spring-boot:run` arranque sin configurar nada; el secreto del cliente no (ver el paso 3 de la Opción 2).
* PostgreSQL toma la contraseña solo al crear el volumen: para cambiarla en una base ya creada, `docker compose down -v`.
* Si cambiás `KEYCLOAK_PORT`, los tokens se piden en `http://localhost:<puerto>/realms/rovedasa-realm/protocol/openid-connect/token` y hay que actualizar `keycloakUrl` en `coleccion/local.postman_environment.json`.

---

## 🔑 Usuarios y token

El realm `rovedasa-realm` se importa al levantar Keycloak con estos usuarios de prueba (UUIDs fijos; detalle en [Configuración de Keycloak](./docs/ConfiguracionKeycloak.md)):

| Usuario | Contraseña | Rol | Puede |
| :--- | :--- | :--- | :--- |
| `admin` | `admin123` | `ADMINISTRADOR` | Leer y escribir todo. |
| `propietario1` | `prop123` | `PROPIETARIO` | Solo `GET` de sus propiedades y contratos. |
| `inquilino1` | `inq123` | `INQUILINO` | Solo `GET` de sus contratos y cuotas. |

Además existe la cuenta de servicio `service-account-backend-services` (rol `ADMINISTRADOR`), que no inicia sesión: la usa ms-contratos con client credentials para llamar al catálogo.

**Obtener un token** (cliente público `rovedasa-backend`, password grant):
```bash
# bash
curl -s -X POST "http://localhost:8083/realms/rovedasa-realm/protocol/openid-connect/token" \
  -d "grant_type=password" -d "client_id=rovedasa-backend" \
  -d "username=admin" -d "password=admin123" | jq -r .access_token
```
```powershell
# PowerShell
$token = (Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8083/realms/rovedasa-realm/protocol/openid-connect/token" `
  -Body @{ grant_type = "password"; client_id = "rovedasa-backend"; username = "admin"; password = "admin123" }).access_token
$token
```
(Sin `jq`, copiá el valor de `access_token` de la respuesta.) El token dura 5 minutos.

**Usarlo en Swagger UI:** abrir `http://localhost:8080/swagger-ui.html`, elegir el servicio arriba a la derecha, botón **Authorize**, pegar el `access_token` (sin la palabra `Bearer`) y **Authorize**. Desde la consola: `curl -H "Authorization: Bearer $token" http://localhost:8080/api/propiedades`.

Sin token cualquier endpoint de `/api/**` responde 401; con un rol sin permiso, 403 (ambos con `ApiError` y `correlationId`).

---

## 🧪 Perfil `demo` y Colección Postman

### Perfil `demo` (BCRA simulado)
El repositorio no puede usar CUITs de personas reales, así que para probar los escenarios del BCRA (garante no solvente, ICL fijo y caídas) los microservicios tienen un perfil `demo` que reemplaza las APIs del BCRA por un simulador (Alcance, sección 12). Fuera de ese perfil se usan las APIs reales.

| Simulación | Comportamiento en el perfil `demo` |
| :--- | :--- |
| Central de Deudores | `20888888811`: situación 3 (no solvente) · `20888888838`: 2 cheques rechazados (no solvente) · `20888888846`: situación 2 (solvente) · cualquier otro CUIT: sin registros (solvente) |
| Estadísticas (ICL) | 10,00 antes del 01/08/2026 y 12,50 desde esa fecha: un ajuste del 01/07/2026 al 01/10/2026 da +25 % (C-05) |
| Estadísticas (IPC) | 2 % mensual; el IPC de cada mes se publica el día 15 del mes siguiente (según el reloj del servicio): por ejemplo, el de agosto 2026 está disponible desde el 15/09/2026 |
| Caídas simuladas | `PUT /api/demo/catalogo/simulacion?bcraCaido=true&georefCaido=true` y `PUT /api/demo/contratos/simulacion?bcraCaido=true` (rol ADMINISTRADOR; `false` para volver) |

GeoRef sigue siendo el real en el perfil `demo`; solo se puede simular su caída. Los CUITs de prueba usan DNIs de un rango no asignado (88.888.88x).

Levantar con Docker Compose usando el perfil `demo`:
```bash
# bash
PERFIL_SERVICIOS=docker,demo docker compose up -d --build
# PowerShell
$env:PERFIL_SERVICIOS="docker,demo"; docker compose up -d --build
```
Sin Docker para los servicios Java: `./mvnw -pl ms-catalogo-inmobiliario spring-boot:run -Dspring-boot.run.profiles=demo` (igual para `ms-contratos`).

### Colección Postman (`/coleccion`)
Cubre los escenarios de la sección 12 del Alcance y las comprobaciones C-01 a C-08, en las carpetas 00 Autenticación · 01 Datos iniciales · 02 Proceso principal · 03 Excepciones y reglas · 04 Ciclo posterior · 05 Seguridad · 06 Integraciones y contingencia. Los tokens de los 3 usuarios se renuevan solos.

Se ejecuta sobre **bases limpias** y con el **perfil `demo`**:
```bash
docker compose down -v
PERFIL_SERVICIOS=docker,demo docker compose up -d --build
npx newman run coleccion/RovedaSA.postman_collection.json -e coleccion/local.postman_environment.json
```

**Fechas.** Las fechas de la colección son relativas al día en que se corre (pre-request de la colección), así que da lo mismo cualquier día después del 15/09/2026. Las únicas fijas dependen del simulador del perfil `demo`, que usa fechas absolutas: el ICL cambia de 10,00 a 12,50 el 01/08/2026 (C-05 e I-03 usan la ventana 01/07/2026 → 01/10/2026) y el IPC de cada mes se publica el día 15 del mes siguiente (el adicional por IPC usa junio, julio y agosto de 2026). El resultado esperado del ajuste periódico (contrato J, con inicio 10 meses antes de hoy) se calcula en el test a partir de esas fechas.

Para probar la colección como si fuera otro día, los servicios aceptan la propiedad `app.reloj.fecha-fija` (solo para pruebas; sin ella se usa el reloj del sistema) y la colección la variable `hoyFijo`, con la misma fecha:
```bash
java -Dapp.reloj.fecha-fija=2027-03-15 -Dspring.profiles.active=demo -jar ms-contratos/target/ms-contratos-1.0.0-SNAPSHOT.jar   # idem catálogo
npx newman run coleccion/RovedaSA.postman_collection.json -e coleccion/local.postman_environment.json --env-var hoyFijo=2027-03-15
```

---

## 📊 Centralización de Logs y Observabilidad (Stack PLG)

El ecosistema implementa el pipeline de 4 etapas recomendado por la cátedra (**Apunte 24**) bajo el principio *12-Factor App*:

| Etapa | Tecnología | Rol en la Arquitectura |
| :--- | :--- | :--- |
| **1. Generación** | `stdout` + archivos | Los servicios Java escriben con `logback-spring.xml` (formato con `[%X{correlationId}]`) a la consola estándar, que es lo que recolecta Promtail, y además a archivos rotativos en `./logs` (`/app/logs` en los contenedores, volúmenes `logs_catalogo`, `logs_contratos` y `logs_gateway`): `<servicio>-service.log` y `<servicio>-service-error.log` (solo `ERROR`). |
| **2. Recolección** | **Promtail** | Agente liviano montado sobre `/var/run/docker.sock` que recolecta los flujos de los contenedores y los etiqueta automáticamente. |
| **3. Almacenamiento** | **Loki** | Almacena y comprime los logs indexados por etiquetas (*labels*) en el volumen persistente `loki_data` (asegurando persistencia ante caídas de contenedores). |
| **4. Visualización** | **Grafana** | Interfaz web en `http://localhost:3000` (credenciales `admin` / `admin`) con datasource Loki preconfigurado. |

### Consultas útiles en Grafana (Explore con LogQL)
* Ver logs de un contenedor específico:
  `{container="tpi-api-gateway"}`
* Seguir la traza completa de una operación a través de todos los microservicios usando el Correlation ID (el que devuelve el header `X-Correlation-ID` o el campo `correlationId` de un error):
  `{container=~"tpi-.*"} |= "req-a1b2c3d4-uuid"`

El `X-Correlation-ID` que manda el cliente se respeta solo si tiene entre 1 y 64 caracteres `A-Z a-z 0-9 . _ -`; si no, el Gateway (y cada servicio) genera uno nuevo con prefijo `req-`.
* Filtrar únicamente errores críticos:
  `{container=~"tpi-.*"} |= "ERROR"`

---

## 🔄 Flujo de Trabajo en GitHub (Anti-Conflictos)

1. **Ramas Separadas**:
   * `main`: Solo entregas estables. Prohibido hacer push directo.
   * `develop`: Rama integradora.
   * `feature/issue-1-infra-gateway` (Dev 1).
   * `feature/catalogo-solvencia-garante` (Dev 2).
   * `feature/contratos-estados` (Dev 3 / Dev 5).
   * `feature/bcra-resiliencia` y `fix/bcra-alcance` (Dev 4).
2. **Ritual Diario de Sincronización**:
   Antes de programar cada día:
   ```bash
   git checkout develop && git pull origin develop
   git checkout feature/mi-rama && git merge develop
   ```
3. **Protocolo Commons First**: Cualquier cambio en DTOs de `inmobiliaria-commons` se avisa al equipo, se aprueba en un PR express a `develop` y todos actualizan sus ramas.
4. **Pull Requests**:
   * Requiere compilación local limpia (`./mvnw clean install`).
   * Al menos 1 aprobación (Review) de un compañero.
   * Merge con opción **"Squash and Merge"**.

