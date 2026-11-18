package com.rovedasa.contratos.state;

import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.model.EstadoContratoEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneradorCuotasTramoTest {

    @Test
    @DisplayName("01/01 -> 31/12 con frecuencia 3: 4 tramos de 3 cuotas = 12 (antes YearMonth.between daba 11)")
    void contratoDeUnAnioGenera12Cuotas() {
        Contrato contrato = contrato(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 3, 10);

        for (int tramo = 0; tramo < 4; tramo++) {
            assertEquals(3, GeneradorCuotasTramo.generarTramo(contrato, tramo, null, null).size());
        }
        assertTrue(GeneradorCuotasTramo.generarTramo(contrato, 4, null, null).isEmpty());

        assertEquals(12, contrato.getCuotas().size());
        Cuota ultima = contrato.getCuotas().get(11);
        assertEquals(12, ultima.getNroCuota());
        assertEquals(12, ultima.getPeriodoMes());
        assertEquals(LocalDate.of(2026, 12, 10), ultima.getFechaVencimiento());
    }

    @Test
    @DisplayName("Contrato de menos de un mes (01/12 -> 20/12): una sola cuota y ninguna después del fin")
    void contratoDeMenosDeUnMesGeneraUnaCuota() {
        Contrato contrato = contrato(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 20), 3, 10);

        List<Cuota> cuotas = GeneradorCuotasTramo.generarTramo(contrato, 0, null, null);

        assertEquals(1, cuotas.size());
        assertEquals(LocalDate.of(2026, 12, 10), cuotas.get(0).getFechaVencimiento());
        assertTrue(CalendarioContrato.siguienteAjuste(contrato).isEmpty());
    }

    @Test
    @DisplayName("Contrato de un mes justo (17/11 -> 17/12): una cuota, que vence el 10/12")
    void contratoDeUnMes() {
        Contrato contrato = contrato(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 12, 17), 1, 10);

        List<Cuota> cuotas = GeneradorCuotasTramo.generarTramo(contrato, 0, null, null);

        assertEquals(1, cuotas.size());
        assertEquals(LocalDate.of(2026, 12, 10), cuotas.get(0).getFechaVencimiento());
        assertTrue(GeneradorCuotasTramo.generarTramo(contrato, 1, null, null).isEmpty());
    }

    @Test
    @DisplayName("Febrero bisiesto: inicio 31/01/2028 y día 31 -> la segunda cuota vence el 29/02/2028")
    void febreroBisiesto() {
        Contrato contrato = contrato(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 7, 31), 6, 31);

        List<Cuota> cuotas = GeneradorCuotasTramo.generarTramo(contrato, 0, null, null);

        assertEquals(6, cuotas.size());
        assertEquals(LocalDate.of(2028, 1, 31), cuotas.get(0).getFechaVencimiento());
        assertEquals(LocalDate.of(2028, 2, 29), cuotas.get(1).getFechaVencimiento());
        assertEquals(LocalDate.of(2028, 3, 31), cuotas.get(2).getFechaVencimiento());
        assertEquals(LocalDate.of(2028, 4, 30), cuotas.get(3).getFechaVencimiento());
    }

    @Test
    @DisplayName("Las cuotas se identifican por período: volver a emitir un tramo no duplica cuotas")
    void tramoRepetidoNoDuplica() {
        Contrato contrato = contrato(LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3, 10);
        GeneradorCuotasTramo.generarTramo(contrato, 0, null, null);

        assertTrue(GeneradorCuotasTramo.generarTramo(contrato, 0, null, null).isEmpty());
        assertEquals(List.of(4, 5, 6), GeneradorCuotasTramo.generarTramo(contrato, 1, null, null).stream()
                .map(Cuota::getNroCuota).toList());
    }

    @Test
    @DisplayName("Ninguna cuota nace vencida respecto de su período ni vence después de fin + 1 mes")
    void ningunaCuotaFueraDeSuPeriodo() {
        LocalDate[] inicios = {LocalDate.of(2026, 1, 31), LocalDate.of(2026, 11, 17), LocalDate.of(2026, 2, 28),
                LocalDate.of(2027, 12, 30), LocalDate.of(2026, 7, 1)};
        int[] dias = {1, 10, 17, 28, 29, 30, 31};
        for (LocalDate inicio : inicios) {
            for (int dia : dias) {
                LocalDate fin = inicio.plusMonths(14).plusDays(3);
                Contrato contrato = contrato(inicio, fin, 5, dia);
                for (int tramo = 0; tramo < 4; tramo++) {
                    GeneradorCuotasTramo.generarTramo(contrato, tramo, null, null);
                }
                assertEquals(CalendarioContrato.totalMeses(inicio, fin), contrato.getCuotas().size());
                for (Cuota cuota : contrato.getCuotas()) {
                    LocalDate inicioPeriodo = CalendarioContrato.inicioPeriodo(contrato, cuota.getNroCuota() - 1);
                    String caso = "inicio " + inicio + ", día " + dia + ", cuota " + cuota.getNroCuota();
                    assertFalse(cuota.getFechaVencimiento().isBefore(inicioPeriodo), caso);
                    assertTrue(cuota.getFechaVencimiento().isBefore(inicioPeriodo.plusMonths(1).plusDays(1)), caso);
                    assertFalse(cuota.getFechaVencimiento().isAfter(fin.plusMonths(1)), caso);
                }
            }
        }
    }

    private static Contrato contrato(LocalDate inicio, LocalDate fin, int frecuencia, int dia) {
        return Contrato.builder()
                .nroContrato("CONT-GEN")
                .fechaInicio(inicio)
                .fechaFin(fin)
                .frecuenciaAjusteMeses(frecuencia)
                .montoInicial(new BigDecimal("500000.00"))
                .montoActual(new BigDecimal("500000.00"))
                .diaVencimientoMes(dia)
                .estado(EstadoContratoEnum.ACTIVO)
                .build();
    }
}
