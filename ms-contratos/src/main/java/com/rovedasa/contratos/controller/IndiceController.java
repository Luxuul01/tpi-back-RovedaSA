package com.rovedasa.contratos.controller;

import com.rovedasa.commons.exception.ApiError;
import com.rovedasa.contratos.dto.CoeficienteAjusteDTO;
import com.rovedasa.contratos.dto.ResultadoAjusteDTO;
import com.rovedasa.contratos.dto.ValorIndiceDTO;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.service.IndiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Consulta de índices de actualización (ICL / IPC) y simulación de ajustes.
 * Todos son GET: no modifican datos de negocio (el caché se actualiza como efecto técnico).
 */
@Tag(name = "Índices BCRA", description = "ICL e IPC del BCRA con caché de contingencia")
@RestController
@RequestMapping("/api/indices")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROPIETARIO', 'INQUILINO')")
@RequiredArgsConstructor
public class IndiceController {

    private final IndiceService indiceService;

    @Operation(summary = "Valor de un índice en una fecha",
            description = "ICL: valor diario. IPC: inflación mensual (%) del último mes publicado. "
                    + "Si no hay valor para la fecha exacta se toma el último publicado anterior. Si el BCRA no "
                    + "responde se usa el caché local (origen = CACHE_CONTINGENCIA), solo con un valor de hasta "
                    + "7 días antes (ICL) o de los 2 meses anteriores (IPC).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Valor encontrado"),
            @ApiResponse(responseCode = "400", description = "Tipo de índice o fecha inválidos, fecha futura "
                    + "o anterior al inicio de la serie",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "BCRA caído o con error y sin valor aceptable en caché",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{tipo}/valor")
    public ResponseEntity<ValorIndiceDTO> obtenerValor(
            @PathVariable("tipo") TipoIndiceEnum tipo,
            @RequestParam("fecha") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return ResponseEntity.ok(indiceService.obtenerValor(tipo, fecha));
    }

    @Operation(summary = "Coeficiente de ajuste entre dos fechas",
            description = "ICL: ICL(hasta) / ICL(desde). IPC: producto de (1 + inflación mensual / 100) "
                    + "de los meses cuyo primer día está entre desde − 1 mes (incluido) y hasta − 1 mes "
                    + "(excluido); p. ej. 01/07 → 01/10 usa junio, julio y agosto.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Coeficiente calculado"),
            @ApiResponse(responseCode = "400", description = "Fechas inválidas",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Índice no disponible (BCRA caído sin caché, "
                    + "o IPC de algún mes todavía no publicado)",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{tipo}/coeficiente")
    public ResponseEntity<CoeficienteAjusteDTO> obtenerCoeficiente(
            @PathVariable("tipo") TipoIndiceEnum tipo,
            @RequestParam("desde") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam("hasta") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return ResponseEntity.ok(indiceService.calcularCoeficiente(tipo, desde, hasta));
    }

    @Operation(summary = "Simular el ajuste de un canon",
            description = "Canon Nuevo = Canon Anterior x coeficiente. Devuelve los mismos datos que "
                    + "se registran en la entidad Ajuste, sin persistir nada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ajuste calculado"),
            @ApiResponse(responseCode = "400", description = "Canon o fechas inválidos",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Índice no disponible",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{tipo}/simulacion-ajuste")
    public ResponseEntity<ResultadoAjusteDTO> simularAjuste(
            @PathVariable("tipo") TipoIndiceEnum tipo,
            @RequestParam("canonAnterior") BigDecimal canonAnterior,
            @RequestParam("desde") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam("hasta") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return ResponseEntity.ok(indiceService.calcularAjuste(tipo, canonAnterior, desde, hasta));
    }
}
