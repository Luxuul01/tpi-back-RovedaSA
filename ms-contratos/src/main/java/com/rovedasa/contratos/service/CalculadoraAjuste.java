package com.rovedasa.contratos.service;

import com.rovedasa.commons.exception.OperacionInvalidaException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Matemática del ajuste de alquiler. No depende de nada externo: se prueba con
 * tests unitarios de JUnit puros.
 *
 *   ICL: coeficiente = ICL_hasta / ICL_desde
 *   IPC: coeficiente = (1 + v1/100) x (1 + v2/100) x ... x (1 + vN/100)
 *   Canon Nuevo = Canon Anterior x coeficiente   (redondeo a 2 decimales)
 *   % Variación = (coeficiente - 1) x 100        (redondeo a 2 decimales)
 */
@Component
public class CalculadoraAjuste {

    /** Decimales con los que se guarda el coeficiente para no perder precisión antes de redondear el monto. */
    static final int ESCALA_COEFICIENTE = 8;
    private static final BigDecimal CIEN = new BigDecimal("100");

    public BigDecimal coeficienteIcl(BigDecimal iclDesde, BigDecimal iclHasta) {
        if (iclDesde == null || iclHasta == null || iclDesde.signum() <= 0 || iclHasta.signum() <= 0) {
            throw new OperacionInvalidaException("Los valores del ICL deben ser mayores a cero");
        }
        return iclHasta.divide(iclDesde, ESCALA_COEFICIENTE, RoundingMode.HALF_UP);
    }

    public BigDecimal coeficienteIpc(List<BigDecimal> variacionesMensualesPorcentaje) {
        if (variacionesMensualesPorcentaje == null || variacionesMensualesPorcentaje.isEmpty()) {
            throw new OperacionInvalidaException("Se necesita al menos una variación mensual de IPC");
        }
        BigDecimal coeficiente = BigDecimal.ONE;
        for (BigDecimal v : variacionesMensualesPorcentaje) {
            BigDecimal factorMes = BigDecimal.ONE.add(v.divide(CIEN, 10, RoundingMode.HALF_UP));
            coeficiente = coeficiente.multiply(factorMes);
        }
        return coeficiente.setScale(ESCALA_COEFICIENTE, RoundingMode.HALF_UP);
    }

    public BigDecimal calcularCanonNuevo(BigDecimal canonAnterior, BigDecimal coeficiente) {
        if (canonAnterior == null || canonAnterior.signum() <= 0) {
            throw new OperacionInvalidaException("El canon anterior debe ser mayor a cero");
        }
        return canonAnterior.multiply(coeficiente).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calcularPorcentajeVariacion(BigDecimal coeficiente) {
        return coeficiente.subtract(BigDecimal.ONE).multiply(CIEN).setScale(2, RoundingMode.HALF_UP);
    }
}
