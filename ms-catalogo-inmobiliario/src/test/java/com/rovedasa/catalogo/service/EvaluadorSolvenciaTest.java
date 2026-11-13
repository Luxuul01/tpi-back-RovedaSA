package com.rovedasa.catalogo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluadorSolvenciaTest {

    private final EvaluadorSolvencia evaluador = new EvaluadorSolvencia(2, 0);

    @Test
    @DisplayName("Situación 1 y sin cheques rechazados: califica")
    void garanteSolvente() {
        var r = evaluador.evaluar(1, 0);
        assertAll(
                () -> assertTrue(r.esSolvente(), r.motivo()),
                () -> assertTrue(r.motivo().startsWith("Califica")));
    }

    @Test
    @DisplayName("Situación 2 (riesgo bajo) todavía califica")
    void situacionDosCalifica() {
        assertTrue(evaluador.evaluar(2, 0).esSolvente());
    }

    @Test
    @DisplayName("Situación 3 o más: no califica")
    void situacionTresRechaza() {
        var r = evaluador.evaluar(3, 0);
        assertAll(
                () -> assertFalse(r.esSolvente()),
                () -> assertTrue(r.motivo().contains("situación crediticia 3")));
    }

    @Test
    @DisplayName("Con un cheque rechazado: no califica")
    void chequeRechazado() {
        var r = evaluador.evaluar(1, 1);
        assertAll(
                () -> assertFalse(r.esSolvente()),
                () -> assertTrue(r.motivo().contains("1 cheque(s) rechazado(s)")));
    }

    @Test
    @DisplayName("Si fallan las dos reglas, el motivo nombra las dos")
    void fallanAmbas() {
        var r = evaluador.evaluar(4, 2);
        assertAll(
                () -> assertFalse(r.esSolvente()),
                () -> assertTrue(r.motivo().contains("situación crediticia 4")),
                () -> assertTrue(r.motivo().contains("2 cheque(s) rechazado(s)")));
    }

    @Test
    @DisplayName("El motivo no menciona ingresos: RN-07 solo usa situación y cheques")
    void motivoSinIngresos() {
        assertAll(
                () -> assertFalse(evaluador.evaluar(1, 0).motivo().contains("ingreso")),
                () -> assertFalse(evaluador.evaluar(3, 1).motivo().contains("ingreso")));
    }
}
