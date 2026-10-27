package com.rovedasa.catalogo.model;

import com.rovedasa.catalogo.model.enums.TipologiaEnum;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "propiedades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Propiedad {

    private static final String PREFIJO_CODIGO = "PROP-";
    private static final BigDecimal PONDERACION_SUPERFICIE_DESCUBIERTA = new BigDecimal("0.5");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String codigo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipologiaEnum tipologia;

    // Dirección cruda tal como la ingresa el administrador
    @Column(nullable = false)
    private String calle;

    @Column(nullable = false, length = 20)
    private String numero;

    @Setter
    @Column(length = 10)
    private String piso;

    @Setter
    @Column(length = 10)
    private String departamento;

    // Informativo, cargado a mano (GeoRef no lo devuelve)
    @Setter
    private String barrio;

    @Column(nullable = false, length = 100)
    private String ciudad;

    @Column(nullable = false, length = 100)
    private String provincia;

    @Setter
    @Column(length = 10)
    private String codigoPostal;

    // Datos devueltos por GeoRef al normalizar la dirección (ver aplicarNormalizacion)
    private String direccionNormalizada;

    private Double latitud;

    private Double longitud;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal superficieTotalM2;

    @Column(precision = 10, scale = 2)
    private BigDecimal superficieCubiertaM2;

    // Resultado de la tasación (ver aplicarTasacion)
    @Column(precision = 15, scale = 2)
    private BigDecimal precioSugeridoM2;

    @Column(precision = 15, scale = 2)
    private BigDecimal canonBaseSugerido;

    // Zona con la que se tasó la propiedad (trazabilidad del precio sugerido)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zona_referencia_id")
    private PrecioReferenciaZona zonaReferencia;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "propietario_id", nullable = false)
    private Persona propietario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquilino_id")
    private Persona inquilino;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "garante_id")
    private Persona garante;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoPropiedadEnum estado = EstadoPropiedadEnum.DISPONIBLE;

    // Optimistic locking: evita que dos reservas concurrentes pisen el estado
    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime fechaAlta;

    @UpdateTimestamp
    private LocalDateTime fechaModificacion;

    @Builder
    private Propiedad(String codigo, TipologiaEnum tipologia, String calle, String numero, String piso,
            String departamento, String barrio, String ciudad, String provincia, String codigoPostal,
            BigDecimal superficieTotalM2, BigDecimal superficieCubiertaM2, Persona propietario) {
        if (superficieTotalM2 != null && superficieCubiertaM2 != null
                && superficieCubiertaM2.compareTo(superficieTotalM2) > 0) {
            throw new OperacionInvalidaException(String.format(
                    "La superficie cubierta (%s m²) no puede ser mayor que la superficie total (%s m²)",
                    superficieCubiertaM2, superficieTotalM2));
        }
        this.codigo = codigo;
        this.tipologia = tipologia;
        this.calle = calle;
        this.numero = numero;
        this.piso = piso;
        this.departamento = departamento;
        this.barrio = barrio;
        this.ciudad = ciudad;
        this.provincia = provincia;
        this.codigoPostal = codigoPostal;
        this.superficieTotalM2 = superficieTotalM2;
        this.superficieCubiertaM2 = superficieCubiertaM2;
        this.propietario = propietario;
        this.estado = EstadoPropiedadEnum.DISPONIBLE;
    }

    @PrePersist
    void generarCodigoSiFalta() {
        if (codigo == null) {
            codigo = PREFIJO_CODIGO + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        }
    }

    /**
     * Guarda el resultado de GeoRef. Provincia y ciudad se reemplazan por los nombres oficiales
     * para que la tasación y las búsquedas trabajen siempre con la misma grafía.
     */
    public void aplicarNormalizacion(String direccionNormalizada, String provinciaOficial, String ciudadOficial,
            Double latitud, Double longitud) {
        exigirTexto(direccionNormalizada, "direccionNormalizada");
        exigirTexto(provinciaOficial, "provinciaOficial");
        exigirTexto(ciudadOficial, "ciudadOficial");
        this.direccionNormalizada = direccionNormalizada;
        this.provincia = provinciaOficial;
        this.ciudad = ciudadOficial;
        this.latitud = latitud;
        this.longitud = longitud;
    }

    // Los tres valores pueden ser nulos cuando la ciudad no tiene cobertura de precios de referencia
    public void aplicarTasacion(PrecioReferenciaZona zona, BigDecimal precioSugeridoM2, BigDecimal canonBaseSugerido) {
        this.zonaReferencia = zona;
        this.precioSugeridoM2 = precioSugeridoM2;
        this.canonBaseSugerido = canonBaseSugerido;
    }

    /**
     * Superficie homogeneizada: lo cubierto computa al 100% y lo descubierto (patios, balcones,
     * terrazas) pondera al 50%. Si no se informó la cubierta, se toma la total.
     */
    public BigDecimal superficieComputableM2() {
        if (superficieCubiertaM2 == null) {
            return superficieTotalM2.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal descubierta = superficieTotalM2.subtract(superficieCubiertaM2);
        return superficieCubiertaM2
                .add(descubierta.multiply(PONDERACION_SUPERFICIE_DESCUBIERTA))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public boolean estaDisponible() {
        return estado == EstadoPropiedadEnum.DISPONIBLE;
    }

    // DISPONIBLE -> RESERVADA. Inquilino y garante pueden venir nulos.
    public void reservar(Persona inquilino, Persona garante) {
        validarEstadoActual("reservar", EstadoPropiedadEnum.DISPONIBLE);
        this.inquilino = inquilino;
        this.garante = garante;
        this.estado = EstadoPropiedadEnum.RESERVADA;
    }

    // RESERVADA -> ALQUILADA. Exige inquilino asignado.
    public void alquilar() {
        validarEstadoActual("alquilar", EstadoPropiedadEnum.RESERVADA);
        if (inquilino == null) {
            throw new OperacionInvalidaException(String.format(
                    "No se puede alquilar la propiedad %s: no tiene un inquilino asignado", codigo));
        }
        this.estado = EstadoPropiedadEnum.ALQUILADA;
    }

    // RESERVADA o ALQUILADA -> DISPONIBLE. Desvincula inquilino y garante.
    public void liberar() {
        validarEstadoActual("liberar", EstadoPropiedadEnum.RESERVADA, EstadoPropiedadEnum.ALQUILADA);
        this.inquilino = null;
        this.garante = null;
        this.estado = EstadoPropiedadEnum.DISPONIBLE;
    }

    // DISPONIBLE -> INACTIVA
    public void inactivar() {
        validarEstadoActual("inactivar", EstadoPropiedadEnum.DISPONIBLE);
        this.estado = EstadoPropiedadEnum.INACTIVA;
    }

    // INACTIVA -> DISPONIBLE
    public void reactivar() {
        validarEstadoActual("reactivar", EstadoPropiedadEnum.INACTIVA);
        this.estado = EstadoPropiedadEnum.DISPONIBLE;
    }

    private void validarEstadoActual(String operacion, EstadoPropiedadEnum... estadosPermitidos) {
        if (Arrays.asList(estadosPermitidos).contains(estado)) {
            return;
        }
        String permitidos = Arrays.stream(estadosPermitidos)
                .map(Enum::name)
                .collect(Collectors.joining(" o "));
        throw new OperacionInvalidaException(String.format(
                "No se puede %s la propiedad %s: su estado actual es %s y la operación requiere %s",
                operacion, codigo, estado, permitidos));
    }

    // Error de programación (no de negocio): quien llama tiene que pasar los datos de GeoRef completos
    private static void exigirTexto(String valor, String nombreCampo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("El campo " + nombreCampo + " es obligatorio para aplicar la normalización");
        }
    }
}
