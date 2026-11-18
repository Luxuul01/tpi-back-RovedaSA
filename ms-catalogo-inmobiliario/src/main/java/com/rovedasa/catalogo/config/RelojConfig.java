package com.rovedasa.catalogo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Reloj de la aplicación. Los servicios toman la fecha y hora de este bean en lugar de llamar a
 * LocalDateTime.now() directo, así los tests pueden fijar el momento con Clock.fixed(...).
 *
 * Solo para pruebas: con la propiedad {@code app.reloj.fecha-fija} (yyyy-MM-dd) el reloj queda fijo en ese
 * día a las 12:00, igual que en ms-contratos. Sin la propiedad (lo normal) es el reloj del sistema.
 */
@Slf4j
@Configuration
public class RelojConfig {

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
