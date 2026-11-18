package com.rovedasa.commons.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rovedasa.commons.filter.CorrelationIdFilter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private String correlationId;
    private Map<String, String> validaciones;

    /**
     * Arma el ApiError de una respuesta de error con el correlationId de la petición en curso (MDC).
     */
    public static ApiError de(HttpStatusCode status, String mensaje, String path) {
        HttpStatus conocido = HttpStatus.resolve(status.value());
        return ApiError.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(conocido != null ? conocido.getReasonPhrase() : String.valueOf(status.value()))
                .message(mensaje)
                .path(path)
                .correlationId(correlationIdActual())
                .build();
    }

    /**
     * correlationId que dejó el CorrelationIdFilter en el MDC, o "N/A" fuera de una petición HTTP.
     */
    public static String correlationIdActual() {
        String id = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID);
        return id != null ? id : "N/A";
    }
}
