package com.rovedasa.contratos.dto;

import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.model.EstadoContratoEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO de salida para consultar contratos (GET /api/contratos y GET /api/contratos/{id}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContratoResponse {

    private Long id;
    private String nroContrato;
    private Long propiedadId;
    private Long propietarioId;
    private Long inquilinoId;
    private Long garanteId;
    private String propietarioKeycloakId;
    private String inquilinoKeycloakId;
    private LocalDate fechaInicio;
    private LocalDate fechaFin;
    private LocalDate fechaRescision;
    private Integer frecuenciaAjusteMeses;
    private TipoIndiceEnum tipoIndice;
    private BigDecimal montoInicial;
    private BigDecimal montoActual;
    private BigDecimal montoSenia;
    private LocalDate fechaLimiteSenia;
    private BigDecimal porcentajeMoraDiaria;
    private Integer diaVencimientoMes;
    private EstadoContratoEnum estado;
    /** RN-09: motivo registrado al cancelar (borrador descartado o seña caída). */
    private String motivoCancelacion;
    /** RN-09: si al cancelar una seña se la retuvo (true) o se la devuelve (false). */
    private Boolean seniaRetenida;
    private BigDecimal penalizacionRescision;
    private List<CuotaResumenDTO> cuotas;
    private List<AjusteResponse> ajustes;
}
