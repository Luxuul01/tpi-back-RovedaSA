package com.rovedasa.contratos.service;

import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.client.CatalogoClient;
import com.rovedasa.contratos.dto.AjusteResponse;
import com.rovedasa.contratos.dto.AplicarAjusteRequest;
import com.rovedasa.contratos.dto.ContratoResponse;
import com.rovedasa.contratos.dto.CrearContratoRequest;
import com.rovedasa.contratos.dto.CuotaResumenDTO;
import com.rovedasa.contratos.dto.RescisionResponse;
import com.rovedasa.contratos.dto.RescindirContratoRequest;
import com.rovedasa.contratos.dto.ResultadoAjusteDTO;
import com.rovedasa.contratos.dto.SeniarContratoRequest;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.AjusteRepository;
import com.rovedasa.contratos.repository.ContratoRepository;
import com.rovedasa.contratos.state.EstadoActivo;
import com.rovedasa.contratos.state.EstadoContrato;
import com.rovedasa.contratos.state.EstadoContratoFactory;
import com.rovedasa.contratos.state.EstadoCreado;
import com.rovedasa.contratos.state.EstadoSeniado;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio de negocio de contratos. Orquesta el Patrón State, las llamadas al catálogo
 * (CatalogoClient), la persistencia (ContratoRepository) y la compensación Saga
 * cuando falla el guardado local tras cambiar el estado de la propiedad.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContratoService {

    private final ContratoRepository contratoRepository;
    private final AjusteRepository ajusteRepository;
    private final CuotaService cuotaService;
    private final CatalogoClient catalogoClient;
    private final EstadoContratoFactory estadoFactory;
    private final IndiceService indiceService;
    private final Clock clock;

    // ------------------------------------------------------------------
    // Crear borrador (CREADO)
    // ------------------------------------------------------------------
    @Transactional
    public ContratoResponse crearContrato(CrearContratoRequest request) {
        log.info("Creando borrador de contrato para propiedadId={}, propietario={}, inquilino={}",
                request.getPropiedadId(), request.getPropietarioId(), request.getInquilinoId());

        if (request.getFechaInicio() != null && request.getFechaFin() != null
                && !request.getFechaFin().isAfter(request.getFechaInicio())) {
            throw new OperacionInvalidaException("La fecha de fin debe ser posterior a la fecha de inicio");
        }

        // 1. Validar que la propiedad exista y no esté INACTIVA ni ALQUILADA
        var propiedad = catalogoClient.obtenerPropiedad(request.getPropiedadId());
        if (propiedad.getEstado() == EstadoPropiedadEnum.INACTIVA || propiedad.getEstado() == EstadoPropiedadEnum.ALQUILADA) {
            throw new OperacionInvalidaException(
                    "La propiedad " + request.getPropiedadId() + " no está disponible para contratar (estado: " + propiedad.getEstado() + ")");
        }
        if (!request.getPropietarioId().equals(propiedad.getPropietarioId())) {
            throw new OperacionInvalidaException(
                    "El propietario indicado no es el dueño de la propiedad " + request.getPropiedadId());
        }

        // 2. Obtener keycloakUserId de propietario e inquilino para guardarlo en el contrato
        var propietario = catalogoClient.obtenerPersona(request.getPropietarioId());
        var inquilino = catalogoClient.obtenerPersona(request.getInquilinoId());

        if (propietario.getKeycloakUserId() == null) {
            throw new OperacionInvalidaException("El propietario no tiene usuario de Keycloak asociado");
        }
        if (inquilino.getKeycloakUserId() == null) {
            throw new OperacionInvalidaException("El inquilino no tiene usuario de Keycloak asociado");
        }

        // 3. Crear entidad Contrato en estado CREADO
        Contrato contrato = Contrato.builder()
                .propiedadId(request.getPropiedadId())
                .propietarioId(request.getPropietarioId())
                .inquilinoId(request.getInquilinoId())
                .garanteId(request.getGaranteId())
                .propietarioKeycloakId(propietario.getKeycloakUserId())
                .inquilinoKeycloakId(inquilino.getKeycloakUserId())
                .fechaInicio(request.getFechaInicio())
                .fechaFin(request.getFechaFin())
                .frecuenciaAjusteMeses(request.getFrecuenciaAjusteMeses())
                .montoInicial(request.getMontoInicial())
                .montoActual(request.getMontoInicial())
                .porcentajeMoraDiaria(request.getPorcentajeMoraDiaria())
                .diaVencimientoMes(request.getDiaVencimientoMes())
                .estado(EstadoContratoEnum.CREADO)
                .build();

        contrato = contratoRepository.save(contrato);
        log.info("Contrato borrador creado: id={}, nroContrato={}, estado={}",
                contrato.getId(), contrato.getNroContrato(), contrato.getEstado());

        return toResponse(contrato);
    }

    // ------------------------------------------------------------------
    // Señar (CREADO -> SENIADO)
    // ------------------------------------------------------------------
    @Transactional
    public ContratoResponse seniarContrato(Long contratoId, SeniarContratoRequest request, String usuarioKeycloakId) {
        log.info("Señando contrato {} con montoSenia={}, fechaLimiteSenia={}", contratoId, request.getMontoSenia(), request.getFechaLimiteSenia());

        Contrato contrato = contratoRepository.findById(contratoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Contrato con ID " + contratoId + " no fue encontrado"));

        // 4. Validar que el contrato esté en CREADO
        EstadoContrato estadoActual = estadoFactory.obtenerEstado(contrato);
        if (!(estadoActual instanceof EstadoCreado)) {
            throw new OperacionInvalidaException(
                    "Solo se puede señar un contrato en estado CREADO. Estado actual: " + contrato.getEstado());
        }

        // 5. Validar que la propiedad siga DISPONIBLE en el catálogo
        var propiedad = catalogoClient.obtenerPropiedad(contrato.getPropiedadId());
        if (propiedad.getEstado() != EstadoPropiedadEnum.DISPONIBLE) {
            throw new OperacionInvalidaException(
                    "La propiedad " + contrato.getPropiedadId() + " ya no está DISPONIBLE (estado: " + propiedad.getEstado() + ")");
        }

        // 6. Ejecutar transición State CREADO -> SENIADO
        estadoActual.seniar(contrato, request.getMontoSenia(), request.getFechaLimiteSenia(), LocalDate.now(clock));

        // 7. Llamar al catálogo para pasar la propiedad a RESERVADA
        try {
            catalogoClient.actualizarEstadoPropiedad(
                    contrato.getPropiedadId(),
                    EstadoPropiedadEnum.RESERVADA,
                    contrato.getInquilinoId(),
                    contrato.getGaranteId());
        } catch (RuntimeException e) {
            log.warn("El catálogo rechazó la reserva de la propiedad {}. No se continúa con la seña.",
                    contrato.getPropiedadId(), e);
            throw e;
        }

        try {
            contrato = contratoRepository.saveAndFlush(contrato);
        } catch (Exception e) {
            // 8. COMPENSACIÓN: si falla el save local, devolver la propiedad a DISPONIBLE
            log.error("Fallo al guardar el contrato tras reservar la propiedad {}. Compensando: devolviendo a DISPONIBLE.",
                    contrato.getPropiedadId(), e);
            compensarEstadoPropiedad(contrato, EstadoPropiedadEnum.DISPONIBLE);
            throw new OperacionInvalidaException("Error al guardar el contrato tras señar. Se revirtió el estado de la propiedad.");
        }

        log.info("Contrato {} pasó a SENIADO. Propiedad {} reservada.", contratoId, contrato.getPropiedadId());
        return toResponse(contrato);
    }

    // ------------------------------------------------------------------
    // Activar (SENIADO -> ACTIVO)
    // ------------------------------------------------------------------
    @Transactional
    public ContratoResponse activarContrato(Long contratoId, String usuarioKeycloakId) {
        log.info("Activando contrato {}", contratoId);

        Contrato contrato = contratoRepository.findById(contratoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Contrato con ID " + contratoId + " no fue encontrado"));

        EstadoContrato estadoActual = estadoFactory.obtenerEstado(contrato);
        if (!(estadoActual instanceof EstadoSeniado)) {
            throw new OperacionInvalidaException(
                    "Solo se puede activar un contrato en estado SENIADO. Estado actual: " + contrato.getEstado());
        }

        // Validar fecha límite de seña no vencida
        if (contrato.getFechaLimiteSenia() != null && LocalDate.now(clock).isAfter(contrato.getFechaLimiteSenia())) {
            throw new OperacionInvalidaException(
                    "La seña del contrato " + contratoId + " venció el " + contrato.getFechaLimiteSenia());
        }

        // Validar solvencia del garante vía catálogo/BCRA
        var validacion = catalogoClient.evaluarGarante(contrato.getGaranteId());
        if (!Boolean.TRUE.equals(validacion.getEsSolvente())) {
            throw new OperacionInvalidaException(
                    "El garante no es solvente: " + validacion.getMotivoEvaluacion() + ". Contrato sigue SENIADO.");
        }

        // Ejecutar transición State SENIADO -> ACTIVO (genera cuotas iniciales)
        List<Cuota> cuotasIniciales = estadoActual.activar(contrato, LocalDate.now(clock));

        // Cambiar propiedad a ALQUILADA en el catálogo
        try {
            catalogoClient.actualizarEstadoPropiedad(
                    contrato.getPropiedadId(),
                    EstadoPropiedadEnum.ALQUILADA,
                    contrato.getInquilinoId(),
                    contrato.getGaranteId());
        } catch (RuntimeException e) {
            log.warn("El catálogo rechazó pasar la propiedad {} a ALQUILADA.", contrato.getPropiedadId(), e);
            throw e;
        }

        try {
            // Cancelar borradores competidores en CREADO sobre la misma propiedad
            final Long contratoIdActual = contrato.getId();
            List<Contrato> borradoresCompetidores = contratoRepository
                    .findByPropiedadIdAndEstado(contrato.getPropiedadId(), EstadoContratoEnum.CREADO)
                    .stream()
                    .filter(c -> !c.getId().equals(contratoIdActual))
                    .collect(Collectors.toList());

            for (Contrato borrador : borradoresCompetidores) {
                borrador.setEstado(EstadoContratoEnum.CANCELADO);
                borrador.setMotivoCancelacion("Cancelado por activación de contrato competidor " + contrato.getNroContrato());
                contratoRepository.save(borrador);
                log.info("Borrador competidor {} cancelado por activación de contrato {}", borrador.getId(), contrato.getId());
            }

            contrato = contratoRepository.saveAndFlush(contrato);
            cuotaService.guardarCuotas(cuotasIniciales);
        } catch (Exception e) {
            // COMPENSACIÓN: devolver propiedad a RESERVADA (estado anterior antes de ALQUILADA)
            log.error("Fallo al guardar el contrato tras activar {}. Compensando: devolviendo propiedad a RESERVADA.",
                    contrato.getPropiedadId(), e);
            compensarEstadoPropiedad(contrato, EstadoPropiedadEnum.RESERVADA);
            throw new OperacionInvalidaException("Error al activar el contrato. Se revirtió el estado de la propiedad.");
        }

        log.info("Contrato {} pasó a ACTIVO. Propiedad {} alquilada. Cuotas iniciales: {}",
                contratoId, contrato.getPropiedadId(), cuotasIniciales.size());
        return toResponse(contrato);
    }

    // ------------------------------------------------------------------
    // Cancelar (CREADO/SENIADO -> CANCELADO)
    // ------------------------------------------------------------------
    @Transactional
    public ContratoResponse cancelarContrato(Long contratoId, String motivo, boolean retenerSenia, String usuarioKeycloakId) {
        log.info("Cancelando contrato {} con motivo: {}", contratoId, motivo);

        Contrato contrato = contratoRepository.findById(contratoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Contrato con ID " + contratoId + " no fue encontrado"));

        EstadoContrato estadoActual = estadoFactory.obtenerEstado(contrato);
        if (!(estadoActual instanceof EstadoCreado
                || estadoActual instanceof EstadoSeniado)) {
            throw new OperacionInvalidaException(
                    "Solo se puede cancelar un contrato en estado CREADO o SENIADO. Estado actual: " + contrato.getEstado());
        }

        EstadoPropiedadEnum estadoPropiedadAnterior = null;
        boolean propiedadOcupada = false;

        // Si estaba SENIADO, la propiedad está RESERVADA y hay que liberarla
        if (estadoActual instanceof EstadoSeniado) {
            var propiedad = catalogoClient.obtenerPropiedad(contrato.getPropiedadId());
            estadoPropiedadAnterior = propiedad.getEstado();
            propiedadOcupada = true;
        }

        // Ejecutar transición State
        estadoActual.cancelar(contrato, motivo, retenerSenia);

        // Liberar propiedad a DISPONIBLE si estaba reservada
        if (propiedadOcupada && estadoPropiedadAnterior == EstadoPropiedadEnum.RESERVADA) {
            try {
                catalogoClient.actualizarEstadoPropiedad(
                        contrato.getPropiedadId(),
                        EstadoPropiedadEnum.DISPONIBLE,
                        null,
                        null);
            } catch (RuntimeException e) {
                log.warn("El catálogo rechazó liberar la propiedad {} tras cancelar el contrato {}.",
                        contrato.getPropiedadId(), contratoId, e);
                throw e;
            }

            try {
                contrato = contratoRepository.saveAndFlush(contrato);
            } catch (Exception ex) {
                // COMPENSACIÓN: devolver a RESERVADA
                log.error("Fallo al guardar tras cancelar {}. Compensando: devolviendo propiedad a RESERVADA.", contratoId, ex);
                compensarEstadoPropiedad(contrato, EstadoPropiedadEnum.RESERVADA);
                throw new OperacionInvalidaException("Error al cancelar el contrato. Se revirtió el estado de la propiedad.");
            }
        } else {
            contrato = contratoRepository.saveAndFlush(contrato);
        }

        log.info("Contrato {} pasó a CANCELADO.", contratoId);
        return toResponse(contrato);
    }

    // ------------------------------------------------------------------
    // Rescindir (ACTIVO -> RESCINDIDO)
    // ------------------------------------------------------------------
    @Transactional
    public RescisionResponse rescindirContrato(Long contratoId, RescindirContratoRequest request, String usuarioKeycloakId) {
        log.info("Rescindiendo contrato {} en fecha {}", contratoId, request.getFechaRescision());

        Contrato contrato = contratoRepository.findById(contratoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Contrato con ID " + contratoId + " no fue encontrado"));

        EstadoContrato estadoActual = estadoFactory.obtenerEstado(contrato);
        if (!(estadoActual instanceof EstadoActivo)) {
            throw new OperacionInvalidaException(
                    "Solo se puede rescindir un contrato en estado ACTIVO. Estado actual: " + contrato.getEstado());
        }

        // Ejecutar transición State (calcula multa y anula cuotas futuras)
        BigDecimal penalizacion = estadoActual.rescindir(contrato, request.getFechaRescision());

        // Liberar propiedad a DISPONIBLE
        try {
            catalogoClient.actualizarEstadoPropiedad(
                    contrato.getPropiedadId(),
                    EstadoPropiedadEnum.DISPONIBLE,
                    null,
                    null);
        } catch (RuntimeException e) {
            log.warn("El catálogo rechazó liberar la propiedad {} tras rescindir el contrato {}.",
                    contrato.getPropiedadId(), contratoId, e);
            throw e;
        }

        try {
            contrato = contratoRepository.saveAndFlush(contrato);
        } catch (Exception e) {
            log.error("Fallo al guardar la rescisión del contrato {}. Compensando: devolviendo propiedad a ALQUILADA.",
                    contratoId, e);
            compensarEstadoPropiedad(contrato, EstadoPropiedadEnum.ALQUILADA);
            throw new OperacionInvalidaException("Error al guardar la rescisión. Se revirtió el estado de la propiedad.");
        }

        Integer cuotasAnuladas = (int) contrato.getCuotas().stream()
                .filter(c -> c.getEstado() == EstadoCuotaEnum.ANULADA)
                .count();

        log.info("Contrato {} pasó a RESCINDIDO. Penalización: {}, cuotas anuladas: {}",
                contratoId, penalizacion, cuotasAnuladas);

        return RescisionResponse.builder()
                .contratoId(contrato.getId())
                .fechaRescision(request.getFechaRescision())
                .penalizacion(penalizacion)
                .cuotasAnuladas(cuotasAnuladas)
                .build();
    }

    // ------------------------------------------------------------------
    // Finalizar (ACTIVO -> FINALIZADO)
    // ------------------------------------------------------------------
    @Transactional
    public ContratoResponse finalizarContrato(Long contratoId, String usuarioKeycloakId) {
        log.info("Finalizando contrato {}", contratoId);

        Contrato contrato = contratoRepository.findById(contratoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Contrato con ID " + contratoId + " no fue encontrado"));

        EstadoContrato estadoActual = estadoFactory.obtenerEstado(contrato);
        if (!(estadoActual instanceof EstadoActivo)) {
            throw new OperacionInvalidaException(
                    "Solo se puede finalizar un contrato en estado ACTIVO. Estado actual: " + contrato.getEstado());
        }

        // Validar que la fecha actual sea >= fechaFin
        LocalDate hoy = LocalDate.now(clock);
        if (hoy.isBefore(contrato.getFechaFin())) {
            throw new OperacionInvalidaException(
                    "No se puede finalizar el contrato " + contratoId + " antes de su fecha de vencimiento (" + contrato.getFechaFin() + ")");
        }

        // Ejecutar transición State
        estadoActual.finalizar(contrato, hoy);

        // Liberar propiedad a DISPONIBLE
        try {
            catalogoClient.actualizarEstadoPropiedad(
                    contrato.getPropiedadId(),
                    EstadoPropiedadEnum.DISPONIBLE,
                    null,
                    null);
        } catch (RuntimeException e) {
            log.warn("El catálogo rechazó liberar la propiedad {} tras finalizar el contrato {}.",
                    contrato.getPropiedadId(), contratoId, e);
            throw e;
        }

        try {
            contrato = contratoRepository.saveAndFlush(contrato);
        } catch (Exception e) {
            log.error("Fallo al guardar la finalización del contrato {}. Compensando: devolviendo propiedad a ALQUILADA.",
                    contratoId, e);
            compensarEstadoPropiedad(contrato, EstadoPropiedadEnum.ALQUILADA);
            throw new OperacionInvalidaException("Error al guardar la finalización. Se revirtió el estado de la propiedad.");
        }

        log.info("Contrato {} pasó a FINALIZADO. Propiedad {} liberada.", contratoId, contrato.getPropiedadId());
        return toResponse(contrato);
    }

    // ------------------------------------------------------------------
    // Aplicar ajuste por ICL (ACTIVO -> ACTIVO + nuevas cuotas)
    // ------------------------------------------------------------------
    @Transactional
    public ContratoResponse aplicarAjuste(Long contratoId, AplicarAjusteRequest request, String usuarioKeycloakId) {
        log.info("Aplicando ajuste ICL a contrato {} desde {} hasta {}", contratoId, request.getFechaIndiceDesde(), request.getFechaIndiceHasta());

        Contrato contrato = contratoRepository.findById(contratoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Contrato con ID " + contratoId + " no fue encontrado"));

        EstadoContrato estadoActual = estadoFactory.obtenerEstado(contrato);
        if (!(estadoActual instanceof EstadoActivo)) {
            throw new OperacionInvalidaException(
                    "Solo se puede aplicar ajuste a un contrato en estado ACTIVO. Estado actual: " + contrato.getEstado());
        }

        // Obtener índices ICL (Dev 4: IndiceService con caché y fallback)
        ResultadoAjusteDTO resultado = indiceService.calcularAjuste(
                TipoIndiceEnum.ICL,
                contrato.getMontoActual(),
                request.getFechaIndiceDesde(),
                request.getFechaIndiceHasta());

        // Crear entidad Ajuste con todos los datos que exige la tabla
        Ajuste ajuste = Ajuste.builder()
                .contrato(contrato)
                .tipoIndice(resultado.getTipoIndice())
                .fechaAplicacion(LocalDate.now(clock))
                .fechaIndiceDesde(resultado.getFechaIndiceDesde())
                .fechaIndiceHasta(resultado.getFechaIndiceHasta())
                .valorIndiceDesde(resultado.getValorIndiceDesde())
                .valorIndiceHasta(resultado.getValorIndiceHasta())
                .coeficiente(resultado.getCoeficiente())
                .montoAnterior(resultado.getMontoAnterior())
                .montoNuevo(resultado.getMontoNuevo())
                .porcentajeVariacion(resultado.getPorcentajeVariacion())
                .origen(resultado.getOrigen())
                .build();

        // Ejecutar transición State (actualiza montoActual y genera nuevas cuotas)
        List<Cuota> nuevasCuotas = estadoActual.aplicarAjuste(contrato, ajuste);

        try {
            ajusteRepository.save(ajuste);
            contrato = contratoRepository.saveAndFlush(contrato);
            cuotaService.guardarCuotas(nuevasCuotas);
        } catch (Exception e) {
            log.error("Fallo al guardar ajuste y cuotas para contrato {}.", contratoId, e);
            throw new OperacionInvalidaException("Error al guardar el ajuste ICL.");
        }

        log.info("Ajuste aplicado a contrato {}: nuevo monto={}, variacion={}%, nuevas cuotas: {}",
                contratoId, resultado.getMontoNuevo(), resultado.getPorcentajeVariacion(), nuevasCuotas.size());
        return toResponse(contrato);
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------
    @Transactional(readOnly = true)
    public List<ContratoResponse> listarContratos(String rol, String usuarioKeycloakId) {
        log.info("Listando contratos para rol={}, usuario={}", rol, usuarioKeycloakId);

        List<Contrato> contratos;
        switch (rol) {
            case "ADMINISTRADOR":
                contratos = contratoRepository.findAll();
                break;
            case "PROPIETARIO":
                contratos = contratoRepository.findByPropietarioKeycloakId(usuarioKeycloakId);
                break;
            case "INQUILINO":
                contratos = contratoRepository.findByInquilinoKeycloakId(usuarioKeycloakId);
                break;
            default:
                throw new OperacionInvalidaException("Rol no reconocido: " + rol);
        }

        return contratos.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ContratoResponse obtenerContrato(Long contratoId, String rol, String usuarioKeycloakId) {
        Contrato contrato = buscarContrato(contratoId);
        validarPertenencia(contrato, rol, usuarioKeycloakId);
        return toResponse(contrato);
    }

    /**
     * Obtiene una cuota con mora validando que el contrato pertenezca al usuario
     * (anti-IDOR para PROPIETARIO e INQUILINO).
     */
    @Transactional(readOnly = true)
    public CuotaResumenDTO obtenerCuotaConMora(Long cuotaId, LocalDate fechaReferencia, String rol, String usuarioKeycloakId) {
        Long contratoId = cuotaService.obtenerContratoIdDeCuota(cuotaId);
        Contrato contrato = buscarContrato(contratoId);
        validarPertenencia(contrato, rol, usuarioKeycloakId);
        return cuotaService.obtenerCuotaConMora(cuotaId, contrato.getPorcentajeMoraDiaria(), fechaReferencia);
    }

    // ------------------------------------------------------------------
    // Ajuste periodico masivo
    // ------------------------------------------------------------------
    @Transactional
    public void ejecutarAjustePeriodico() {
        log.info("Ejecutando ajuste periodico de contratos activos");

        LocalDate hoy = LocalDate.now(clock);
        List<Contrato> contratosActivos = contratoRepository.findByEstado(EstadoContratoEnum.ACTIVO);

        for (Contrato contrato : contratosActivos) {
            try {
                // Verificar si corresponde ajuste según frecuencia
                // Se usa la última fecha de ajuste o fechaInicio como referencia
                LocalDate fechaUltimoHito = contrato.getAjustes().stream()
                        .map(a -> a.getFechaAplicacion() != null ? a.getFechaAplicacion() : a.getFechaIndiceHasta())
                        .max(LocalDate::compareTo)
                        .orElse(contrato.getFechaInicio());

                long mesesTranscurridos = ChronoUnit.MONTHS.between(fechaUltimoHito, hoy);
                if (mesesTranscurridos >= contrato.getFrecuenciaAjusteMeses()) {
                    // Calcular fechas de índice: desde el último hito hasta hoy
                    AplicarAjusteRequest request = AplicarAjusteRequest.builder()
                            .fechaIndiceDesde(fechaUltimoHito)
                            .fechaIndiceHasta(hoy)
                            .build();
                    aplicarAjuste(contrato.getId(), request, "SISTEMA");
                    log.info("Ajuste periodico aplicado a contrato {}", contrato.getNroContrato());
                }
            } catch (Exception e) {
                log.warn("No se pudo aplicar ajuste periodico a contrato {}: {}", contrato.getNroContrato(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private Contrato buscarContrato(Long contratoId) {
        return contratoRepository.findById(contratoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Contrato con ID " + contratoId + " no fue encontrado"));
    }

    private void validarPertenencia(Contrato contrato, String rol, String usuarioKeycloakId) {
        boolean pertenece = switch (rol) {
            case "ADMINISTRADOR" -> true;
            case "PROPIETARIO" -> contrato.getPropietarioKeycloakId() != null
                    && contrato.getPropietarioKeycloakId().equals(usuarioKeycloakId);
            case "INQUILINO" -> contrato.getInquilinoKeycloakId() != null
                    && contrato.getInquilinoKeycloakId().equals(usuarioKeycloakId);
            default -> false;
        };
        if (!pertenece) {
            throw new RecursoNoEncontradoException("Contrato con ID " + contrato.getId() + " no fue encontrado");
        }
    }

    /**
     * Compensa el estado de la propiedad en el catálogo si falla el guardado local
     * después de un PATCH exitoso (patrón Saga). Si la compensación también falla,
     * se registra para revisión manual.
     */
    private void compensarEstadoPropiedad(Contrato contrato, EstadoPropiedadEnum estadoARestaurar) {
        try {
            boolean disponible = estadoARestaurar == EstadoPropiedadEnum.DISPONIBLE;
            catalogoClient.actualizarEstadoPropiedad(
                    contrato.getPropiedadId(),
                    estadoARestaurar,
                    disponible ? null : contrato.getInquilinoId(),
                    disponible ? null : contrato.getGaranteId());
            log.info("Estado de la propiedad {} compensado a {}", contrato.getPropiedadId(), estadoARestaurar);
        } catch (Exception ex) {
            log.error("Fallo al compensar el estado de la propiedad {}. Revisar manualmente.", contrato.getPropiedadId(), ex);
        }
    }

    private ContratoResponse toResponse(Contrato contrato) {
        return ContratoResponse.builder()
                .id(contrato.getId())
                .nroContrato(contrato.getNroContrato())
                .propiedadId(contrato.getPropiedadId())
                .propietarioId(contrato.getPropietarioId())
                .inquilinoId(contrato.getInquilinoId())
                .garanteId(contrato.getGaranteId())
                .propietarioKeycloakId(contrato.getPropietarioKeycloakId())
                .inquilinoKeycloakId(contrato.getInquilinoKeycloakId())
                .fechaInicio(contrato.getFechaInicio())
                .fechaFin(contrato.getFechaFin())
                .fechaRescision(contrato.getFechaRescision())
                .frecuenciaAjusteMeses(contrato.getFrecuenciaAjusteMeses())
                .montoInicial(contrato.getMontoInicial())
                .montoActual(contrato.getMontoActual())
                .montoSenia(contrato.getMontoSenia())
                .fechaLimiteSenia(contrato.getFechaLimiteSenia())
                .porcentajeMoraDiaria(contrato.getPorcentajeMoraDiaria())
                .diaVencimientoMes(contrato.getDiaVencimientoMes())
                .estado(contrato.getEstado())
                .penalizacionRescision(contrato.getPenalizacionRescision())
                .cuotas(cuotaService.toResumenList(contrato.getCuotas(), contrato.getPorcentajeMoraDiaria(), LocalDate.now(clock)))
                .ajustes(contrato.getAjustes().stream()
                        .map(a -> AjusteResponse.builder()
                                .id(a.getId())
                                .fechaAplicacion(a.getFechaAplicacion())
                                .fechaIndiceDesde(a.getFechaIndiceDesde())
                                .fechaIndiceHasta(a.getFechaIndiceHasta())
                                .valorIndiceDesde(a.getValorIndiceDesde())
                                .valorIndiceHasta(a.getValorIndiceHasta())
                                .montoAnterior(a.getMontoAnterior())
                                .montoNuevo(a.getMontoNuevo())
                                .porcentajeVariacion(a.getPorcentajeVariacion())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

}
