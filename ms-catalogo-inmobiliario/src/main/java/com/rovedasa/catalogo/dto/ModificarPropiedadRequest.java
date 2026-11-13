package com.rovedasa.catalogo.dto;

import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Datos descriptivos de una propiedad para PUT /api/propiedades/{id} (F-06). Reemplaza todos los campos,
 * así que los opcionales que vengan nulos quedan en null.
 * Mismos campos y validaciones que CrearPropiedadRequest, salvo propietarioId: el cambio de titular no
 * está en el alcance.
 */
public record ModificarPropiedadRequest(
        @NotNull TipologiaEnum tipologia,
        @NotBlank @Size(max = 255) String calle,
        @NotBlank @Size(max = 20) String numero,
        @Size(max = 10) String piso,
        @Size(max = 10) String departamento,
        @Size(max = 255) String barrio,
        @Size(max = 10) String codigoPostal,
        // Nombre oficial de la localidad, tal como lo conoce GeoRef (ej. "Córdoba", no "Córdoba Capital")
        @NotBlank @Size(max = 100) String ciudad,
        @NotBlank @Size(max = 100) String provincia,
        @NotNull @Positive @Digits(integer = 8, fraction = 2) BigDecimal superficieTotalM2,
        @Positive @Digits(integer = 8, fraction = 2) BigDecimal superficieCubiertaM2) {
}
