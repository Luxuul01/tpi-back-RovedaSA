package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.model.PrecioReferenciaZona;
import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import com.rovedasa.catalogo.repository.PrecioReferenciaZonaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TasacionServiceTest {

    private static final BigDecimal SUPERFICIE_55 = new BigDecimal("55.00");

    // Mismos valores que el seed
    private final PrecioReferenciaZona nuevaCordoba = zona("Nueva Córdoba", -31.4245, -64.1870, 1200, "14000");
    private final PrecioReferenciaZona centro = zona("Centro", -31.4167, -64.1833, 1000, "12000");
    private final PrecioReferenciaZona guemes = zona("Güemes", -31.4280, -64.1950, 900, "12500");
    private final PrecioReferenciaZona cerroDeLasRosas = zona("Cerro de las Rosas", -31.3700, -64.2300, 1500, "13500");
    private final PrecioReferenciaZona cordobaDefault = zona("Córdoba (default)", null, null, null, "9000");

    @Mock
    private PrecioReferenciaZonaRepository precioReferenciaZonaRepository;

    @InjectMocks
    private TasacionService tasacionService;

    @Test
    void puntoDentroDeUnaSolaZona() {
        cuandoLaCiudadTiene(List.of(nuevaCordoba, cerroDeLasRosas, cordobaDefault));

        Tasacion tasacion = tasacionService.tasar(-31.3720, -64.2310, "Córdoba", "Córdoba",
                TipologiaEnum.DEPARTAMENTO, SUPERFICIE_55);

        assertSame(cerroDeLasRosas, tasacion.zona());
    }

    @Test
    void puntoEnDosZonasSuperpuestasGanaLaMasCercana() {
        // Independencia 900 (coordenadas reales de GeoRef): ~282 m del centro de Nueva Córdoba y ~633 m del de
        // Güemes, así que cae en los dos círculos. Güemes va primero en la lista para que no gane por orden.
        cuandoLaCiudadTiene(List.of(guemes, centro, nuevaCordoba, cordobaDefault));
        double latitud = -31.4266835418118;
        double longitud = -64.1885105207543;
        assertTrue(guemes.contienePunto(latitud, longitud));
        assertTrue(nuevaCordoba.contienePunto(latitud, longitud));
        assertFalse(centro.contienePunto(latitud, longitud));

        Tasacion tasacion = tasacionService.tasar(latitud, longitud, "Córdoba", "Córdoba",
                TipologiaEnum.DEPARTAMENTO, SUPERFICIE_55);

        assertSame(nuevaCordoba, tasacion.zona());
    }

    @Test
    void puntoFueraDeTodaZonaUsaElValorPorDefecto() {
        // Av. Gauss 5800, Villa Belgrano (coordenadas reales de GeoRef): ~2,5 km del centro de Cerro de las Rosas
        cuandoLaCiudadTiene(List.of(nuevaCordoba, centro, cerroDeLasRosas, cordobaDefault));

        Tasacion tasacion = tasacionService.tasar(-31.3551323416974, -64.2502891790095, "Córdoba", "Córdoba",
                TipologiaEnum.DEPARTAMENTO, SUPERFICIE_55);

        assertSame(cordobaDefault, tasacion.zona());
        assertEquals(new BigDecimal("9000.00"), tasacion.precioSugeridoM2());
    }

    @Test
    void ciudadSinCoberturaDevuelveTasacionVacia() {
        when(precioReferenciaZonaRepository.findByProvinciaNormalizadaAndCiudadNormalizada("cordoba", "rio cuarto"))
                .thenReturn(List.of());

        Tasacion tasacion = tasacionService.tasar(-33.1232, -64.3493, "Córdoba", "Río Cuarto",
                TipologiaEnum.CASA, SUPERFICIE_55);

        assertFalse(tasacion.tieneCobertura());
        assertNull(tasacion.zona());
        assertNull(tasacion.precioSugeridoM2());
        assertNull(tasacion.canonBaseSugerido());
    }

    @Test
    void calculoConFactorYSuperficieComputable() {
        // CASA en Nueva Córdoba: 14000 × 1,10 = 15400,00 ARS/m²; 15400,00 × 90,00 m² = 1.386.000,00 ARS
        cuandoLaCiudadTiene(List.of(nuevaCordoba));

        Tasacion tasacion = tasacionService.tasar(-31.4245, -64.1870, "Córdoba", "Córdoba",
                TipologiaEnum.CASA, new BigDecimal("90.00"));

        assertEquals(new BigDecimal("15400.00"), tasacion.precioSugeridoM2());
        assertEquals(new BigDecimal("1386000.00"), tasacion.canonBaseSugerido());
    }

    @Test
    void calculoRedondeaHalfUpEnCadaPaso() {
        // PH: 12345,67 × 0,95 = 11728,3865 -> 11728,39
        // 11728,39 × 45,55 = 534228,1645 -> 534228,16
        // (sin redondear el paso intermedio daría 534228,01: el redondeo es por paso)
        PrecioReferenciaZona zonaConDecimales = zona("Zona decimal", -31.4245, -64.1870, 1200, "12345.67");
        cuandoLaCiudadTiene(List.of(zonaConDecimales));

        Tasacion tasacion = tasacionService.tasar(-31.4245, -64.1870, "Córdoba", "Córdoba",
                TipologiaEnum.PH, new BigDecimal("45.55"));

        assertEquals(new BigDecimal("11728.39"), tasacion.precioSugeridoM2());
        assertEquals(new BigDecimal("534228.16"), tasacion.canonBaseSugerido());
    }

    @Test
    void provinciaYCiudadLleganNormalizadasAlRepositorio() {
        cuandoLaCiudadTiene(List.of(cordobaDefault));

        tasacionService.tasar(-31.4245, -64.1870, "  CÓRDOBA ", "córdoba", TipologiaEnum.OFICINA, SUPERFICIE_55);

        verify(precioReferenciaZonaRepository).findByProvinciaNormalizadaAndCiudadNormalizada("cordoba", "cordoba");
    }

    private void cuandoLaCiudadTiene(List<PrecioReferenciaZona> zonas) {
        when(precioReferenciaZonaRepository.findByProvinciaNormalizadaAndCiudadNormalizada("cordoba", "cordoba"))
                .thenReturn(zonas);
    }

    private static PrecioReferenciaZona zona(String nombre, Double latitud, Double longitud, Integer radio,
            String valorM2) {
        return PrecioReferenciaZona.builder()
                .nombreZona(nombre)
                .provincia("Córdoba")
                .ciudad("Córdoba")
                .latitudCentro(latitud)
                .longitudCentro(longitud)
                .radioMetros(radio)
                .valorM2(new BigDecimal(valorM2))
                .puntajeZona(5)
                .fechaActualizacion(LocalDate.now())
                .build();
    }
}
