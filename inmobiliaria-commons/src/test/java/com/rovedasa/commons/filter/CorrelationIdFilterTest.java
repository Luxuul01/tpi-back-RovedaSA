package com.rovedasa.commons.filter;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

class CorrelationIdFilterTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilterTest.class);
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void debeGenerarCorrelationIdCuandoElClienteNoLoEnvia() throws ServletException, IOException {
        // 1. Simulamos una petición HTTP que NO trae header X-Correlation-ID
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 2. Simulamos lo que pasaría adentro del Controller (un log en el medio de la
        // petición)
        FilterChain filterChain = (req, res) -> {
            // Acá adentro estamos en pleno vuelo de la petición HTTP
            log.info(">>> ¡MENSAJE DE PRUEBA: El correlationId actual en el MDC es: {} <<<", MDC.get("correlationId"));
            assertNotNull(MDC.get("correlationId"), "El MDC debería tener un correlationId cargado");
            assertTrue(MDC.get("correlationId").startsWith("req-"), "Debe tener el prefijo req-");
        };

        // 3. Ejecutamos tu filtro
        filter.doFilter(request, response, filterChain);

        // 4. Verificaciones después de que terminó la petición:
        // A) ¿El response tiene el header para que el cliente lo vea?
        String headerEnRespuesta = response.getHeader("X-Correlation-ID");
        assertNotNull(headerEnRespuesta);
        System.out.println("Header devuelto al cliente: " + headerEnRespuesta);

        // B) ¿Se limpió el MDC para no contaminar a otros hilos? (Bloque finally)
        assertNull(MDC.get("correlationId"), "El MDC debe estar limpio después de la petición");
    }

    @Test
    void debeRespetarCorrelationIdCuandoElClienteYaEnviaUno() throws ServletException, IOException {
        // Simulamos que el cliente mandó su propio ID:
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "mi-id-personalizado-1234");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            log.info(">>> MENSAJE CON ID PROPIO: {} <<<", MDC.get("correlationId"));
            assertEquals("mi-id-personalizado-1234", MDC.get("correlationId"));
        };

        filter.doFilter(request, response, filterChain);

        // Verificamos que devolvió el mismo que enviamos:
        assertEquals("mi-id-personalizado-1234", response.getHeader("X-Correlation-ID"));
    }
}