package com.rovedasa.contratos.service;

import com.rovedasa.commons.exception.OperacionInvalidaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculadoraAjusteTest {

    private final CalculadoraAjuste calculadora = new CalculadoraAjuste();

    @Test
    @DisplayName("ICL: el coeficiente es ICL_hasta / ICL_desde")
    void coeficienteIcl() {
        BigDecimal coef = calculadora.coeficienteIcl(new BigDecimal("30.00"), new BigDecimal("36.00"));
        assertEquals(new BigDecimal("1.20000000"), coef);
    }

    @Test
    @DisplayName("IPC: se encadenan las variaciones mensuales")
    void coeficienteIpcEncadenado() {
        // (1 + 0.10) x (1 + 0.10) = 1.21  (no 1.20: el IPC se compone mes a mes)
        BigDecimal coef = calculadora.coeficienteIpc(List.of(new BigDecimal("10"), new BigDecimal("10")));
        assertEquals(new BigDecimal("1.21000000"), coef);
    }

    @Test
    @DisplayName("IPC con inflaciones reales de 3 meses")
    void coeficienteIpcTresMeses() {
        // 2.1%, 1.7%, 1.9% -> 1.021 x 1.017 x 1.019 = 1.0580858...
        BigDecimal coef = calculadora.coeficienteIpc(
                List.of(new BigDecimal("2.1"), new BigDecimal("1.7"), new BigDecimal("1.9")));
        assertEquals(new BigDecimal("1.05808578"), coef);
    }

    @Test
    @DisplayName("Canon Nuevo = Canon Anterior x coeficiente, redondeado a 2 decimales")
    void canonNuevoYPorcentaje() {
        BigDecimal coef = new BigDecimal("1.05808578");
        assertAll("Ajuste de un canon de 500.000",
                () -> assertEquals(new BigDecimal("529042.89"),
                        calculadora.calcularCanonNuevo(new BigDecimal("500000"), coef)),
                () -> assertEquals(new BigDecimal("5.81"), calculadora.calcularPorcentajeVariacion(coef)));
    }

    @Test
    @DisplayName("Valores inválidos lanzan OperacionInvalidaException")
    void valoresInvalidos() {
        assertAll(
                () -> assertThrows(OperacionInvalidaException.class,
                        () -> calculadora.coeficienteIcl(BigDecimal.ZERO, BigDecimal.TEN)),
                () -> assertThrows(OperacionInvalidaException.class,
                        () -> calculadora.coeficienteIpc(List.of())),
                () -> assertThrows(OperacionInvalidaException.class,
                        () -> calculadora.calcularCanonNuevo(new BigDecimal("-1"), BigDecimal.ONE)));
    }
}
