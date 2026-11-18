package com.rovedasa.contratos.exception;

import com.rovedasa.commons.exception.ApiError;
import com.rovedasa.commons.exception.GlobalExceptionHandler;
import com.rovedasa.contratos.client.CatalogoConflictoException;
import com.rovedasa.contratos.client.CatalogoIntegracionException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Respuestas del catálogo que ms-contratos traduce con un código propio (las traduce CatalogoClient):
 * 409 cuando el catálogo informa un conflicto (la propiedad no estaba en el estado esperado o cambió al mismo
 * tiempo) y 502 cuando rechazó la credencial del servicio o respondió algo inesperado. El 503 del catálogo
 * caído lo resuelve IndiceExceptionHandler. Corre antes que el GlobalExceptionHandler de commons y devuelve el
 * mismo ApiError.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class CatalogoExceptionHandler {

    /** 409 del catálogo (estado esperado distinto o conflicto de versión): se responde 409, no 400. */
    @ExceptionHandler(CatalogoConflictoException.class)
    public ResponseEntity<ApiError> handleConflictoCatalogo(CatalogoConflictoException ex,
                                                            HttpServletRequest request) {
        log.warn("Conflicto informado por el catálogo: {}", ex.getMessage());
        return GlobalExceptionHandler.responder(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /** El catálogo rechazó la credencial del servicio o respondió algo inesperado: 502, no 503. */
    @ExceptionHandler(CatalogoIntegracionException.class)
    public ResponseEntity<ApiError> handleIntegracionCatalogo(CatalogoIntegracionException ex,
                                                              HttpServletRequest request) {
        log.error("Error de integración con el catálogo: {}", ex.getMessage());
        return GlobalExceptionHandler.responder(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }
}
