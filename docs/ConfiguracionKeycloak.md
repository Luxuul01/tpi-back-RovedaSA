# 🔐 Configuración y Guía de Uso de Keycloak — Alquileres Roveda SA

> **Ubicación del manifiesto:** `keycloak/realm-export.json`  
> **Puerto del contenedor:** `8083:8080` (Mapeado a `8083` en el host para no colisionar con el `api-gateway` en el `8080`)  
> **Comando de arranque:** `docker compose up -d keycloak` (auto-importa el realm al iniciar)

---

## 1. Arquitectura de Identidad y Acceso

En nuestra arquitectura de microservicios:
- **Las bases de datos PostgreSQL no almacenan contraseñas.**
- **Keycloak** es el único *Identity Provider (IdP)* y *Authorization Server* (OIDC / OAuth 2.0).
- Corre en un contenedor local e independiente dentro de la red Docker (`tpi-network`). **No depende de ningún servidor externo de la facultad**, garantizando que el sistema sea 100% reproducible en cualquier computadora o en la mesa de examen final.
- Emite tokens **JWT firmados con RS256 (clave asimétrica)**. Los microservicios (`api-gateway`, `ms-catalogo-inmobiliario`, `ms-contratos`) descargan la clave pública desde el endpoint de certificados (`JWKS`) al iniciar y validan la autenticidad del token en memoria sin saturar a Keycloak.

---

## 2. Usuarios de Prueba con Identificadores Fijos (UUIDs)

Para evitar que al recrear el contenedor de Keycloak cambien los IDs de usuario y se rompan las referencias en las bases de datos (`keycloakUserId` en la tabla `Persona`), se fijaron los siguientes UUIDs determinísticos:

| Usuario | Contraseña | Rol en Realm | UUID Inmutable (`sub` del JWT) | Propósito en el Negocio |
| :--- | :--- | :--- | :--- | :--- |
| **`admin`** | `admin123` | `ADMINISTRADOR` | `00000000-0000-0000-0000-000000000001` | Gestión total de inmuebles, contratos y configuración. |
| **`propietario1`** | `prop123` | `PROPIETARIO` | `00000000-0000-0000-0000-000000000002` | Consulta de inmuebles en locación y sus contratos. |
| **`inquilino1`** | `inq123` | `INQUILINO` | `00000000-0000-0000-0000-000000000003` | Consulta de cuotas vigentes y mora calculada. |

> [!IMPORTANT] Clave Foránea Lógica en Bases de Datos
> Cuando `ms-catalogo-inmobiliario` persiste una `Persona` (por ejemplo a Carlos Propietario o Ana Inquilina), debe asociar en el campo `keycloakUserId` el UUID de la tabla anterior. De este modo, cuando el usuario envíe su JWT, el backend extrae el claim `sub` y lo encuentra de inmediato con `findByPropietario_KeycloakUserId(sub)`.

---

## 3. Clientes OIDC Configurados

Keycloak administra dos tipos de clientes para dos escenarios arquitectónicos distintos:

### A. Cliente Público: `rovedasa-backend`
- **Tipo:** Público (sin *client secret*).
- **Flujo permitido:** `Direct Access Grants` (Resource Owner Password Credentials) y `Standard Flow` (Authorization Code).
- **Destinatarios:** Postman, Swagger UI y aplicaciones Frontend.
- **Uso:** Obtención de tokens a nombre de usuarios humanos (`admin`, `propietario1`, `inquilino1`).

### B. Cliente Confidencial: `backend-services`
- **Tipo:** Confidencial con credencial secreta.
- **Client Secret:** `backend-secret-rovedasa-2026`
- **Flujo permitido:** `Service Accounts` (`client_credentials`).
- **Destinatarios:** Tareas en segundo plano (`@Scheduled` en `ms-contratos` como el cierre automático de contratos o indexación por ICL) cuando un microservicio necesita llamar a otro sin la presencia de un usuario humano interactuando.

---

## 4. Guía Práctica: Cómo Obtener Tokens JWT

### Opción 1: Token de Usuario Humano (Password Grant con `rovedasa-backend`)

#### Petición HTTP / cURL:
```bash
curl -X POST "http://localhost:8083/realms/rovedasa-realm/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=rovedasa-backend" \
  -d "username=admin" \
  -d "password=admin123"
```

#### En Postman:
1. Método: **POST**
2. URL: `http://localhost:8083/realms/rovedasa-realm/protocol/openid-connect/token`
3. Body: Seleccionar **`x-www-form-urlencoded`**:
   - `grant_type`: `password`
   - `client_id`: `rovedasa-backend`
   - `username`: `admin` *(o `propietario1` / `inquilino1`)*
   - `password`: `admin123` *(o `prop123` / `inq123`)*

---

### Opción 2: Token de Servicio a Servicio (Client Credentials con `backend-services`)

#### Petición HTTP / cURL:
```bash
curl -X POST "http://localhost:8083/realms/rovedasa-realm/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=backend-services" \
  -d "client_secret=backend-secret-rovedasa-2026"
```

---

## 5. Inspección del Token JWT y Conversión en Spring

Cuando decodificás el `access_token` en [jwt.io](https://jwt.io), la estructura de claims relevante es:

```json
{
  "exp": 1730000000,
  "iat": 1729999700,
  "iss": "http://localhost:8083/realms/rovedasa-realm",
  "sub": "00000000-0000-0000-0000-000000000001",
  "preferred_username": "admin",
  "realm_access": {
    "roles": [
      "ADMINISTRADOR"
    ]
  }
}
```

### Configuración en los `application.yml` de los Microservicios:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://localhost:8083/realms/rovedasa-realm/protocol/openid-connect/certs
```

> [!TIP] Conversión de Roles en Spring Security
> Spring Security por defecto busca los roles en el claim `scope` con prefijo `SCOPE_`. Dado que Keycloak almacena los roles en `realm_access.roles`, la librería `inmobiliaria-commons` provee el convertidor `KeycloakRoleConverter` para transformar automáticamente cada rol a `ROLE_ADMINISTRADOR`, `ROLE_PROPIETARIO` y `ROLE_INQUILINO`.
