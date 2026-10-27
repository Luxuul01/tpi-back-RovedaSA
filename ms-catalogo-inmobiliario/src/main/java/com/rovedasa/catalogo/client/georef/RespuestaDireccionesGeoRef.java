package com.rovedasa.catalogo.client.georef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Respuesta de GET /direcciones de GeoRef AR. Solo se mapean los campos que usa el catálogo;
 * la forma se tomó de una respuesta real (ver src/test/resources/georef).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RespuestaDireccionesGeoRef(Integer cantidad, List<DireccionGeoRef> direcciones) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DireccionGeoRef(
            String nomenclatura,
            EntidadGeoRef provincia,
            EntidadGeoRef departamento,
            @JsonProperty("localidad_censal") EntidadGeoRef localidadCensal,
            UbicacionGeoRef ubicacion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntidadGeoRef(String id, String nombre) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UbicacionGeoRef(Double lat, Double lon) {
    }
}
