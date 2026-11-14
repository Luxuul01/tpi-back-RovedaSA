package com.rovedasa.contratos.controller;

import com.rovedasa.contratos.dto.CuotaResumenDTO;
import com.rovedasa.contratos.service.ContratoService;
import com.rovedasa.contratos.service.CuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Controlador REST de cuotas.
 * Expone la consulta individual con mora y el registro de pagos.
 */
@Slf4j
@RestController
@RequestMapping("/api/cuotas")
@Tag(name = "Cuotas", description = "Consulta de cuotas con mora y registro de pagos")
@RequiredArgsConstructor
public class CuotaController {

    private final ContratoService contratoService;
    private final CuotaService cuotaService;

    // ------------------------------------------------------------------
    // Obtener una cuota con mora (GET /api/cuotas/{id})
    // ------------------------------------------------------------------
    @Operation(summary = "Obtener una cuota individual con su mora calculada")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROPIETARIO', 'INQUILINO')")
    public ResponseEntity<CuotaResumenDTO> obtenerCuota(
            @PathVariable("id") Long id,
            @RequestParam(name = "fechaReferencia", required = false) LocalDate fechaReferencia,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("GET /api/cuotas/{} - rol={}, sub={}", id, usuario.rol(), usuario.sub());
        CuotaResumenDTO cuota = contratoService.obtenerCuotaConMora(id, fechaReferencia, usuario.rol(), usuario.sub());
        return ResponseEntity.ok(cuota);
    }

    // ------------------------------------------------------------------
    // Registrar pago de cuota (PATCH /api/cuotas/{id}/pago)
    // ------------------------------------------------------------------
    @Operation(summary = "Registrar pago de una cuota pendiente (PENDIENTE -> PAGADA)")
    @PatchMapping("/{id}/pago")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<CuotaResumenDTO> pagarCuota(
            @PathVariable("id") Long id,
            @RequestParam(name = "fechaPago", required = false) LocalDate fechaPago,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("PATCH /api/cuotas/{}/pago - fechaPago={}, usuario sub={}", id, fechaPago, usuario.sub());
        CuotaResumenDTO response = cuotaService.pagarCuota(id, fechaPago);
        return ResponseEntity.ok(response);
    }
}
