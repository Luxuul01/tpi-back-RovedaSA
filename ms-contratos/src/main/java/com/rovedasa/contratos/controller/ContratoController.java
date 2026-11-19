package com.rovedasa.contratos.controller;

import com.rovedasa.contratos.dto.AjusteResponse;
import com.rovedasa.contratos.dto.AplicarAjusteRequest;
import com.rovedasa.contratos.dto.ContratoResponse;
import com.rovedasa.contratos.dto.CrearContratoRequest;
import com.rovedasa.contratos.dto.CuotaResumenDTO;
import com.rovedasa.contratos.dto.RescisionResponse;
import com.rovedasa.contratos.dto.RescindirContratoRequest;
import com.rovedasa.contratos.dto.ResumenAjustePeriodicoResponse;
import com.rovedasa.contratos.dto.SeniarContratoRequest;
import com.rovedasa.contratos.service.AjusteProgramadoService;
import com.rovedasa.contratos.service.ContratoService;
import com.rovedasa.contratos.service.CuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
    private final AjusteProgramadoService ajusteProgramadoService;

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
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
    // Seña (POST /api/contratos/{id}/senia)
    // ------------------------------------------------------------------
    @Operation(summary = "Registrar seña y reservar propiedad (CREADO -> SENIADO)")
    @PostMapping("/{id}/senia")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> seniarContrato(
            @PathVariable("id") Long id,
            @Valid @RequestBody SeniarContratoRequest request,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/senia - usuario sub={}", id, usuario.sub());
        ContratoResponse response = contratoService.seniarContrato(id, request, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Activación (POST /api/contratos/{id}/activacion)
    // ------------------------------------------------------------------
    @Operation(summary = "Formalizar contrato: verificar garante, alquilar propiedad, generar cuotas (SENIADO -> ACTIVO)")
    @PostMapping("/{id}/activacion")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> activarContrato(
            @PathVariable("id") Long id,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/activacion - usuario sub={}", id, usuario.sub());
        ContratoResponse response = contratoService.activarContrato(id, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Cancelación (POST /api/contratos/{id}/cancelacion)
    // ------------------------------------------------------------------
    @Operation(summary = "Cancelar borrador o seña (CREADO/SENIADO -> CANCELADO)")
    @PostMapping("/{id}/cancelacion")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> cancelarContrato(
            @PathVariable("id") Long id,
            // Mismo largo que la columna motivo_cancelacion (500): se valida antes de tocar el catálogo (400)
            @RequestParam("motivo")
            @Size(max = 500, message = "El motivo de cancelación admite como máximo 500 caracteres") String motivo,
            @RequestParam(value = "retenerSenia", defaultValue = "false") boolean retenerSenia,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/cancelacion - motivo={}, retenerSenia={}", id, motivo, retenerSenia);
        ContratoResponse response = contratoService.cancelarContrato(id, motivo, retenerSenia, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Rescisión (POST /api/contratos/{id}/rescision)
    // ------------------------------------------------------------------
    @Operation(summary = "Rescindir contrato anticipadamente: multa, anular cuotas futuras, liberar propiedad (ACTIVO -> RESCINDIDO)")
    @PostMapping("/{id}/rescision")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<RescisionResponse> rescindirContrato(
            @PathVariable("id") Long id,
            @Valid @RequestBody RescindirContratoRequest request,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/rescision - fechaRescision={}", id, request.getFechaRescision());
        RescisionResponse response = contratoService.rescindirContrato(id, request, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Finalización (POST /api/contratos/{id}/finalizacion)
    // ------------------------------------------------------------------
    @Operation(summary = "Finalizar contrato por cumplimiento de plazo (ACTIVO -> FINALIZADO)")
    @PostMapping("/{id}/finalizacion")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> finalizarContrato(
            @PathVariable("id") Long id,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/finalizacion - usuario sub={}", id, usuario.sub());
        ContratoResponse response = contratoService.finalizarContrato(id, usuario.sub());
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // Aplicar ajuste por el índice del contrato (POST /api/contratos/{id}/ajustes)
    // ------------------------------------------------------------------
    @Operation(summary = "Aplicar el próximo ajuste por el índice del contrato (ICL o IPC) y generar las cuotas del período",
            description = "El ajuste k usa la ventana desde H(k-1) hasta H(k), con H(k) = fechaInicio + k x frecuencia meses. "
                    + "Las fechas del body son opcionales; si se envían tienen que coincidir con esa ventana (si no, 400).")
    @PostMapping("/{id}/ajustes")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ContratoResponse> aplicarAjuste(
            @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AplicarAjusteRequest request,
            Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/{}/ajustes - desde={} hasta={}", id,
                request != null ? request.getFechaIndiceDesde() : null, request != null ? request.getFechaIndiceHasta() : null);
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
    // Ajuste periódico masivo (POST /api/contratos/ajustes-periodicos)
    // ------------------------------------------------------------------
    @Operation(summary = "Ejecutar el ajuste periódico: pone al día todos los ajustes pendientes de los contratos activos",
            description = "Devuelve cuántos contratos se revisaron, cuántos ajustes se aplicaron y cuáles quedaron pendientes y por qué.")
    @PostMapping("/ajustes-periodicos")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ResumenAjustePeriodicoResponse> ejecutarAjustePeriodico(Authentication authentication) {
        UsuarioToken usuario = UsuarioToken.desde(authentication);
        log.info("POST /api/contratos/ajustes-periodicos - usuario sub={}", usuario.sub());
        return ResponseEntity.ok(ajusteProgramadoService.ejecutarAjustePeriodico());
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
