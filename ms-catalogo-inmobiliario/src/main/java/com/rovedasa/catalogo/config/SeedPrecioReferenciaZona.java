package com.rovedasa.catalogo.config;

import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import com.rovedasa.catalogo.repository.PrecioReferenciaZonaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Carga los precios de referencia por zona de la ciudad de Córdoba si la tabla está vacía.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedPrecioReferenciaZona implements CommandLineRunner {

    private static final String PROVINCIA = "Córdoba";
    private static final String CIUDAD = "Córdoba";

    private final PrecioReferenciaZonaRepository precioReferenciaZonaRepository;

    @Override
    @Transactional
    public void run(String... args) {
        long existentes = precioReferenciaZonaRepository.count();
        if (existentes > 0) {
            log.debug("Seed de zonas omitido: ya existen {} precios de referencia cargados", existentes);
            return;
        }

        LocalDate hoy = LocalDate.now();
        // Valores FICTICIOS de referencia para el TPI: no surgen de un relevamiento real del mercado
        List<PrecioReferenciaZona> zonas = List.of(
                zona("Nueva Córdoba", -31.4245, -64.1870, 1200, 14000, 9, hoy),
                zona("Centro", -31.4167, -64.1833, 1000, 12000, 8, hoy),
                zona("Güemes", -31.4280, -64.1950, 900, 12500, 8, hoy),
                zona("General Paz", -31.4100, -64.1640, 1200, 11500, 7, hoy),
                zona("Alberdi", -31.4080, -64.2000, 1100, 10000, 6, hoy),
                zona("Cerro de las Rosas", -31.3700, -64.2300, 1500, 13500, 9, hoy),
                // Sin centro ni radio: valor por defecto de la ciudad
                zona("Córdoba (default)", null, null, null, 9000, 5, hoy));

        precioReferenciaZonaRepository.saveAll(zonas);
        log.info("Seed de zonas completado: se cargaron {} precios de referencia para {}, {}",
                zonas.size(), CIUDAD, PROVINCIA);
    }

    private PrecioReferenciaZona zona(String nombre, Double latitud, Double longitud, Integer radioMetros,
            long valorM2, int puntaje, LocalDate fecha) {
        return PrecioReferenciaZona.builder()
                .nombreZona(nombre)
                .provincia(PROVINCIA)
                .ciudad(CIUDAD)
                .latitudCentro(latitud)
                .longitudCentro(longitud)
                .radioMetros(radioMetros)
                .valorM2(BigDecimal.valueOf(valorM2))
                .puntajeZona(puntaje)
                .fechaActualizacion(fecha)
                .build();
    }
}
