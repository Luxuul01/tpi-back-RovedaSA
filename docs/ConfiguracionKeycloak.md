# 🔐 Configuración y Guía de Uso de Keycloak — Alquileres Roveda SA

> **Ubicación del manifiesto:** `keycloak/realm-export.json`  
> **Puerto del contenedor:** `8083:8080` (Mapeado a `8083` en el host para no colisionar con el `api-gateway` en el `8080`; se cambia con la variable `KEYCLOAK_PORT`)  
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
- **Client Secret:** no está escrito en el realm. `realm-export.json` tiene el placeholder `${KEYCLOAK_CLIENT_SECRET}`, que Keycloak 24 reemplaza por la variable de entorno del mismo nombre al importar el realm con `--import-realm` (función documentada de Keycloak: *Using Environment Variables within the Realm Configuration Files*). El `docker-compose.yml` le pasa esa variable a Keycloak y a `ms-contratos` con el valor de demo `backend-secret-rovedasa-2026` por defecto (ver `.env.example`).
- **Flujo permitido:** `Service Accounts` (`client_credentials`). No tiene login de usuario (`standardFlowEnabled` y `directAccessGrantsEnabled` en `false`).
- **Cuenta de servicio:** `service-account-backend-services` (UUID `00000000-0000-0000-0000-000000000004`) con el rol de realm `ADMINISTRADOR`. Como el realm no define `clientScopes` propios, Keycloak crea los scopes por defecto (entre ellos `roles`, con el mapper *realm roles*) y el token de la cuenta de servicio trae `realm_access.roles` con `ADMINISTRADOR`, igual que el de un usuario.
- **Destinatario:** `ms-contratos`, en **todas** sus llamadas a `ms-catalogo-inmobiliario` (consultar propiedad y personas, evaluar al garante, cambiar el estado de la propiedad y compensarlo en la Saga), tanto si las dispara un usuario como el ajuste programado (`@Scheduled`, sin usuario). Ver la sección 6.

> [!NOTE] Si el secreto se cambia
> Keycloak (en modo `start-dev`, sin volumen) importa el realm cada vez que se crea el contenedor. Para aplicar un secreto nuevo: cambiar `KEYCLOAK_CLIENT_SECRET` en el `.env` y recrear Keycloak y `ms-contratos` (`docker compose up -d --force-recreate keycloak ms-contratos`). Si se importa `realm-export.json` a mano desde la consola, el placeholder no se reemplaza y hay que cargar el secreto en *Clients → backend-services → Credentials*.

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

Es el mismo pedido que hace `ms-contratos`. Sirve para revisar el token de la cuenta de servicio (por ejemplo, que `realm_access.roles` incluya `ADMINISTRADOR`).

#### Petición HTTP / cURL:
```bash
# Sin .env, el secreto es el valor de demo del docker compose
KEYCLOAK_CLIENT_SECRET=${KEYCLOAK_CLIENT_SECRET:-backend-secret-rovedasa-2026}
curl -X POST "http://localhost:8083/realms/rovedasa-realm/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=backend-services" \
  -d "client_secret=$KEYCLOAK_CLIENT_SECRET"
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

---

## 6. Token de Servicio en `ms-contratos` (contratos → catálogo)

`ms-contratos` no reenvía el token del usuario al catálogo: usa siempre el de la cuenta de servicio `backend-services`.

- **Cómo lo obtiene:** `ProveedorTokenServicio` (`ms-contratos/.../config`) hace `POST` al endpoint de tokens con `grant_type=client_credentials`, `client_id` y `client_secret` (formulario `x-www-form-urlencoded`) usando `RestClient`. El token se guarda en memoria y se reutiliza hasta 30 segundos antes de que venza (`expires_in`, 300 s por defecto en Keycloak); recién ahí se pide otro. El primer pedido ocurre en la primera llamada al catálogo, no al arrancar.
- **Cómo lo envía:** el `catalogoRestClient` (`RestClientConfig`) tiene dos interceptores: uno propaga el `X-Correlation-ID` de la petición en curso y el otro pone `Authorization: Bearer <token de servicio>`. Si el catálogo responde 401 (por ejemplo, porque Keycloak se reinició y cambió sus claves), se descarta el token, se pide uno nuevo y se reintenta una sola vez.
- **Si falla:** si Keycloak no responde, rechaza las credenciales o falta el secreto, la operación responde **503** (`CatalogoNoDisponibleException`) sin cambiar ningún estado.
- **Configuración** (`ms-contratos/src/main/resources/application*.yml`):

```yaml
keycloak:
  cuenta-servicio:
    token-uri: http://localhost:8083/realms/rovedasa-realm/protocol/openid-connect/token   # perfil docker: http://keycloak:8080/...
    client-id: backend-services
    client-secret: ${KEYCLOAK_CLIENT_SECRET:}   # perfil docker: ${KEYCLOAK_CLIENT_SECRET} (obligatoria)
    connect-timeout-ms: 3000
    read-timeout-ms: 5000
```

El secreto no tiene valor por defecto en el código. Con Docker Compose lo pone el compose; corriendo `ms-contratos` desde el host hay que exportar `KEYCLOAK_CLIENT_SECRET` (ver README, Opción 2).

**Por qué no depende de la identidad del usuario:** todos los endpoints del catálogo que usa `ms-contratos` (`GET /api/propiedades/{id}`, `GET /api/personas/{id}`, `POST /api/personas/{id}/evaluacion-solvencia`, `PATCH /api/propiedades/{id}/estado` y su compensación) se autorizan solo por el rol `ADMINISTRADOR` y no filtran por el `sub` del token. La pertenencia de propietarios e inquilinos se resuelve en `ms-contratos` con los `keycloak_id` guardados en el contrato, sin llamar al catálogo.
