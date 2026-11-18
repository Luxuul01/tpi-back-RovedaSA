package com.rovedasa.contratos.service;

import com.rovedasa.contratos.client.CatalogoClient;
import com.rovedasa.contratos.dto.OrigenDatoEnum;
import com.rovedasa.contratos.dto.ResultadoAjusteDTO;
import com.rovedasa.contratos.dto.ResumenAjustePeriodicoResponse;
import com.rovedasa.contratos.exception.IndiceNoDisponibleException;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.model.Cuota;
import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.repository.ContratoRepository;
import com.rovedasa.contratos.state.EstadoContratoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proceso de ajuste periódico con ContratoService real (State real, reloj fijo en el 17/11/2026),
 * repositorio, catálogo e índice mockeados.
 */
class AjusteProgramadoServiceTest {

    private static final LocalDate HOY = ContratoServiceTest.HOY;

    private ContratoRepository contratoRepository;
    private IndiceService indiceService;
    private AjusteProgramadoService ajusteProgramado;

    @BeforeEach
    void setUp() {
        contratoRepository = mock(ContratoRepository.class);
        indiceService = mock(IndiceService.class);
        CuotaService cuotaService = mock(CuotaService.class);
        when(cuotaService.toResumenList(anyList(), any(), any())).thenReturn(List.of());
        when(contratoRepository.saveAndFlush(any(Contrato.class))).thenAnswer(inv -> inv.getArgument(0));
        ContratoService contratoService = ContratoServiceTest.crearServicio(
                contratoRepository, cuotaService, mock(CatalogoClient.class), indiceService, HOY);
        ajusteProgramado = new AjusteProgramadoService(contratoService);

        // Cada ajuste multiplica el canon por 1,10
        when(indiceService.calcularAjuste(eq(TipoIndiceEnum.ICL), any(), any(), any())).thenAnswer(inv -> {
            BigDecimal anterior = inv.getArgument(1);
            return ResultadoAjusteDTO.builder()
                    .tipoIndice(TipoIndiceEnum.ICL)
                    .fechaIndiceDesde(inv.getArgument(2)).fechaIndiceHasta(inv.getArgument(3))
                    .valorIndiceDesde(new BigDecimal("10")).valorIndiceHasta(new BigDecimal("11"))
                    .coeficiente(new BigDecimal("1.10000000"))
                    .montoAnterior(anterior)
                    .montoNuevo(anterior.multiply(new BigDecimal("1.1")).setScale(2, RoundingMode.HALF_UP))
                    .porcentajeVariacion(new BigDecimal("10.00"))
                    .origen(OrigenDatoEnum.BCRA)
                    .build();
        });
    }

    @Test
    @DisplayName("Contrato 01/01/2026 -> 31/12/2027 frecuencia 3 con un ajuste: se pone al día (ajustes 2 y 3) y quedan las cuotas de julio a diciembre")
    void contratoAtrasadoSePoneAlDia() {
        Contrato contrato = ContratoServiceTest.contratoActivo(1L, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), 3);
        new EstadoContratoFactory().obtenerEstado(contrato)
                .aplicarAjuste(contrato, ContratoServiceTest.ajusteAplicado(LocalDate.of(2026, 4, 20), "550000.00"));
        assertEquals(6, contrato.getCuotas().size());
        mockearActivos(contrato);

        ResumenAjustePeriodicoResponse resumen = ajusteProgramado.ejecutarAjustePeriodico();

        assertEquals(1, resumen.getContratosRevisados());
        assertEquals(2, resumen.getAjustesAplicados());
        assertTrue(resumen.getPendientes().isEmpty());
        var orden = inOrder(indiceService);
        orden.verify(indiceService).calcularAjuste(TipoIndiceEnum.ICL, new BigDecimal("550000.00"),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 1));
        orden.verify(indiceService).calcularAjuste(TipoIndiceEnum.ICL, new BigDecimal("605000.00"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1));
        assertEquals(3, contrato.getAjustes().size());
        assertEquals(12, contrato.getCuotas().size());
        assertEquals(List.of(7, 8, 9, 10, 11, 12), contrato.getCuotas().subList(6, 12).stream()
                .map(Cuota::getNroCuota).toList());
        assertEquals(LocalDate.of(2026, 7, 10), contrato.getCuotas().get(6).getFechaVencimiento());
        assertEquals(new BigDecimal("665500.00"), contrato.getMontoActual());
        // El ajuste 4 (hito 01/01/2027) todavía no corresponde
        assertTrue(ajusteProgramado.ejecutarAjustePeriodico().getAjustesAplicados() == 0);
    }

    @Test
    @DisplayName("Un contrato que falla queda pendiente con su motivo y no impide ajustar a otro")
    void unContratoQueFallaNoFrenaAlOtro() {
        Contrato sinIndice = ContratoServiceTest.contratoActivo(1L, LocalDate.of(2026, 3, 1), LocalDate.of(2028, 3, 1), 3);
        sinIndice.setTipoIndice(TipoIndiceEnum.IPC);
        Contrato conErrorDeBase = ContratoServiceTest.contratoActivo(2L, LocalDate.of(2026, 7, 1), LocalDate.of(2028, 7, 1), 3);
        Contrato sano = ContratoServiceTest.contratoActivo(3L, LocalDate.of(2026, 8, 1), LocalDate.of(2028, 8, 1), 3);
        mockearActivos(sinIndice, conErrorDeBase, sano);
        when(indiceService.calcularAjuste(eq(TipoIndiceEnum.IPC), any(), any(), any()))
                .thenThrow(new IndiceNoDisponibleException("IPC de octubre todavía no publicado"));
        when(contratoRepository.saveAndFlush(conErrorDeBase))
                .thenThrow(new DataIntegrityViolationException("uk_ajuste_contrato_fecha_hasta"));

        ResumenAjustePeriodicoResponse resumen = ajusteProgramado.ejecutarAjustePeriodico();

        assertEquals(3, resumen.getContratosRevisados());
        assertEquals(1, resumen.getAjustesAplicados());
        assertEquals(List.of(1L, 2L), resumen.getPendientes().stream()
                .map(ResumenAjustePeriodicoResponse.Pendiente::getContratoId).toList());
        String motivo = resumen.getPendientes().get(0).getMotivo();
        assertTrue(motivo.contains("Ajuste N° 1 (2026-03-01 a 2026-06-01)"), motivo);
        assertTrue(motivo.contains("IPC de octubre todavía no publicado"), motivo);
        assertTrue(sinIndice.getAjustes().isEmpty());
        assertEquals(1, sano.getAjustes().size());
        assertEquals(6, sano.getCuotas().size());
    }

    @Test
    @DisplayName("Los contratos sin ajuste pendiente se revisan pero no consultan el índice")
    void sinAjustesPendientes() {
        Contrato reciente = ContratoServiceTest.contratoActivo(1L, HOY, HOY.plusYears(2), 3);
        mockearActivos(reciente);

        ResumenAjustePeriodicoResponse resumen = ajusteProgramado.ejecutarAjustePeriodico();

        assertEquals(1, resumen.getContratosRevisados());
        assertEquals(0, resumen.getAjustesAplicados());
        org.mockito.Mockito.verifyNoInteractions(indiceService);
    }

    private void mockearActivos(Contrato... contratos) {
        when(contratoRepository.findByEstado(EstadoContratoEnum.ACTIVO)).thenReturn(List.of(contratos));
        for (Contrato contrato : contratos) {
            when(contratoRepository.findById(contrato.getId())).thenReturn(Optional.of(contrato));
        }
    }
}
