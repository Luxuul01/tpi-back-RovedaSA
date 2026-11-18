package com.rovedasa.commons.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Asigna el X-Correlation-ID de cada petición: lo deja en el MDC (para los logs) y en la respuesta.
 * Si el cliente (o el Gateway) manda uno válido se respeta; si no viene o no cumple el formato
 * {@code [A-Za-z0-9._-]{1,64}} se genera uno nuevo con prefijo "req-". Así un valor con saltos de línea,
 * tabs, HTML o miles de caracteres nunca llega al header, al ApiError ni a los logs.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_CORRELATION_ID = "correlationId";

    private static final Pattern FORMATO_VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = sanitizar(request.getHeader(CORRELATION_ID_HEADER));

        MDC.put(MDC_CORRELATION_ID, correlationId);
        // En la respuesta, para que el cliente sepa qué ID buscar en los logs
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Siempre se limpia: el hilo vuelve al pool y el próximo request no debe heredar el ID
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    /**
     * Devuelve el ID recibido si cumple el formato permitido, o uno nuevo si no viene o no lo cumple.
     */
    public static String sanitizar(String recibido) {
        if (esValido(recibido)) {
            return recibido;
        }
        if (recibido != null && !recibido.isEmpty()) {
            // No se loguea el valor recibido: justamente puede traer saltos de línea o basura
            log.debug("X-Correlation-ID recibido con formato inválido (largo {}); se genera uno nuevo",
                    recibido.length());
        }
        return generarCorrelationId();
    }

    public static boolean esValido(String valor) {
        return valor != null && FORMATO_VALIDO.matcher(valor).matches();
    }

    private static String generarCorrelationId() {
        return "req-" + UUID.randomUUID();
    }
}
