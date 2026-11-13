package com.rovedasa.catalogo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluadorSolvenciaTest {

    private final EvaluadorSolvencia evaluador = new EvaluadorSolvencia(2, 0, new BigDecimal("3"));
    private final BigDecimal canon = new BigDecimal("400000");

    @Test
    @DisplayName("Situación 1, sin cheques e ingresos >= 3 x canon: califica")
    void garanteSolvente() {
        var r = evaluador.evaluar(1, 0, new BigDecimal("1200000"), canon);
        assertTrue(r.esSolvente(), r.motivo());
    }

    @Test
    @DisplayName("Situación 2 (riesgo bajo) todavía califica")
    void situacionDosCalifica() {
        assertTrue(evaluador.evaluar(2, 0, new BigDecimal("2000000"), canon).esSolvente());
    }

    @Test
    @DisplayName("Situación 3 o más: no califica")
    void situacionTresRechaza() {
        var r = evaluador.evaluar(3, 0, new BigDecimal("2000000"), canon);
        assertAll(
                () -> assertFalse(r.esSolvente()),
                () -> assertTrue(r.motivo().contains("situación crediticia 3")));
    }

    @Test
    @DisplayName("Con un cheque rechazado: no califica")
    void chequeRechazado() {
        assertFalse(evaluador.evaluar(1, 1, new BigDecimal("2000000"), canon).esSolvente());
    }

    @Test
    @DisplayName("Ingresos insuficientes o no declarados: no califica")
    void ingresosInsuficientes() {
        assertAll(
                () -> assertFalse(evaluador.evaluar(1, 0, new BigDecimal("1199999"), canon).esSolvente()),
                () -> assertFalse(evaluador.evaluar(1, 0, null, canon).esSolvente()));
    }

    @Test
    @DisplayName("Sin canon informado la regla de ingresos no se evalúa")
    void sinCanon() {
        var r = evaluador.evaluar(1, 0, null, null);
        assertAll(
                () -> assertTrue(r.esSolvente()),
                () -> assertTrue(r.motivo().contains("ingresos no evaluados")));
    }
}
