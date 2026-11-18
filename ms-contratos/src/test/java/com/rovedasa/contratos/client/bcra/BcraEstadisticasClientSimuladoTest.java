package com.rovedasa.contratos.client.bcra;

import com.rovedasa.contratos.client.bcra.dto.BcraValorDTO;
import com.rovedasa.contratos.demo.SimuladorBcraEstadisticas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BcraEstadisticasClientSimuladoTest {

    private final SimuladorBcraEstadisticas simulador = new SimuladorBcraEstadisticas();
    private final BcraEstadisticasClientSimulado cliente = conReloj(LocalDate.of(2026, 11, 17));

    private BcraEstadisticasClientSimulado conReloj(LocalDate hoy) {
        Clock clock = Clock.fixed(hoy.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        return new BcraEstadisticasClientSimulado(simulador, clock);
    }

    @Test
    @DisplayName("ICL fijo: 10,00 el 01/07/2026 y 12,50 el 01/10/2026 (C-05: +25 %)")
    void iclFijo() {
        BigDecimal desde = cliente.obtenerUltimoValor(40, LocalDate.of(2026, 7, 1)).get(0).getValor();
        BigDecimal hasta = cliente.obtenerUltimoValor(40, LocalDate.of(2026, 10, 1)).get(0).getValor();
        assertAll(
                () -> assertEquals(new BigDecimal("10.00"), desde),
                () -> assertEquals(new BigDecimal("12.50"), hasta),
                () -> assertTrue(cliente.obtenerUltimoValor(40, LocalDate.of(2020, 1, 1)).isEmpty()));
    }

    @Test
    @DisplayName("IPC: 2 % mensual, fechado a fin de mes, del más nuevo al más viejo")
    void ipcMensual() {
        List<BcraValorDTO> serie = cliente.obtenerSerie(27, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 10, 1));
        assertAll(
                () -> assertEquals(4, serie.size()),
                () -> assertEquals(LocalDate.of(2026, 9, 30), serie.get(0).getFecha()),
                () -> assertEquals(LocalDate.of(2026, 6, 30), serie.get(3).getFecha()),
                () -> assertEquals(new BigDecimal("2.0"), serie.get(0).getValor()));
    }

    @Test
    @DisplayName("IPC: el mes M se publica el día 15 de M+1 según el reloj")
    void ipcPublicadoElQuinceDelMesSiguiente() {
        LocalDate desde = LocalDate.of(2026, 6, 1);
        LocalDate hasta = LocalDate.of(2026, 10, 1);
        List<BcraValorDTO> el14 = conReloj(LocalDate.of(2026, 9, 14)).obtenerSerie(27, desde, hasta);
        List<BcraValorDTO> el15 = conReloj(LocalDate.of(2026, 9, 15)).obtenerSerie(27, desde, hasta);
        assertAll(
                () -> assertEquals(LocalDate.of(2026, 7, 31), el14.get(0).getFecha()),
                () -> assertEquals(LocalDate.of(2026, 8, 31), el15.get(0).getFecha()),
                () -> assertEquals(LocalDate.of(2026, 7, 31),
                        conReloj(LocalDate.of(2026, 9, 14)).obtenerUltimoValor(27, LocalDate.of(2026, 9, 14)).get(0).getFecha()));
    }

    @Test
    @DisplayName("ICL: también devuelve fechas futuras, como la API real")
    void iclFechaFutura() {
        assertEquals(new BigDecimal("12.50"),
                cliente.obtenerUltimoValor(40, LocalDate.of(2026, 12, 16)).get(0).getValor());
    }

    @Test
    @DisplayName("Con la caída simulada lanza la misma excepción que un timeout real")
    void caidaSimulada() {
        simulador.setCaido(true);
        assertThrows(ResourceAccessException.class, () -> cliente.obtenerUltimoValor(40, LocalDate.of(2026, 7, 1)));
    }
}
