package com.rovedasa.catalogo.client.bcra;

import com.rovedasa.catalogo.demo.SimuladorServiciosExternos;
import com.rovedasa.catalogo.service.EvaluadorSolvencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BcraCentralDeudoresClientSimuladoTest {

    private final SimuladorServiciosExternos simulador = new SimuladorServiciosExternos();
    private final BcraCentralDeudoresClientSimulado cliente = new BcraCentralDeudoresClientSimulado(simulador);
    private final EvaluadorSolvencia evaluador = new EvaluadorSolvencia(2, 0);

    private boolean esSolvente(String cuit) {
        int situacion = cliente.obtenerDeudas(cuit)
                .map(d -> d.getPeriodos().get(0).getEntidades().get(0).getSituacion()).orElse(1);
        int cheques = cliente.obtenerChequesRechazados(cuit)
                .map(c -> c.getCausales().get(0).getEntidades().get(0).getDetalle().size()).orElse(0);
        return evaluador.evaluar(situacion, cheques).esSolvente();
    }

    @Test
    @DisplayName("CUITs de demo: situación 3 y con cheques no califican; situación 2 y sin registros sí")
    void cuitsDeDemo() {
        assertAll(
                () -> assertFalse(esSolvente(BcraCentralDeudoresClientSimulado.CUIT_SITUACION_3)),
                () -> assertFalse(esSolvente(BcraCentralDeudoresClientSimulado.CUIT_CON_CHEQUES)),
                () -> assertTrue(esSolvente(BcraCentralDeudoresClientSimulado.CUIT_SITUACION_2)),
                () -> assertTrue(esSolvente("20888888889")),
                () -> assertEquals(3, cliente.obtenerDeudas(BcraCentralDeudoresClientSimulado.CUIT_SITUACION_3)
                        .orElseThrow().getPeriodos().get(0).getEntidades().get(0).getSituacion()));
    }

    @Test
    @DisplayName("Con la caída simulada lanza la misma excepción que un timeout real")
    void caidaSimulada() {
        simulador.setBcraCaido(true);
        assertThrows(ResourceAccessException.class, () -> cliente.obtenerDeudas("20888888889"));
        simulador.setBcraCaido(false);
        assertTrue(cliente.obtenerDeudas("20888888889").isEmpty());
    }
}
