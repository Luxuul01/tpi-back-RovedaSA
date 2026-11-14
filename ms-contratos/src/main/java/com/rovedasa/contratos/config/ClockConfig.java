package com.rovedasa.contratos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Reloj del sistema inyectable. Permite reemplazarlo por un reloj fijo en tests
 * sin usar LocalDate.now() directamente en la lógica de negocio.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
