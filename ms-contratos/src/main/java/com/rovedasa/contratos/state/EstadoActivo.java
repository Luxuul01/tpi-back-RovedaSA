package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class EstadoActivo implements EstadoContrato {

    private static final int MESES_UMBRAL_MULTA_ALTA = 6;
    private static final BigDecimal FACTOR_MULTA_TEMPRANA = new BigDecimal("1.50");
    private static final BigDecimal FACTOR_MULTA_ESTANDAR = new BigDecimal("1.00");
    /** Las columnas de montos son NUMERIC(15,2): como mucho 13 dígitos enteros. */
    private static final BigDecimal MONTO_MAXIMO_EXCLUSIVO = new BigDecimal("1E13");

    @Override
    public EstadoContratoEnum getTipoEstado() {
        return EstadoContratoEnum.ACTIVO;
    }

    @Override
    public EstadoPropiedadEnum estadoPropiedadOcupada() {
        return EstadoPropiedadEnum.ALQUILADA;
    }

    /**
     * RN-11: el ajuste k corresponde si hoy ≥ H_k, H_k &lt; fechaFin y ya se aplicaron los anteriores.
     * Las fechas pedidas son opcionales; si vienen tienen que ser exactamente H_{k-1} y H_k.
     */
    @Override
    public PeriodoAjuste validarAjuste(Contrato contrato, LocalDate fechaActual,
                                       LocalDate desdePedido, LocalDate hastaPedido) {
        if (fechaActual == null) {
            throw new OperacionInvalidaException("La fecha actual es obligatoria para aplicar un ajuste");
        }
        if (hastaPedido != null && contrato.getAjustes().stream()
                .anyMatch(existente -> hastaPedido.equals(existente.getFechaIndiceHasta()))) {
            throw new OperacionInvalidaException(String.format(
                    "Ya existe un ajuste aplicado para el período que termina el %s en el contrato %s",
                    hastaPedido, contrato.getNroContrato()));
        }

        PeriodoAjuste periodo = CalendarioContrato.siguienteAjuste(contrato)
                .orElseThrow(() -> new OperacionInvalidaException(String.format(
                        "El contrato %s no tiene más ajustes: el próximo hito (%s) no es anterior a su fecha de fin (%s)",
                        contrato.getNroContrato(), CalendarioContrato.proximoHito(contrato), contrato.getFechaFin())));

        if (fechaActual.isBefore(periodo.hasta())) {
            throw new OperacionInvalidaException(String.format(
                    "No se puede aplicar el ajuste N° %d al contrato %s: aún no se cumplió el período mínimo de %d meses "
                            + "(corresponde a partir del %s)",
                    periodo.numero(), contrato.getNroContrato(), contrato.getFrecuenciaAjusteMeses(), periodo.hasta()));
        }

        boolean desdeCoincide = desdePedido == null || desdePedido.equals(periodo.desde());
        boolean hastaCoincide = hastaPedido == null || hastaPedido.equals(periodo.hasta());
        if (!desdeCoincide || !hastaCoincide) {
            throw new OperacionInvalidaException(String.format(
                    "Las fechas del índice no corresponden al ajuste N° %d del contrato %s: deben ser desde %s "
                            + "hasta %s (o pueden omitirse)",
                    periodo.numero(), contrato.getNroContrato(), periodo.desde(), periodo.hasta()));
        }
        return periodo;
    }

    @Override
    public Optional<PeriodoAjuste> ajustePendiente(Contrato contrato, LocalDate fechaActual) {
        return CalendarioContrato.ajustePendiente(contrato, fechaActual);
    }

    /**
     * Registra el ajuste en la ventana de su período (fechaIndiceDesde/Hasta = H_{k-1}/H_k), actualiza el canon
     * y emite las cuotas del tramo k. La fecha de aplicación del ajuste es la fecha actual de la validación.
     */
    @Override
    public List<Cuota> aplicarAjuste(Contrato contrato, Ajuste nuevoAjuste) {
        if (nuevoAjuste == null || nuevoAjuste.getMontoNuevo() == null
                || nuevoAjuste.getMontoNuevo().compareTo(BigDecimal.ZERO) <= 0) {
            throw new OperacionInvalidaException("El ajuste debe contener un nuevo monto válido mayor a cero");
        }
        if (nuevoAjuste.getFechaAplicacion() == null) {
            throw new OperacionInvalidaException("La fecha de aplicación del ajuste es obligatoria");
        }

        PeriodoAjuste periodo = validarAjuste(contrato, nuevoAjuste.getFechaAplicacion(),
                nuevoAjuste.getFechaIndiceDesde(), nuevoAjuste.getFechaIndiceHasta());
        nuevoAjuste.setFechaIndiceDesde(periodo.desde());
        nuevoAjuste.setFechaIndiceHasta(periodo.hasta());

        BigDecimal canonActualizado = nuevoAjuste.getMontoNuevo().setScale(2, RoundingMode.HALF_UP);
        validarMonto(canonActualizado, "El canon ajustado", contrato);
        nuevoAjuste.setMontoNuevo(canonActualizado);
        contrato.agregarAjuste(nuevoAjuste);
        contrato.setMontoActual(canonActualizado);

        List<Cuota> nuevasCuotas = GeneradorCuotasTramo.generarTramo(contrato, periodo.numero(), nuevoAjuste,
                nuevoAjuste.getFechaAplicacion());

        log.info("Ajuste N° {} aplicado al contrato {} (período {} a {}): nuevo canon={} ({}%). Se emitieron {} cuotas",
                periodo.numero(), contrato.getNroContrato(), periodo.desde(), periodo.hasta(),
                canonActualizado, nuevoAjuste.getPorcentajeVariacion(), nuevasCuotas.size());
        return nuevasCuotas;
    }

    /**
     * RN-14: la fecha de rescisión va entre fechaInicio y hoy (como mucho la fecha de fin): no puede ser futura,
     * porque la rescisión libera la propiedad en el acto. Penalización de 1,5 cánones con menos de 6 meses de
     * antigüedad y de 1 canon con 6 o más. Anula las cuotas PENDIENTE que vencen después de la fecha de rescisión.
     */
    @Override
    public BigDecimal rescindir(Contrato contrato, LocalDate fechaRescision, LocalDate fechaActual) {
        if (fechaRescision == null) {
            throw new OperacionInvalidaException("La fecha de rescisión es obligatoria");
        }
        if (fechaActual == null) {
            throw new OperacionInvalidaException("La fecha actual es obligatoria para rescindir el contrato");
        }
        LocalDate inicio = contrato.getFechaInicio();
        if (fechaActual.isBefore(inicio)) {
            throw new OperacionInvalidaException(String.format(
                    "No se puede rescindir el contrato %s antes de su fecha de inicio (%s): la fecha de rescisión "
                            + "no puede ser futura porque la rescisión libera la propiedad en el acto (RN-14)",
                    contrato.getNroContrato(), inicio));
        }
        boolean antesDelFin = fechaActual.isBefore(contrato.getFechaFin());
        LocalDate maxima = antesDelFin ? fechaActual : contrato.getFechaFin();
        if (fechaRescision.isBefore(inicio) || fechaRescision.isAfter(maxima)) {
            throw new OperacionInvalidaException(String.format(
                    "La fecha de rescisión del contrato %s debe estar entre el %s (inicio) y el %s (%s): no puede "
                            + "ser futura porque la rescisión libera la propiedad en el acto (RN-14)",
                    contrato.getNroContrato(), inicio, maxima, antesDelFin ? "hoy" : "fecha de fin"));
        }

        long mesesTranscurridos = ChronoUnit.MONTHS.between(inicio, fechaRescision);
        BigDecimal factorMulta = mesesTranscurridos < MESES_UMBRAL_MULTA_ALTA
                ? FACTOR_MULTA_TEMPRANA
                : FACTOR_MULTA_ESTANDAR;

        BigDecimal penalizacion = contrato.getMontoActual()
                .multiply(factorMulta)
                .setScale(2, RoundingMode.HALF_UP);
        validarMonto(penalizacion, "La penalización por rescisión", contrato);

        int cuotasAnuladas = 0;
        for (Cuota cuota : contrato.getCuotas()) {
            if (esPendienteSinPago(cuota) && cuota.getFechaVencimiento().isAfter(fechaRescision)) {
                cuota.anular();
                cuotasAnuladas++;
            }
        }

        contrato.setFechaRescision(fechaRescision);
        contrato.setPenalizacionRescision(penalizacion);
        contrato.setEstado(EstadoContratoEnum.RESCINDIDO);

        log.info("Contrato {} pasó de ACTIVO a RESCINDIDO (antigüedad={} meses, penalización={}, cuotasAnuladas={})",
                contrato.getNroContrato(), mesesTranscurridos, penalizacion, cuotasAnuladas);
        return penalizacion;
    }

    /**
     * RN-15: solo desde la fecha de fin. Las cuotas cuyo período empezó antes de la fecha de fin están devengadas
     * y no se anulan aunque venzan después (01/12 → 20/12 con vencimiento el día 25: la del 25/12 se cobra).
     * Solo se anulan, como defensa, las PENDIENTE cuyo período empieza en o después de la fecha de fin, que con
     * la generación por tramos no deberían existir.
     */
    @Override
    public void finalizar(Contrato contrato, LocalDate fechaActual) {
        if (fechaActual == null) {
            throw new OperacionInvalidaException("La fecha actual es obligatoria para finalizar el contrato");
        }
        if (fechaActual.isBefore(contrato.getFechaFin())) {
            throw new OperacionInvalidaException(String.format(
                    "No se puede finalizar el contrato %s antes de su fecha de vencimiento (%s)",
                    contrato.getNroContrato(), contrato.getFechaFin()));
        }

        int cuotasAnuladas = 0;
        for (Cuota cuota : contrato.getCuotas()) {
            LocalDate inicioPeriodo = CalendarioContrato.inicioPeriodo(contrato, cuota.getNroCuota() - 1);
            if (esPendienteSinPago(cuota) && !inicioPeriodo.isBefore(contrato.getFechaFin())) {
                cuota.anular();
                cuotasAnuladas++;
            }
        }
        contrato.setEstado(EstadoContratoEnum.FINALIZADO);

        log.info("Contrato {} pasó de ACTIVO a FINALIZADO por cumplimiento de plazo en fecha {} (cuotasAnuladas={})",
                contrato.getNroContrato(), fechaActual, cuotasAnuladas);
    }

    private static void validarMonto(BigDecimal monto, String descripcion, Contrato contrato) {
        if (monto.abs().compareTo(MONTO_MAXIMO_EXCLUSIVO) >= 0) {
            throw new OperacionInvalidaException(String.format(
                    "%s del contrato %s (%s) supera el máximo admitido de 13 dígitos enteros",
                    descripcion, contrato.getNroContrato(), monto.toPlainString()));
        }
    }

    private static boolean esPendienteSinPago(Cuota cuota) {
        return cuota.getEstado() == EstadoCuotaEnum.PENDIENTE && cuota.getFechaPago() == null;
    }
}
