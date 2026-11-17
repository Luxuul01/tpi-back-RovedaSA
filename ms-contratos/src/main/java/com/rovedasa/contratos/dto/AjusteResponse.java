package com.rovedasa.contratos.dto;

import com.rovedasa.contratos.model.TipoIndiceEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO de salida para historial de ajustes (GET /api/contratos/{id}/ajustes).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AjusteResponse {

    private Long id;
    private TipoIndiceEnum tipoIndice;
    private LocalDate fechaAplicacion;
    private LocalDate fechaIndiceDesde;
    private LocalDate fechaIndiceHasta;
    private BigDecimal valorIndiceDesde;
    private BigDecimal valorIndiceHasta;
    private BigDecimal coeficiente;
    private BigDecimal montoAnterior;
    private BigDecimal montoNuevo;
    private BigDecimal porcentajeVariacion;
    private OrigenDatoEnum origen;
}
