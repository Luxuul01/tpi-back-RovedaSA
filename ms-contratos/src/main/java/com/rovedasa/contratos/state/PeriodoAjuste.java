package com.rovedasa.contratos.state;

import java.time.LocalDate;

/**
 * Período de un ajuste de canon: el ajuste número {@code numero} (k ≥ 1) usa la ventana del índice
 * desde H_{k-1} hasta H_k, con H_k = fechaInicio + k · frecuencia meses.
 */
public record PeriodoAjuste(int numero, LocalDate desde, LocalDate hasta) {
}
