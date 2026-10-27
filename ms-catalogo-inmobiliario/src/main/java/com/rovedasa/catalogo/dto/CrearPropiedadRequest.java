package com.rovedasa.catalogo.dto;

import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// Los @Size y @Digits replican el largo y la precisión de las columnas de la tabla propiedades
public record CrearPropiedadRequest(
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
        @Positive @Digits(integer = 8, fraction = 2) BigDecimal superficieCubiertaM2,
        @NotNull Long propietarioId) {
}
