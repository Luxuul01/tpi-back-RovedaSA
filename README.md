# TPI Backend — Alquileres Roveda SA

Sistema Backend de Administración de Alquileres Inmobiliarios basado en microservicios, desarrollado para el **Trabajo Práctico Integrador 2026** (Backend de Aplicaciones / Diseño de Sistemas de Información — UTN FRC).

---

## 🏗️ Arquitectura de la Solución

El sistema se compone de una arquitectura desacoplada en microservicios, punto de entrada único por API Gateway y aislamiento estricto de base de datos por servicio (*Database per Service*):

```
                                  ┌────────────────────────┐
                                  │   Keycloak IdP (8080)  │
                                  │   (OAuth2 / OIDC JWT)  │
                                  └───────────▲────────────┘
                                              │ Valida JWT
┌───────────────────────┐         ┌───────────┴────────────┐
│   Clientes / Front    ├────────►│   API Gateway (8080)   │
└───────────────────────┘         │ (Spring Cloud Gateway) │
                                  └─────┬────────────┬─────┘
                     /api/propiedades/**│            │/api/contratos/**
                     /api/personas/**   │            │
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

* **`api-gateway` (Puerto 8080)**: Enrutamiento perimetral, validación de firma criptográfica JWT contra Keycloak, inyección de `TokenRelay` y encabezado `X-Correlation-ID`.
* **`ms-catalogo-inmobiliario` (Puerto 8081)**: Gestión de inmuebles, cálculo de valor de mercado por $m^2$ e integración con GeoRef AR. Base de datos `db_catalogo` (PostgreSQL en puerto 5432).
* **`ms-contratos` (Puerto 8082)**: Gestión del ciclo de vida contractual con **Patrón State** (`Creado`, `Señado`, `Activo`, `Cancelado`, `Rescindido`, `Finalizado`), indexación por ICL y resiliencia. Base de datos `db_contratos` (PostgreSQL en puerto 5433).
* **`inmobiliaria-commons`**: Artefacto Maven compartido con DTOs de comunicación inter-servicio, enums y manejo unificado de excepciones (`ApiError`).

---

## 👥 Organización del Equipo y Roles

| Integrante | Rol y Módulo | Responsabilidad Principal |
| :--- | :--- | :--- |
| **Dev 1** | **Tech Lead & Gateway** | Gateway, seguridad perimetral JWT, Docker Compose, Keycloak Realm y estructura base de `inmobiliaria-commons`. |
| **Dev 2** | **Catálogo Inmobiliario** | Módulo `ms-catalogo-inmobiliario`, base `db_catalogo`, integración con GeoRef AR y filtro por rol Propietario. |
| **Dev 3** | **Contratos & Patrón State** | Módulo `ms-contratos`, base `db_contratos`, implementación del Patrón State y orquestación de señas y alquileres. |
| **Dev 4** | **BCRA, ICL & QA/Demo** | Clientes BCRA (Central de Deudores e ICL), resiliencia local `IndiceIclCache`, ajuste periódico y suite E2E en Postman. |

---

## 🚀 Puesta en Marcha Local

### Prerrequisitos
* **Java 21** (JDK Temurin o Corretto).
* **Maven 3.9+**.
* **Docker y Docker Compose**.

### 1. Levantar Infraestructura Auxiliar (Bases de Datos y Keycloak)
```bash
docker compose up -d
```
Esto iniciará:
* `tpi-db-catalogo`: PostgreSQL en `localhost:5432` (`catalogo_db`).
* `tpi-db-contratos`: PostgreSQL en `localhost:5433` (`contratos_db`).
* `tpi-keycloak`: Keycloak en `localhost:8080` (Realm `rovedasa-realm` pre-configurado).

### 2. Compilar e Instalar el Proyecto
Desde la raíz del repositorio:
```bash
mvn clean install
```

### 3. Ejecutar los Microservicios
Podés iniciarlos desde tu IDE o por consola:
```bash
# Terminal 1: Catálogo
cd ms-catalogo-inmobiliario && mvn spring-boot:run

# Terminal 2: Contratos
cd ms-contratos && mvn spring-boot:run

# Terminal 3: Gateway
cd api-gateway && mvn spring-boot:run
```

---

## 🔄 Flujo de Trabajo en GitHub (Anti-Conflictos)

1. **Ramas Separadas**:
   * `main`: Solo entregas estables. Prohibido hacer push directo.
   * `develop`: Rama integradora.
   * `feature/gateway-security` (Dev 1).
   * `feature/catalogo-georef` (Dev 2).
   * `feature/contratos-state` (Dev 3).
   * `feature/bcra-resiliencia` (Dev 4).
2. **Ritual Diario de Sincronización**:
   Antes de programar cada día:
   ```bash
   git checkout develop && git pull origin develop
   git checkout feature/mi-rama && git merge develop
   ```
3. **Protocolo Commons First**: Cualquier cambio en DTOs de `inmobiliaria-commons` se avisa al equipo, se aprueba en un PR express a `develop` y todos actualizan sus ramas.
4. **Pull Requests**:
   * Requiere compilación local limpia (`mvn clean install`).
   * Al menos 1 aprobación (Review) de un compañero.
   * Merge con opción **"Squash and Merge"**.
