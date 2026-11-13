package com.rovedasa.catalogo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reglas de dominio para que una persona califique como garante solvente:
 *
 *   1. situacionCrediticia <= 2   (1 = normal, 2 = riesgo bajo; 3 a 5 rechaza)
 *   2. chequesRechazados == 0
 *   3. ingresos suficientes: ingresosDeclarados >= canonMensual x multiplo (configurable)
 *
 * No depende del BCRA ni de la base: recibe los datos ya obtenidos y decide.
 * Así se prueba con JUnit puro.
 */
@Component
public class EvaluadorSolvencia {

    private final int situacionMaxima;
    private final int chequesRechazadosMaximos;
    private final BigDecimal multiploIngresosCanon;

    public EvaluadorSolvencia(@Value("${bcra.solvencia.situacion-maxima:2}") int situacionMaxima,
                              @Value("${bcra.solvencia.cheques-rechazados-maximos:0}") int chequesRechazadosMaximos,
                              @Value("${bcra.solvencia.multiplo-ingresos-canon:3}") BigDecimal multiploIngresosCanon) {
        this.situacionMaxima = situacionMaxima;
        this.chequesRechazadosMaximos = chequesRechazadosMaximos;
        this.multiploIngresosCanon = multiploIngresosCanon;
    }

    public record Resultado(boolean esSolvente, String motivo) { }

    /**
     * @param canonMensual puede ser null (por ejemplo, al registrar un garante antes de tener contrato):
     *                     en ese caso la regla de ingresos no se evalúa y se aclara en el motivo.
     */
    public Resultado evaluar(int situacionCrediticia, int chequesRechazados,
                             BigDecimal ingresosDeclarados, BigDecimal canonMensual) {
        List<String> rechazos = new ArrayList<>();

        if (situacionCrediticia > situacionMaxima) {
            rechazos.add("situación crediticia " + situacionCrediticia
                    + " en el BCRA (máximo permitido " + situacionMaxima + ")");
        }
        if (chequesRechazados > chequesRechazadosMaximos) {
            rechazos.add("registra " + chequesRechazados + " cheque(s) rechazado(s)");
        }

        String notaIngresos;
        if (canonMensual == null) {
            notaIngresos = "ingresos no evaluados (no se informó canon)";
        } else {
            BigDecimal minimo = canonMensual.multiply(multiploIngresosCanon);
            if (ingresosDeclarados == null) {
                rechazos.add("no declaró ingresos");
            } else if (ingresosDeclarados.compareTo(minimo) < 0) {
                rechazos.add("ingresos declarados " + ingresosDeclarados.toPlainString()
                        + " menores a " + multiploIngresosCanon.toPlainString()
                        + " veces el canon (" + minimo.toPlainString() + ")");
            }
            notaIngresos = "ingresos suficientes";
        }

        if (!rechazos.isEmpty()) {
            return new Resultado(false, "No califica: " + String.join("; ", rechazos));
        }
        return new Resultado(true, "Califica: situación " + situacionCrediticia
                + ", " + chequesRechazados + " cheques rechazados, " + notaIngresos);
    }
}
