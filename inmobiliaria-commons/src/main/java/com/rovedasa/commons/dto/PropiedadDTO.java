package com.rovedasa.commons.dto;

import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropiedadDTO {
    private Long id;
    private String codigo;
    private String direccionNormalizada;
    private BigDecimal superficieTotalM2;
    private BigDecimal precioSugeridoM2;
    private BigDecimal canonBaseSugerido;
    private Long propietarioId;
    private Long inquilinoId;
    private EstadoPropiedadEnum estado;
}
