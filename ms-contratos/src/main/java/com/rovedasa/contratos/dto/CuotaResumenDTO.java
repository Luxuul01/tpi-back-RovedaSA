package com.rovedasa.contratos.dto;

import com.rovedasa.contratos.model.EstadoCuotaEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO de salida para el estado de cuenta de cuotas con mora calculada al día.
 * Pertenece a ms-contratos (tipo que usa un solo servicio).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CuotaResumenDTO {

    private Long id;
    private Integer nroCuota;
    private Integer periodoMes;
    private Integer periodoAnio;
    private LocalDate fechaVencimiento;
    private BigDecimal montoBase;
    private BigDecimal montoMora;
    private BigDecimal montoTotal;
    private EstadoCuotaEnum estado;
    private LocalDate fechaPago;
}
