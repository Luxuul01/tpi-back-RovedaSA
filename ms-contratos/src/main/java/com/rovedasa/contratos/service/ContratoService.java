package com.rovedasa.contratos.service;

import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.client.CatalogoClient;
import com.rovedasa.contratos.client.CatalogoConflictoException;
import com.rovedasa.contratos.client.CatalogoIntegracionException;
import com.rovedasa.contratos.config.RestClientConfig;
import com.rovedasa.contratos.dto.AjusteResponse;
import com.rovedasa.contratos.dto.AplicarAjusteRequest;
import com.rovedasa.contratos.dto.ContratoResponse;
import com.rovedasa.contratos.dto.CrearContratoRequest;
import com.rovedasa.contratos.dto.PersonaResumenDTO;
import com.rovedasa.contratos.dto.RescisionResponse;
import com.rovedasa.contratos.dto.RescindirContratoRequest;
import com.rovedasa.contratos.dto.ResultadoAjusteDTO;
import com.rovedasa.contratos.dto.SeniarContratoRequest;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.ContratoRepository;
import com.rovedasa.contratos.state.CalendarioContrato;
import com.rovedasa.contratos.state.EstadoContrato;
import com.rovedasa.contratos.state.EstadoContratoFactory;
import com.rovedasa.contratos.state.PeriodoAjuste;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Servicio de negocio de contratos. Orquesta el Patrón State (las reglas de cada transición viven en el
 * estado), las llamadas al catálogo (CatalogoClient), la persistencia (ContratoRepository) y la Saga:
 * cada cambio de estado de la propiedad se pide con el estado esperado (contrato 1) y, si después falla
 * el guardado local, se deshace con el endpoint de compensación del catálogo (contrato 2).
 *
 * Ninguna operación que llama a otro servicio usa @Transactional: el catálogo (hasta 12 s, el garante pasa por el
 * BCRA) y el índice (BCRA) se consultan fuera de toda transacción, entre una lectura corta y una escritura corta
 * con TransactionTemplate (igual que EvaluacionGaranteService en el catálogo). Entre las dos, el @Version del
 * contrato protege contra cambios concurrentes. Si el PATCH al catálogo termina sin respuesta clara (timeout o
 * 5xx), se compensa igual por las dudas.
 */
@Slf4j
@Service
public class ContratoService {

    static final String MOTIVO_COMPETIDOR = "Otro contrato de la propiedad se activó";

    private final ContratoRepository contratoRepository;
    private final CuotaService cuotaService;
    private final CatalogoClient catalogoClient;
    private final EstadoContratoFactory estadoFactory;
    private final IndiceService indiceService;
    private final Clock clock;
    private final TransactionTemplate transaccionLectura;
    private final TransactionTemplate transaccionEscritura;

    public ContratoService(ContratoRepository contratoRepository,
                           CuotaService cuotaService,
                           CatalogoClient catalogoClient,
                           EstadoContratoFactory estadoFactory,
                           IndiceService indiceService,
                           Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.contratoRepository = contratoRepository;
        this.cuotaService = cuotaService;
        this.catalogoClient = catalogoClient;
        this.estadoFactory = estadoFactory;
        this.indiceService = indiceService;
        this.clock = clock;
        this.transaccionLectura = new TransactionTemplate(transactionManager);
        this.transaccionLectura.setReadOnly(true);
        this.transaccionEscritura = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // Crear borrador (CREADO)
    // ------------------------------------------------------------------
    /**
     * Alta del borrador. Las consultas al catálogo se hacen sin transacción abierta; el guardado es la
     * transacción corta del propio repositorio.
     */
    public ContratoResponse crearContrato(CrearContratoRequest request) {
        log.info("Creando borrador de contrato para propiedadId={}, propietario={}, inquilino={}",
                request.getPropiedadId(), request.getPropietarioId(), request.getInquilinoId());

        // Rango admitido de fechas (constantes en CrearContratoRequest): una fecha absurda no llega a la base
        if (request.getFechaInicio() != null
                && request.getFechaInicio().isBefore(CrearContratoRequest.FECHA_INICIO_MINIMA)) {
            throw new OperacionInvalidaException(String.format(
                    "La fecha de inicio no puede ser anterior al %s", CrearContratoRequest.FECHA_INICIO_MINIMA));
        }
        if (request.getFechaFin() != null && request.getFechaFin().isAfter(CrearContratoRequest.FECHA_FIN_MAXIMA)) {
            throw new OperacionInvalidaException(String.format(
                    "La fecha de fin no puede ser posterior al %s", CrearContratoRequest.FECHA_FIN_MAXIMA));
        }
        if (request.getFechaInicio() != null && request.getFechaFin() != null
                && !request.getFechaFin().isAfter(request.getFechaInicio())) {
            throw new OperacionInvalidaException("La fecha de fin debe ser posterior a la fecha de inicio");
        }
        if (request.getFechaInicio() != null && request.getFechaFin() != null
                && request.getFrecuenciaAjusteMeses() != null) {
            int duracionMeses = CalendarioContrato.totalMeses(request.getFechaInicio(), request.getFechaFin());
            if (request.getFrecuenciaAjusteMeses() > duracionMeses) {
                throw new OperacionInvalidaException(String.format(
                        "La frecuencia de ajuste (%d meses) no puede ser mayor que la duración del contrato (%d meses)",
                        request.getFrecuenciaAjusteMeses(), duracionMeses));
            }
        }
        if (request.getGaranteId().equals(request.getInquilinoId())
                || request.getGaranteId().equals(request.getPropietarioId())) {
            throw new OperacionInvalidaException("El garante no puede ser el inquilino ni el propietario (RN-02)");
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

        // 2. Validar existencia, roles asignados (RN-02) y obtener keycloakUserId de propietario e inquilino
        var propietario = catalogoClient.obtenerPersona(request.getPropietarioId());
        var inquilino = catalogoClient.obtenerPersona(request.getInquilinoId());
        var garante = catalogoClient.obtenerPersona(request.getGaranteId());

        validarRolPersona(propietario, "PROPIETARIO");
        validarRolPersona(inquilino, "INQUILINO");
        validarRolPersona(garante, "GARANTE");

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
                .tipoIndice(request.getTipoIndice() != null ? request.getTipoIndice() : TipoIndiceEnum.ICL)
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

    /**
     * Seña (RN-06). Lectura y transición del State en una transacción corta; consulta y PATCH al catálogo sin
     * transacción abierta; escritura corta (o compensación si falla).
     */
    public ContratoResponse seniarContrato(Long contratoId, SeniarContratoRequest request, String usuarioKeycloakId) {
        log.info("Señando contrato {} con montoSenia={}, fechaLimiteSenia={}", contratoId, request.getMontoSenia(), request.getFechaLimiteSenia());
        LocalDate hoy = LocalDate.now(clock);

        // 1. Transición State CREADO -> SENIADO (valida estado, monto y fecha límite antes de tocar el catálogo)
        Contrato contrato = leerYAplicarTransicion(contratoId, c -> {
            estadoFactory.obtenerEstado(c).seniar(c, request.getMontoSenia(), request.getFechaLimiteSenia(), hoy);
            return null;
        }).contrato();

        // 2. RN-06: la propiedad tiene que seguir DISPONIBLE
        var propiedad = catalogoClient.obtenerPropiedad(contrato.getPropiedadId());
        if (propiedad.getEstado() != EstadoPropiedadEnum.DISPONIBLE) {
            throw new OperacionInvalidaException(
                    "La propiedad " + contrato.getPropiedadId() + " ya no está DISPONIBLE (estado: " + propiedad.getEstado() + ")");
        }

        // Saga paso 1: DISPONIBLE -> RESERVADA (409 si otra seña la reservó entre la consulta y el PATCH)
        cambiarEstadoPropiedad(contrato, "señar", EstadoPropiedadEnum.RESERVADA, EstadoPropiedadEnum.DISPONIBLE,
                contrato.getInquilinoId(), contrato.getGaranteId());

        // 3. Saga paso 2: escritura corta; si falla se compensa RESERVADA -> DISPONIBLE
        ContratoResponse respuesta = guardarOCompensar(contrato, "señar",
                EstadoPropiedadEnum.RESERVADA, EstadoPropiedadEnum.DISPONIBLE, () -> contratoRepository.saveAndFlush(contrato));

        log.info("Contrato {} pasó a SENIADO. Propiedad {} reservada.", contratoId, respuesta.getPropiedadId());
        return respuesta;
    }

    // ------------------------------------------------------------------
    // Activar (SENIADO -> ACTIVO)
    // ------------------------------------------------------------------

    /**
     * Activación (RN-07, RN-08). La evaluación del garante (catálogo -> BCRA, hasta 12 s) y el PATCH se hacen
     * sin transacción abierta, entre la lectura corta y la escritura corta.
     */
    public ContratoResponse activarContrato(Long contratoId, String usuarioKeycloakId) {
        log.info("Activando contrato {}", contratoId);
        LocalDate hoy = LocalDate.now(clock);

        // 1. Transición State SENIADO -> ACTIVO en memoria: valida la seña vigente y genera las cuotas del tramo 0.
        // Si después el garante no es solvente o algo falla, no se guarda nada y el contrato sigue SEÑADO.
        Transicion<List<Cuota>> activacion = leerYAplicarTransicion(contratoId,
                c -> estadoFactory.obtenerEstado(c).activar(c, hoy));
        Contrato contrato = activacion.contrato();

        // 2. RN-07: solvencia del garante vía catálogo/BCRA
        var validacion = catalogoClient.evaluarGarante(contrato.getGaranteId());
        if (!Boolean.TRUE.equals(validacion.getEsSolvente())) {
            throw new OperacionInvalidaException(
                    "El garante no es solvente: " + validacion.getMotivoEvaluacion() + ". Contrato sigue SENIADO.");
        }

        // Saga paso 1: RESERVADA -> ALQUILADA (409 si mientras tanto se canceló la seña y se liberó)
        cambiarEstadoPropiedad(contrato, "activar", EstadoPropiedadEnum.ALQUILADA, EstadoPropiedadEnum.RESERVADA,
                contrato.getInquilinoId(), contrato.getGaranteId());

        // 3. Saga paso 2: cancelar borradores competidores y guardar; si falla se compensa ALQUILADA -> RESERVADA
        ContratoResponse respuesta = guardarOCompensar(contrato, "activar",
                EstadoPropiedadEnum.ALQUILADA, EstadoPropiedadEnum.RESERVADA, () -> {
                    cancelarBorradoresCompetidores(contrato);
                    return contratoRepository.saveAndFlush(contrato);
                });

        log.info("Contrato {} pasó a ACTIVO. Propiedad {} alquilada. Cuotas iniciales: {}",
                contratoId, respuesta.getPropiedadId(), activacion.resultado().size());
        return respuesta;
    }

    // ------------------------------------------------------------------
    // Cancelar (CREADO/SENIADO -> CANCELADO)
    // ------------------------------------------------------------------
    public ContratoResponse cancelarContrato(Long contratoId, String motivo, boolean retenerSenia, String usuarioKeycloakId) {
        log.info("Cancelando contrato {} con motivo: {}", contratoId, motivo);

        // Transición State (rechaza ACTIVO y los terminales); resultado: estado en que ocupaba la propiedad
        Transicion<EstadoPropiedadEnum> cancelacion = leerYAplicarTransicion(contratoId, c -> {
            EstadoContrato estadoActual = estadoFactory.obtenerEstado(c);
            EstadoPropiedadEnum estadoOcupado = estadoActual.estadoPropiedadOcupada();
            estadoActual.cancelar(c, motivo, retenerSenia);
            return estadoOcupado;
        });

        ContratoResponse respuesta = liberarPropiedadYGuardar(cancelacion.contrato(), cancelacion.resultado(), "cancelar");
        log.info("Contrato {} pasó a CANCELADO.", contratoId);
        return respuesta;
    }

    // ------------------------------------------------------------------
    // Rescindir (ACTIVO -> RESCINDIDO)
    // ------------------------------------------------------------------
    public RescisionResponse rescindirContrato(Long contratoId, RescindirContratoRequest request, String usuarioKeycloakId) {
        log.info("Rescindiendo contrato {} en fecha {}", contratoId, request.getFechaRescision());
        LocalDate hoy = LocalDate.now(clock);

        // Transición State (valida el rango de la fecha, calcula multa y anula cuotas futuras)
        Transicion<DatosRescision> rescision = leerYAplicarTransicion(contratoId, c -> {
            EstadoContrato estadoActual = estadoFactory.obtenerEstado(c);
            EstadoPropiedadEnum estadoOcupado = estadoActual.estadoPropiedadOcupada();
            long anuladasAntes = contarAnuladas(c);
            BigDecimal penalizacion = estadoActual.rescindir(c, request.getFechaRescision(), hoy);
            return new DatosRescision(estadoOcupado, penalizacion, (int) (contarAnuladas(c) - anuladasAntes));
        });
        DatosRescision datos = rescision.resultado();

        ContratoResponse respuesta = liberarPropiedadYGuardar(rescision.contrato(), datos.estadoOcupado(), "rescindir");

        log.info("Contrato {} pasó a RESCINDIDO. Penalización: {}, cuotas anuladas: {}",
                contratoId, datos.penalizacion(), datos.cuotasAnuladas());

        return RescisionResponse.builder()
                .contratoId(respuesta.getId())
                .fechaRescision(request.getFechaRescision())
                .penalizacion(datos.penalizacion())
                .cuotasAnuladas(datos.cuotasAnuladas())
                .build();
    }

    // ------------------------------------------------------------------
    // Finalizar (ACTIVO -> FINALIZADO)
    // ------------------------------------------------------------------
    public ContratoResponse finalizarContrato(Long contratoId, String usuarioKeycloakId) {
        log.info("Finalizando contrato {}", contratoId);
        LocalDate hoy = LocalDate.now(clock);

        // Transición State (exige haber llegado a la fecha de fin)
        Transicion<EstadoPropiedadEnum> finalizacion = leerYAplicarTransicion(contratoId, c -> {
            EstadoContrato estadoActual = estadoFactory.obtenerEstado(c);
            EstadoPropiedadEnum estadoOcupado = estadoActual.estadoPropiedadOcupada();
            estadoActual.finalizar(c, hoy);
            return estadoOcupado;
        });

        ContratoResponse respuesta = liberarPropiedadYGuardar(finalizacion.contrato(), finalizacion.resultado(), "finalizar");
        log.info("Contrato {} pasó a FINALIZADO. Propiedad {} liberada.", contratoId, respuesta.getPropiedadId());
        return respuesta;
    }

    // ------------------------------------------------------------------
    // Aplicar ajuste por el índice del contrato, ICL o IPC (ACTIVO -> ACTIVO + nuevas cuotas)
    // ------------------------------------------------------------------

    /**
     * Aplica el próximo ajuste del contrato (RN-11). Tres pasos:
     * 1. Lectura corta: el State valida estado, período cumplido y fechas pedidas (antes de consultar el índice).
     * 2. Índice (BCRA o caché), fuera de toda transacción.
     * 3. Escritura corta: el State vuelve a validar sobre el contrato recién leído, registra el ajuste y emite
     *    las cuotas del tramo. Un ajuste concurrente del mismo período da 400 (ya aplicado) o 409 (versión).
     */
    public ContratoResponse aplicarAjuste(Long contratoId, AplicarAjusteRequest request, String usuarioKeycloakId) {
        LocalDate desdePedido = request != null ? request.getFechaIndiceDesde() : null;
        LocalDate hastaPedido = request != null ? request.getFechaIndiceHasta() : null;
        log.info("Aplicando ajuste a contrato {} (fechas pedidas: desde={}, hasta={})", contratoId, desdePedido, hastaPedido);
        LocalDate hoy = LocalDate.now(clock);

        // 1. Validación del período, antes de llamar al índice
        DatosAjuste datos = transaccionLectura.execute(estado -> {
            Contrato contrato = buscarContrato(contratoId);
            PeriodoAjuste periodo = estadoFactory.obtenerEstado(contrato)
                    .validarAjuste(contrato, hoy, desdePedido, hastaPedido);
            return new DatosAjuste(contrato.getTipoIndice(), contrato.getMontoActual(), periodo);
        });
        PeriodoAjuste periodo = datos.periodo();

        // 2. Índice pactado en el contrato (ICL por defecto o IPC), sin transacción abierta
        ResultadoAjusteDTO resultado = indiceService.calcularAjuste(
                datos.tipoIndice(), datos.canonAnterior(), periodo.desde(), periodo.hasta());

        // 3. Registro del ajuste y cuotas del nuevo tramo
        return transaccionEscritura.execute(estado -> {
            Contrato contrato = buscarContrato(contratoId);
            Ajuste ajuste = Ajuste.builder()
                    .contrato(contrato)
                    .tipoIndice(resultado.getTipoIndice())
                    .fechaAplicacion(hoy)
                    .fechaIndiceDesde(periodo.desde())
                    .fechaIndiceHasta(periodo.hasta())
                    .fechaValorIndiceDesde(resultado.getFechaIndiceDesde())
                    .fechaValorIndiceHasta(resultado.getFechaIndiceHasta())
                    .valorIndiceDesde(resultado.getValorIndiceDesde())
                    .valorIndiceHasta(resultado.getValorIndiceHasta())
                    .coeficiente(resultado.getCoeficiente())
                    .montoAnterior(resultado.getMontoAnterior())
                    .montoNuevo(resultado.getMontoNuevo())
                    .porcentajeVariacion(resultado.getPorcentajeVariacion())
                    .origen(resultado.getOrigen())
                    .build();

            List<Cuota> nuevasCuotas = estadoFactory.obtenerEstado(contrato).aplicarAjuste(contrato, ajuste);
            Contrato guardado = contratoRepository.saveAndFlush(contrato);

            log.info("Ajuste N° {} aplicado a contrato {}: nuevo monto={}, variacion={}%, nuevas cuotas: {}",
                    periodo.numero(), contratoId, resultado.getMontoNuevo(), resultado.getPorcentajeVariacion(),
                    nuevasCuotas.size());
            return toResponse(guardado);
        });
    }

    /** Ids de los contratos ACTIVOS (los recorre el ajuste periódico). */
    public List<Long> listarIdsContratosActivos() {
        return transaccionLectura.execute(estado -> contratoRepository.findByEstado(EstadoContratoEnum.ACTIVO)
                .stream().map(Contrato::getId).toList());
    }

    /** Si el contrato ya tiene registrado el ajuste del período que termina en {@code hasta}. */
    public boolean ajusteYaAplicado(Long contratoId, LocalDate hasta) {
        return Boolean.TRUE.equals(transaccionLectura.execute(estado -> buscarContrato(contratoId).getAjustes()
                .stream().anyMatch(ajuste -> hasta.equals(ajuste.getFechaIndiceHasta()))));
    }

    /** Próximo ajuste que ya corresponde aplicar al contrato, si hay alguno (lo decide el State). */
    public Optional<PeriodoAjuste> buscarAjustePendiente(Long contratoId) {
        LocalDate hoy = LocalDate.now(clock);
        return transaccionLectura.execute(estado -> {
            Contrato contrato = buscarContrato(contratoId);
            return estadoFactory.obtenerEstado(contrato).ajustePendiente(contrato, hoy);
        });
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

    // ------------------------------------------------------------------
    // Helpers de la Saga
    // ------------------------------------------------------------------

    /**
     * Cancela por el State (CREADO -> CANCELADO, seña no retenida) los borradores de la misma propiedad
     * cuando otro contrato se activa (RN-08).
     */
    private void cancelarBorradoresCompetidores(Contrato contratoActivado) {
        List<Contrato> competidores = contratoRepository
                .findByPropiedadIdAndEstado(contratoActivado.getPropiedadId(), EstadoContratoEnum.CREADO)
                .stream()
                .filter(c -> !c.getId().equals(contratoActivado.getId()))
                .toList();

        for (Contrato borrador : competidores) {
            estadoFactory.obtenerEstado(borrador).cancelar(borrador, MOTIVO_COMPETIDOR, false);
            contratoRepository.save(borrador);
            log.info("Borrador competidor {} cancelado por activación del contrato {}",
                    borrador.getNroContrato(), contratoActivado.getNroContrato());
        }
    }

    /**
     * Lectura corta: lee el contrato y le aplica en memoria la transición del State, que valida todo antes de
     * llamar al catálogo. La transacción es de solo lectura, así que no se escribe nada: el contrato sale de acá
     * desacoplado (open-in-view está apagado) y se guarda después en la escritura corta, con merge. Si otro
     * proceso lo modificó mientras tanto, el @Version del contrato (o el de una cuota) rechaza esa escritura con
     * un conflicto (409) y se compensa la propiedad.
     */
    private <T> Transicion<T> leerYAplicarTransicion(Long contratoId, Function<Contrato, T> transicion) {
        return transaccionLectura.execute(estado -> {
            Contrato contrato = buscarContrato(contratoId);
            // Colecciones cargadas acá: después se usan sin sesión abierta y viajan con el merge
            contrato.getCuotas().size();
            contrato.getAjustes().size();
            T resultado = transicion.apply(contrato);
            return new Transicion<>(contrato, resultado);
        });
    }

    /**
     * Libera la propiedad (estadoOcupado -> DISPONIBLE, con estado esperado) y guarda el contrato,
     * compensando si falla el guardado. Si la propiedad ya estaba DISPONIBLE no hay nada que liberar
     * (se registra y solo se guarda el contrato); si el contrato no la ocupaba (CREADO), tampoco.
     * Las llamadas al catálogo van sin transacción abierta.
     */
    private ContratoResponse liberarPropiedadYGuardar(Contrato contrato, EstadoPropiedadEnum estadoOcupado,
                                                      String operacion) {
        if (estadoOcupado == null) {
            return guardar(() -> contratoRepository.saveAndFlush(contrato));
        }
        var propiedad = catalogoClient.obtenerPropiedad(contrato.getPropiedadId());
        if (propiedad.getEstado() == EstadoPropiedadEnum.DISPONIBLE) {
            log.warn("La propiedad {} ya estaba DISPONIBLE al {} el contrato {}: no se la libera",
                    contrato.getPropiedadId(), operacion, contrato.getNroContrato());
            return guardar(() -> contratoRepository.saveAndFlush(contrato));
        }

        cambiarEstadoPropiedad(contrato, operacion, EstadoPropiedadEnum.DISPONIBLE, estadoOcupado,
                contrato.getInquilinoId(), contrato.getGaranteId());

        return guardarOCompensar(contrato, operacion, EstadoPropiedadEnum.DISPONIBLE, estadoOcupado,
                () -> contratoRepository.saveAndFlush(contrato));
    }

    /**
     * Saga paso 1: PATCH de estado de la propiedad con estado esperado. Si el catálogo lo rechaza con un 4xx
     * claro (400/422, 404, 409, 401/403 u otro 4xx) no cambió nada y se relanza tal cual. Ante cualquier otra
     * falla (timeout, 5xx, sin respuesta) no se sabe si el cambio se aplicó: se intenta igual la compensación
     * (si no se había aplicado, el catálogo responde 409 y no toca nada) y se relanza el error original.
     */
    private void cambiarEstadoPropiedad(Contrato contrato, String operacion, EstadoPropiedadEnum nuevoEstado,
                                        EstadoPropiedadEnum estadoEsperado, Long inquilinoId, Long garanteId) {
        try {
            catalogoClient.actualizarEstadoPropiedad(contrato.getPropiedadId(), nuevoEstado, estadoEsperado,
                    inquilinoId, garanteId);
        } catch (RuntimeException e) {
            if (esRechazoClaroDelCatalogo(e)) {
                throw e;
            }
            log.warn("El cambio de la propiedad {} de {} a {} al {} el contrato {} terminó sin una respuesta clara "
                            + "({}): puede haberse aplicado. Se intenta la compensación por las dudas.",
                    contrato.getPropiedadId(), estadoEsperado, nuevoEstado, operacion, contrato.getNroContrato(),
                    e.getMessage());
            compensarEstadoPropiedad(contrato, operacion, nuevoEstado, estadoEsperado, true);
            throw e;
        }
    }

    /**
     * Conflictos esperables entre operaciones concurrentes: el @Version (contrato o cuota) o una restricción de
     * unicidad (dos corridas del mismo ajuste). commons los responde con 409.
     */
    static boolean esConflictoDeConcurrencia(RuntimeException e) {
        return e instanceof OptimisticLockingFailureException || e instanceof DataIntegrityViolationException;
    }

    /** Rechazos del catálogo en los que se sabe que la propiedad no cambió (ver CatalogoClient.traducirError). */
    private static boolean esRechazoClaroDelCatalogo(RuntimeException e) {
        return e instanceof OperacionInvalidaException
                || e instanceof RecursoNoEncontradoException
                || e instanceof CatalogoConflictoException
                || e instanceof CatalogoIntegracionException;
    }

    /** Escritura corta sin nada que compensar en el catálogo. */
    private ContratoResponse guardar(Supplier<Contrato> guardado) {
        return transaccionEscritura.execute(estado -> toResponse(guardado.get()));
    }

    /**
     * Saga paso 2: escritura corta. Si falla, compensa en el catálogo (estadoActual -> estadoAnterior) y relanza
     * la excepción original: un conflicto de versión o de integridad sale como 409 (commons) y no como 400.
     */
    private ContratoResponse guardarOCompensar(Contrato contrato, String operacion, EstadoPropiedadEnum estadoActual,
                                               EstadoPropiedadEnum estadoAnterior, Supplier<Contrato> guardado) {
        try {
            return guardar(guardado);
        } catch (RuntimeException e) {
            if (esConflictoDeConcurrencia(e)) {
                // Esperable: otra operación cambió el contrato mientras tanto (sale 409); sin stacktrace
                log.warn("Fallo el guardado local al {} el contrato {} después de pasar la propiedad {} a {} por un "
                                + "cambio concurrente ({}). Compensando a {}.", operacion, contrato.getNroContrato(),
                        contrato.getPropiedadId(), estadoActual, e.getClass().getSimpleName(), estadoAnterior);
            } else {
                log.error("Fallo el guardado local al {} el contrato {} después de pasar la propiedad {} a {}. "
                                + "Compensando a {}.", operacion, contrato.getNroContrato(), contrato.getPropiedadId(),
                        estadoActual, estadoAnterior, e);
            }
            compensarEstadoPropiedad(contrato, operacion, estadoActual, estadoAnterior, false);
            throw e;
        }
    }

    /**
     * Compensación Saga con PATCH /estado/compensacion. Con {@code dudoso} (el PATCH de ida terminó sin respuesta
     * clara) un 409 significa que la propiedad no estaba en estadoActual, o sea que el PATCH no se había aplicado:
     * no hacía falta compensar. Si la compensación falla por otro motivo, la propiedad puede haber quedado en un
     * estado que no corresponde al contrato: se registra en ERROR con todo lo necesario para corregirla a mano.
     */
    private void compensarEstadoPropiedad(Contrato contrato, String operacion, EstadoPropiedadEnum estadoActual,
                                          EstadoPropiedadEnum estadoAnterior, boolean dudoso) {
        // Siempre con las partes: volver de DISPONIBLE exige reponerlas y, en las demás, el catálogo verifica
        // que la propiedad siga tomada por este inquilino
        try {
            catalogoClient.compensarEstadoPropiedad(contrato.getPropiedadId(), estadoActual, estadoAnterior,
                    contrato.getInquilinoId(), contrato.getGaranteId());
            log.info("Compensación aplicada: propiedad {} devuelta de {} a {} (contrato {})",
                    contrato.getPropiedadId(), estadoActual, estadoAnterior, contrato.getNroContrato());
        } catch (CatalogoConflictoException ex) {
            if (dudoso) {
                log.info("Compensación no necesaria al {} el contrato {}: la propiedad {} no estaba en {} "
                                + "(el cambio de estado no se había aplicado). {}", operacion, contrato.getNroContrato(),
                        contrato.getPropiedadId(), estadoActual, ex.getMessage());
            } else {
                registrarCompensacionFallida(contrato, operacion, estadoActual, estadoAnterior, ex);
            }
        } catch (RuntimeException ex) {
            registrarCompensacionFallida(contrato, operacion, estadoActual, estadoAnterior, ex);
        }
    }

    private static void registrarCompensacionFallida(Contrato contrato, String operacion,
                                                     EstadoPropiedadEnum estadoActual,
                                                     EstadoPropiedadEnum estadoAnterior, RuntimeException ex) {
        log.error("COMPENSACIÓN FALLIDA al {}: contrato={} (id {}), propiedad={}, correlationId={}. "
                        + "La propiedad puede haber quedado en {} y debía volver a {}. Revisar manualmente.",
                operacion, contrato.getNroContrato(), contrato.getId(), contrato.getPropiedadId(),
                MDC.get(RestClientConfig.CORRELATION_ID_MDC_KEY), estadoActual, estadoAnterior, ex);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private void validarRolPersona(PersonaResumenDTO persona, String rolEsperado) {
        if (persona.getRoles() == null || !persona.getRoles().contains(rolEsperado)) {
            throw new OperacionInvalidaException(
                    "La persona con ID " + persona.getId() + " no posee el rol requerido " + rolEsperado + " (RN-02)");
        }
    }

    private Contrato buscarContrato(Long contratoId) {
        return contratoRepository.findById(contratoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Contrato con ID " + contratoId + " no fue encontrado"));
    }

    private static long contarAnuladas(Contrato contrato) {
        return contrato.getCuotas().stream().filter(c -> c.getEstado() == EstadoCuotaEnum.ANULADA).count();
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
                .tipoIndice(contrato.getTipoIndice())
                .montoInicial(contrato.getMontoInicial())
                .montoActual(contrato.getMontoActual())
                .montoSenia(contrato.getMontoSenia())
                .fechaLimiteSenia(contrato.getFechaLimiteSenia())
                .porcentajeMoraDiaria(contrato.getPorcentajeMoraDiaria())
                .diaVencimientoMes(contrato.getDiaVencimientoMes())
                .estado(contrato.getEstado())
                .motivoCancelacion(contrato.getMotivoCancelacion())
                .seniaRetenida(contrato.getSeniaRetenida())
                .penalizacionRescision(contrato.getPenalizacionRescision())
                .cuotas(cuotaService.toResumenList(contrato.getCuotas(), contrato.getPorcentajeMoraDiaria(), LocalDate.now(clock)))
                .ajustes(contrato.getAjustes().stream()
                        .map(a -> AjusteResponse.builder()
                                .id(a.getId())
                                .tipoIndice(a.getTipoIndice())
                                .fechaAplicacion(a.getFechaAplicacion())
                                .fechaIndiceDesde(a.getFechaIndiceDesde())
                                .fechaIndiceHasta(a.getFechaIndiceHasta())
                                .fechaValorIndiceDesde(a.getFechaValorIndiceDesde())
                                .fechaValorIndiceHasta(a.getFechaValorIndiceHasta())
                                .valorIndiceDesde(a.getValorIndiceDesde())
                                .valorIndiceHasta(a.getValorIndiceHasta())
                                .coeficiente(a.getCoeficiente())
                                .montoAnterior(a.getMontoAnterior())
                                .montoNuevo(a.getMontoNuevo())
                                .porcentajeVariacion(a.getPorcentajeVariacion())
                                .origen(a.getOrigen())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    /** Contrato leído con la transición del State ya aplicada en memoria, y lo que devolvió la transición. */
    private record Transicion<T>(Contrato contrato, T resultado) {
    }

    /** Resultado de la rescisión que se informa en la respuesta. */
    private record DatosRescision(EstadoPropiedadEnum estadoOcupado, BigDecimal penalizacion, int cuotasAnuladas) {
    }

    /** Lo que hace falta del contrato fuera de la transacción de lectura para consultar el índice. */
    private record DatosAjuste(TipoIndiceEnum tipoIndice, BigDecimal canonAnterior, PeriodoAjuste periodo) {
    }
}
