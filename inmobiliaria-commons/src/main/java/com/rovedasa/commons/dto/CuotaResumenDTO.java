package com.rovedasa.commons.dto;

import com.rovedasa.commons.enums.EstadoCuotaEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CuotaResumenDTO {
    private Integer nroCuota;
    private Integer periodoMes;
    private Integer periodoAnio;
    private LocalDate fechaVencimiento;
    private BigDecimal montoBase;
    private BigDecimal montoMora;
    private BigDecimal montoTotal;
    private EstadoCuotaEnum estado;
}