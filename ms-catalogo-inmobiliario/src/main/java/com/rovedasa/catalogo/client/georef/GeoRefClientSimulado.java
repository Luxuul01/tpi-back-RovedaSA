package com.rovedasa.catalogo.client.georef;

import com.rovedasa.catalogo.demo.SimuladorServiciosExternos;
import com.rovedasa.catalogo.exception.ServicioExternoNoDisponibleException;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * GeoRef en el perfil "demo": consulta la API real, salvo que esté prendida la caída simulada.
 * En ese caso responde igual que cuando GeoRef no contesta (503, RN-03), para el escenario I-01.
 */
@Component
@Primary
@Profile("demo")
public class GeoRefClientSimulado extends GeoRefClient {

    private final SimuladorServiciosExternos simulador;

    public GeoRefClientSimulado(RestClient.Builder builder, GeoRefProperties propiedades,
                                SimuladorServiciosExternos simulador) {
        super(builder, propiedades);
        this.simulador = simulador;
    }

    @Override
    public Optional<DireccionNormalizada> normalizar(String calle, String numero, String provincia, String ciudad) {
        if (simulador.isGeorefCaido()) {
            throw new ServicioExternoNoDisponibleException(
                    "El servicio de normalización de direcciones (GeoRef) no está disponible (caída simulada, perfil demo)",
                    null);
        }
        return super.normalizar(calle, numero, provincia, ciudad);
    }
}
