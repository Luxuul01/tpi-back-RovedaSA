package com.rovedasa.contratos.dto;

import com.rovedasa.contratos.model.TipoIndiceEnum;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO de entrada para crear un borrador de contrato (POST /api/contratos).
 * Los límites de los montos siguen las columnas de la tabla contratos:
 * monto_inicial NUMERIC(15,2) y porcentaje_mora_diaria NUMERIC(7,4).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrearContratoRequest {

    /**
     * Rango de fechas admitido para un contrato. El Alcance no fija una duración máxima; estos topes solo frenan
     * fechas absurdas (año 0001 o 5874898) que la base rechazaría. ContratoService.crearContrato los valida (400)
     * antes de consultar el catálogo, junto con la regla fechaFin posterior a fechaInicio.
     */
    public static final LocalDate FECHA_INICIO_MINIMA = LocalDate.of(2000, 1, 1);
    public static final LocalDate FECHA_FIN_MAXIMA = LocalDate.of(2100, 12, 31);

    @NotNull(message = "El ID de la propiedad es obligatorio")
    private Long propiedadId;

    @NotNull(message = "El ID del propietario es obligatorio")
    private Long propietarioId;

    @NotNull(message = "El ID del inquilino es obligatorio")
    private Long inquilinoId;

    @NotNull(message = "El ID del garante es obligatorio")
    private Long garanteId;

    @NotNull(message = "La fecha de inicio es obligatoria")
    private LocalDate fechaInicio;

    @NotNull(message = "La fecha de fin es obligatoria")
    @Future(message = "La fecha de fin debe ser futura")
    private LocalDate fechaFin;

    @NotNull(message = "La frecuencia de ajuste es obligatoria")
    @Positive(message = "La frecuencia de ajuste debe ser mayor a cero")
    private Integer frecuenciaAjusteMeses;

    /**
     * Índice pactado para los ajustes: ICL (por defecto, si no se informa) o IPC.
     * El IPC es una funcionalidad adicional al alcance comprometido (ver docs/BCRA-Resiliencia.md).
     */
    private TipoIndiceEnum tipoIndice;

    @NotNull(message = "El monto inicial es obligatorio")
    @Positive(message = "El monto inicial debe ser mayor a cero")
    @Digits(integer = 13, fraction = 2,
            message = "El monto inicial admite como máximo 13 dígitos enteros y 2 decimales")
    private BigDecimal montoInicial;

    @NotNull(message = "El porcentaje de mora diaria es obligatorio")
    @PositiveOrZero(message = "El porcentaje de mora diaria no puede ser negativo")
    @DecimalMax(value = "100", message = "El porcentaje de mora diaria no puede superar el 100 %")
    @Digits(integer = 3, fraction = 4,
            message = "El porcentaje de mora diaria admite como máximo 3 dígitos enteros y 4 decimales")
    private BigDecimal porcentajeMoraDiaria;

    @NotNull(message = "El día de vencimiento mensual es obligatorio")
    @Min(value = 1, message = "El día de vencimiento mensual debe estar entre 1 y 31")
    @Max(value = 31, message = "El día de vencimiento mensual debe estar entre 1 y 31")
    private Integer diaVencimientoMes;
}
