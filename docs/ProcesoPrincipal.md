# Proceso Principal — Formalizar un Contrato de Alquiler

Diagrama de secuencia del proceso de negocio principal del sistema: desde que la inmobiliaria inicia la operación hasta que el contrato queda `ACTIVO` y las partes pueden consultarlo. Cada nota del diagrama corresponde a un paso del proceso (P1 a P7) e indica las funcionalidades (F-xx) y reglas de negocio (RN-xx) que intervienen, según el formulario de alcance del TPI.

Los bloques `alt` representan las decisiones y excepciones del proceso: propiedad no disponible, seña vencida, garante no solvente y BCRA no disponible. `ms-contratos` nunca accede a los datos del catálogo en forma directa: siempre los consulta o modifica a través de su API REST (*Database per Service*).

```mermaid
sequenceDiagram
    title Proceso principal - Formalizar un contrato de alquiler
    actor A as Administrador
    participant GW as API Gateway + Keycloak
    participant CT as ms-contratos
    participant CA as ms-catalogo-inmobiliario
    participant BC as BCRA Central de Deudores
    actor U as Inquilino / Propietario

    Note over A,GW: P1 - Autenticación [F-01 / RN-16]
    A->>GW: Login con credenciales
    GW-->>A: Token JWT con rol ADMINISTRADOR

    Note over A,CA: P2 - Verificar o registrar inquilino y garante [F-02, F-03 / RN-01, RN-02]
    A->>GW: POST /api/personas
    GW->>CA: Reenvía con TokenRelay y X-Correlation-ID
    alt Datos válidos y sin duplicados
        CA-->>A: 201 Persona creada con sus roles
    else CUIT inválido o duplicado
        CA-->>A: 400 / 409 Rechazo
    end

    Note over A,CT: P3 - Crear borrador [F-09 / RN-02, RN-10]
    A->>GW: POST /api/contratos
    GW->>CT: Reenvía
    CT-->>A: 201 Contrato CREADO

    Note over A,CA: P4 - Registrar seña [F-10, F-07 / RN-05, RN-06]
    A->>GW: POST /api/contratos/{id}/seniar
    GW->>CT: Reenvía
    CT->>CA: Consultar propiedad
    alt Propiedad DISPONIBLE
        CT->>CA: PATCH /api/propiedades/{id}/estado - RESERVADA
        CA-->>CT: 200 Propiedad RESERVADA
        CT-->>A: 200 Contrato SEÑADO con monto y fecha límite
    else Propiedad RESERVADA o ALQUILADA
        CT-->>A: Rechazo - el contrato sigue CREADO
    end

    Note over A,BC: P5 - Activar contrato [F-11, F-08, F-07 / RN-07, RN-08]
    A->>GW: POST /api/contratos/{id}/activar
    GW->>CT: Reenvía
    alt Fecha límite de seña vencida
        CT-->>A: Rechazo - corresponde cancelar la seña (P6)
    else Dentro del plazo
        CT->>CA: Solicitar evaluación de solvencia del garante
        CA->>BC: Consulta por CUIT
        BC-->>CA: Situación crediticia y cheques rechazados
        CA-->>CT: Resultado de la evaluación
        alt Garante solvente
            CT->>CA: PATCH /api/propiedades/{id}/estado - ALQUILADA
            CA-->>CT: 200 Propiedad ALQUILADA
            CT->>CT: Cancelar otros borradores de la propiedad y generar cuotas del primer período
            CT-->>A: 200 Contrato ACTIVO
        else Garante no solvente
            CT-->>A: Rechazo - el contrato sigue SEÑADO
        else BCRA no disponible
            CT-->>A: 503 - el contrato sigue SEÑADO
        end
    end

    Note over A,CA: P6 - Excepción: cancelar seña [F-12 / RN-09]
    A->>GW: POST /api/contratos/{id}/cancelar
    GW->>CT: Reenvía
    CT->>CA: PATCH /api/propiedades/{id}/estado - DISPONIBLE
    CT-->>A: 200 Contrato CANCELADO

    Note over GW,U: P7 - Consulta del resultado [F-16, F-05 / RN-13, RN-17]
    U->>GW: GET /api/contratos (token de INQUILINO o PROPIETARIO)
    GW->>CT: Reenvía
    CT-->>U: Solo sus contratos y cuotas, con mora calculada a la fecha
```

El ciclo de vida posterior del contrato (ajustes por ICL, rescisión y finalización) se detalla en la máquina de estados de [Diagramas](./Diagramas.md).
