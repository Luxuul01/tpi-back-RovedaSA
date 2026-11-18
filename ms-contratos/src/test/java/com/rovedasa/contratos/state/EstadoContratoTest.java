package com.rovedasa.contratos.state;

import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
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
        assertThrows(OperacionInvalidaException.class, () -> estado.rescindir(contratoBase, hoy, hoy));
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

        BigDecimal multa = factory.obtenerEstado(contratoBase).rescindir(contratoBase, fechaRescision, fechaRescision);

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
        // Frecuencia 12: al 15/01/2027 todavía no corresponde ningún ajuste (con 3 faltarían aplicar dos)
        contratoBase.setFrecuenciaAjusteMeses(12);
        activarContratoDePrueba();
        LocalDate fechaRescision = LocalDate.of(2027, 1, 15); // 6 meses cumplidos desde 2026-07-01

        BigDecimal multa = factory.obtenerEstado(contratoBase).rescindir(contratoBase, fechaRescision, fechaRescision);

        assertEquals(EstadoContratoEnum.RESCINDIDO, contratoBase.getEstado());
        assertEquals(new BigDecimal("500000.00"), multa);
    }

    @Test
    @DisplayName("ACTIVO -> FINALIZADO: exige haber alcanzado la fechaFin y bloquea finalización prematura")
    void debeFinalizarSoloCuandoAlcanzaFechaFin() {
        // Frecuencia 24 (toda la duración): no queda ningún ajuste por aplicar al llegar a la fecha de fin
        contratoBase.setFrecuenciaAjusteMeses(24);
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
        factory.obtenerEstado(contratoBase).rescindir(contratoBase, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1));

        EstadoContrato estadoRescindido = factory.obtenerEstado(contratoBase);
        assertThrows(OperacionInvalidaException.class,
                () -> estadoRescindido.rescindir(contratoBase, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 2)));
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

    @Test
    @DisplayName("ACTIVO: rechaza un segundo ajuste cuando aún no transcurrió la frecuencia mínima desde el ajuste anterior")
    void debeRechazarSegundoAjustePrematuro() {
        activarContratoDePrueba();
        EstadoContrato estadoActivo = factory.obtenerEstado(contratoBase);

        Ajuste primerAjuste = Ajuste.builder()
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
        estadoActivo.aplicarAjuste(contratoBase, primerAjuste);

        Ajuste segundoAjustePrematuro = Ajuste.builder()
                .tipoIndice(TipoIndiceEnum.ICL)
                .fechaAplicacion(LocalDate.of(2026, 11, 1))
                .fechaIndiceDesde(LocalDate.of(2026, 10, 1))
                .fechaIndiceHasta(LocalDate.of(2026, 11, 1))
                .valorIndiceDesde(new BigDecimal("12.500000"))
                .valorIndiceHasta(new BigDecimal("13.000000"))
                .coeficiente(new BigDecimal("1.040000"))
                .montoAnterior(new BigDecimal("625000.00"))
                .montoNuevo(new BigDecimal("650000.00"))
                .porcentajeVariacion(new BigDecimal("4.0000"))
                .origen(OrigenDatoEnum.BCRA)
                .build();

        assertThrows(OperacionInvalidaException.class,
                () -> estadoActivo.aplicarAjuste(contratoBase, segundoAjustePrematuro));
        assertEquals(1, contratoBase.getAjustes().size());
        assertEquals(6, contratoBase.getCuotas().size());
    }

    @Test
    @DisplayName("ACTIVO: permite un segundo ajuste al cumplirse otro período de frecuencia y emite cuotas 7, 8 y 9")
    void debePermitirSegundoAjusteCumplidoElSiguientePeriodo() {
        activarContratoDePrueba();
        EstadoContrato estadoActivo = factory.obtenerEstado(contratoBase);

        Ajuste primerAjuste = Ajuste.builder()
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
        estadoActivo.aplicarAjuste(contratoBase, primerAjuste);

        Ajuste segundoAjuste = Ajuste.builder()
                .tipoIndice(TipoIndiceEnum.ICL)
                .fechaAplicacion(LocalDate.of(2027, 1, 1))
                .fechaIndiceDesde(LocalDate.of(2026, 10, 1))
                .fechaIndiceHasta(LocalDate.of(2027, 1, 1))
                .valorIndiceDesde(new BigDecimal("12.500000"))
                .valorIndiceHasta(new BigDecimal("15.000000"))
                .coeficiente(new BigDecimal("1.200000"))
                .montoAnterior(new BigDecimal("625000.00"))
                .montoNuevo(new BigDecimal("750000.00"))
                .porcentajeVariacion(new BigDecimal("20.0000"))
                .origen(OrigenDatoEnum.BCRA)
                .build();

        List<Cuota> nuevasCuotas = estadoActivo.aplicarAjuste(contratoBase, segundoAjuste);

        assertEquals(new BigDecimal("750000.00"), contratoBase.getMontoActual());
        assertEquals(2, contratoBase.getAjustes().size());
        assertEquals(3, nuevasCuotas.size());
        assertEquals(9, contratoBase.getCuotas().size());
        assertEquals(7, nuevasCuotas.get(0).getNroCuota());
        assertEquals(LocalDate.of(2027, 1, 10), nuevasCuotas.get(0).getFechaVencimiento());
    }

    // ------------------------------------------------------------------
    // Ajustes: hitos H_k, ventana y validación de fechas
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVO: un ajuste sin fechas toma la ventana del período (H0 a H1) y la guarda en el ajuste")
    void ajusteSinFechasTomaLaVentanaDelPeriodo() {
        activarContratoDePrueba();
        Ajuste ajuste = ajuste(LocalDate.of(2026, 11, 17), null, null, "625000.00");

        List<Cuota> nuevas = factory.obtenerEstado(contratoBase).aplicarAjuste(contratoBase, ajuste);

        assertEquals(LocalDate.of(2026, 7, 1), ajuste.getFechaIndiceDesde());
        assertEquals(LocalDate.of(2026, 10, 1), ajuste.getFechaIndiceHasta());
        assertEquals(List.of(4, 5, 6), nuevas.stream().map(Cuota::getNroCuota).toList());
        assertEquals(LocalDate.of(2026, 10, 10), nuevas.get(0).getFechaVencimiento());
    }

    @Test
    @DisplayName("ACTIVO: rechaza fechas que no coinciden con la ventana e indica las que corresponden")
    void ajusteConFechasQueNoCoincidenSeRechaza() {
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> activo.validarAjuste(contratoBase, LocalDate.of(2026, 11, 17),
                        LocalDate.of(2026, 7, 15), LocalDate.of(2026, 10, 15)));

        assertTrue(ex.getMessage().contains("2026-07-01"), ex.getMessage());
        assertTrue(ex.getMessage().contains("2026-10-01"), ex.getMessage());
        assertTrue(contratoBase.getAjustes().isEmpty());
    }

    @Test
    @DisplayName("ACTIVO: el hito es el período, no el día en que se ejecutó el ajuste anterior")
    void elHitoDelSegundoAjusteNoDependeDeCuandoSeAplicoElPrimero() {
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);
        // El primer ajuste se aplicó tarde (el 20/12), pero el segundo igual corresponde desde el 01/01
        activo.aplicarAjuste(contratoBase, ajuste(LocalDate.of(2026, 12, 20), null, null, "625000.00"));

        PeriodoAjuste segundo = activo.validarAjuste(contratoBase, LocalDate.of(2027, 1, 1), null, null);

        assertEquals(new PeriodoAjuste(2, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1)), segundo);
    }

    @Test
    @DisplayName("ACTIVO: no hay ajuste cuyo hito caiga en la fecha de fin o después")
    void noHayAjusteEnLaFechaDeFin() {
        contratoBase.setFechaFin(LocalDate.of(2026, 10, 1));
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);

        assertTrue(activo.ajustePendiente(contratoBase, LocalDate.of(2026, 10, 1)).isEmpty());
        assertThrows(OperacionInvalidaException.class,
                () -> activo.validarAjuste(contratoBase, LocalDate.of(2026, 10, 1), null, null));
    }

    @Test
    @DisplayName("Solo ACTIVO informa ajustes pendientes; los demás estados no")
    void soloActivoTieneAjustesPendientes() {
        LocalDate muyTarde = LocalDate.of(2027, 6, 1);
        assertTrue(factory.obtenerEstado(contratoBase).ajustePendiente(contratoBase, muyTarde).isEmpty());
        activarContratoDePrueba();
        assertEquals(1, factory.obtenerEstado(contratoBase).ajustePendiente(contratoBase, muyTarde).orElseThrow().numero());
    }

    // ------------------------------------------------------------------
    // Cuotas con fechas no "felices": inicio día 17 y día 31
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Activar con inicio el 17/11 y vencimiento día 10: la primera cuota vence el 10/12, no nace vencida")
    void activarConInicioDia17NoGeneraCuotasVencidas() {
        contratoBase.setFechaInicio(LocalDate.of(2026, 11, 17));
        contratoBase.setFechaFin(LocalDate.of(2028, 11, 17));
        contratoBase.setEstado(EstadoContratoEnum.SENIADO);

        List<Cuota> cuotas = factory.obtenerEstado(contratoBase).activar(contratoBase, LocalDate.of(2026, 11, 17));

        assertEquals(3, cuotas.size());
        assertEquals(LocalDate.of(2026, 12, 10), cuotas.get(0).getFechaVencimiento());
        assertEquals(LocalDate.of(2027, 1, 10), cuotas.get(1).getFechaVencimiento());
        assertEquals(LocalDate.of(2027, 2, 10), cuotas.get(2).getFechaVencimiento());
        assertEquals(11, cuotas.get(0).getPeriodoMes());
        assertFalse(cuotas.get(0).getFechaVencimiento().isBefore(contratoBase.getFechaInicio()));
    }

    @Test
    @DisplayName("Inicio el 31/01 con vencimiento día 31: las cuotas vencen 31/01, 28/02 y 31/03 (sin correrse al 28)")
    void activarConInicioDia31RespetaElFinDeMes() {
        contratoBase.setFechaInicio(LocalDate.of(2026, 1, 31));
        contratoBase.setFechaFin(LocalDate.of(2027, 1, 31));
        contratoBase.setDiaVencimientoMes(31);
        contratoBase.setEstado(EstadoContratoEnum.SENIADO);

        List<Cuota> cuotas = factory.obtenerEstado(contratoBase).activar(contratoBase, LocalDate.of(2026, 1, 31));

        assertEquals(LocalDate.of(2026, 1, 31), cuotas.get(0).getFechaVencimiento());
        assertEquals(LocalDate.of(2026, 2, 28), cuotas.get(1).getFechaVencimiento());
        assertEquals(LocalDate.of(2026, 3, 31), cuotas.get(2).getFechaVencimiento());

        // El ajuste 1 (hito 30/04: 31/01 + 3 meses) emite abril, mayo y junio con vencimiento el último día
        List<Cuota> tramo1 = factory.obtenerEstado(contratoBase)
                .aplicarAjuste(contratoBase, ajuste(LocalDate.of(2026, 4, 30), null, null, "550000.00"));
        assertEquals(LocalDate.of(2026, 4, 30), contratoBase.getAjustes().get(0).getFechaIndiceHasta());
        assertEquals(LocalDate.of(2026, 4, 30), tramo1.get(0).getFechaVencimiento());
        assertEquals(LocalDate.of(2026, 5, 31), tramo1.get(1).getFechaVencimiento());
        assertEquals(LocalDate.of(2026, 6, 30), tramo1.get(2).getFechaVencimiento());
    }

    // ------------------------------------------------------------------
    // Rescisión y finalización
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rescindir: rechaza una fecha posterior a la de fin (antes cobraba 1 canon en 2030)")
    void rescisionPosteriorALaFechaDeFinSeRechaza() {
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);

        assertThrows(OperacionInvalidaException.class,
                () -> activo.rescindir(contratoBase, LocalDate.of(2030, 1, 1), LocalDate.of(2026, 7, 17)));
        assertEquals(EstadoContratoEnum.ACTIVO, contratoBase.getEstado());
    }

    @Test
    @DisplayName("Rescindir: rechaza una fecha futura (libera la propiedad en el acto) o anterior al inicio")
    void rescisionFuturaOAnteriorAlInicioSeRechaza() {
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);

        OperacionInvalidaException futura = assertThrows(OperacionInvalidaException.class,
                () -> activo.rescindir(contratoBase, LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 17)));
        assertTrue(futura.getMessage().contains("entre el 2026-07-01 (inicio) y el 2026-07-17 (hoy)"), futura.getMessage());
        assertTrue(futura.getMessage().contains("no puede ser futura"), futura.getMessage());
        assertThrows(OperacionInvalidaException.class,
                () -> activo.rescindir(contratoBase, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 17)));
        assertEquals(EstadoContratoEnum.ACTIVO, contratoBase.getEstado());
    }

    @Test
    @DisplayName("Rescindir con una fecha pasada (entre el inicio y hoy) es válido y anula las cuotas que vencen después")
    void rescisionConFechaPasadaEsValida() {
        activarContratoDePrueba();

        // Hoy 20/08; se rescinde con fecha 05/08: la cuota del 10/07 queda, las del 10/08 y 10/09 se anulan
        BigDecimal multa = factory.obtenerEstado(contratoBase)
                .rescindir(contratoBase, LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 20));

        assertEquals(new BigDecimal("750000.00"), multa);
        assertEquals(LocalDate.of(2026, 8, 5), contratoBase.getFechaRescision());
        assertEquals(EstadoCuotaEnum.PENDIENTE, contratoBase.getCuotas().get(0).getEstado());
        assertEquals(EstadoCuotaEnum.ANULADA, contratoBase.getCuotas().get(1).getEstado());
        assertEquals(EstadoCuotaEnum.ANULADA, contratoBase.getCuotas().get(2).getEstado());
    }

    @Test
    @DisplayName("Rescindir después de la fecha de fin: el último día válido es la fecha de fin")
    void rescisionDespuesDelFinTopeaEnLaFechaDeFin() {
        contratoBase.setFrecuenciaAjusteMeses(24);
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> activo.rescindir(contratoBase, LocalDate.of(2028, 7, 2), LocalDate.of(2028, 8, 1)));
        assertTrue(ex.getMessage().contains("y el 2028-07-01 (fecha de fin)"), ex.getMessage());
        assertEquals(new BigDecimal("500000.00"),
                activo.rescindir(contratoBase, LocalDate.of(2028, 7, 1), LocalDate.of(2028, 8, 1)));
    }

    @Test
    @DisplayName("Rescindir un contrato que todavía no empezó: 400 con la fecha de inicio")
    void rescisionAntesDelInicioDelContrato() {
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> activo.rescindir(contratoBase, LocalDate.of(2026, 6, 24), LocalDate.of(2026, 6, 24)));
        assertTrue(ex.getMessage().contains("antes de su fecha de inicio (2026-07-01)"), ex.getMessage());
    }

    @Test
    @DisplayName("Finalizar no anula cuotas devengadas: 01/12 -> 20/12 con día 25, la cuota del 25/12 queda PENDIENTE")
    void finalizarNoAnulaCuotasCuyoPeriodoEmpezoAntesDelFin() {
        contratoBase.setFechaInicio(LocalDate.of(2026, 12, 1));
        contratoBase.setFechaFin(LocalDate.of(2026, 12, 20));
        contratoBase.setFrecuenciaAjusteMeses(1);
        contratoBase.setDiaVencimientoMes(25);
        contratoBase.setEstado(EstadoContratoEnum.SENIADO);
        List<Cuota> cuotas = factory.obtenerEstado(contratoBase).activar(contratoBase, LocalDate.of(2026, 12, 1));
        assertEquals(1, cuotas.size());
        assertEquals(LocalDate.of(2026, 12, 25), cuotas.get(0).getFechaVencimiento());

        factory.obtenerEstado(contratoBase).finalizar(contratoBase, LocalDate.of(2026, 12, 20));

        assertEquals(EstadoContratoEnum.FINALIZADO, contratoBase.getEstado());
        assertEquals(EstadoCuotaEnum.PENDIENTE, cuotas.get(0).getEstado());
    }

    @Test
    @DisplayName("Finalizar: el último período (17/11/2027 -> 20/11/2027) empezó antes del fin y su cuota no se anula")
    void finalizarConservaElUltimoPeriodo() {
        // 17/11/2026 -> 20/11/2027: 13 meses; el último período (17/11/2027) vence el 10/12/2027
        contratoBase.setFechaInicio(LocalDate.of(2026, 11, 17));
        contratoBase.setFechaFin(LocalDate.of(2027, 11, 20));
        contratoBase.setFrecuenciaAjusteMeses(13);
        contratoBase.setEstado(EstadoContratoEnum.SENIADO);
        List<Cuota> cuotas = factory.obtenerEstado(contratoBase).activar(contratoBase, LocalDate.of(2026, 11, 17));
        assertEquals(13, cuotas.size());

        factory.obtenerEstado(contratoBase).finalizar(contratoBase, LocalDate.of(2027, 11, 20));

        assertEquals(EstadoContratoEnum.FINALIZADO, contratoBase.getEstado());
        assertTrue(cuotas.stream().allMatch(c -> c.getEstado() == EstadoCuotaEnum.PENDIENTE));
    }

    @Test
    @DisplayName("Finalizar (defensa): anula una cuota PENDIENTE cuyo período empieza en o después de la fecha de fin")
    void finalizarAnulaCuotasDePeriodosPosterioresAlFin() {
        contratoBase.setFechaInicio(LocalDate.of(2026, 12, 1));
        contratoBase.setFechaFin(LocalDate.of(2026, 12, 20));
        contratoBase.setFrecuenciaAjusteMeses(1);
        contratoBase.setEstado(EstadoContratoEnum.SENIADO);
        factory.obtenerEstado(contratoBase).activar(contratoBase, LocalDate.of(2026, 12, 1));
        // Cuota 2 (período desde el 01/01/2027, después del fin): no la genera el sistema, viene de datos viejos
        Cuota sobrante = Cuota.builder().nroCuota(2).periodoMes(1).periodoAnio(2027)
                .fechaVencimiento(LocalDate.of(2027, 1, 10)).montoBase(new BigDecimal("500000.00"))
                .estado(EstadoCuotaEnum.PENDIENTE).build();
        contratoBase.agregarCuota(sobrante);

        factory.obtenerEstado(contratoBase).finalizar(contratoBase, LocalDate.of(2026, 12, 20));

        assertEquals(EstadoCuotaEnum.PENDIENTE, contratoBase.getCuotas().get(0).getEstado());
        assertEquals(EstadoCuotaEnum.ANULADA, sobrante.getEstado());
    }

    @Test
    @DisplayName("Las cuotas llevan como fecha de emisión la fecha actual de la activación o del ajuste")
    void lasCuotasLlevanLaFechaDeEmision() {
        activarContratoDePrueba();
        assertTrue(contratoBase.getCuotas().stream()
                .allMatch(c -> LocalDate.of(2026, 6, 24).equals(c.getFechaEmision())));

        // El ajuste 1 se aplica tarde (el 15/11): sus cuotas (10/10, 10/11, 10/12) nacen el 15/11
        List<Cuota> tramo1 = factory.obtenerEstado(contratoBase)
                .aplicarAjuste(contratoBase, ajuste(LocalDate.of(2026, 11, 15), null, null, "625000.00"));
        assertEquals(3, tramo1.size());
        assertTrue(tramo1.stream().allMatch(c -> LocalDate.of(2026, 11, 15).equals(c.getFechaEmision())));
        assertEquals(new BigDecimal("0.00"), tramo1.get(0).calcularMora(LocalDate.of(2026, 11, 15), new BigDecimal("0.1")));
    }

    @Test
    @DisplayName("Rescindir con un ajuste pendiente a la fecha de rescisión: 400 que pide aplicarlo y no cambia nada")
    void rescindirConAjustePendienteSeRechaza() {
        // Inicio 01/07/2026, frecuencia 3: el ajuste 1 (hito 01/10) no se aplicó (BCRA caído); octubre y noviembre
        // están devengados y no se emitieron
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> activo.rescindir(contratoBase, LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 18)));

        assertTrue(ex.getMessage().contains("Antes de rescindir el contrato CONT-TEST0001 aplicá el ajuste N° 1 "
                + "(ventana 2026-07-01 → 2026-10-01)"), ex.getMessage());
        assertEquals(EstadoContratoEnum.ACTIVO, contratoBase.getEstado());
        assertTrue(contratoBase.getCuotas().stream().allMatch(c -> c.getEstado() == EstadoCuotaEnum.PENDIENTE));
        // El hito es la fecha de corte: rescindir el mismo 01/10 también lo exige; el 30/09 no
        assertThrows(OperacionInvalidaException.class,
                () -> activo.rescindir(contratoBase, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 18)));
        assertEquals(new BigDecimal("750000.00"),
                activo.rescindir(contratoBase, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 11, 18)));
    }

    @Test
    @DisplayName("Finalizar con un ajuste sin aplicar: 400 que pide aplicarlo antes")
    void finalizarConAjustePendienteSeRechaza() {
        // 01/07/2026 -> 01/07/2028 con frecuencia 3: el ajuste 1 (hito 01/10/2026) nunca se aplicó
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> activo.finalizar(contratoBase, LocalDate.of(2028, 7, 1)));

        assertTrue(ex.getMessage().contains("Antes de finalizar el contrato CONT-TEST0001 aplicá el ajuste N° 1"),
                ex.getMessage());
        assertEquals(EstadoContratoEnum.ACTIVO, contratoBase.getEstado());
    }

    @Test
    @DisplayName("Umbral de 6 meses con plusMonths: inicio 31/08/2026, el 28/02/2027 ya cobra 1 canon y el 27/02 cobra 1,5")
    void umbralDeSeisMesesUsaLaConvencionDelCalendario() {
        contratoBase.setFechaInicio(LocalDate.of(2026, 8, 31));
        contratoBase.setFechaFin(LocalDate.of(2028, 8, 31));
        contratoBase.setFrecuenciaAjusteMeses(12);
        contratoBase.setEstado(EstadoContratoEnum.SENIADO);
        factory.obtenerEstado(contratoBase).activar(contratoBase, LocalDate.of(2026, 8, 31));
        EstadoContrato activo = factory.obtenerEstado(contratoBase);
        // inicio + 6 meses = 28/02/2027 (MONTHS.between daba 5 y cobraba 1,5)
        assertEquals(new BigDecimal("750000.00"),
                activo.rescindir(contratoBase, LocalDate.of(2027, 2, 27), LocalDate.of(2027, 3, 1)));

        contratoBase.setEstado(EstadoContratoEnum.ACTIVO);
        contratoBase.getCuotas().forEach(c -> c.setEstado(EstadoCuotaEnum.PENDIENTE));
        assertEquals(new BigDecimal("500000.00"),
                activo.rescindir(contratoBase, LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 1)));
    }

    @Test
    @DisplayName("La multa usa el canon vigente a la fecha de rescisión: al 15/09 500.000 (750.000), al 15/10 625.000 (937.500)")
    void multaConElCanonVigenteALaFechaDeRescision() {
        activarContratoDePrueba();
        EstadoContrato activo = factory.obtenerEstado(contratoBase);
        // Ajuste 1 aplicado el 18/11: canon 625.000 desde el 01/10
        activo.aplicarAjuste(contratoBase, ajuste(LocalDate.of(2026, 11, 18), null, null, "625000.00"));

        assertEquals(new BigDecimal("750000.00"),
                activo.rescindir(contratoBase, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 11, 18)));

        contratoBase.setEstado(EstadoContratoEnum.ACTIVO);
        contratoBase.getCuotas().forEach(c -> c.setEstado(EstadoCuotaEnum.PENDIENTE));
        assertEquals(new BigDecimal("937500.00"),
                activo.rescindir(contratoBase, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 18)));
    }

    private static Ajuste ajuste(LocalDate fechaAplicacion, LocalDate desde, LocalDate hasta, String montoNuevo) {
        return Ajuste.builder()
                .tipoIndice(TipoIndiceEnum.ICL)
                .fechaAplicacion(fechaAplicacion)
                .fechaIndiceDesde(desde)
                .fechaIndiceHasta(hasta)
                .valorIndiceDesde(new BigDecimal("10"))
                .valorIndiceHasta(new BigDecimal("11"))
                .coeficiente(new BigDecimal("1.1"))
                .montoAnterior(new BigDecimal("500000.00"))
                .montoNuevo(new BigDecimal(montoNuevo))
                .porcentajeVariacion(new BigDecimal("10"))
                .origen(OrigenDatoEnum.BCRA)
                .build();
    }

    private void activarContratoDePrueba() {
        factory.obtenerEstado(contratoBase).seniar(
                contratoBase, new BigDecimal("150000.00"), LocalDate.of(2026, 6, 25), LocalDate.of(2026, 6, 20));
        factory.obtenerEstado(contratoBase).activar(contratoBase, LocalDate.of(2026, 6, 24));
    }
}
