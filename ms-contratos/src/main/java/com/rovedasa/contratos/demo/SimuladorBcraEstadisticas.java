package com.rovedasa.contratos.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estado del perfil "demo": permite simular la caída de la API Estadísticas del BCRA para mostrar
 * el caché de contingencia (I-03) y el 503 sin caché (I-04) sin depender de que el BCRA falle de verdad.
 */
@Component
@Profile("demo")
public class SimuladorBcraEstadisticas {

    private final AtomicBoolean caido = new AtomicBoolean(false);

    public boolean isCaido() {
        return caido.get();
    }

    public void setCaido(boolean valor) {
        caido.set(valor);
    }
}
