package com.rovedasa.contratos.controller;

import com.rovedasa.contratos.dto.AjusteResponse;
import com.rovedasa.contratos.dto.AplicarAjusteRequest;
import com.rovedasa.contratos.dto.ContratoResponse;
import com.rovedasa.contratos.dto.CrearContratoRequest;
import com.rovedasa.contratos.dto.CuotaResumenDTO;
import com.rovedasa.contratos.dto.RescisionResponse;
import com.rovedasa.contratos.dto.RescindirContratoRequest;
import com.rovedasa.contratos.dto.SeniarContratoRequest;
import com.rovedasa.contratos.service.ContratoService;
import com.rovedasa.contratos.service.CuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Controlador REST de ms-contratos.
 * Expone los endpoints del ciclo de vida de contratos (Patrón State), cuotas, mora y ajustes ICL.
 *
 * Seguridad:
 * - POST/PUT/PATCH/DELETE: solo ADMINISTRADOR (configurado en SecurityConfig y @PreAuthorize).
 * - GET: ADMINISTRADOR, PROPIETARIO e INQUILINO; el filtrado por sub se hace en el servicio.
 */
@Slf4j
@RestController
@RequestMapping("/api/contratos")
@Tag(name = "Contratos", description = "Ciclo de vida de contratos de alquiler (Patrón State), cuotas, mora e ICL")
@RequiredArgsConstructor
public class ContratoController {

    private final ContratoService contratoService;
    private final CuotaService cuotaService;

    // ------------------------------------------------------------------
    // Crear borrador (POST /api/contratos)
    // ------------------------------------------------------------------
    @Operation(summary = "Crear borrador de contrato en estado CREADO")
    @PostMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> crearContrato(
            @Valid @RequestBody CrearContratoRequest request,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos - usuario sub={}", usuario.sub());
        ContratoResponse response = contratoService.crearContrato(request);
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Listar contratos filtrado por rol (GET /api/contratos) - ÚNICO endpoint
    // ------------------------------------------------------------------
    @Operation(summary = "Listar contratos filtrado por rol del usuario autenticado")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROPIETARIO', 'INQUILINO')")
    public ResponseEntity<List<ContratoResponse>> listarContratos(Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("GET /api/contratos - rol={}, sub={}", usuario.rol(), usuario.sub());
        List<ContratoResponse> contratos = contratoService.listarContratos(usuario.rol(), usuario.sub());
        return ResponseEntity.ok(contratos);
    }

    // ------------------------------------------------------------------
    // Detalle de contrato (GET /api/contratos/{id})
    // ------------------------------------------------------------------
    @Operation(summary = "Obtener detalle de un contrato por ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROPIETARIO', 'INQUILINO')")
    public ResponseEntity<ContratoResponse> obtenerContrato(
            @PathVariable("id") Long id,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("GET /api/contratos/{} - rol={}, sub={}", id, usuario.rol(), usuario.sub());
        ContratoResponse response = contratoService.obtenerContrato(id, usuario.rol(), usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Señar (POST /api/contratos/{id}/seniar)
    // ------------------------------------------------------------------
    @Operation(summary = "Registrar seña y reservar propiedad (CREADO -> SENIADO)")
    @PostMapping("/{id}/seniar")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> seniarContrato(
            @PathVariable("id") Long id,
            @Valid @RequestBody SeniarContratoRequest request,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/seniar - usuario sub={}", id, usuario.sub());
        ContratoResponse response = contratoService.seniarContrato(id, request, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Activar (POST /api/contratos/{id}/activar)
    // ------------------------------------------------------------------
    @Operation(summary = "Formalizar contrato: verificar garante, alquilar propiedad, generar cuotas (SENIADO -> ACTIVO)")
    @PostMapping("/{id}/activar")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> activarContrato(
            @PathVariable("id") Long id,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/activar - usuario sub={}", id, usuario.sub());
        ContratoResponse response = contratoService.activarContrato(id, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Cancelar (POST /api/contratos/{id}/cancelar)
    // ------------------------------------------------------------------
    @Operation(summary = "Cancelar borrador o seña (CREADO/SENIADO -> CANCELADO)")
    @PostMapping("/{id}/cancelar")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> cancelarContrato(
            @PathVariable("id") Long id,
            @RequestParam("motivo") String motivo,
            @RequestParam(value = "retenerSenia", defaultValue = "false") boolean retenerSenia,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/cancelar - motivo={}, retenerSenia={}", id, motivo, retenerSenia);
        ContratoResponse response = contratoService.cancelarContrato(id, motivo, retenerSenia, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Rescindir (POST /api/contratos/{id}/rescindir)
    // ------------------------------------------------------------------
    @Operation(summary = "Rescindir contrato anticipadamente: multa, anular cuotas futuras, liberar propiedad (ACTIVO -> RESCINDIDO)")
    @PostMapping("/{id}/rescindir")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<RescisionResponse> rescindirContrato(
            @PathVariable("id") Long id,
            @Valid @RequestBody RescindirContratoRequest request,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/rescindir - fechaRescision={}", id, request.getFechaRescision());
        RescisionResponse response = contratoService.rescindirContrato(id, request, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Finalizar (POST /api/contratos/{id}/finalizar)
    // ------------------------------------------------------------------
    @Operation(summary = "Finalizar contrato por cumplimiento de plazo (ACTIVO -> FINALIZADO)")
    @PostMapping("/{id}/finalizar")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> finalizarContrato(
            @PathVariable("id") Long id,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/finalizar - usuario sub={}", id, usuario.sub());
        ContratoResponse response = contratoService.finalizarContrato(id, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Aplicar ajuste por ICL (POST /api/contratos/{id}/ajustes)
    // ------------------------------------------------------------------
    @Operation(summary = "Aplicar ajuste por ICL a un contrato activo y generar nuevas cuotas")
    @PostMapping("/{id}/ajustes")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> aplicarAjuste(
            @PathVariable("id") Long id,
            @Valid @RequestBody AplicarAjusteRequest request,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/ajustes - desde={} hasta={}", id, request.getFechaIndiceDesde(), request.getFechaIndiceHasta());
        ContratoResponse response = contratoService.aplicarAjuste(id, request, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Historial de ajustes (GET /api/contratos/{id}/ajustes)
    // ------------------------------------------------------------------
    @Operation(summary = "Consultar historial de ajustes de un contrato")
    @GetMapping("/{id}/ajustes")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROPIETARIO', 'INQUILINO')")
    public ResponseEntity<List<AjusteResponse>> obtenerAjustes(
            @PathVariable("id") Long id,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("GET /api/contratos/{}/ajustes - rol={}, sub={}", id, usuario.rol(), usuario.sub());
        ContratoResponse contrato = contratoService.obtenerContrato(id, usuario.rol(), usuario.sub());
        return ResponseEntity.ok(contrato.getAjustes());
    }

    // ------------------------------------------------------------------
    // Ajuste periódico masivo (POST /api/contratos/ajuste-periodico)
    // ------------------------------------------------------------------
    @Operation(summary = "Ejecutar ajuste masivo de ICL para todos los contratos activos que correspondan")
    @PostMapping("/ajuste-periodico")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<Void> ejecutarAjustePeriodico(Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/ajuste-periodico - usuario sub={}", usuario.sub());
        contratoService.ejecutarAjustePeriodico();
        return ResponseEntity.ok().build();
    }

    // ------------------------------------------------------------------
    // Listar cuotas con mora (GET /api/contratos/{id}/cuotas)
    // ------------------------------------------------------------------
    @Operation(summary = "Listar cuotas de un contrato con mora calculada a la fecha de referencia")
    @GetMapping("/{id}/cuotas")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROPIETARIO', 'INQUILINO')")
    public ResponseEntity<List<CuotaResumenDTO>> listarCuotas(
            @PathVariable("id") Long id,
            @RequestParam(name = "fechaReferencia", required = false) LocalDate fechaReferencia,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("GET /api/contratos/{}/cuotas - fechaReferencia={}", id, fechaReferencia);
        // Obtener el contrato para validar pertenencia y obtener porcentaje de mora
        ContratoResponse contrato = contratoService.obtenerContrato(id, usuario.rol(), usuario.sub());
        List<CuotaResumenDTO> cuotas = cuotaService.listarCuotasConMora(
                id, contrato.getPorcentajeMoraDiaria(), fechaReferencia);
        return ResponseEntity.ok(cuotas);
    }

}
