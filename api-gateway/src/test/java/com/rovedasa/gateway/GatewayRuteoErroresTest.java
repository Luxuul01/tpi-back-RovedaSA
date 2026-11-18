package com.rovedasa.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Gateway completo (rutas, seguridad, filtros y manejo de errores) contra un catálogo falso que contesta
 * con el X-Correlation-ID recibido (como los microservicios reales) y un ms-contratos apagado.
 * El JWT de prueba (mockJwt) evita depender de Keycloak.
 */
@SpringBootTest
class GatewayRuteoErroresTest {

    private static final HttpServer CATALOGO_FALSO = iniciarCatalogoFalso();
    private static final int PUERTO_CERRADO = puertoLibre();

    @Autowired
    private ApplicationContext contexto;

    private WebTestClient cliente;

    @DynamicPropertySource
    static void rutas(DynamicPropertyRegistry registro) {
        registro.add("rovedasa-gw.url-microservicio-catalogo",
                () -> "http://localhost:" + CATALOGO_FALSO.getAddress().getPort());
        registro.add("rovedasa-gw.url-microservicio-contratos", () -> "http://localhost:" + PUERTO_CERRADO);
    }

    @BeforeEach
    void crearCliente() {
        cliente = WebTestClient.bindToApplicationContext(contexto)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @AfterAll
    static void apagarCatalogoFalso() {
        CATALOGO_FALSO.stop(0);
    }

    @Test
    @DisplayName("La respuesta ruteada lleva un único X-Correlation-ID aunque el microservicio también lo devuelva")
    void respuestaRuteadaLlevaUnSoloCorrelationId() {
        cliente.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR")))
                .get().uri("/api/propiedades/1")
                .header("X-Correlation-ID", "corr-unico-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valuesMatch("X-Correlation-ID", "corr-unico-1");
    }

    @Test
    @DisplayName("Un X-Correlation-ID inválido no llega al microservicio ni vuelve al cliente")
    void correlationIdInvalidoSeReemplazaAntesDeRutear() {
        cliente.get().uri("/v3/api-docs/catalogo")
                .header("X-Correlation-ID", "<script>alert(1)</script>")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("X-Correlation-ID", startsWith("req-"))
                // El catálogo falso devuelve en el body el header que recibió
                .expectBody(String.class).value(startsWith("recibido=req-"));
    }

    @Test
    @DisplayName("Ruta inexistente: 404 con ApiError y correlationId")
    void rutaInexistenteDa404ConApiError() {
        cliente.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR")))
                .get().uri("/api/foo")
                .header("X-Correlation-ID", "corr-404")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valuesMatch("X-Correlation-ID", "corr-404")
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.error").isEqualTo("Not Found")
                .jsonPath("$.path").isEqualTo("/api/foo")
                .jsonPath("$.correlationId").isEqualTo("corr-404");
    }

    @Test
    @DisplayName("Microservicio caído: 503 con ApiError (no el 500 por defecto)")
    void servicioCaidoDa503ConApiError() {
        cliente.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR")))
                .get().uri("/api/contratos")
                .header("X-Correlation-ID", "corr-503")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valuesMatch("X-Correlation-ID", "corr-503")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.path").isEqualTo("/api/contratos")
                .jsonPath("$.correlationId").isEqualTo("corr-503");
    }

    @Test
    @DisplayName("Sin token: 401 con WWW-Authenticate: Bearer y ApiError")
    void sinTokenDa401ConWwwAuthenticate() {
        cliente.get().uri("/api/contratos")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectHeader().value("X-Correlation-ID", startsWith("req-"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("Actuator (salvo health) exige ADMINISTRADOR: un INQUILINO recibe 403")
    void actuatorLoggersConInquilinoDa403() {
        cliente.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_INQUILINO")))
                .get().uri("/actuator/loggers")
                .exchange()
                .expectStatus().isForbidden();
    }

    // Responde 200 a todo, devolviendo el X-Correlation-ID recibido en el header y en el body
    private static HttpServer iniciarCatalogoFalso() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            servidor.createContext("/", intercambio -> {
                String recibido = intercambio.getRequestHeaders().getFirst("X-Correlation-ID");
                byte[] cuerpo = ("recibido=" + recibido).getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().put("X-Correlation-ID", List.of(String.valueOf(recibido)));
                intercambio.getResponseHeaders().add("Content-Type", "text/plain");
                intercambio.sendResponseHeaders(200, cuerpo.length);
                try (OutputStream salida = intercambio.getResponseBody()) {
                    salida.write(cuerpo);
                }
            });
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo levantar el catálogo falso", e);
        }
    }

    private static int puertoLibre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
