package com.rovedasa.contratos.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Resultado del proceso de ajuste periódico (programado o POST /api/contratos/ajustes-periodicos).
 * Cada contrato ACTIVO se pone al día aplicando en orden todos sus ajustes pendientes; los que no se
 * pudieron aplicar (por ejemplo, índice no publicado o BCRA caído sin caché) quedan en {@code pendientes}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumenAjustePeriodicoResponse {

    private int contratosRevisados;
    private int ajustesAplicados;
    private List<Pendiente> pendientes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pendiente {
        private Long contratoId;
        private String motivo;
    }
}
