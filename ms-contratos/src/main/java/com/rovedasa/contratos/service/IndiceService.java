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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Obtención de índices (ICL e IPC) con resiliencia ante caídas del BCRA (RNF-03).
 *
 * Estrategia:
 *  1. Se consulta el BCRA. Si responde, cada valor recibido se guarda en indices_cache.
 *  2. Si el BCRA falla (caída, timeout, 5xx, 401/403, 429 por cuota agotada o respuesta inesperada)
 *     se registra un log.warn y se leen los valores de indices_cache (fallback de contingencia).
 *  3. Si tampoco hay valores en el caché dentro de la ventana de tolerancia, se lanza
 *     IndiceNoDisponibleException (503): no se aplica un ajuste con datos viejos.
 *
 * Los errores del pedido no son caídas y responden 400: fecha futura, un 400 o 404 del BCRA
 * o un rango en el que el BCRA respondió pero no tiene valores publicados.
 */
@Slf4j
@Service
public class IndiceService {

    private final BcraEstadisticasClient bcraClient;
    private final IndiceCacheService cacheService;
    private final CalculadoraAjuste calculadora;
    private final int diasToleranciaIcl;
    private final int mesesToleranciaIpc;

    public IndiceService(BcraEstadisticasClient bcraClient,
                         IndiceCacheService cacheService,
                         CalculadoraAjuste calculadora,
                         @Value("${bcra.indices.dias-tolerancia-icl:7}") int diasToleranciaIcl,
                         @Value("${bcra.indices.meses-tolerancia-ipc:2}") int mesesToleranciaIpc) {
        this.bcraClient = bcraClient;
        this.cacheService = cacheService;
        this.calculadora = calculadora;
        this.diasToleranciaIcl = diasToleranciaIcl;
        this.mesesToleranciaIpc = mesesToleranciaIpc;
    }

    /** Serie de valores junto con el lugar de donde salieron. */
    record SerieConOrigen(List<BcraValorDTO> valores, OrigenDatoEnum origen) { }

    // ------------------------------------------------------------------
    // 1. Valor puntual de un índice
    // ------------------------------------------------------------------

    /**
     * Último valor publicado del índice en o antes de la fecha pedida.
     * ICL: valor diario. IPC: inflación del último mes publicado (en %).
     */
    public ValorIndiceDTO obtenerValor(TipoIndiceEnum tipo, LocalDate fecha) {
        validarNoFutura(fecha);
        LocalDate desde = (tipo == TipoIndiceEnum.ICL)
                ? fecha.minusDays(diasToleranciaIcl)
                : fecha.minusMonths(mesesToleranciaIpc + 1L).withDayOfMonth(1);

        SerieConOrigen serie = obtenerSerie(tipo, desde, fecha);

        BcraValorDTO valor = serie.valores().stream()
                .filter(v -> !v.getFecha().isAfter(fecha))
                .max(Comparator.comparing(BcraValorDTO::getFecha))
                .orElseThrow(() -> sinDatos(serie.origen(),
                        "No hay valor de " + tipo + " publicado entre " + desde + " y " + fecha));

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
     * IPC: se encadenan las inflaciones de los últimos N meses publicados al momento
     * del ajuste, donde N es la cantidad de meses entre 'desde' y 'hasta'.
     * Ej.: contrato con ajuste trimestral (desde 01/07, hasta 01/10) usa los 3 últimos
     * meses de inflación publicados hasta el 01/10.
     */
    private CoeficienteAjusteDTO coeficienteIpc(LocalDate desde, LocalDate hasta) {
        long meses = ChronoUnit.MONTHS.between(desde.withDayOfMonth(1), hasta.withDayOfMonth(1));
        if (meses < 1) {
            throw new OperacionInvalidaException("Para ajustar por IPC tiene que haber al menos un mes entre las fechas");
        }

        LocalDate inicioVentana = hasta.minusMonths(meses + mesesToleranciaIpc).withDayOfMonth(1);
        SerieConOrigen serie = obtenerSerie(TipoIndiceEnum.IPC, inicioVentana, hasta);

        List<BcraValorDTO> mesesUsados = serie.valores().stream()
                .filter(v -> !v.getFecha().isAfter(hasta))
                .sorted(Comparator.comparing(BcraValorDTO::getFecha).reversed())
                .limit(meses)
                .sorted(Comparator.comparing(BcraValorDTO::getFecha))
                .toList();

        if (mesesUsados.size() < meses) {
            throw sinDatos(serie.origen(), "Para ajustar por IPC se necesitan " + meses
                    + " meses publicados entre " + inicioVentana + " y " + hasta
                    + " y solo hay " + mesesUsados.size());
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

    SerieConOrigen obtenerSerie(TipoIndiceEnum tipo, LocalDate desde, LocalDate hasta) {
        List<BcraValorDTO> valores;
        try {
            valores = bcraClient.obtenerSerie(tipo.getIdVariableBcra(), desde, hasta);
        } catch (HttpClientErrorException ex) {
            int status = ex.getStatusCode().value();
            if (status == HttpStatus.BAD_REQUEST.value() || status == HttpStatus.NOT_FOUND.value()) {
                // 400/404: el BCRA dice que el pedido está mal (parámetro o variable inválidos).
                // No es una caída: no se usa el caché
                log.warn("El BCRA rechazó la consulta de {} entre {} y {}: {}", tipo, desde, hasta, ex.getStatusCode());
                throw new OperacionInvalidaException("El BCRA rechazó la consulta de " + tipo + " entre "
                        + desde + " y " + hasta + " (" + status + ")");
            }
            // 401, 403, 429 (cuota agotada) y el resto: problema del lado del BCRA, se usa el caché
            return usarCache(tipo, desde, hasta, ex);
        } catch (RestClientException ex) {
            return usarCache(tipo, desde, hasta, ex);
        }

        // Si falla guardar en el caché, la consulta igual se responde con el dato del BCRA
        try {
            cacheService.guardar(tipo, valores);
        } catch (DataAccessException ex) {
            log.warn("No se pudo actualizar el caché de {}: {}", tipo, ex.getMessage());
        }
        return new SerieConOrigen(valores, OrigenDatoEnum.BCRA);
    }

    private SerieConOrigen usarCache(TipoIndiceEnum tipo, LocalDate desde, LocalDate hasta, RestClientException causa) {
        log.warn("API de BCRA no disponible para {} entre {} y {}. Utilizando caché local de contingencia. Causa: {}",
                tipo, desde, hasta, causa.getMessage());
        List<BcraValorDTO> valoresCache = cacheService.buscarEntreFechas(tipo, desde, hasta);
        return new SerieConOrigen(valoresCache, OrigenDatoEnum.CACHE_CONTINGENCIA);
    }

    /** El BCRA no publica índices de fechas que todavía no pasaron (y responde 400). */
    private void validarNoFutura(LocalDate fecha) {
        if (fecha == null) {
            throw new OperacionInvalidaException("La fecha es obligatoria");
        }
        if (fecha.isAfter(LocalDate.now())) {
            throw new OperacionInvalidaException("No se puede consultar un índice de una fecha futura: " + fecha);
        }
    }

    /**
     * Sin datos para el rango pedido. Si el BCRA respondió, es un problema del pedido (400);
     * si se buscó en el caché porque el BCRA estaba caído, el índice no está disponible (503).
     */
    private RuntimeException sinDatos(OrigenDatoEnum origen, String mensaje) {
        return origen == OrigenDatoEnum.BCRA
                ? new OperacionInvalidaException(mensaje)
                : new IndiceNoDisponibleException(mensaje + " (el BCRA no responde y el caché no tiene ese dato)");
    }

    private OrigenDatoEnum combinarOrigen(OrigenDatoEnum a, OrigenDatoEnum b) {
        return (a == OrigenDatoEnum.BCRA && b == OrigenDatoEnum.BCRA)
                ? OrigenDatoEnum.BCRA
                : OrigenDatoEnum.CACHE_CONTINGENCIA;
    }
}
