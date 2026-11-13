package com.rovedasa.catalogo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reglas de RN-07 para que una persona califique como garante solvente:
 *
 *   1. situacionCrediticia <= 2   (1 = normal, 2 = riesgo bajo; 3 a 5 rechaza)
 *   2. chequesRechazados == 0
 *
 * No depende del BCRA ni de la base: recibe los datos ya obtenidos y decide.
 * Así se prueba con JUnit puro.
 */
@Component
public class EvaluadorSolvencia {

    private final int situacionMaxima;
    private final int chequesRechazadosMaximos;

    public EvaluadorSolvencia(@Value("${bcra.solvencia.situacion-maxima:2}") int situacionMaxima,
                              @Value("${bcra.solvencia.cheques-rechazados-maximos:0}") int chequesRechazadosMaximos) {
        this.situacionMaxima = situacionMaxima;
        this.chequesRechazadosMaximos = chequesRechazadosMaximos;
    }

    public record Resultado(boolean esSolvente, String motivo) { }

    public Resultado evaluar(int situacionCrediticia, int chequesRechazados) {
        List<String> rechazos = new ArrayList<>();

        if (situacionCrediticia > situacionMaxima) {
            rechazos.add("situación crediticia " + situacionCrediticia
                    + " en el BCRA (máximo permitido " + situacionMaxima + ")");
        }
        if (chequesRechazados > chequesRechazadosMaximos) {
            rechazos.add("registra " + chequesRechazados + " cheque(s) rechazado(s)");
        }

        if (!rechazos.isEmpty()) {
            return new Resultado(false, "No califica: " + String.join("; ", rechazos));
        }
        return new Resultado(true, "Califica: situación " + situacionCrediticia
                + " y " + chequesRechazados + " cheques rechazados");
    }
}
