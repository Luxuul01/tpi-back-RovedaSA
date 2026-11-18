package com.rovedasa.contratos.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Reloj del sistema inyectable. Permite reemplazarlo por un reloj fijo en tests
 * sin usar LocalDate.now() directamente en la lógica de negocio.
 *
 * Solo para pruebas: con la propiedad {@code app.reloj.fecha-fija} (yyyy-MM-dd, por ejemplo
 * {@code -Dapp.reloj.fecha-fija=2027-03-15}) el servicio arranca con el reloj fijo en ese día a las 12:00,
 * para correr la colección como si fuera otra fecha. Sin la propiedad (lo normal) es el reloj del sistema.
 */
@Slf4j
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.reloj.fecha-fija:}") String fechaFija) {
        return crearReloj(fechaFija, ZoneId.systemDefault());
    }

    static Clock crearReloj(String fechaFija, ZoneId zona) {
        if (fechaFija == null || fechaFija.isBlank()) {
            return Clock.system(zona);
        }
        LocalDate fecha = LocalDate.parse(fechaFija.trim());
        log.warn("RELOJ FIJO (app.reloj.fecha-fija): la fecha de la aplicación es {} y no avanza. Solo para pruebas.",
                fecha);
        return Clock.fixed(fecha.atTime(LocalTime.NOON).atZone(zona).toInstant(), zona);
    }
}
