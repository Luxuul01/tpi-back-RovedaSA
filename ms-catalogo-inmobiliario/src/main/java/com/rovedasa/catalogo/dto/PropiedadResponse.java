package com.rovedasa.catalogo.dto;

import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Respuesta de propiedad. Es un SUPERSET de com.rovedasa.commons.dto.PropiedadDTO: los primeros nueve
 * campos tienen el mismo nombre y tipo, para que ms-contratos pueda deserializar
 * GET /api/propiedades/{id} directo a PropiedadDTO (Jackson ignora el resto de los campos).
 * No renombrar esos campos sin cambiar también el DTO de commons. Todos los campos son planos.
 */
public record PropiedadResponse(
        // --- Campos de PropiedadDTO (commons) ---
        Long id,
        String codigo,
        String direccionNormalizada,
        BigDecimal superficieTotalM2,
        BigDecimal precioSugeridoM2,
        BigDecimal canonBaseSugerido,
        Long propietarioId,
        Long inquilinoId,
        EstadoPropiedadEnum estado,
        // --- Campos propios del catálogo ---
        TipologiaEnum tipologia,
        String calle,
        String numero,
        String piso,
        String departamento,
        String barrio,
        String ciudad,
        String provincia,
        String codigoPostal,
        Double latitud,
        Double longitud,
        BigDecimal superficieCubiertaM2,
        BigDecimal superficieComputableM2,
        Long garanteId,
        String propietarioNombreCompleto,
        String zonaNombre,
        Integer zonaPuntaje,
        LocalDateTime fechaAlta) {
}
