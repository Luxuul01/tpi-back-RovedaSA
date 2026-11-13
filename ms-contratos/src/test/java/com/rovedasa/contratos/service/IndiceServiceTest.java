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
        service = new IndiceService(bcraClient, cacheService, new CalculadoraAjuste(), 2);
    }

    private static BcraValorDTO v(String fecha, String valor) {
        return new BcraValorDTO(LocalDate.parse(fecha), new BigDecimal(valor));
    }

    @Test
    @DisplayName("BCRA disponible: usa su valor y lo guarda en el caché")
    void valorDesdeBcra() {
        List<BcraValorDTO> serie = List.of(v("2026-10-26", "37.09"), v("2026-10-25", "37.07"));
        when(bcraClient.obtenerUltimoValor(eq(40), any())).thenReturn(serie);

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertAll(
                () -> assertEquals(OrigenDatoEnum.BCRA, valor.getOrigen()),
                () -> assertEquals(new BigDecimal("37.09"), valor.getValor()));
        verify(cacheService).guardar(TipoIndiceEnum.ICL, serie);
    }

    @Test
    @DisplayName("BCRA caído: usa el caché de contingencia")
    void fallbackAlCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"));
        when(cacheService.buscarUltimoHasta(eq(TipoIndiceEnum.ICL), any()))
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
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new ResourceAccessException("Connection refused"));
        when(cacheService.buscarUltimoHasta(any(), any())).thenReturn(List.of());

        assertThrows(IndiceNoDisponibleException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)));
    }

    @Test
    @DisplayName("Ajuste por ICL: canon x ICL_hasta / ICL_desde")
    void ajustePorIcl() {
        when(bcraClient.obtenerUltimoValor(eq(40), eq(LocalDate.of(2026, 7, 1))))
                .thenReturn(List.of(v("2026-07-01", "30.00")));
        when(bcraClient.obtenerUltimoValor(eq(40), eq(LocalDate.of(2026, 10, 1))))
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
        verify(bcraClient, never()).obtenerUltimoValor(anyInt(), any());
    }

    @Test
    @DisplayName("El BCRA rechaza el pedido (400) -> 400, no se usa el caché")
    void bcraRechazaPedido() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThrows(OperacionInvalidaException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)));
        verify(cacheService, never()).buscarUltimoHasta(any(), any());
    }

    @Test
    @DisplayName("El BCRA responde 429 (cuota agotada) -> usa el caché")
    void cuotaAgotadaUsaCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        when(cacheService.buscarUltimoHasta(eq(TipoIndiceEnum.ICL), any()))
                .thenReturn(List.of(v("2026-10-25", "37.07")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen());
    }

    @Test
    @DisplayName("El BCRA responde 403 (bloqueo de su lado) -> usa el caché, no es culpa del pedido")
    void bcraBloqueaUsaCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        when(cacheService.buscarUltimoHasta(eq(TipoIndiceEnum.ICL), any()))
                .thenReturn(List.of(v("2026-10-25", "37.07")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen());
    }

    @Test
    @DisplayName("Si falla guardar en el caché, igual responde con el dato del BCRA")
    void fallaGuardarCache() {
        List<BcraValorDTO> serie = List.of(v("2026-10-26", "37.09"));
        when(bcraClient.obtenerUltimoValor(eq(40), any())).thenReturn(serie);
        doThrow(new DataIntegrityViolationException("duplicada")).when(cacheService).guardar(any(), any());

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.BCRA, valor.getOrigen());
    }

    @Test
    @DisplayName("El BCRA responde pero no tiene valores hasta esa fecha -> 400")
    void bcraSinValores() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any())).thenReturn(List.of());
        assertThrows(OperacionInvalidaException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2015, 1, 10)));
    }

    @Test
    @DisplayName("Fechas invertidas: 400")
    void fechasInvertidas() {
        assertThrows(OperacionInvalidaException.class, () -> service.calcularCoeficiente(
                TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 7, 1)));
    }

    @Test
    @DisplayName("Al BCRA se le pide el último valor hasta la fecha, sin ventana hacia atrás")
    void pideUltimoValorHastaLaFecha() {
        when(bcraClient.obtenerUltimoValor(eq(40), eq(LocalDate.of(2026, 10, 26))))
                .thenReturn(List.of(v("2026-10-26", "37.09")));

        service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        verify(bcraClient).obtenerUltimoValor(40, LocalDate.of(2026, 10, 26));
        verify(bcraClient, never()).obtenerSerie(anyInt(), any(), any());
    }

    @Test
    @DisplayName("BCRA caído: usa el último valor del caché anterior a la fecha, aunque sea viejo (sección 14)")
    void cacheUsaUltimoAnteriorSinLimite() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"));
        // 40 días antes de la fecha pedida: con la tolerancia vieja de 7 días daba 503
        when(cacheService.buscarUltimoHasta(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)))
                .thenReturn(List.of(v("2026-09-16", "36.27")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertAll(
                () -> assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen()),
                () -> assertEquals(LocalDate.of(2026, 10, 26), valor.getFechaSolicitada()),
                () -> assertEquals(LocalDate.of(2026, 9, 16), valor.getFechaIndice()),
                () -> assertEquals(new BigDecimal("36.27"), valor.getValor()));
    }

    @Test
    @DisplayName("C-05: ICL 10,00 -> 12,50 da +25 % y canon 625.000, también con el BCRA caído y el caché")
    void casoC05ConBcraYConCache() {
        LocalDate desde = LocalDate.of(2026, 7, 1);
        LocalDate hasta = LocalDate.of(2026, 10, 1);
        when(bcraClient.obtenerUltimoValor(40, desde)).thenReturn(List.of(v("2026-07-01", "10.00")));
        when(bcraClient.obtenerUltimoValor(40, hasta)).thenReturn(List.of(v("2026-10-01", "12.50")));

        ResultadoAjusteDTO conBcra = service.calcularAjuste(TipoIndiceEnum.ICL, new BigDecimal("500000"), desde, hasta);

        when(bcraClient.obtenerUltimoValor(anyInt(), any())).thenThrow(new ResourceAccessException("caído"));
        when(cacheService.buscarUltimoHasta(TipoIndiceEnum.ICL, desde)).thenReturn(List.of(v("2026-07-01", "10.00")));
        when(cacheService.buscarUltimoHasta(TipoIndiceEnum.ICL, hasta)).thenReturn(List.of(v("2026-10-01", "12.50")));

        ResultadoAjusteDTO conCache = service.calcularAjuste(TipoIndiceEnum.ICL, new BigDecimal("500000"), desde, hasta);

        assertAll(
                () -> assertEquals(new BigDecimal("625000.00"), conBcra.getMontoNuevo()),
                () -> assertEquals(new BigDecimal("25.00"), conBcra.getPorcentajeVariacion()),
                () -> assertEquals(OrigenDatoEnum.BCRA, conBcra.getOrigen()),
                () -> assertEquals(new BigDecimal("625000.00"), conCache.getMontoNuevo()),
                () -> assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, conCache.getOrigen()));
    }
}
