package com.rovedasa.catalogo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Reloj de la aplicación. Los servicios toman la fecha y hora de este bean en lugar de llamar a
 * LocalDateTime.now() directo, así los tests pueden fijar el momento con Clock.fixed(...).
 */
@Configuration
public class RelojConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
