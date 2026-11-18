package com.rovedasa.contratos.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO de entrada para aplicar un ajuste por el índice del contrato, ICL o IPC (POST /api/contratos/{id}/ajustes).
 * Las fechas son opcionales: el período lo fija el contrato (ajuste k: desde H_{k-1} hasta H_k, con
 * H_k = fechaInicio + k · frecuencia meses). Si se envían, tienen que coincidir con ese período (si no, 400).
 * No llevan @PastOrPresent: un "hasta" futuro lo rechaza el State con el mensaje de la regla (todavía no
 * corresponde, a partir de qué fecha) en lugar de un error genérico de validación del campo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AplicarAjusteRequest {

    private LocalDate fechaIndiceDesde;

    private LocalDate fechaIndiceHasta;
}
