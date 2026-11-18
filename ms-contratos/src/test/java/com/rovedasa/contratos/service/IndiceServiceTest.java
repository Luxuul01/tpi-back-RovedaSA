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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * El comportamiento transaccional del caché (REQUIRES_NEW, upsert idempotente) se prueba con la
 * base real en IndiceCacheServiceTest.
 */
class IndiceServiceTest {

    /** "Hoy" fijo de los tests: 17/11/2026. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 17);

    private BcraEstadisticasClient bcraClient;
    private IndiceCacheService cacheService;
    private IndiceService service;

    @BeforeEach
    void setUp() {
        bcraClient = mock(BcraEstadisticasClient.class);
        cacheService = mock(IndiceCacheService.class);
        service = crearServicio(HOY);
    }

    private IndiceService crearServicio(LocalDate hoy) {
        Clock clock = Clock.fixed(hoy.atStartOfDay(ZoneId.of("America/Argentina/Cordoba")).toInstant(),
                ZoneId.of("America/Argentina/Cordoba"));
        return new IndiceService(bcraClient, cacheService, new CalculadoraAjuste(), clock, 7, 2);
    }

    private static BcraValorDTO v(String fecha, String valor) {
        return new BcraValorDTO(LocalDate.parse(fecha), new BigDecimal(valor));
    }

    // ---------------------------------------------------------------- BCRA disponible

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
    @DisplayName("Al BCRA se le pide el último valor hasta la fecha, sin ventana hacia atrás")
    void pideUltimoValorHastaLaFecha() {
        when(bcraClient.obtenerUltimoValor(eq(40), eq(LocalDate.of(2026, 10, 26))))
                .thenReturn(List.of(v("2026-10-26", "37.09")));

        service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        verify(bcraClient).obtenerUltimoValor(40, LocalDate.of(2026, 10, 26));
        verify(bcraClient, never()).obtenerSerie(anyInt(), any(), any());
    }

    @Test
    @DisplayName("BCRA arriba: si no hay valor de la fecha exacta usa el último publicado anterior, aunque sea viejo")
    void bcraUltimoPublicadoAnterior() {
        when(bcraClient.obtenerUltimoValor(40, LocalDate.of(2026, 10, 26)))
                .thenReturn(List.of(v("2026-09-16", "36.27")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertAll(
                () -> assertEquals(OrigenDatoEnum.BCRA, valor.getOrigen()),
                () -> assertEquals(LocalDate.of(2026, 9, 16), valor.getFechaIndice()));
    }

    @Test
    @DisplayName("El BCRA responde sin valores (fecha anterior al inicio de la serie) -> 400")
    void bcraSinValores() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any())).thenReturn(List.of());
        assertThrows(OperacionInvalidaException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2015, 1, 10)));
        verify(cacheService, never()).buscarUltimoEntre(any(), any(), any());
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

    // ---------------------------------------------------------------- Contingencia

    @Test
    @DisplayName("BCRA caído: usa el caché de contingencia, buscando como mucho 7 días antes")
    void fallbackAlCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"));
        when(cacheService.buscarUltimoEntre(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26)))
                .thenReturn(List.of(v("2026-10-24", "37.05")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertAll(
                () -> assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen()),
                () -> assertEquals(LocalDate.of(2026, 10, 24), valor.getFechaIndice()),
                () -> assertEquals(new BigDecimal("37.05"), valor.getValor()));
        verify(cacheService, never()).guardar(any(), any());
    }

    @Test
    @DisplayName("BCRA caído y el caché solo tiene un valor de más de 7 días antes: 503 (no se ajusta con un dato viejo)")
    void cacheViejoNoSirve() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"));
        // El repositorio ya filtra por el mínimo: un valor del 16/09 no entra en [19/10, 26/10]
        when(cacheService.buscarUltimoEntre(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26)))
                .thenReturn(List.of());

        IndiceNoDisponibleException ex = assertThrows(IndiceNoDisponibleException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)));
        assertTrue(ex.getMessage().contains("2026-10-19"));
    }

    @Test
    @DisplayName("La antigüedad máxima del caché sale de la propiedad bcra.cache.max-antiguedad-dias")
    void antiguedadConfigurable() {
        Clock clock = Clock.fixed(HOY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        IndiceService conTresDias = new IndiceService(bcraClient, cacheService, new CalculadoraAjuste(), clock, 3, 2);
        when(bcraClient.obtenerUltimoValor(anyInt(), any())).thenThrow(new ResourceAccessException("caído"));
        when(cacheService.buscarUltimoEntre(any(), any(), any())).thenReturn(List.of(v("2026-10-24", "37.05")));

        conTresDias.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        verify(cacheService).buscarUltimoEntre(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 26));
    }

    @Test
    @DisplayName("BCRA caído y caché vacío: 503 (no se ajusta con datos inventados)")
    void sinBcraNiCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new ResourceAccessException("Connection refused"));
        when(cacheService.buscarUltimoEntre(any(), any(), any())).thenReturn(List.of());

        assertThrows(IndiceNoDisponibleException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)));
    }

    @Test
    @DisplayName("BCRA caído y tampoco se puede leer el caché (base caída): 503, no 500")
    void cacheIlegible() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any())).thenThrow(new ResourceAccessException("caído"));
        when(cacheService.buscarUltimoEntre(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("sin conexión"));

        assertThrows(IndiceNoDisponibleException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)));
    }

    @Test
    @DisplayName("El BCRA responde 429 (cuota agotada) -> usa el caché")
    void cuotaAgotadaUsaCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        when(cacheService.buscarUltimoEntre(eq(TipoIndiceEnum.ICL), any(), any()))
                .thenReturn(List.of(v("2026-10-25", "37.07")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen());
    }

    @Test
    @DisplayName("El BCRA responde 403 (bloqueo de su lado) -> usa el caché, no es culpa del pedido")
    void bcraBloqueaUsaCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        when(cacheService.buscarUltimoEntre(eq(TipoIndiceEnum.ICL), any(), any()))
                .thenReturn(List.of(v("2026-10-25", "37.07")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen());
    }

    @Test
    @DisplayName("Un 400 del BCRA es falla de integración (configuración o cambio de API): usa el caché")
    void bcra400UsaCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        when(cacheService.buscarUltimoEntre(eq(TipoIndiceEnum.ICL), any(), any()))
                .thenReturn(List.of(v("2026-10-25", "37.07")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26));

        assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen());
    }

    @Test
    @DisplayName("Un 404 del BCRA sin caché -> 503 (antes daba 400 al cliente)")
    void bcra404SinCache() {
        when(bcraClient.obtenerUltimoValor(anyInt(), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(cacheService.buscarUltimoEntre(any(), any(), any())).thenReturn(List.of());

        assertThrows(IndiceNoDisponibleException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 26)));
    }

    // ---------------------------------------------------------------- Validaciones del pedido

    @Test
    @DisplayName("Fecha futura según el Clock -> 400 sin consultar al BCRA")
    void fechaFutura() {
        assertThrows(OperacionInvalidaException.class,
                () -> service.obtenerValor(TipoIndiceEnum.ICL, HOY.plusDays(1)));
        verify(bcraClient, never()).obtenerUltimoValor(anyInt(), any());
    }

    @Test
    @DisplayName("La fecha de hoy según el Clock se acepta")
    void fechaDeHoy() {
        when(bcraClient.obtenerUltimoValor(40, HOY)).thenReturn(List.of(v("2026-11-17", "38.00")));
        assertEquals(HOY, service.obtenerValor(TipoIndiceEnum.ICL, HOY).getFechaIndice());
    }

    @Test
    @DisplayName("Fechas invertidas: 400")
    void fechasInvertidas() {
        assertThrows(OperacionInvalidaException.class, () -> service.calcularCoeficiente(
                TipoIndiceEnum.ICL, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 7, 1)));
    }

    // ---------------------------------------------------------------- ICL

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
    @DisplayName("C-05: ICL 10,00 -> 12,50 da +25 % y canon 625.000, también con el BCRA caído y el caché")
    void casoC05ConBcraYConCache() {
        LocalDate desde = LocalDate.of(2026, 7, 1);
        LocalDate hasta = LocalDate.of(2026, 10, 1);
        when(bcraClient.obtenerUltimoValor(40, desde)).thenReturn(List.of(v("2026-07-01", "10.00")));
        when(bcraClient.obtenerUltimoValor(40, hasta)).thenReturn(List.of(v("2026-10-01", "12.50")));

        ResultadoAjusteDTO conBcra = service.calcularAjuste(TipoIndiceEnum.ICL, new BigDecimal("500000"), desde, hasta);

        when(bcraClient.obtenerUltimoValor(anyInt(), any())).thenThrow(new ResourceAccessException("caído"));
        when(cacheService.buscarUltimoEntre(TipoIndiceEnum.ICL, desde.minusDays(7), desde))
                .thenReturn(List.of(v("2026-07-01", "10.00")));
        when(cacheService.buscarUltimoEntre(TipoIndiceEnum.ICL, hasta.minusDays(7), hasta))
                .thenReturn(List.of(v("2026-10-01", "12.50")));

        ResultadoAjusteDTO conCache = service.calcularAjuste(TipoIndiceEnum.ICL, new BigDecimal("500000"), desde, hasta);

        assertAll(
                () -> assertEquals(new BigDecimal("625000.00"), conBcra.getMontoNuevo()),
                () -> assertEquals(new BigDecimal("25.00"), conBcra.getPorcentajeVariacion()),
                () -> assertEquals(OrigenDatoEnum.BCRA, conBcra.getOrigen()),
                () -> assertEquals(new BigDecimal("625000.00"), conCache.getMontoNuevo()),
                () -> assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, conCache.getOrigen()));
    }

    // ---------------------------------------------------------------- IPC

    @Test
    @DisplayName("Meses del IPC: los que empiezan en [desde − 1 mes, hasta − 1 mes)")
    void mesesIpcConvencion() {
        assertAll(
                () -> assertEquals(List.of(YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8)),
                        IndiceService.mesesIpc(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1))),
                () -> assertEquals(List.of(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9)),
                        IndiceService.mesesIpc(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 10, 15))),
                () -> assertEquals(List.of(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3)),
                        IndiceService.mesesIpc(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 30))),
                () -> assertEquals(List.of(),
                        IndiceService.mesesIpc(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20))));
    }

    @Test
    @DisplayName("Ajuste por IPC 01/07 -> 01/10: encadena junio, julio y agosto (fechados a fin de mes)")
    void ajustePorIpc() {
        // El BCRA devuelve del más nuevo al más viejo, con más meses de los necesarios
        when(bcraClient.obtenerSerie(27, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 10, 1))).thenReturn(List.of(
                v("2026-09-30", "3.5"), v("2026-08-31", "1.7"), v("2026-07-31", "2.1"),
                v("2026-06-30", "1.9"), v("2026-05-31", "4.0")));

        CoeficienteAjusteDTO c = service.calcularCoeficiente(TipoIndiceEnum.IPC,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1));

        assertAll(
                () -> assertEquals(3, c.getVariacionesMensuales().size()),
                () -> assertEquals(LocalDate.of(2026, 6, 30), c.getFechaIndiceDesde()),
                () -> assertEquals(LocalDate.of(2026, 8, 31), c.getFechaIndiceHasta()),
                () -> assertEquals(new BigDecimal("1.05808578"), c.getCoeficiente()),
                () -> assertEquals(new BigDecimal("5.81"), c.getPorcentajeVariacion()),
                () -> assertEquals(OrigenDatoEnum.BCRA, c.getOrigen()));
    }

    @Test
    @DisplayName("IPC anclado a la ventana: da lo mismo calcularlo en octubre o en diciembre")
    void ipcNoDependeDelDiaDeEjecucion() {
        when(bcraClient.obtenerSerie(eq(27), any(), any())).thenReturn(List.of(
                v("2026-11-30", "9.9"), v("2026-10-31", "9.9"), v("2026-09-30", "9.9"),
                v("2026-08-31", "1.7"), v("2026-07-31", "2.1"), v("2026-06-30", "1.9")));
        LocalDate desde = LocalDate.of(2026, 7, 1);
        LocalDate hasta = LocalDate.of(2026, 10, 1);

        BigDecimal enOctubre = crearServicio(LocalDate.of(2026, 10, 20))
                .calcularCoeficiente(TipoIndiceEnum.IPC, desde, hasta).getCoeficiente();
        BigDecimal enDiciembre = crearServicio(LocalDate.of(2026, 12, 20))
                .calcularCoeficiente(TipoIndiceEnum.IPC, desde, hasta).getCoeficiente();

        assertAll(
                () -> assertEquals(new BigDecimal("1.05808578"), enOctubre),
                () -> assertEquals(enOctubre, enDiciembre));
    }

    @Test
    @DisplayName("IPC: el BCRA responde pero falta un mes todavía no publicado -> 503 y el ajuste queda pendiente")
    void ipcNoPublicadoEnBcra() {
        when(bcraClient.obtenerSerie(eq(27), any(), any())).thenReturn(List.of(
                v("2026-07-31", "2.1"), v("2026-06-30", "1.9")));

        IndiceNoDisponibleException ex = assertThrows(IndiceNoDisponibleException.class,
                () -> service.calcularCoeficiente(TipoIndiceEnum.IPC, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)));
        assertEquals("IPC de agosto 2026 todavía no publicado; el ajuste queda pendiente", ex.getMessage());
    }

    @Test
    @DisplayName("IPC: ventana sin ningún mes completo -> 400")
    void ipcVentanaSinMeses() {
        assertThrows(OperacionInvalidaException.class, () -> service.calcularCoeficiente(
                TipoIndiceEnum.IPC, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20)));
        verify(bcraClient, never()).obtenerSerie(anyInt(), any(), any());
    }

    @Test
    @DisplayName("IPC: BCRA caído y el caché tiene los tres meses -> usa el caché")
    void ipcDesdeCache() {
        when(bcraClient.obtenerSerie(eq(27), any(), any())).thenThrow(new ResourceAccessException("timeout"));
        when(cacheService.buscarEntreFechas(TipoIndiceEnum.IPC, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 10, 1)))
                .thenReturn(List.of(v("2026-08-31", "1.7"), v("2026-07-31", "2.1"), v("2026-06-30", "1.9")));

        CoeficienteAjusteDTO c = service.calcularCoeficiente(TipoIndiceEnum.IPC,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1));

        assertAll(
                () -> assertEquals(new BigDecimal("1.05808578"), c.getCoeficiente()),
                () -> assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, c.getOrigen()));
    }

    @Test
    @DisplayName("IPC: BCRA caído y el caché no tiene los meses -> 503")
    void ipcInsuficienteEnCache() {
        when(bcraClient.obtenerSerie(eq(27), any(), any())).thenThrow(new ResourceAccessException("timeout"));
        when(cacheService.buscarEntreFechas(eq(TipoIndiceEnum.IPC), any(), any()))
                .thenReturn(List.of(v("2026-08-31", "1.7")));

        IndiceNoDisponibleException ex = assertThrows(IndiceNoDisponibleException.class, () -> service.calcularCoeficiente(
                TipoIndiceEnum.IPC, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)));
        assertTrue(ex.getMessage().startsWith("IPC de junio 2026 no disponible"));
    }

    @Test
    @DisplayName("IPC puntual en contingencia: acepta un mes del caché de hasta 2 meses antes")
    void ipcPuntualDesdeCache() {
        when(bcraClient.obtenerUltimoValor(eq(27), any())).thenThrow(new ResourceAccessException("timeout"));
        when(cacheService.buscarUltimoEntre(TipoIndiceEnum.IPC, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 17)))
                .thenReturn(List.of(v("2026-09-30", "1.9")));

        ValorIndiceDTO valor = service.obtenerValor(TipoIndiceEnum.IPC, LocalDate.of(2026, 11, 17));

        assertAll(
                () -> assertEquals(LocalDate.of(2026, 9, 30), valor.getFechaIndice()),
                () -> assertEquals(OrigenDatoEnum.CACHE_CONTINGENCIA, valor.getOrigen()));
    }
}
