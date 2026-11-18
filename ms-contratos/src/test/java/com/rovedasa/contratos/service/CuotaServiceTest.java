package com.rovedasa.contratos.service;

import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.dto.CuotaResumenDTO;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.repository.CuotaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CuotaServiceTest {

    private static final BigDecimal MORA_DIARIA = new BigDecimal("0.1000");
    private static final LocalDate HOY = LocalDate.of(2026, 7, 20);
    private static final LocalDate INICIO_CONTRATO = LocalDate.of(2026, 1, 1);

    private CuotaRepository cuotaRepository;
    private CuotaService cuotaService;

    @BeforeEach
    void setUp() {
        cuotaRepository = mock(CuotaRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);
        cuotaService = new CuotaService(cuotaRepository, clock);
        when(cuotaRepository.saveAndFlush(any(Cuota.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    // ------------------------------------------------------------------
    // Pago y mora (RN-13, RN-18, C-06)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RN-18: pagar fuera de término deja la cuota PAGADA con la mora calculada hasta la fecha de pago")
    void debeRegistrarPagoFueraDeTerminoConMoraHastaLaFechaDePago() {
        // Vence el 10/07 y se paga el 18/07: 500.000 x 0,1 % x 8 días = 4.000
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        CuotaResumenDTO resumen = cuotaService.pagarCuota(1L, LocalDate.of(2026, 7, 18));

        assertEquals(EstadoCuotaEnum.PAGADA, cuota.getEstado());
        assertEquals(LocalDate.of(2026, 7, 18), cuota.getFechaPago());
        assertEquals(EstadoCuotaEnum.PAGADA, resumen.getEstado());
        assertEquals(LocalDate.of(2026, 7, 18), resumen.getFechaPago());
        assertEquals(new BigDecimal("4000.00"), resumen.getMontoMora());
        assertEquals(new BigDecimal("504000.00"), resumen.getMontoTotal());
    }

    @Test
    @DisplayName("La mora de una cuota pagada fuera de término queda fija en las consultas posteriores")
    void debeMantenerFijaLaMoraDeUnaCuotaPagadaEnLasConsultas() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));
        when(cuotaRepository.findByContratoIdOrderByNroCuotaAsc(1L)).thenReturn(List.of(cuota));
        cuotaService.pagarCuota(1L, LocalDate.of(2026, 7, 18));

        CuotaResumenDTO alListar = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, LocalDate.of(2026, 12, 31)).get(0);
        CuotaResumenDTO individual = cuotaService.obtenerCuotaConMora(1L, null, "ADMINISTRADOR", "sub-admin");

        assertEquals(new BigDecimal("4000.00"), alListar.getMontoMora());
        assertEquals(new BigDecimal("504000.00"), alListar.getMontoTotal());
        assertEquals(new BigDecimal("4000.00"), individual.getMontoMora());
    }

    @Test
    @DisplayName("C-06: PENDIENTE a vencimiento + 10 días da mora 5.000 / total 505.000; pagada en término queda PAGADA y sin mora")
    void debeCumplirFlujoC06DeMoraYPago() {
        // Cuota que vence después de hoy (20/07): se consulta a vencimiento + 10 días y se paga hoy, en término
        LocalDate vencimiento = LocalDate.of(2026, 7, 25);
        LocalDate referencia = vencimiento.plusDays(10);
        Cuota cuota = crearCuota(vencimiento, EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findByContratoIdOrderByNroCuotaAsc(1L)).thenReturn(List.of(cuota));
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        List<CuotaResumenDTO> antesDelPago = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, referencia);
        assertEquals(new BigDecimal("5000.00"), antesDelPago.get(0).getMontoMora());
        assertEquals(new BigDecimal("505000.00"), antesDelPago.get(0).getMontoTotal());

        CuotaResumenDTO pago = cuotaService.pagarCuota(1L, null);
        assertEquals(EstadoCuotaEnum.PAGADA, pago.getEstado());
        assertEquals(HOY, pago.getFechaPago());
        assertEquals(new BigDecimal("0.00"), pago.getMontoMora());
        assertEquals(new BigDecimal("500000.00"), pago.getMontoTotal());

        List<CuotaResumenDTO> despuesDelPago = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, referencia);
        assertEquals(EstadoCuotaEnum.PAGADA, despuesDelPago.get(0).getEstado());
        assertEquals(new BigDecimal("0.00"), despuesDelPago.get(0).getMontoMora());
        assertEquals(new BigDecimal("500000.00"), despuesDelPago.get(0).getMontoTotal());
    }

    @Test
    @DisplayName("Si no se indica fecha de pago, usa el reloj inyectado y no cobra mora si está en término")
    void debeUsarElRelojCuandoNoSeIndicaFechaDePago() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 25), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        CuotaResumenDTO resumen = cuotaService.pagarCuota(1L, null);

        assertEquals(HOY, cuota.getFechaPago());
        assertEquals(new BigDecimal("0.00"), resumen.getMontoMora());
        assertEquals(new BigDecimal("500000.00"), resumen.getMontoTotal());
    }

    @Test
    @DisplayName("Al listar, una cuota PAGADA en término no genera mora; una ANULADA tampoco")
    void noDebeCalcularMoraDeCuotaPagadaEnTerminoNiAnulada() {
        Cuota pagada = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PAGADA, LocalDate.of(2026, 7, 9));
        Cuota anulada = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.ANULADA, null);
        when(cuotaRepository.findByContratoIdOrderByNroCuotaAsc(1L)).thenReturn(List.of(pagada, anulada));

        List<CuotaResumenDTO> resumen = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, LocalDate.of(2026, 7, 30));

        assertEquals(2, resumen.size());
        resumen.forEach(r -> {
            assertEquals(new BigDecimal("0.00"), r.getMontoMora());
            assertEquals(new BigDecimal("500000.00"), r.getMontoTotal());
        });
    }

    @Test
    @DisplayName("Sin fecha de referencia, el listado calcula la mora a la fecha del reloj inyectado")
    void debeUsarElRelojCuandoNoSeIndicaFechaDeReferencia() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findByContratoIdOrderByNroCuotaAsc(1L)).thenReturn(List.of(cuota));

        CuotaResumenDTO resumen = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, null).get(0);

        assertEquals(new BigDecimal("5000.00"), resumen.getMontoMora());
    }

    // ------------------------------------------------------------------
    // Rechazos del pago
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No permite pagar una cuota ya pagada")
    void noDebePermitirPagarCuotaYaPagada() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PAGADA, LocalDate.of(2026, 7, 15));
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> cuotaService.pagarCuota(1L, LocalDate.of(2026, 7, 20)));
        assertEquals("La cuota 1 ya fue pagada el 2026-07-15", ex.getMessage());
        verify(cuotaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("No permite pagar una cuota anulada")
    void noDebePermitirPagarCuotaAnulada() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.ANULADA, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        assertThrows(OperacionInvalidaException.class,
                () -> cuotaService.pagarCuota(1L, LocalDate.of(2026, 7, 20)));
        verify(cuotaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("No permite registrar un pago con fecha futura")
    void noDebePermitirFechaDePagoFutura() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> cuotaService.pagarCuota(1L, HOY.plusDays(1)));
        assertEquals("La fecha de pago (2026-07-21) no puede ser futura: hoy es 2026-07-20", ex.getMessage());
        assertEquals(EstadoCuotaEnum.PENDIENTE, cuota.getEstado());
        verify(cuotaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("No permite registrar un pago con fecha anterior al inicio del contrato")
    void noDebePermitirFechaDePagoAnteriorAlInicioDelContrato() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> cuotaService.pagarCuota(1L, LocalDate.of(1990, 1, 1)));
        assertEquals("La fecha de pago (1990-01-01) no puede ser anterior al inicio del contrato (2026-01-01)",
                ex.getMessage());
        assertEquals(EstadoCuotaEnum.PENDIENTE, cuota.getEstado());
        verify(cuotaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Acepta como fecha de pago el mismo día de inicio del contrato")
    void debeAceptarFechaDePagoIgualAlInicioDelContrato() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 1, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        CuotaResumenDTO resumen = cuotaService.pagarCuota(1L, INICIO_CONTRATO);

        assertEquals(INICIO_CONTRATO, resumen.getFechaPago());
        assertEquals(new BigDecimal("0.00"), resumen.getMontoMora());
    }

    @Test
    @DisplayName("Pagar una cuota inexistente responde 404")
    void debeResponder404AlPagarCuotaInexistente() {
        when(cuotaRepository.findById(99L)).thenReturn(Optional.empty());

        RecursoNoEncontradoException ex = assertThrows(RecursoNoEncontradoException.class,
                () -> cuotaService.pagarCuota(99L, HOY));
        assertEquals("Cuota con ID 99 no fue encontrada", ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Concurrencia (@Version)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El pago se guarda con saveAndFlush para que el conflicto de versión salte dentro de la operación")
    void debeUsarSaveAndFlushAlPagar() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 25), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        cuotaService.pagarCuota(1L, HOY);

        verify(cuotaRepository).saveAndFlush(cuota);
        verify(cuotaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Un conflicto de versión al pagar se propaga sin envolver (commons lo mapea a 409)")
    void debePropagarElConflictoDeVersionAlPagar() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 25), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));
        when(cuotaRepository.saveAndFlush(any(Cuota.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Cuota.class, 1L));

        assertThrows(ObjectOptimisticLockingFailureException.class, () -> cuotaService.pagarCuota(1L, HOY));
    }

    // ------------------------------------------------------------------
    // Pertenencia (RN-17)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ADMINISTRADOR, el propietario y el inquilino del contrato pueden ver la cuota")
    void debePermitirVerLaCuotaAlAdministradorYALosDuenios() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        assertEquals(new BigDecimal("5000.00"),
                cuotaService.obtenerCuotaConMora(1L, HOY, "ADMINISTRADOR", "sub-admin").getMontoMora());
        assertEquals(new BigDecimal("5000.00"),
                cuotaService.obtenerCuotaConMora(1L, HOY, "PROPIETARIO", "sub-prop").getMontoMora());
        assertEquals(new BigDecimal("505000.00"),
                cuotaService.obtenerCuotaConMora(1L, HOY, "INQUILINO", "sub-inq").getMontoTotal());
    }

    @Test
    @DisplayName("Una cuota de un contrato ajeno responde 404 con el mismo mensaje que una inexistente")
    void debeResponder404AnteCuotaAjena() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        RecursoNoEncontradoException inquilinoAjeno = assertThrows(RecursoNoEncontradoException.class,
                () -> cuotaService.obtenerCuotaConMora(1L, HOY, "INQUILINO", "sub-otro"));
        RecursoNoEncontradoException propietarioAjeno = assertThrows(RecursoNoEncontradoException.class,
                () -> cuotaService.obtenerCuotaConMora(1L, HOY, "PROPIETARIO", "sub-inq"));
        RecursoNoEncontradoException rolDesconocido = assertThrows(RecursoNoEncontradoException.class,
                () -> cuotaService.obtenerCuotaConMora(1L, HOY, "GARANTE", "sub-inq"));

        assertEquals("Cuota con ID 1 no fue encontrada", inquilinoAjeno.getMessage());
        assertEquals("Cuota con ID 1 no fue encontrada", propietarioAjeno.getMessage());
        assertEquals("Cuota con ID 1 no fue encontrada", rolDesconocido.getMessage());
    }

    @Test
    @DisplayName("Consultar una cuota inexistente responde 404")
    void debeResponder404AnteCuotaInexistente() {
        when(cuotaRepository.findById(99L)).thenReturn(Optional.empty());

        RecursoNoEncontradoException ex = assertThrows(RecursoNoEncontradoException.class,
                () -> cuotaService.obtenerCuotaConMora(99L, HOY, "ADMINISTRADOR", "sub-admin"));
        assertEquals("Cuota con ID 99 no fue encontrada", ex.getMessage());
    }

    @Test
    @DisplayName("La respuesta muestra la fecha de emisión; una cuota emitida tarde no tiene mora retroactiva")
    void respuestaConFechaDeEmisionYSinMoraRetroactiva() {
        // Vence el 10/07 pero se emitió hoy (20/07): sin mora hoy, 5.000 a los 10 días de la emisión
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        cuota.setFechaEmision(HOY);
        when(cuotaRepository.findByContratoIdOrderByNroCuotaAsc(1L)).thenReturn(List.of(cuota));

        CuotaResumenDTO hoy = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, null).get(0);
        CuotaResumenDTO diezDiasDespues = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, HOY.plusDays(10)).get(0);

        assertEquals(HOY, hoy.getFechaEmision());
        assertEquals(new BigDecimal("0.00"), hoy.getMontoMora());
        assertEquals(new BigDecimal("5000.00"), diezDiasDespues.getMontoMora());
        assertEquals(new BigDecimal("505000.00"), diezDiasDespues.getMontoTotal());
    }

    private Cuota crearCuota(LocalDate vencimiento, EstadoCuotaEnum estado, LocalDate fechaPago) {
        Contrato contrato = Contrato.builder()
                .propiedadId(1L)
                .propietarioId(2L)
                .inquilinoId(3L)
                .garanteId(4L)
                .propietarioKeycloakId("sub-prop")
                .inquilinoKeycloakId("sub-inq")
                .fechaInicio(INICIO_CONTRATO)
                .fechaFin(LocalDate.of(2027, 1, 1))
                .frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00"))
                .montoActual(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(MORA_DIARIA)
                .diaVencimientoMes(10)
                .build();

        return Cuota.builder()
                .contrato(contrato)
                .nroCuota(1)
                .periodoMes(7)
                .periodoAnio(2026)
                .fechaVencimiento(vencimiento)
                .montoBase(new BigDecimal("500000.00"))
                .estado(estado)
                .fechaPago(fechaPago)
                .build();
    }
}
