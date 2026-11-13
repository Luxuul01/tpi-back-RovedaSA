package com.rovedasa.contratos.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Coeficiente por el que se multiplica el canon entre dos fechas.
 *
 * ICL: coeficiente = ICL(fechaIndiceHasta) / ICL(fechaIndiceDesde)
 * IPC: coeficiente = producto de (1 + inflación mensual / 100) de los meses usados.
 *      Para IPC se informa un índice encadenado base 1: valorIndiceDesde = 1 y
 *      valorIndiceHasta = coeficiente, así el registro de Ajuste queda igual que con ICL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CoeficienteAjusteDTO {
    private TipoIndiceEnum tipoIndice;
    private LocalDate fechaDesde;
    private LocalDate fechaHasta;
    private LocalDate fechaIndiceDesde;
    private LocalDate fechaIndiceHasta;
    private BigDecimal valorIndiceDesde;
    private BigDecimal valorIndiceHasta;
    private BigDecimal coeficiente;
    private BigDecimal porcentajeVariacion;
    private OrigenDatoEnum origen;
    /** Solo IPC: las inflaciones mensuales que se encadenaron. */
    private List<ValorIndiceDTO> variacionesMensuales;
}
