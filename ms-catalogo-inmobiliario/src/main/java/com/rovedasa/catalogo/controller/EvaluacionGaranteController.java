package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.service.SolvenciaGaranteService;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Evaluación de solvencia de garantes contra la Central de Deudores del BCRA (CU-02).
 * Queda bajo /api/personas para que el Gateway ya lo enrute a ms-catalogo.
 */
@Tag(name = "Garantes BCRA", description = "Evaluación de solvencia con la Central de Deudores del BCRA")
@RestController
@RequestMapping("/api/personas/garantes")
@PreAuthorize("hasRole('ADMINISTRADOR')")
@RequiredArgsConstructor
public class EvaluacionGaranteController {

    private final SolvenciaGaranteService solvenciaService;

    @Operation(summary = "Evaluar solvencia de un garante por CUIT/CUIL",
            description = "Reglas: situación crediticia <= 2, 0 cheques rechazados e ingresos >= 3 x canon "
                    + "(si se informa canonMensual).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evaluación realizada (ver esSolvente y motivoEvaluacion)"),
            @ApiResponse(responseCode = "400", description = "CUIT inválido o parámetros mal formados",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Central de Deudores del BCRA no disponible",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{cuit}/evaluacion-bcra")
    public ResponseEntity<ValidacionGaranteDTO> evaluar(
            @PathVariable("cuit") String cuit,
            @RequestParam(name = "ingresosDeclarados", required = false) BigDecimal ingresosDeclarados,
            @RequestParam(name = "canonMensual", required = false) BigDecimal canonMensual) {
        return ResponseEntity.ok(solvenciaService.evaluar(cuit, ingresosDeclarados, canonMensual));
    }
}
