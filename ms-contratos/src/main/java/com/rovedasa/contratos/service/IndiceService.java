package com.rovedasa.contratos.service;

import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.client.bcra.BcraEstadisticasClient;
import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import com.rovedasa.contratos.dto.CoeficienteAjusteDTO;
import com.rovedasa.contratos.dto.OrigenDatoEnum;
import com.rovedasa.contratos.dto.ResultadoAjusteDTO;
import com.rovedasa.contratos.dto.ValorIndiceDTO;
import com.rovedasa.contratos.exception.IndiceNoDisponibleException;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Obtención de índices (ICL e IPC) con resiliencia ante caídas del BCRA (RNF-03, RN-12).
 *
 * Estrategia:
 *  1. Se consulta el BCRA. Si responde, cada valor recibido se guarda en indices_cache
 *     (en una transacción propia: ver IndiceCacheService).
 *  2. Si el BCRA falla (caída, timeout, 5xx, cualquier 4xx o respuesta inesperada) se leen los
 *     valores de indices_cache (contingencia). Un 400 o 404 se loguea como ERROR: como el tipo de
 *     índice es un enum, solo aparecen por configuración o por un cambio de versión de la API.
 *  3. Si el caché no tiene un valor aceptable, IndiceNoDisponibleException (503): el ajuste queda
 *     pendiente para la próxima ejecución.
 *
 * Valor puntual: con el BCRA arriba se usa el valor de esa fecha o el último publicado anterior
 * (Alcance, sección 14; la API lo resuelve con hasta=fecha&limit=1). En contingencia, el valor del
 * caché solo se acepta si es de como mucho bcra.cache.max-antiguedad-dias (7) días antes (ICL), o del
 * mes de la fecha o los bcra.indices.meses-tolerancia-ipc (2) anteriores (IPC puntual).
 *
 * IPC de un ajuste: se usan las variaciones de los meses cuyo primer día está en
 * [desde − 1 mes, hasta − 1 mes) (un mes de rezago por la publicación). Si falta alguno → 503.
 *
 * No depende de estar dentro de una transacción: no tiene @Transactional y el caché maneja las suyas.
 * Responde 400 solo por errores del pedido: fechas inválidas o futuras, ventana de IPC sin meses, o
 * una fecha anterior al inicio de la serie (el BCRA responde sin valores).
 */
@Slf4j
@Service
public class IndiceService {

    private static final DateTimeFormatter FORMATO_MES =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.of("es", "AR"));

    private final BcraEstadisticasClient bcraClient;
    private final IndiceCacheService cacheService;
    private final CalculadoraAjuste calculadora;
    private final Clock clock;
    private final int maxAntiguedadDias;
    private final int mesesToleranciaIpc;

    public IndiceService(BcraEstadisticasClient bcraClient,
                         IndiceCacheService cacheService,
                         CalculadoraAjuste calculadora,
                         Clock clock,
                         @Value("${bcra.cache.max-antiguedad-dias:7}") int maxAntiguedadDias,
                         @Value("${bcra.indices.meses-tolerancia-ipc:2}") int mesesToleranciaIpc) {
        this.bcraClient = bcraClient;
        this.cacheService = cacheService;
        this.calculadora = calculadora;
        this.clock = clock;
        this.maxAntiguedadDias = maxAntiguedadDias;
        this.mesesToleranciaIpc = mesesToleranciaIpc;
    }

    /** Serie de valores junto con el lugar de donde salieron. */
    record SerieConOrigen(List<BcraValorDTO> valores, OrigenDatoEnum origen) { }

    // ------------------------------------------------------------------
    // 1. Valor puntual de un índice
    // ------------------------------------------------------------------

    /**
     * Valor del índice en la fecha pedida o, si no hay, el último publicado anterior (Alcance, sección 14).
     * ICL: valor diario. IPC: inflación (%) del último mes publicado.
     * La respuesta indica la fecha real del valor (fechaIndice) y si salió del BCRA o del caché.
     */
    public ValorIndiceDTO obtenerValor(TipoIndiceEnum tipo, LocalDate fecha) {
        validarNoFutura(fecha);

        LocalDate minimoCache = minimoAceptadoEnCache(tipo, fecha);
        SerieConOrigen serie = obtenerUltimo(tipo, minimoCache, fecha);

        BcraValorDTO valor = serie.valores().stream()
                .filter(v -> !v.getFecha().isAfter(fecha))
                .max(Comparator.comparing(BcraValorDTO::getFecha))
                .orElseThrow(() -> sinValorPuntual(tipo, minimoCache, fecha, serie.origen()));

        return ValorIndiceDTO.builder()
                .tipoIndice(tipo)
                .fechaSolicitada(fecha)
                .fechaIndice(valor.getFecha())
                .valor(valor.getValor())
                .origen(serie.origen())
                .build();
    }

    // ------------------------------------------------------------------
    // 2. Coeficiente de ajuste entre dos fechas
    // ------------------------------------------------------------------

    public CoeficienteAjusteDTO calcularCoeficiente(TipoIndiceEnum tipo, LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null || !desde.isBefore(hasta)) {
            throw new OperacionInvalidaException("La fecha 'desde' debe ser anterior a la fecha 'hasta'");
        }
        validarNoFutura(hasta);
        return (tipo == TipoIndiceEnum.ICL)
                ? coeficienteIcl(desde, hasta)
                : coeficienteIpc(desde, hasta);
    }

    private CoeficienteAjusteDTO coeficienteIcl(LocalDate desde, LocalDate hasta) {
        ValorIndiceDTO iclDesde = obtenerValor(TipoIndiceEnum.ICL, desde);
        ValorIndiceDTO iclHasta = obtenerValor(TipoIndiceEnum.ICL, hasta);

        BigDecimal coeficiente = calculadora.coeficienteIcl(iclDesde.getValor(), iclHasta.getValor());

        return CoeficienteAjusteDTO.builder()
                .tipoIndice(TipoIndiceEnum.ICL)
                .fechaDesde(desde)
                .fechaHasta(hasta)
                .fechaIndiceDesde(iclDesde.getFechaIndice())
                .fechaIndiceHasta(iclHasta.getFechaIndice())
                .valorIndiceDesde(iclDesde.getValor())
                .valorIndiceHasta(iclHasta.getValor())
                .coeficiente(coeficiente)
                .porcentajeVariacion(calculadora.calcularPorcentajeVariacion(coeficiente))
                .origen(combinarOrigen(iclDesde.getOrigen(), iclHasta.getOrigen()))
                .build();
    }

    /**
     * IPC: se encadenan las inflaciones de los meses cuyo primer día está en [desde − 1 mes, hasta − 1 mes).
     * Ej.: ventana 01/07 → 01/10 usa junio, julio y agosto. El resultado depende solo de la ventana, no
     * del día en que se calcula. Si falta algún mes (todavía no publicado, o el BCRA caído y el caché
     * sin ese mes) → 503 y el ajuste queda pendiente.
     */
    private CoeficienteAjusteDTO coeficienteIpc(LocalDate desde, LocalDate hasta) {
        List<YearMonth> meses = mesesIpc(desde, hasta);
        if (meses.isEmpty()) {
            throw new OperacionInvalidaException("Para ajustar por IPC la ventana tiene que incluir al menos un mes: "
                    + "se usan los meses cuyo primer día está entre " + desde.minusMonths(1)
                    + " (incluido) y " + hasta.minusMonths(1) + " (excluido)");
        }

        SerieConOrigen serie = obtenerSerie(TipoIndiceEnum.IPC, meses.get(0).atDay(1), hasta);

        // El BCRA fecha el IPC a fin de mes; se agrupa por mes para no depender del día
        BinaryOperator<BcraValorDTO> elMasNuevo = (a, b) -> a.getFecha().isAfter(b.getFecha()) ? a : b;
        Map<YearMonth, BcraValorDTO> porMes = serie.valores().stream()
                .filter(v -> !v.getFecha().isAfter(hasta))
                .collect(Collectors.toMap(v -> YearMonth.from(v.getFecha()), v -> v, elMasNuevo));

        List<BcraValorDTO> mesesUsados = new ArrayList<>();
        for (YearMonth mes : meses) {
            BcraValorDTO valor = porMes.get(mes);
            if (valor == null) {
                throw ipcFaltante(mes, serie.origen());
            }
            mesesUsados.add(valor);
        }

        BigDecimal coeficiente = calculadora.coeficienteIpc(
                mesesUsados.stream().map(BcraValorDTO::getValor).toList());

        List<ValorIndiceDTO> detalle = mesesUsados.stream()
                .map(v -> ValorIndiceDTO.builder()
                        .tipoIndice(TipoIndiceEnum.IPC)
                        .fechaIndice(v.getFecha())
                        .valor(v.getValor())
                        .origen(serie.origen())
                        .build())
                .toList();

        return CoeficienteAjusteDTO.builder()
                .tipoIndice(TipoIndiceEnum.IPC)
                .fechaDesde(desde)
                .fechaHasta(hasta)
                .fechaIndiceDesde(mesesUsados.get(0).getFecha())
                .fechaIndiceHasta(mesesUsados.get(mesesUsados.size() - 1).getFecha())
                .valorIndiceDesde(BigDecimal.ONE)
                .valorIndiceHasta(coeficiente)
                .coeficiente(coeficiente)
                .porcentajeVariacion(calculadora.calcularPorcentajeVariacion(coeficiente))
                .origen(serie.origen())
                .variacionesMensuales(detalle)
                .build();
    }

    /** Meses cuyo primer día está en [desde − 1 mes, hasta − 1 mes), en orden. */
    static List<YearMonth> mesesIpc(LocalDate desde, LocalDate hasta) {
        LocalDate inicio = desde.minusMonths(1);
        LocalDate fin = hasta.minusMonths(1);
        YearMonth mes = YearMonth.from(inicio);
        if (mes.atDay(1).isBefore(inicio)) {
            mes = mes.plusMonths(1);
        }
        List<YearMonth> meses = new ArrayList<>();
        while (mes.atDay(1).isBefore(fin)) {
            meses.add(mes);
            mes = mes.plusMonths(1);
        }
        return meses;
    }

    // ------------------------------------------------------------------
    // 3. Ajuste completo del canon (lo usa el proceso de indexación de contratos)
    // ------------------------------------------------------------------

    public ResultadoAjusteDTO calcularAjuste(TipoIndiceEnum tipo, BigDecimal canonAnterior,
                                             LocalDate desde, LocalDate hasta) {
        if (canonAnterior == null || canonAnterior.signum() <= 0) {
            throw new OperacionInvalidaException("El canon anterior debe ser mayor a cero");
        }
        CoeficienteAjusteDTO coef = calcularCoeficiente(tipo, desde, hasta);
        BigDecimal canonNuevo = calculadora.calcularCanonNuevo(canonAnterior, coef.getCoeficiente());

        log.info("Ajuste {} calculado: canon {} -> {} ({}%) con índices del {} al {} [origen {}]",
                tipo, canonAnterior, canonNuevo, coef.getPorcentajeVariacion(),
                coef.getFechaIndiceDesde(), coef.getFechaIndiceHasta(), coef.getOrigen());

        return ResultadoAjusteDTO.builder()
                .tipoIndice(tipo)
                .fechaIndiceDesde(coef.getFechaIndiceDesde())
                .fechaIndiceHasta(coef.getFechaIndiceHasta())
                .valorIndiceDesde(coef.getValorIndiceDesde())
                .valorIndiceHasta(coef.getValorIndiceHasta())
                .coeficiente(coef.getCoeficiente())
                .montoAnterior(canonAnterior)
                .montoNuevo(canonNuevo)
                .porcentajeVariacion(coef.getPorcentajeVariacion())
                .origen(coef.getOrigen())
                .build();
    }

    // ------------------------------------------------------------------
    // Resiliencia: BCRA primero, caché de contingencia si falla
    // ------------------------------------------------------------------

    /** Serie entre dos fechas (la usa el IPC, que necesita varios meses). */
    SerieConOrigen obtenerSerie(TipoIndiceEnum tipo, LocalDate desde, LocalDate hasta) {
        return consultar(tipo, "entre " + desde + " y " + hasta,
                () -> bcraClient.obtenerSerie(tipo.getIdVariableBcra(), desde, hasta),
                () -> cacheService.buscarEntreFechas(tipo, desde, hasta));
    }

    /**
     * Último valor publicado en o antes de 'hasta' (0 o 1 valor). Al BCRA se le pide sin límite hacia
     * atrás; en el caché solo se acepta uno que no sea anterior a 'minimoCache'.
     */
    SerieConOrigen obtenerUltimo(TipoIndiceEnum tipo, LocalDate minimoCache, LocalDate hasta) {
        return consultar(tipo, "hasta " + hasta,
                () -> bcraClient.obtenerUltimoValor(tipo.getIdVariableBcra(), hasta),
                () -> cacheService.buscarUltimoEntre(tipo, minimoCache, hasta));
    }

    /**
     * BCRA primero; si falla, el caché de contingencia (RN-12).
     * Lo que se recibe del BCRA se guarda en el caché para la próxima caída.
     */
    private SerieConOrigen consultar(TipoIndiceEnum tipo, String rango,
                                     Supplier<List<BcraValorDTO>> consultaBcra,
                                     Supplier<List<BcraValorDTO>> consultaCache) {
        List<BcraValorDTO> valores;
        try {
            valores = consultaBcra.get();
        } catch (RestClientException ex) {
            registrarFalla(tipo, rango, ex);
            return usarCache(tipo, rango, consultaCache);
        }

        // Si falla guardar en el caché, la consulta igual se responde con el dato del BCRA.
        // guardar() tiene su propia transacción: la falla no afecta a la de quien llamó.
        try {
            cacheService.guardar(tipo, valores);
        } catch (DataAccessException | TransactionException ex) {
            log.warn("No se pudo actualizar el caché de {}: {}", tipo, ex.getMessage());
        }
        return new SerieConOrigen(valores, OrigenDatoEnum.BCRA);
    }

    /**
     * Toda falla del BCRA es de integración: el pedido lo arma el sistema con un tipo de índice fijo.
     * Un 400 o 404 no es una caída sino una mala configuración o un cambio de la API (Alcance, sección 13):
     * se usa el caché igual, pero se loguea como ERROR para que alguien lo revise.
     */
    private void registrarFalla(TipoIndiceEnum tipo, String rango, RestClientException ex) {
        if (ex instanceof HttpClientErrorException http
                && (http.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()
                || http.getStatusCode().value() == HttpStatus.NOT_FOUND.value())) {
            log.error("El BCRA respondió {} a la consulta de {} (variable {}) {}: revisar bcra.api.base-url "
                            + "o si cambió la versión de la API. Se usa el caché de contingencia",
                    http.getStatusCode().value(), tipo, tipo.getIdVariableBcra(), rango);
            return;
        }
        log.warn("API de BCRA no disponible para {} {}. Utilizando caché local de contingencia. Causa: {}",
                tipo, rango, ex.getMessage());
    }

    private SerieConOrigen usarCache(TipoIndiceEnum tipo, String rango, Supplier<List<BcraValorDTO>> consultaCache) {
        try {
            return new SerieConOrigen(consultaCache.get(), OrigenDatoEnum.CACHE_CONTINGENCIA);
        } catch (DataAccessException | TransactionException ex) {
            log.error("Tampoco se pudo leer el caché de {} {}: {}", tipo, rango, ex.getMessage());
            return new SerieConOrigen(List.of(), OrigenDatoEnum.CACHE_CONTINGENCIA);
        }
    }

    /** Fecha más vieja que se acepta del caché para un valor puntual pedido en 'fecha'. */
    private LocalDate minimoAceptadoEnCache(TipoIndiceEnum tipo, LocalDate fecha) {
        return tipo == TipoIndiceEnum.ICL
                ? fecha.minusDays(maxAntiguedadDias)
                : fecha.minusMonths(mesesToleranciaIpc).withDayOfMonth(1);
    }

    /**
     * No se calculan índices de fechas futuras: un ajuste solo usa períodos cumplidos (RN-11).
     * Es una regla del sistema, no de la API: el BCRA publica el ICL por adelantado (días futuros).
     */
    private void validarNoFutura(LocalDate fecha) {
        if (fecha == null) {
            throw new OperacionInvalidaException("La fecha es obligatoria");
        }
        if (fecha.isAfter(LocalDate.now(clock))) {
            throw new OperacionInvalidaException("No se puede consultar un índice de una fecha futura: " + fecha);
        }
    }

    /**
     * Sin valor puntual. Si el BCRA respondió sin valores, la fecha es anterior al inicio de la serie (400).
     * Si se buscó en el caché porque el BCRA falló, el índice no está disponible (503).
     */
    private RuntimeException sinValorPuntual(TipoIndiceEnum tipo, LocalDate minimo, LocalDate fecha,
                                             OrigenDatoEnum origen) {
        if (origen == OrigenDatoEnum.BCRA) {
            return new OperacionInvalidaException("No hay valor de " + tipo + " publicado en o antes del " + fecha
                    + ": la fecha es anterior al inicio de la serie del BCRA");
        }
        return new IndiceNoDisponibleException("El BCRA no responde y el caché no tiene un valor de " + tipo
                + " entre el " + minimo + " y el " + fecha + " (antigüedad máxima aceptada en contingencia); "
                + "el ajuste queda pendiente");
    }

    private IndiceNoDisponibleException ipcFaltante(YearMonth mes, OrigenDatoEnum origen) {
        String nombreMes = mes.format(FORMATO_MES);
        if (origen == OrigenDatoEnum.BCRA) {
            return new IndiceNoDisponibleException("IPC de " + nombreMes
                    + " todavía no publicado; el ajuste queda pendiente");
        }
        return new IndiceNoDisponibleException("IPC de " + nombreMes
                + " no disponible: el BCRA no responde y el caché no tiene ese mes; el ajuste queda pendiente");
    }

    private OrigenDatoEnum combinarOrigen(OrigenDatoEnum a, OrigenDatoEnum b) {
        return (a == OrigenDatoEnum.BCRA && b == OrigenDatoEnum.BCRA)
                ? OrigenDatoEnum.BCRA
                : OrigenDatoEnum.CACHE_CONTINGENCIA;
    }
}
