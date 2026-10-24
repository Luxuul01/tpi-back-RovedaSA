package com.rovedasa.commons.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        // 1. Verificar si viene del cliente
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);

        // 2. Si no viene, generar uno nuevo
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = generateCorrelationId();
        }

        // 3. Agregar a MDC (para Slf4j)
        MDC.put("correlationId", correlationId);

        // 4. Agregar a la respuesta (para que el cliente sepa qué ID usar en el futuro)
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            // 5. Continuar el flujo normal de la petición
            filterChain.doFilter(request, response);
        } finally {
            // 6. LIMPIAR SIEMPRE el MDC al terminar
            // Esto es vital para que el siguiente request no se contamine con el ID del
            // anterior
            MDC.remove("correlationId");
        }
    }

    private String generateCorrelationId() {
        return "req-" + UUID.randomUUID().toString();
    }
}
