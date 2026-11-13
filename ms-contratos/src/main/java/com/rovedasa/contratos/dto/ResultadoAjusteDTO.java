package com.rovedasa.contratos.dto;

import com.rovedasa.contratos.model.TipoIndiceEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Resultado de calcular un ajuste de canon. Sus campos coinciden 1 a 1 con la
 * entidad Ajuste del diagrama de clases, para que ms-contratos (patrón State,
 * estado Activo.aplicarAjuste) pueda persistir el registro histórico directamente.
 *
 *   Canon Nuevo = Canon Anterior x coeficiente
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoAjusteDTO {
    private TipoIndiceEnum tipoIndice;
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
