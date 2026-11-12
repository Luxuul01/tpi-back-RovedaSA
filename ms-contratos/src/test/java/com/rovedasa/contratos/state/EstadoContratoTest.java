package com.rovedasa.contratos.state;

import com.rovedasa.commons.enums.EstadoContratoEnum;
import com.rovedasa.commons.enums.EstadoCuotaEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.dto.OrigenDatoEnum;
import com.rovedasa.contratos.model.Ajuste;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EstadoContratoTest {

    private EstadoContratoFactory factory;
    private Contrato contratoBase;

    @BeforeEach
    void setUp() {
        factory = new EstadoContratoFactory();
        contratoBase = Contrato.builder()
                .nroContrato("CONT-TEST0001")
                .propiedadId(10L)
                .propietarioId(20L)
                .inquilinoId(30L)
                .garanteId(40L)
                .propietarioKeycloakId("sub-propietario-20")
                .inquilinoKeycloakId("sub-inquilino-30")
                .fechaInicio(LocalDate.of(2026, 7, 1))
                .fechaFin(LocalDate.of(2028, 7, 1))
                .frecuenciaAjusteMeses(3)
                .tipoIndice(TipoIndiceEnum.ICL)
                .montoInicial(new BigDecimal("500000.00"))
                .montoActual(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000"))
                .diaVencimientoMes(10)
                .estado(EstadoContratoEnum.CREADO)
                .build();
    }

    @Test
    @DisplayName("CREADO -> SENIADO: registra monto y fecha límite de seña correctamente")
    void debePasarDeCreadoASeniado() {
        EstadoContrato estado = factory.obtenerEstado(contratoBase);
        LocalDate hoy = LocalDate.of(2026, 6, 20);
        LocalDate limite = LocalDate.of(2026, 6, 25);

        estado.seniar(contratoBase, new BigDecimal("150000"), limite, hoy);

        assertEquals(EstadoContratoEnum.SENIADO, contratoBase.getEstado());
        assertEquals(new BigDecimal("150000.00"), contratoBase.getMontoSenia());
        assertEquals(limite, contratoBase.getFechaLimiteSenia());
    }

    @Test
    @DisplayName("CREADO: rechaza activar, ajustar, rescindir o finalizar directamente")
    void debeRechazarOperacionesInvalidasEnEstadoCreado() {
        EstadoContrato estado = factory.obtenerEstado(contratoBase);
        LocalDate hoy = LocalDate.of(2026, 7, 1);

        assertThrows(OperacionInvalidaException.class, () -> estado.activar(contratoBase, hoy));
        assertThrows(OperacionInvalidaException.class, () -> estado.rescindir(contratoBase, hoy));
        assertThrows(OperacionInvalidaException.class, () -> estado.finalizar(contratoBase, hoy));
        assertThrows(OperacionInvalidaException.class, () -> estado.aplicarAjuste(contratoBase, Ajuste.builder().build()));
    }

    @Test
    @DisplayName("SENIADO -> ACTIVO: activa dentro de plazo y genera las 3 cuotas del primer trimestre")
    void debeActivarContratoSeniadoYGenerarCuotasIniciales() {
        factory.obtenerEstado(contratoBase).seniar(
                contratoBase, new BigDecimal("150000.00"), LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 20));

        List<Cuota> cuotas = factory.obtenerEstado(contratoBase)
                .activar(contratoBase, LocalDate.of(2026, 6, 24));

        assertEquals(EstadoContratoEnum.ACTIVO, contratoBase.getEstado());
        assertEquals(3, cuotas.size());
        assertEquals(3, contratoBase.getCuotas().size());
        assertEquals(1, cuotas.get(0).getNroCuota());
        assertEquals(LocalDate.of(2026, 7, 10), cuotas.get(0).getFechaVencimiento());
        assertEquals(new BigDecimal("500000.00"), cuotas.get(0).getMontoBase());
        assertEquals(EstadoCuotaEnum.PENDIENTE, cuotas.get(0).getEstado());
    }

    @Test
    @DisplayName("SENIADO: rechaza activar cuando la fecha límite de la seña está vencida")
    void debeRechazarActivacionConSeniaVencida() {
        factory.obtenerEstado(contratoBase).seniar(
                contratoBase, new BigDecimal("150000.00"), LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 20));

        EstadoContrato estadoSeniado = factory.obtenerEstado(contratoBase);
        LocalDate fechaVencida = LocalDate.of(2026, 6, 26);

        assertThrows(OperacionInvalidaException.class,
                () -> estadoSeniado.activar(contratoBase, fechaVencida));
        assertEquals(EstadoContratoEnum.SENIADO, contratoBase.getEstado());
    }

    @Test
    @DisplayName("SENIADO -> CANCELADO: registra motivo y retención de seña")
    void debeCancelarContratoSeniadoConRetencionDeSenia() {
        factory.obtenerEstado(contratoBase).seniar(
                contratoBase, new BigDecimal("150000.00"), LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 20));

        factory.obtenerEstado(contratoBase).cancelar(contratoBase, "Desistimiento del postulante", true);

        assertEquals(EstadoContratoEnum.CANCELADO, contratoBase.getEstado());
        assertTrue(contratoBase.getSeniaRetenida());
        assertEquals("Desistimiento del postulante", contratoBase.getMotivoCancelacion());
    }

    @Test
    @DisplayName("ACTIVO -> ACTIVO (aplicarAjuste): actualiza canon a 625000 y genera cuotas 4, 5 y 6")
    void debeAplicarAjusteYGenerarSiguienteTramoDeCuotas() {
        activarContratoDePrueba();

        Ajuste ajuste = Ajuste.builder()
                .tipoIndice(TipoIndiceEnum.ICL)
                .fechaAplicacion(LocalDate.of(2026, 10, 1))
                .fechaIndiceDesde(LocalDate.of(2026, 7, 1))
                .fechaIndiceHasta(LocalDate.of(2026, 10, 1))
                .valorIndiceDesde(new BigDecimal("10.000000"))
                .valorIndiceHasta(new BigDecimal("12.500000"))
                .coeficiente(new BigDecimal("1.250000"))
                .montoAnterior(new BigDecimal("500000.00"))
                .montoNuevo(new BigDecimal("625000.00"))
                .porcentajeVariacion(new BigDecimal("25.0000"))
                .origen(OrigenDatoEnum.BCRA)
                .build();

        List<Cuota> nuevasCuotas = factory.obtenerEstado(contratoBase).aplicarAjuste(contratoBase, ajuste);

        assertEquals(new BigDecimal("625000.00"), contratoBase.getMontoActual());
        assertEquals(1, contratoBase.getAjustes().size());
        assertEquals(3, nuevasCuotas.size());
        assertEquals(6, contratoBase.getCuotas().size());
        assertEquals(4, nuevasCuotas.get(0).getNroCuota());
        assertEquals(new BigDecimal("625000.00"), nuevasCuotas.get(0).getMontoBase());
        assertNotNull(nuevasCuotas.get(0).getAjuste());
    }

    @Test
    @DisplayName("ACTIVO: rechaza repetir el mismo ajuste con igual fechaIndiceHasta")
    void debeRechazarAjusteDuplicado() {
        activarContratoDePrueba();

        Ajuste ajuste1 = Ajuste.builder()
                .fechaAplicacion(LocalDate.of(2026, 10, 1))
                .fechaIndiceDesde(LocalDate.of(2026, 7, 1))
                .fechaIndiceHasta(LocalDate.of(2026, 10, 1))
                .valorIndiceDesde(new BigDecimal("10.000000"))
                .valorIndiceHasta(new BigDecimal("12.500000"))
                .coeficiente(new BigDecimal("1.250000"))
                .montoAnterior(new BigDecimal("500000.00"))
                .montoNuevo(new BigDecimal("625000.00"))
                .porcentajeVariacion(new BigDecimal("25.0000"))
                .build();

        EstadoContrato estadoActivo = factory.obtenerEstado(contratoBase);
        estadoActivo.aplicarAjuste(contratoBase, ajuste1);

        Ajuste ajusteRepetido = Ajuste.builder()
                .fechaAplicacion(LocalDate.of(2026, 10, 1))
                .fechaIndiceDesde(LocalDate.of(2026, 7, 1))
                .fechaIndiceHasta(LocalDate.of(2026, 10, 1))
                .valorIndiceDesde(new BigDecimal("10.000000"))
                .valorIndiceHasta(new BigDecimal("12.500000"))
                .coeficiente(new BigDecimal("1.250000"))
                .montoAnterior(new BigDecimal("625000.00"))
                .montoNuevo(new BigDecimal("781250.00"))
                .porcentajeVariacion(new BigDecimal("25.0000"))
                .build();

        assertThrows(OperacionInvalidaException.class,
                () -> estadoActivo.aplicarAjuste(contratoBase, ajusteRepetido));
    }

    @Test
    @DisplayName("ACTIVO -> RESCINDIDO (< 6 meses): aplica multa de 1.5 meses (750000) y anula cuotas futuras")
    void debeRescindirConMultaDeUnMesYMedioCuandoAntiguedadMenorASeisMeses() {
        activarContratoDePrueba();
        // Simular que la cuota 1 (2026-07-10) ya venció pero rescinde el 2026-07-15 (cuotas 2 y 3 son futuras)
        LocalDate fechaRescision = LocalDate.of(2026, 7, 15);

        BigDecimal multa = factory.obtenerEstado(contratoBase).rescindir(contratoBase, fechaRescision);

        assertEquals(EstadoContratoEnum.RESCINDIDO, contratoBase.getEstado());
        assertEquals(new BigDecimal("750000.00"), multa);
        assertEquals(new BigDecimal("750000.00"), contratoBase.getPenalizacionRescision());
        assertEquals(EstadoCuotaEnum.PENDIENTE, contratoBase.getCuotas().get(0).getEstado());
        assertEquals(EstadoCuotaEnum.ANULADA, contratoBase.getCuotas().get(1).getEstado());
        assertEquals(EstadoCuotaEnum.ANULADA, contratoBase.getCuotas().get(2).getEstado());
    }

    @Test
    @DisplayName("ACTIVO -> RESCINDIDO (>= 6 meses): aplica multa de 1 mes exacto (500000)")
    void debeRescindirConMultaDeUnMesCuandoAntiguedadMayorOIgualASeisMeses() {
        activarContratoDePrueba();
        LocalDate fechaRescision = LocalDate.of(2027, 1, 15); // 6 meses cumplidos desde 2026-07-01

        BigDecimal multa = factory.obtenerEstado(contratoBase).rescindir(contratoBase, fechaRescision);

        assertEquals(EstadoContratoEnum.RESCINDIDO, contratoBase.getEstado());
        assertEquals(new BigDecimal("500000.00"), multa);
    }

    @Test
    @DisplayName("ACTIVO -> FINALIZADO: exige haber alcanzado la fechaFin y bloquea finalización prematura")
    void debeFinalizarSoloCuandoAlcanzaFechaFin() {
        activarContratoDePrueba();
        EstadoContrato estadoActivo = factory.obtenerEstado(contratoBase);

        assertThrows(OperacionInvalidaException.class,
                () -> estadoActivo.finalizar(contratoBase, LocalDate.of(2028, 6, 30)));

        estadoActivo.finalizar(contratoBase, LocalDate.of(2028, 7, 1));
        assertEquals(EstadoContratoEnum.FINALIZADO, contratoBase.getEstado());
    }

    @Test
    @DisplayName("Estados terminales (CANCELADO, RESCINDIDO, FINALIZADO) rechazan cualquier operación posterior")
    void estadosTerminalesDebenRechazarTodaOperacion() {
        activarContratoDePrueba();
        factory.obtenerEstado(contratoBase).rescindir(contratoBase, LocalDate.of(2026, 8, 1));

        EstadoContrato estadoRescindido = factory.obtenerEstado(contratoBase);
        assertThrows(OperacionInvalidaException.class,
                () -> estadoRescindido.rescindir(contratoBase, LocalDate.of(2026, 8, 2)));
        assertThrows(OperacionInvalidaException.class,
                () -> estadoRescindido.finalizar(contratoBase, LocalDate.of(2028, 7, 1)));
    }

    @Test
    @DisplayName("CREADO -> CANCELADO: descarta borrador sin retener seña")
    void debeCancelarBorradorEnEstadoCreadoSinRetenerSenia() {
        factory.obtenerEstado(contratoBase).cancelar(contratoBase, "Inmueble alquilado por otro postulante", true);

        assertEquals(EstadoContratoEnum.CANCELADO, contratoBase.getEstado());
        assertFalse(contratoBase.getSeniaRetenida());
    }

    private void activarContratoDePrueba() {
        factory.obtenerEstado(contratoBase).seniar(
                contratoBase, new BigDecimal("150000.00"), LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 20));
        factory.obtenerEstado(contratoBase).activar(contratoBase, LocalDate.of(2026, 6, 24));
    }
}
