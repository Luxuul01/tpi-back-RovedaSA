package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import com.rovedasa.catalogo.repository.PrecioReferenciaZonaRepository;
import com.rovedasa.catalogo.util.NormalizadorTexto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Calcula el precio sugerido de una propiedad a partir de su ubicación (CU-01).
 *
 * Regla de selección de zona:
 * 1. Se traen todas las zonas de la ciudad (provincia y ciudad normalizadas, sin acentos ni mayúsculas).
 * 2. De las zonas cuyo círculo contiene el punto, gana la de MENOR distancia al centro: los círculos se
 *    superponen (ej. Nueva Córdoba y Güemes), y el centro más cercano es el que mejor representa al punto.
 * 3. Si ningún círculo contiene el punto, se usa la zona por defecto de la ciudad (fila sin centro).
 * 4. Si la ciudad no tiene ni zonas ni valor por defecto, se devuelve una tasación sin cobertura:
 *    el alta de la propiedad NO se bloquea, simplemente queda sin precio sugerido.
 *
 * Cálculo (escala 2 y HALF_UP en cada paso):
 *   precioSugeridoM2  = valorM2 de la zona × factor de ajuste de la tipología
 *   canonBaseSugerido = precioSugeridoM2 × superficie computable (homogeneizada)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TasacionService {

    private static final int ESCALA = 2;

    private final PrecioReferenciaZonaRepository precioReferenciaZonaRepository;

    public Tasacion tasar(double latitud, double longitud, String provincia, String ciudad,
            TipologiaEnum tipologia, BigDecimal superficieComputableM2) {
        String provinciaNormalizada = NormalizadorTexto.normalizar(provincia);
        String ciudadNormalizada = NormalizadorTexto.normalizar(ciudad);
        List<PrecioReferenciaZona> zonasDeLaCiudad = precioReferenciaZonaRepository
                .findByProvinciaNormalizadaAndCiudadNormalizada(provinciaNormalizada, ciudadNormalizada);

        Optional<PrecioReferenciaZona> zonaElegida = buscarZonaMasCercanaQueContiene(zonasDeLaCiudad, latitud, longitud)
                .or(() -> buscarZonaPorDefecto(zonasDeLaCiudad));

        if (zonaElegida.isEmpty()) {
            log.warn("Sin cobertura de precios de referencia para {}, {}: la propiedad queda sin precio sugerido",
                    ciudad, provincia);
            return Tasacion.sinCobertura();
        }

        PrecioReferenciaZona zona = zonaElegida.get();
        BigDecimal precioSugeridoM2 = zona.getValorM2()
                .multiply(tipologia.getFactorAjuste())
                .setScale(ESCALA, RoundingMode.HALF_UP);
        BigDecimal canonBaseSugerido = precioSugeridoM2
                .multiply(superficieComputableM2)
                .setScale(ESCALA, RoundingMode.HALF_UP);

        log.info("Tasación: zona {} ({} ARS/m²) × {} ({}) × {} m² -> {} ARS/m², canon sugerido {} ARS",
                zona.getNombreZona(), zona.getValorM2(), tipologia, tipologia.getFactorAjuste(),
                superficieComputableM2, precioSugeridoM2, canonBaseSugerido);
        return new Tasacion(zona, precioSugeridoM2, canonBaseSugerido);
    }

    // Paso 2: entre las zonas que contienen el punto, la de centro más cercano
    private Optional<PrecioReferenciaZona> buscarZonaMasCercanaQueContiene(List<PrecioReferenciaZona> zonas,
            double latitud, double longitud) {
        Optional<PrecioReferenciaZona> masCercana = zonas.stream()
                .filter(zona -> zona.contienePunto(latitud, longitud))
                .min(Comparator.comparingDouble(zona -> zona.distanciaAlCentroMetros(latitud, longitud)));
        masCercana.ifPresent(zona -> log.debug("Zona elegida por cercanía: {} a {} m del centro",
                zona.getNombreZona(), Math.round(zona.distanciaAlCentroMetros(latitud, longitud))));
        return masCercana;
    }

    // Paso 3: el valor por defecto de la ciudad viene en la misma lista (fila sin centro)
    private Optional<PrecioReferenciaZona> buscarZonaPorDefecto(List<PrecioReferenciaZona> zonas) {
        Optional<PrecioReferenciaZona> porDefecto = zonas.stream()
                .filter(PrecioReferenciaZona::esValorPorDefecto)
                .findFirst();
        porDefecto.ifPresent(zona -> log.debug("El punto no cae en ninguna zona: se usa el valor por defecto {}",
                zona.getNombreZona()));
        return porDefecto;
    }
}
