# ==============================================================================
# ETAPA 1: Build (Compilación con Maven + JDK 21)
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copiamos el wrapper y los POMs primero para maximizar la caché de capas
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY inmobiliaria-commons/pom.xml inmobiliaria-commons/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY ms-catalogo-inmobiliario/pom.xml ms-catalogo-inmobiliario/pom.xml
COPY ms-contratos/pom.xml ms-contratos/pom.xml

RUN chmod +x mvnw && ./mvnw dependency:go-offline -B -pl ms-contratos -am || true

# Copiamos commons y el servicio objetivo para empaquetar el JAR ejecutable
COPY inmobiliaria-commons/src inmobiliaria-commons/src
COPY ms-contratos/src ms-contratos/src
RUN ./mvnw package -pl ms-contratos -am -DskipTests -B

# ==============================================================================
# ETAPA 2: Runtime (Ejecución liviana con JRE 21 y usuario no-root)
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /app/logs \
    && chown -R appuser:appgroup /app

COPY --from=builder --chown=appuser:appgroup /workspace/ms-contratos/target/ms-contratos-*.jar app.jar

USER appuser

EXPOSE 8082

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
