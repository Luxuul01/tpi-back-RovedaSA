# TPI Backend — Alquileres Roveda SA

Sistema Backend de Administración de Alquileres Inmobiliarios basado en microservicios, desarrollado para el **Trabajo Práctico Integrador 2026** (Backend de Aplicaciones / Diseño de Sistemas de Información — UTN FRC).

---

## 📚 Documentación del Proyecto

Toda la documentación teórica, de negocio, diseño y organización del equipo se encuentra versionada dentro de la carpeta [`/docs`](./docs/):

* 📌 **[Alcance Comprometido con la Cátedra](./docs/Alcance.md)**: Fuente de verdad del proyecto. Consolida lo declarado en el formulario de propuesta (funcionalidades, reglas, endpoints, persistencia, seguridad, pruebas y comprobaciones) y la lista de pendientes. Ante cualquier diferencia con otro documento, prevalece este.
* 📄 **[Especificación del Dominio y Reglas de Negocio](./docs/Dominio.md)**: Descripción detallada del problema, servicios, bases de datos por servicio, APIs externas, roles de acceso (aislamiento estricto para Inquilinos y Propietarios), Casos de Uso (DSI) y Requerimientos No Funcionales (ISO 25010).
* 📐 **[Diagramas de Arquitectura — Entrega Final (Implementado)](./docs/Diagramas-EntregaFinal.md)**: Refleja la arquitectura y el modelo efectivamente implementados (Contexto C1, Contenedores C2, Clases del Dominio, DER y Máquina de Estados del Contrato `Creado` $\rightarrow$ `Señado` $\rightarrow$ `Activo` $\rightarrow$ `Cancelado` / `Rescindido` / `Finalizado`).
* 🗂️ **[Diagramas de Arquitectura — 1ra Entrega (Diseño Inicial)](./docs/Diagramas.md)**: Conserva el diseño PlantUML original de la 1ra Entrega para trazabilidad histórica y comparación frente a `Diagramas-EntregaFinal.md`.
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

* **`api-gateway` (Puerto 8080)**: Enrutamiento perimetral (`/api/propiedades/**`, `/api/personas/**`, `/api/contratos/**`, `/api/cuotas/**`, `/api/indices/**`), validación de firma criptográfica JWT contra Keycloak, inyección de `TokenRelay`, encabezado `X-Correlation-ID`, **CORS global** y **Swagger UI unificado** en `http://localhost:8080/swagger-ui.html`.
* **`ms-catalogo-inmobiliario` (Puerto 8081)**: Gestión de inmuebles, cálculo de valor de mercado por $m^2$ e integración con GeoRef AR. Base de datos `db_catalogo` (PostgreSQL en puerto 5432).
* **`ms-contratos` (Puerto 8082)**: Gestión del ciclo de vida contractual con **Patrón State** (`Creado`, `Señado`, `Activo`, `Cancelado`, `Rescindido`, `Finalizado`), indexación por ICL y resiliencia. Base de datos `db_contratos` (PostgreSQL en puerto 5433).
* **`inmobiliaria-commons`**: Artefacto Maven compartido con DTOs de comunicación inter-servicio, enums y manejo unificado de excepciones (`ApiError`).

---

## 👥 Organización del Equipo y Roles

| Integrante | Rol y Módulo | Responsabilidad Principal |
| :--- | :--- | :--- |
| **Dev 1** — Luciano Agüero | **Tech Lead & Gateway** | Gateway, seguridad perimetral JWT, Docker Compose, Keycloak Realm y estructura base de `inmobiliaria-commons`. |
| **Dev 2** — Levi Angelino | **Catálogo Inmobiliario** | Módulo `ms-catalogo-inmobiliario`, base `db_catalogo`, integración con GeoRef AR y filtro por rol Propietario. |
| **Dev 3** — Santino Brenna | **Contratos & Patrón State** | Módulo `ms-contratos`, base `db_contratos`, implementación del Patrón State y orquestación de señas y alquileres. |
| **Dev 4** — Lautaro Roveda | **BCRA, ICL & QA/Demo** | Clientes BCRA (Central de Deudores e ICL), resiliencia local `IndiceIclCache`, ajuste periódico y suite E2E en Postman. |
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
3. Ejecutá cada servicio en una terminal independiente:
   ```bash
   # Terminal 1: Catálogo
   ./mvnw -pl ms-catalogo-inmobiliario spring-boot:run

   # Terminal 2: Contratos
   ./mvnw -pl ms-contratos spring-boot:run

   # Terminal 3: Gateway
   ./mvnw -pl api-gateway spring-boot:run
   ```

---

## 📊 Centralización de Logs y Observabilidad (Stack PLG)

El ecosistema implementa el pipeline de 4 etapas recomendado por la cátedra (**Apunte 24**) bajo el principio *12-Factor App*:

| Etapa | Tecnología | Rol en la Arquitectura |
| :--- | :--- | :--- |
| **1. Generación** | `stdout / stderr` | Los microservicios Java no guardan logs en servidores remotos; escriben a consola estándar con `logback-spring.xml` formateando el `[%X{correlationId}]`. |
| **2. Recolección** | **Promtail** | Agente liviano montado sobre `/var/run/docker.sock` que recolecta los flujos de los contenedores y los etiqueta automáticamente. |
| **3. Almacenamiento** | **Loki** | Almacena y comprime los logs indexados por etiquetas (*labels*) en el volumen persistente `loki_data` (asegurando persistencia ante caídas de contenedores). |
| **4. Visualización** | **Grafana** | Interfaz web en `http://localhost:3000` (credenciales `admin` / `admin`) con datasource Loki preconfigurado. |

### Consultas útiles en Grafana (Explore con LogQL)
* Ver logs de un contenedor específico:
  `{container="tpi-api-gateway"}`
* Seguir la traza completa de una operación a través de todos los microservicios usando el Correlation ID:
  `{container=~"tpi-.*"} |= "a1b2-c3d4-uuid"`
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

