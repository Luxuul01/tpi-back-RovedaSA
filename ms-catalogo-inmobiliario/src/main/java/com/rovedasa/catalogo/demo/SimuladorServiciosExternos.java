package com.rovedasa.catalogo.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estado del perfil "demo": permite simular la caída de la Central de Deudores del BCRA y de GeoRef
 * para mostrar la resiliencia (escenarios I-01 e I-02 del Alcance) sin depender de que fallen de verdad.
 */
@Component
@Profile("demo")
public class SimuladorServiciosExternos {

    private final AtomicBoolean bcraCaido = new AtomicBoolean(false);
    private final AtomicBoolean georefCaido = new AtomicBoolean(false);

    public boolean isBcraCaido() {
        return bcraCaido.get();
    }

    public boolean isGeorefCaido() {
        return georefCaido.get();
    }

    public void setBcraCaido(boolean valor) {
        bcraCaido.set(valor);
    }

    public void setGeorefCaido(boolean valor) {
        georefCaido.set(valor);
    }
}
