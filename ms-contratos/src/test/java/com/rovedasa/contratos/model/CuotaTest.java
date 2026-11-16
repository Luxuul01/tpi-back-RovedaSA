package com.rovedasa.contratos.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuotaTest {

    @Test
    @DisplayName("La mora es 0.00 cuando la fecha de referencia no supera el vencimiento")
    void debeRetornarMoraCeroCuandoNoEstaVencida() {
        Cuota cuota = Cuota.builder()
                .nroCuota(1)
                .periodoMes(7)
                .periodoAnio(2026)
                .fechaVencimiento(LocalDate.of(2026, 7, 10))
                .montoBase(new BigDecimal("500000.00"))
                .build();

        LocalDate fechaConsulta = LocalDate.of(2026, 7, 10);

        assertFalse(cuota.estaVencida(fechaConsulta));
        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(fechaConsulta, new BigDecimal("0.1000")));
        assertEquals(new BigDecimal("500000.00"), cuota.calcularTotalConMora(fechaConsulta, new BigDecimal("0.1000")));
    }

    @Test
    @DisplayName("Calcula mora diaria exacta del 0.1% sobre 10 días de atraso")
    void debeCalcularMoraDiariaExactaCuandoEstaVencida() {
        Cuota cuota = Cuota.builder()
                .nroCuota(1)
                .periodoMes(7)
                .periodoAnio(2026)
                .fechaVencimiento(LocalDate.of(2026, 7, 10))
                .montoBase(new BigDecimal("500000.00"))
                .build();

        LocalDate fechaConsulta = LocalDate.of(2026, 7, 20);
        BigDecimal porcentajeDiario = new BigDecimal("0.1000");

        assertTrue(cuota.estaVencida(fechaConsulta));
        assertEquals(new BigDecimal("5000.00"), cuota.calcularMora(fechaConsulta, porcentajeDiario));
        assertEquals(new BigDecimal("505000.00"), cuota.calcularTotalConMora(fechaConsulta, porcentajeDiario));
    }

    @Test
    @DisplayName("Una cuota PAGADA no genera mora aunque se consulte después de su vencimiento")
    void noDebeCalcularMoraDeCuotaPagada() {
        Cuota cuota = Cuota.builder()
                .nroCuota(1)
                .periodoMes(7)
                .periodoAnio(2026)
                .fechaVencimiento(LocalDate.of(2026, 7, 10))
                .montoBase(new BigDecimal("500000.00"))
                .estado(EstadoCuotaEnum.PAGADA)
                .fechaPago(LocalDate.of(2026, 7, 18))
                .build();

        LocalDate fechaConsulta = LocalDate.of(2026, 7, 30);
        BigDecimal porcentajeDiario = new BigDecimal("0.1000");

        assertFalse(cuota.estaVencida(fechaConsulta));
        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(fechaConsulta, porcentajeDiario));
        assertEquals(new BigDecimal("500000.00"), cuota.calcularTotalConMora(fechaConsulta, porcentajeDiario));
    }

    @Test
    @DisplayName("Al anular una cuota pendiente pasa a ANULADA y no devenga mora")
    void debeAnularCuotaPendienteYRetornarMoraCero() {
        Cuota cuota = Cuota.builder()
                .nroCuota(2)
                .periodoMes(8)
                .periodoAnio(2026)
                .fechaVencimiento(LocalDate.of(2026, 8, 10))
                .montoBase(new BigDecimal("500000.00"))
                .build();

        cuota.anular();
        LocalDate fechaPosterior = LocalDate.of(2026, 8, 25);

        assertEquals(EstadoCuotaEnum.ANULADA, cuota.getEstado());
        assertFalse(cuota.estaVencida(fechaPosterior));
        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(fechaPosterior, new BigDecimal("0.1000")));
    }

    @Test
    @DisplayName("Contrato inicializa nroContrato, estado CREADO, tipoIndice ICL y montoActual por defecto")
    void debeInicializarValoresPorDefectoEnContrato() {
        Contrato contrato = Contrato.builder()
                .propiedadId(1L)
                .propietarioId(2L)
                .inquilinoId(3L)
                .garanteId(4L)
                .propietarioKeycloakId("sub-prop")
                .inquilinoKeycloakId("sub-inq")
                .fechaInicio(LocalDate.of(2026, 7, 1))
                .fechaFin(LocalDate.of(2028, 7, 1))
                .frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000"))
                .diaVencimientoMes(10)
                .build();

        contrato.inicializarValoresPorDefecto();

        assertNotNull(contrato.getNroContrato());
        assertTrue(contrato.getNroContrato().startsWith("CONT-"));
        assertEquals(EstadoContratoEnum.CREADO, contrato.getEstado());
        assertEquals(TipoIndiceEnum.ICL, contrato.getTipoIndice());
        assertEquals(new BigDecimal("500000.00"), contrato.getMontoActual());
    }
}
