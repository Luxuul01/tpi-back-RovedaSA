package com.rovedasa.contratos.service;

import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.contratos.client.bcra.BcraEstadisticasClient;
import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import com.rovedasa.contratos.dto.CoeficienteAjusteDTO;
import com.rovedasa.contratos.dto.OrigenDatoEnum;
import com.rovedasa.contratos.dto.ResultadoAjusteDTO;
import com.rovedasa.contratos.dto.ValorIndiceDTO;
import com.rovedasa.contratos.exception.IndiceNoDisponibleException;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prueba la estrategia de resiliencia: el BCRA y el caché se reemplazan por mocks (Mockito).
 */
class IndiceServiceTest {

    private BcraEstadisticasClient bcraClient;
    private IndiceCacheService cacheService;
    private IndiceService service;

    @BeforeEach
    void setUp() {
        bcraClient = mock(BcraEstadisticasClient.class);
        cacheService = mock(IndiceCacheService.class);
        service = new IndiceService(bcraClient, cacheService, new CalculadoraAjuste(), 7, 2);
    }

    private static BcraValorDTO v(String fecha, String valor) {
        return new BcraValorDTO(LocalDate.parse(fecha), new BigDecimal(valor));
    }

    @Test
    @DisplayName("BCRA disponible: usa su valor y lo guarda en el caché")
    void valorDesdeBcra() {
        List<BcraValorDTO> serie = List.of(v("2026-10-26", "37.09"), v("2026-10-25", "37.07"));
        when(bcraClient.obtenerSerie(eq(40), any(), any())).thenReturn(serie);

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertAll(
                () -> assertEquals(OrigenDatoEnum.BCRA, valor.getOrigen()),
                () -> assertEquals(new BigDecimal("37.09"), valor.getValor()));
        verify(cacheService).guardar(TipoIndiceEnum.ICL, serie);
    }

    @Test
    @DisplayName("BCRA caído: usa el caché de contingencia")
    void fallbackAlCache() {
        when(bcraClient.obtenerSerie(anyInt(), any(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"));
        when(cacheService.buscarEntreFechas(eq(TipoIndiceEnum.ICL), any(), any()))
                .thenReturn(List.of(v("2026-10-24", "37.05")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertAll(
                () -> assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen()),
                () -> assertEquals(LocalDate.of(2026, 10, 24), valor.getFechaIndice()),
                () -> assertEquals(new BigDecimal("37.05"), valor.getValor()));
        verify(cacheService, never()).guardar(any(), any());
    }

    @Test
    @DisplayName("BCRA caído y caché vacío: 503 (no se ajusta con datos inventados)")
    void sinBcraNiCache() {
        when(bcraClient.obtenerSerie(anyInt(), any(), any()))
                .thenThrow(new ResourceAccessException("Connection refused"));
        when(cacheService.buscarEntreFechas(any(), any(), any())).thenReturn(List.of());

        assertThrows(IndiceNoDisponibleException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)));
    }

    @Test
    @DisplayName("Ajuste por ICL: canon x ICL_hasta / ICL_desde")
    void ajustePorIcl() {
        when(bcraClient.obtenerSerie(eq(40), any(), eq(LocalDate.of(2026, 7, 1))))
                .thenReturn(List.of(v("2026-07-01", "30.00")));
        when(bcraClient.obtenerSerie(eq(40), any(), eq(LocalDate.of(2026, 10, 1))))
                .thenReturn(List.of(v("2026-10-01", "33.00")));

        ResultadoAjusteDTO r = service.calcularAjuste(TipoIndiceEnum.ICL, new BigDecimal("400000"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1));

        assertAll(
                () -> assertEquals(new BigDecimal("440000.00"), r.getMontoNuevo()),
                () -> assertEquals(new BigDecimal("10.00"), r.getPorcentajeVariacion()),
                () -> assertEquals(OrigenDatoEnum.BCRA, r.getOrigen()));
    }

    @Test
    @DisplayName("Ajuste por IPC trimestral: encadena los 3 últimos meses publicados")
    void ajustePorIpc() {
        // El BCRA devuelve del más nuevo al más viejo, con más meses de los necesarios
        when(bcraClient.obtenerSerie(eq(27), any(), any())).thenReturn(List.of(
                v("2026-09-30", "1.9"), v("2026-08-31", "1.7"), v("2026-07-31", "2.1"),
                v("2026-06-30", "1.9"), v("2026-05-31", "2.1")));

        CoeficienteAjusteDTO c = service.calcularCoeficiente(TipoIndiceEnum.IPC,
                LocalDate.of(2026, 7, 15), LocalDate.of(2026, 10, 15));

        assertAll(
                () -> assertEquals(3, c.getVariacionesMensuales().size()),
                () -> assertEquals(LocalDate.of(2026, 7, 31), c.getFechaIndiceDesde()),
                () -> assertEquals(LocalDate.of(2026, 9, 30), c.getFechaIndiceHasta()),
                () -> assertEquals(new BigDecimal("1.05808578"), c.getCoeficiente()),
                () -> assertEquals(new BigDecimal("5.81"), c.getPorcentajeVariacion()));
    }

    @Test
    @DisplayName("IPC: el BCRA responde pero faltan meses publicados -> 400")
    void ipcInsuficienteEnBcra() {
        when(bcraClient.obtenerSerie(eq(27), any(), any())).thenReturn(List.of(v("2026-09-30", "1.9")));
        assertThrows(OperacionInvalidaException.class, () -> service.calcularCoeficiente(
                TipoIndiceEnum.IPC, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)));
    }

    @Test
    @DisplayName("IPC: BCRA caído y el caché no tiene los meses -> 503")
    void ipcInsuficienteEnCache() {
        when(bcraClient.obtenerSerie(eq(27), any(), any())).thenThrow(new ResourceAccessException("timeout"));
        when(cacheService.buscarEntreFechas(eq(TipoIndiceEnum.IPC), any(), any()))
                .thenReturn(List.of(v("2026-09-30", "1.9")));
        assertThrows(IndiceNoDisponibleException.class, () -> service.calcularCoeficiente(
                TipoIndiceEnum.IPC, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)));
    }

    @Test
    @DisplayName("Fecha futura -> 400 sin consultar al BCRA")
    void fechaFutura() {
        assertThrows(OperacionInvalidaException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.now().plusDays(1)));
        verify(bcraClient, never()).obtenerSerie(anyInt(), any(), any());
    }

    @Test
    @DisplayName("El BCRA rechaza el pedido (400) -> 400, no se usa el caché")
    void bcraRechazaPedido() {
        when(bcraClient.obtenerSerie(anyInt(), any(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThrows(OperacionInvalidaException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)));
        verify(cacheService, never()).buscarEntreFechas(any(), any(), any());
    }

    @Test
    @DisplayName("El BCRA responde 429 (cuota agotada) -> usa el caché")
    void cuotaAgotadaUsaCache() {
        when(bcraClient.obtenerSerie(anyInt(), any(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        when(cacheService.buscarEntreFechas(eq(TipoIndiceEnum.ICL), any(), any()))
                .thenReturn(List.of(v("2026-10-25", "37.07")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen());
    }

    @Test
    @DisplayName("El BCRA responde 403 (bloqueo de su lado) -> usa el caché, no es culpa del pedido")
    void bcraBloqueaUsaCache() {
        when(bcraClient.obtenerSerie(anyInt(), any(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        when(cacheService.buscarEntreFechas(eq(TipoIndiceEnum.ICL), any(), any()))
                .thenReturn(List.of(v("2026-10-25", "37.07")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen());
    }

    @Test
    @DisplayName("Si falla guardar en el caché, igual responde con el dato del BCRA")
    void fallaGuardarCache() {
        List<BcraValorDTO> serie = List.of(v("2026-10-26", "37.09"));
        when(bcraClient.obtenerSerie(eq(40), any(), any())).thenReturn(serie);
        doThrow(new DataIntegrityViolationException("duplicada")).when(cacheService).guardar(any(), any());

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.BCRA, valor.getOrigen());
    }

    @Test
    @DisplayName("El BCRA responde sin valores para el rango -> 400")
    void bcraSinValores() {
        when(bcraClient.obtenerSerie(anyInt(), any(), any())).thenReturn(List.of());
        assertThrows(OperacionInvalidaException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2015, 1, 10)));
    }

    @Test
    @DisplayName("Fechas invertidas: 400")
    void fechasInvertidas() {
        assertThrows(OperacionInvalidaException.class, () -> service.calcularCoeficiente(
                TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 7, 1)));
    }
}
