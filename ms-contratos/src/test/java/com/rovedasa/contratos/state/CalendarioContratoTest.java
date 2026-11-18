package com.rovedasa.contratos.state;

import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.EstadoContratoEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarioContratoTest {

    @ParameterizedTest(name = "{0} -> {1} = {2} meses")
    @CsvSource({
            "2026-01-01, 2026-12-31, 12",
            "2026-11-17, 2028-11-17, 24",
            "2026-07-01, 2028-07-01, 24",
            "2026-12-01, 2026-12-20, 1",
            "2026-03-01, 2027-02-28, 12",
            "2026-01-31, 2026-03-01, 2",
            "2026-01-01, 2027-12-31, 24"
    })
    @DisplayName("totalMeses: cantidad de i con inicio + i meses < fin (ejemplos del PLAN)")
    void totalMesesSegunElPlan(LocalDate inicio, LocalDate fin, int esperado) {
        assertEquals(esperado, CalendarioContrato.totalMeses(inicio, fin));
    }

    @ParameterizedTest(name = "período desde {0}, día {1} -> vence {2}")
    @CsvSource({
            "2026-11-17, 10, 2026-12-10",
            "2026-07-01, 10, 2026-07-10",
            "2026-11-17, 17, 2026-11-17",
            "2026-02-28, 31, 2026-02-28",
            "2028-02-29, 31, 2028-02-29",
            "2026-01-30, 31, 2026-01-31",
            "2026-01-31, 30, 2026-02-28",
            "2026-12-20, 5, 2027-01-05"
    })
    @DisplayName("Vencimiento: primer día con ese número (recortado al mes) no anterior al inicio del período")
    void vencimientoNoEsAnteriorAlInicioDelPeriodo(LocalDate inicioPeriodo, int dia, LocalDate esperado) {
        assertEquals(esperado, CalendarioContrato.vencimiento(inicioPeriodo, dia));
    }

    @Test
    @DisplayName("Hitos H_k = inicio + k·f calculados desde el inicio (31/01 + 3 = 30/04, + 6 = 31/07)")
    void hitosSeCalculanDesdeElInicio() {
        Contrato contrato = contrato(LocalDate.of(2026, 1, 31), LocalDate.of(2027, 1, 31), 3);

        assertEquals(LocalDate.of(2026, 1, 31), CalendarioContrato.hito(contrato, 0));
        assertEquals(LocalDate.of(2026, 4, 30), CalendarioContrato.hito(contrato, 1));
        assertEquals(LocalDate.of(2026, 7, 31), CalendarioContrato.hito(contrato, 2));
    }

    @Test
    @DisplayName("Ajuste pendiente: corresponde desde el hito, no antes, y nunca con hito en la fecha de fin")
    void ajustePendienteSegunHito() {
        Contrato contrato = contrato(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1), 3);
        assertTrue(CalendarioContrato.ajustePendiente(contrato, LocalDate.of(2026, 12, 1)).isEmpty());

        contrato = contrato(LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3);
        assertTrue(CalendarioContrato.ajustePendiente(contrato, LocalDate.of(2026, 9, 30)).isEmpty());
        assertEquals(Optional.of(new PeriodoAjuste(1, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1))),
                CalendarioContrato.ajustePendiente(contrato, LocalDate.of(2026, 10, 1)));
    }

    private static Contrato contrato(LocalDate inicio, LocalDate fin, int frecuencia) {
        return Contrato.builder()
                .nroContrato("CONT-CAL")
                .fechaInicio(inicio)
                .fechaFin(fin)
                .frecuenciaAjusteMeses(frecuencia)
                .montoInicial(new BigDecimal("500000.00"))
                .montoActual(new BigDecimal("500000.00"))
                .diaVencimientoMes(10)
                .estado(EstadoContratoEnum.ACTIVO)
                .build();
    }
}
