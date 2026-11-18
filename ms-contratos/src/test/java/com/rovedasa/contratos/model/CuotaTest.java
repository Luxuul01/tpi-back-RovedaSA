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
    @DisplayName("Una cuota PAGADA en término no genera mora aunque se consulte después de su vencimiento")
    void noDebeCalcularMoraDeCuotaPagadaEnTermino() {
        Cuota cuota = cuotaDeJulio(EstadoCuotaEnum.PAGADA, LocalDate.of(2026, 7, 10));

        LocalDate fechaConsulta = LocalDate.of(2026, 7, 30);
        BigDecimal porcentajeDiario = new BigDecimal("0.1000");

        assertFalse(cuota.estaVencida(fechaConsulta));
        assertEquals(0, cuota.calcularDiasAtraso(fechaConsulta));
        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(fechaConsulta, porcentajeDiario));
        assertEquals(new BigDecimal("500000.00"), cuota.calcularTotalConMora(fechaConsulta, porcentajeDiario));
    }

    @Test
    @DisplayName("RN-18: una cuota PAGADA fuera de término conserva la mora hasta la fecha de pago, sin seguir creciendo")
    void debeFijarLaMoraHastaLaFechaDePagoCuandoSePagaFueraDeTermino() {
        // Vence el 10/07 y se paga el 18/07: 8 días x 0,1 % x 500.000 = 4.000
        Cuota cuota = cuotaDeJulio(EstadoCuotaEnum.PAGADA, LocalDate.of(2026, 7, 18));
        BigDecimal porcentajeDiario = new BigDecimal("0.1000");

        assertFalse(cuota.estaVencida(LocalDate.of(2026, 7, 30)));
        assertEquals(8, cuota.calcularDiasAtraso(LocalDate.of(2026, 7, 30)));
        assertEquals(new BigDecimal("4000.00"), cuota.calcularMora(LocalDate.of(2026, 7, 18), porcentajeDiario));
        assertEquals(new BigDecimal("4000.00"), cuota.calcularMora(LocalDate.of(2026, 7, 30), porcentajeDiario));
        assertEquals(new BigDecimal("4000.00"), cuota.calcularMora(LocalDate.of(2027, 1, 1), porcentajeDiario));
        assertEquals(new BigDecimal("504000.00"), cuota.calcularTotalConMora(LocalDate.of(2026, 7, 30), porcentajeDiario));
    }

    @Test
    @DisplayName("Una cuota PAGADA consultada con fecha de referencia anterior al pago muestra la mora a esa fecha")
    void debeCalcularMoraHastaLaFechaDeReferenciaSiEsAnteriorAlPago() {
        Cuota cuota = cuotaDeJulio(EstadoCuotaEnum.PAGADA, LocalDate.of(2026, 7, 18));

        assertEquals(new BigDecimal("2500.00"), cuota.calcularMora(LocalDate.of(2026, 7, 15), new BigDecimal("0.1000")));
        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(LocalDate.of(2026, 7, 5), new BigDecimal("0.1000")));
    }

    @Test
    @DisplayName("registrarPago pasa la cuota a PAGADA con su fecha de pago")
    void debeRegistrarPago() {
        Cuota cuota = cuotaDeJulio(EstadoCuotaEnum.PENDIENTE, null);

        cuota.registrarPago(LocalDate.of(2026, 7, 12));

        assertEquals(EstadoCuotaEnum.PAGADA, cuota.getEstado());
        assertEquals(LocalDate.of(2026, 7, 12), cuota.getFechaPago());
    }

    @Test
    @DisplayName("La mora se redondea HALF_UP a 2 decimales una sola vez, sin redondeos intermedios")
    void debeRedondearLaMoraSoloAlFinal() {
        // 1,50 x 0,0001 % x 3000 días = 0,0045 -> 0,00. Redondeando el recargo diario a 6 decimales
        // antes de multiplicar (0,0000015 -> 0,000002) daba 0,006 -> 0,01.
        Cuota cuota = Cuota.builder()
                .nroCuota(1).periodoMes(1).periodoAnio(2018)
                .fechaVencimiento(LocalDate.of(2018, 1, 10))
                .montoBase(new BigDecimal("1.50"))
                .build();
        LocalDate fechaConsulta = LocalDate.of(2018, 1, 10).plusDays(3000);
        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(fechaConsulta, new BigDecimal("0.0001")));

        // HALF_UP en el último paso: 333,33 x 0,15 % x 1 día = 0,499995 -> 0,50
        Cuota otra = Cuota.builder()
                .nroCuota(1).periodoMes(7).periodoAnio(2026)
                .fechaVencimiento(LocalDate.of(2026, 7, 10))
                .montoBase(new BigDecimal("333.33"))
                .build();
        assertEquals(new BigDecimal("0.50"), otra.calcularMora(LocalDate.of(2026, 7, 11), new BigDecimal("0.1500")));
    }

    // ------------------------------------------------------------------
    // Fecha de emisión: una cuota emitida tarde no nace con mora retroactiva
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Cuota emitida después de su vencimiento: mora 0 el día de emisión y crece desde ahí")
    void cuotaEmitidaTardeNoTieneMoraRetroactiva() {
        // Vence el 10/07 pero se emite el 17/11 (contrato que se pone al día o ajuste demorado)
        Cuota cuota = cuotaEmitida(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 11, 17));
        BigDecimal porcentajeDiario = new BigDecimal("0.1000");

        assertTrue(cuota.estaVencida(LocalDate.of(2026, 11, 17)));
        assertEquals(LocalDate.of(2026, 11, 17), cuota.fechaInicioMora());
        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(LocalDate.of(2026, 11, 17), porcentajeDiario));
        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(LocalDate.of(2026, 8, 1), porcentajeDiario));
        assertEquals(new BigDecimal("500.00"), cuota.calcularMora(LocalDate.of(2026, 11, 18), porcentajeDiario));
        assertEquals(new BigDecimal("5000.00"), cuota.calcularMora(LocalDate.of(2026, 11, 27), porcentajeDiario));
        assertEquals(new BigDecimal("505000.00"), cuota.calcularTotalConMora(LocalDate.of(2026, 11, 27), porcentajeDiario));
    }

    @Test
    @DisplayName("Cuota emitida tarde y pagada con una fecha anterior a la emisión: sin mora")
    void cuotaEmitidaTardePagadaAntesDeLaEmisionNoTieneMora() {
        Cuota cuota = cuotaEmitida(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 11, 17));
        cuota.registrarPago(LocalDate.of(2026, 7, 20));

        assertEquals(new BigDecimal("0.00"), cuota.calcularMora(LocalDate.of(2026, 12, 1), new BigDecimal("0.1000")));
    }

    @Test
    @DisplayName("C-06 con fecha de emisión anterior al vencimiento: 10 días de atraso dan 5.000 y total 505.000")
    void c06ConFechaDeEmisionAnteriorAlVencimiento() {
        Cuota cuota = cuotaEmitida(LocalDate.of(2026, 12, 10), LocalDate.of(2026, 11, 17));
        BigDecimal porcentajeDiario = new BigDecimal("0.1000");

        assertEquals(LocalDate.of(2026, 12, 10), cuota.fechaInicioMora());
        assertEquals(new BigDecimal("5000.00"), cuota.calcularMora(LocalDate.of(2026, 12, 20), porcentajeDiario));
        assertEquals(new BigDecimal("505000.00"), cuota.calcularTotalConMora(LocalDate.of(2026, 12, 20), porcentajeDiario));
    }

    @Test
    @DisplayName("Sin fecha de emisión (cuotas viejas) la mora corre desde el vencimiento, como antes")
    void sinFechaDeEmisionCorreDesdeElVencimiento() {
        Cuota cuota = cuotaEmitida(LocalDate.of(2026, 7, 10), null);

        assertEquals(LocalDate.of(2026, 7, 10), cuota.fechaInicioMora());
        assertEquals(new BigDecimal("5000.00"), cuota.calcularMora(LocalDate.of(2026, 7, 20), new BigDecimal("0.1000")));
    }

    private static Cuota cuotaEmitida(LocalDate vencimiento, LocalDate emision) {
        return Cuota.builder()
                .nroCuota(1)
                .periodoMes(vencimiento.getMonthValue())
                .periodoAnio(vencimiento.getYear())
                .fechaVencimiento(vencimiento)
                .fechaEmision(emision)
                .montoBase(new BigDecimal("500000.00"))
                .estado(EstadoCuotaEnum.PENDIENTE)
                .build();
    }

    private Cuota cuotaDeJulio(EstadoCuotaEnum estado, LocalDate fechaPago) {
        return Cuota.builder()
                .nroCuota(1)
                .periodoMes(7)
                .periodoAnio(2026)
                .fechaVencimiento(LocalDate.of(2026, 7, 10))
                .montoBase(new BigDecimal("500000.00"))
                .estado(estado)
                .fechaPago(fechaPago)
                .build();
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
