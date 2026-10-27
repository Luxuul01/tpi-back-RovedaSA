package com.rovedasa.catalogo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Valor de alquiler mensual de referencia por m² de una zona geográfica.
 * La zona se identifica por un centro y un radio, porque GeoRef no devuelve el barrio.
 * Si el centro es nulo, la fila es el valor por defecto (fallback) de toda la ciudad.
 */
@Entity
@Table(name = "precios_referencia_zona", uniqueConstraints = @UniqueConstraint(
        name = "uk_precio_zona_provincia_ciudad_nombre",
        columnNames = {"provincia", "ciudad", "nombre_zona"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrecioReferenciaZona {

    private static final double RADIO_TIERRA_METROS = 6_371_000d;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_zona", nullable = false, length = 100)
    private String nombreZona;

    @Column(nullable = false, length = 100)
    private String provincia;

    @Column(nullable = false, length = 100)
    private String ciudad;

    private Double latitudCentro;

    private Double longitudCentro;

    private Integer radioMetros;

    // ARS por m² por mes
    @Setter
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal valorM2;

    // De 1 a 10, es el "puntaje de zona" del CU-01
    @Setter
    @Column(nullable = false)
    private Integer puntajeZona;

    @Setter
    @Column(nullable = false)
    private LocalDate fechaActualizacion;

    @Builder
    private PrecioReferenciaZona(String nombreZona, String provincia, String ciudad, Double latitudCentro,
            Double longitudCentro, Integer radioMetros, BigDecimal valorM2, Integer puntajeZona,
            LocalDate fechaActualizacion) {
        this.nombreZona = nombreZona;
        this.provincia = provincia;
        this.ciudad = ciudad;
        this.latitudCentro = latitudCentro;
        this.longitudCentro = longitudCentro;
        this.radioMetros = radioMetros;
        this.valorM2 = valorM2;
        this.puntajeZona = puntajeZona;
        this.fechaActualizacion = fechaActualizacion;
    }

    public boolean esValorPorDefecto() {
        return latitudCentro == null || longitudCentro == null;
    }

    public boolean contienePunto(double latitud, double longitud) {
        if (esValorPorDefecto() || radioMetros == null) {
            return false;
        }
        return distanciaAlCentroMetros(latitud, longitud) <= radioMetros;
    }

    /**
     * Distancia en metros entre el punto y el centro de la zona, calculada con la fórmula de Haversine.
     */
    public double distanciaAlCentroMetros(double latitud, double longitud) {
        if (esValorPorDefecto()) {
            throw new IllegalStateException(
                    "La zona '" + nombreZona + "' es el valor por defecto de la ciudad y no tiene centro");
        }
        double deltaLatitud = Math.toRadians(latitud - latitudCentro);
        double deltaLongitud = Math.toRadians(longitud - longitudCentro);
        double a = Math.pow(Math.sin(deltaLatitud / 2), 2)
                + Math.cos(Math.toRadians(latitudCentro)) * Math.cos(Math.toRadians(latitud))
                * Math.pow(Math.sin(deltaLongitud / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return RADIO_TIERRA_METROS * c;
    }
}
