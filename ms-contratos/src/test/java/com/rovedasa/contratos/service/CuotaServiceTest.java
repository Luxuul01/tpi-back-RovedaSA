package com.rovedasa.contratos.service;

import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.dto.CuotaResumenDTO;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.repository.CuotaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.when;

class CuotaServiceTest {

    private static final BigDecimal MORA_DIARIA = new BigDecimal("0.1000");
    private static final LocalDate HOY = LocalDate.of(2026, 7, 20);

    private CuotaRepository cuotaRepository;
    private CuotaService cuotaService;

    @BeforeEach
    void setUp() {
        cuotaRepository = mock(CuotaRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);
        cuotaService = new CuotaService(cuotaRepository, clock);
        when(cuotaRepository.save(any(Cuota.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    @Test
    @DisplayName("Registra el pago con la fecha indicada y la cuota queda PAGADA sin mora")
    void debeRegistrarPagoYPasarCuotaAPagadaSinMora() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        CuotaResumenDTO resumen = cuotaService.pagarCuota(1L, LocalDate.of(2026, 7, 18));

        assertEquals(EstadoCuotaEnum.PAGADA, cuota.getEstado());
        assertEquals(LocalDate.of(2026, 7, 18), cuota.getFechaPago());
        assertEquals(LocalDate.of(2026, 7, 18), resumen.getFechaPago());
        assertEquals(new BigDecimal("0.00"), resumen.getMontoMora());
        assertEquals(new BigDecimal("500000.00"), resumen.getMontoTotal());
    }

    @Test
    @DisplayName("C-06: una cuota PENDIENTE vencida acumula mora y al pagarse queda PAGADA sin mora")
    void debeCumplirFlujoC06DeMoraYPago() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findByContratoIdOrderByNroCuotaAsc(1L)).thenReturn(List.of(cuota));

        List<CuotaResumenDTO> antesDelPago = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, HOY);
        assertEquals(new BigDecimal("5000.00"), antesDelPago.get(0).getMontoMora());
        assertEquals(new BigDecimal("505000.00"), antesDelPago.get(0).getMontoTotal());

        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));
        CuotaResumenDTO pago = cuotaService.pagarCuota(1L, HOY);
        assertEquals(EstadoCuotaEnum.PAGADA, pago.getEstado());
        assertEquals(new BigDecimal("0.00"), pago.getMontoMora());
        assertEquals(new BigDecimal("500000.00"), pago.getMontoTotal());

        List<CuotaResumenDTO> despuesDelPago = cuotaService.listarCuotasConMora(1L, MORA_DIARIA, HOY.plusDays(5));
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
    @DisplayName("No permite pagar una cuota ya pagada")
    void noDebePermitirPagarCuotaYaPagada() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PAGADA, LocalDate.of(2026, 7, 15));
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        assertThrows(OperacionInvalidaException.class,
                () -> cuotaService.pagarCuota(1L, LocalDate.of(2026, 7, 20)));
    }

    @Test
    @DisplayName("No permite pagar una cuota anulada")
    void noDebePermitirPagarCuotaAnulada() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.ANULADA, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        assertThrows(OperacionInvalidaException.class,
                () -> cuotaService.pagarCuota(1L, LocalDate.of(2026, 7, 20)));
    }

    @Test
    @DisplayName("No permite registrar un pago con fecha futura")
    void noDebePermitirFechaDePagoFutura() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PENDIENTE, null);
        when(cuotaRepository.findById(1L)).thenReturn(Optional.of(cuota));

        assertThrows(OperacionInvalidaException.class,
                () -> cuotaService.pagarCuota(1L, HOY.plusDays(1)));
    }

    @Test
    @DisplayName("Al listar, una cuota PAGADA no genera mora")
    void noDebeCalcularMoraDeCuotaPagadaAlListar() {
        Cuota cuota = crearCuota(LocalDate.of(2026, 7, 10), EstadoCuotaEnum.PAGADA,
                LocalDate.of(2026, 7, 15));
        when(cuotaRepository.findByContratoIdOrderByNroCuotaAsc(1L)).thenReturn(List.of(cuota));

        List<CuotaResumenDTO> resumen = cuotaService.listarCuotasConMora(
                1L, MORA_DIARIA, LocalDate.of(2026, 7, 30));

        assertEquals(1, resumen.size());
        assertEquals(new BigDecimal("0.00"), resumen.get(0).getMontoMora());
        assertEquals(new BigDecimal("500000.00"), resumen.get(0).getMontoTotal());
    }

    private Cuota crearCuota(LocalDate vencimiento, EstadoCuotaEnum estado, LocalDate fechaPago) {
        Contrato contrato = Contrato.builder()
                .propiedadId(1L)
                .propietarioId(2L)
                .inquilinoId(3L)
                .garanteId(4L)
                .propietarioKeycloakId("sub-prop")
                .inquilinoKeycloakId("sub-inq")
                .fechaInicio(LocalDate.of(2026, 1, 1))
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
