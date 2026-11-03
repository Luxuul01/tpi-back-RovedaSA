package com.rovedasa.catalogo.client.georef;

import com.rovedasa.catalogo.client.georef.RespuestaDireccionesGeoRef.DireccionGeoRef;
import com.rovedasa.catalogo.client.georef.RespuestaDireccionesGeoRef.EntidadGeoRef;
import com.rovedasa.catalogo.client.georef.RespuestaDireccionesGeoRef.UbicacionGeoRef;
import com.rovedasa.catalogo.exception.ServicioExternoNoDisponibleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente de la API GeoRef AR: normaliza una dirección y obtiene sus coordenadas oficiales.
 */
@Slf4j
@Component
public class GeoRefClient {

    private static final String RUTA_DIRECCIONES = "/direcciones";
    private static final String MENSAJE_NO_DISPONIBLE =
            "El servicio de normalización de direcciones (GeoRef) no está disponible";

    private final RestClient restClient;

    @Autowired
    public GeoRefClient(RestClient.Builder builder, GeoRefProperties propiedades) {
        this(builder
                .baseUrl(propiedades.baseUrl())
                .requestFactory(crearFabricaConTimeouts(propiedades))
                .build());
    }

    // Recibe el RestClient ya armado: permite enlazar MockRestServiceServer en los tests
    GeoRefClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Normaliza la dirección con GET /direcciones. Si viene la ciudad, filtra por localidad censal.
     *
     * @return la dirección normalizada, o vacío si GeoRef no la encontró o no tiene coordenadas
     * @throws ServicioExternoNoDisponibleException si GeoRef no responde o devuelve un error
     */
    public Optional<DireccionNormalizada> normalizar(String calle, String numero, String provincia, String ciudad) {
        String direccion = calle.trim() + " " + numero.trim();
        log.debug("Consultando GeoRef para normalizar la dirección: {} ({}, {})", direccion, ciudad, provincia);

        RespuestaDireccionesGeoRef respuesta = consultarDirecciones(direccion, provincia, ciudad);

        if (respuesta == null || respuesta.direcciones() == null || respuesta.direcciones().isEmpty()) {
            log.info("GeoRef no encontró la dirección: {} ({}, {})", direccion, ciudad, provincia);
            return Optional.empty();
        }

        DireccionGeoRef encontrada = respuesta.direcciones().get(0);
        UbicacionGeoRef ubicacion = encontrada.ubicacion();
        if (ubicacion == null || ubicacion.lat() == null || ubicacion.lon() == null
                || encontrada.provincia() == null || encontrada.localidadCensal() == null) {
            log.info("GeoRef devolvió la dirección {} sin ubicación o sin localidad: se considera no normalizable",
                    encontrada.nomenclatura());
            return Optional.empty();
        }

        DireccionNormalizada normalizada = new DireccionNormalizada(
                encontrada.nomenclatura(),
                nombre(encontrada.provincia()),
                nombre(encontrada.localidadCensal()),
                nombre(encontrada.departamento()),
                ubicacion.lat(),
                ubicacion.lon());
        log.info("Dirección normalizada por GeoRef: {} (lat {}, lon {})",
                normalizada.nomenclatura(), normalizada.latitud(), normalizada.longitud());
        return Optional.of(normalizada);
    }

    private RespuestaDireccionesGeoRef consultarDirecciones(String direccion, String provincia, String ciudad) {
        // Los valores van como variables de la plantilla para que se encodeen siempre (espacios, acentos, &, +)
        Map<String, Object> variables = new HashMap<>();
        variables.put("direccion", direccion);
        variables.put("provincia", provincia);
        boolean filtrarPorCiudad = ciudad != null && !ciudad.isBlank();
        if (filtrarPorCiudad) {
            variables.put("ciudad", ciudad);
        }

        try {
            return restClient.get()
                    .uri(uri -> {
                        uri.path(RUTA_DIRECCIONES)
                                .queryParam("direccion", "{direccion}")
                                .queryParam("provincia", "{provincia}");
                        if (filtrarPorCiudad) {
                            uri.queryParam("localidad_censal", "{ciudad}");
                        }
                        return uri.queryParam("max", 1).build(variables);
                    })
                    .retrieve()
                    .body(RespuestaDireccionesGeoRef.class);
        } catch (ResourceAccessException ex) {
            log.warn("GeoRef no respondió (timeout o error de conexión) al normalizar {}: {}", direccion, ex.getMessage());
            throw new ServicioExternoNoDisponibleException(MENSAJE_NO_DISPONIBLE, ex);
        } catch (HttpServerErrorException ex) {
            log.warn("GeoRef respondió {} al normalizar {}", ex.getStatusCode(), direccion);
            throw new ServicioExternoNoDisponibleException(MENSAJE_NO_DISPONIBLE, ex);
        } catch (HttpClientErrorException ex) {
            // Un 4xx de GeoRef significa que armamos mal la request: es un bug nuestro
            log.error("GeoRef rechazó la consulta con {} al normalizar {}: {}",
                    ex.getStatusCode(), direccion, ex.getResponseBodyAsString(), ex);
            throw new ServicioExternoNoDisponibleException(MENSAJE_NO_DISPONIBLE, ex);
        } catch (RestClientException ex) {
            // Por ejemplo, una respuesta que no se puede deserializar
            log.error("Error inesperado al consultar GeoRef para normalizar {}", direccion, ex);
            throw new ServicioExternoNoDisponibleException(MENSAJE_NO_DISPONIBLE, ex);
        }
    }

    private static String nombre(EntidadGeoRef entidad) {
        return entidad != null ? entidad.nombre() : null;
    }

    private static SimpleClientHttpRequestFactory crearFabricaConTimeouts(GeoRefProperties propiedades) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(propiedades.connectTimeoutMs());
        fabrica.setReadTimeout(propiedades.readTimeoutMs());
        return fabrica;
    }
}
