package com.rovedasa.contratos.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "contratos")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Contrato {

    private static final String PREFIJO_CODIGO = "CONT-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "nro_contrato", nullable = false, unique = true, length = 20)
    private String nroContrato;

    // Referencias lógicas hacia ms-catalogo-inmobiliario (Database per Service, sin FK física)
    @Column(name = "propiedad_id", nullable = false)
    private Long propiedadId;

    @Column(name = "propietario_id", nullable = false)
    private Long propietarioId;

    @Column(name = "inquilino_id", nullable = false)
    private Long inquilinoId;

    @Column(name = "garante_id", nullable = false)
    private Long garanteId;

    // Identificadores de Keycloak (claim sub) para filtrado de consultas por rol sin llamar al catálogo
    @Column(name = "propietario_keycloak_id", nullable = false, length = 100)
    private String propietarioKeycloakId;

    @Column(name = "inquilino_keycloak_id", nullable = false, length = 100)
    private String inquilinoKeycloakId;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin", nullable = false)
    private LocalDate fechaFin;

    @Column(name = "fecha_rescision")
    private LocalDate fechaRescision;

    @Column(name = "frecuencia_ajuste_meses", nullable = false)
    private Integer frecuenciaAjusteMeses;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_indice", nullable = false, length = 10)
    @Builder.Default
    private TipoIndiceEnum tipoIndice = TipoIndiceEnum.ICL;

    @Column(name = "monto_inicial", nullable = false, precision = 15, scale = 2)
    private BigDecimal montoInicial;

    @Column(name = "monto_actual", nullable = false, precision = 15, scale = 2)
    private BigDecimal montoActual;

    @Column(name = "porcentaje_mora_diaria", nullable = false, precision = 7, scale = 4)
    private BigDecimal porcentajeMoraDiaria;

    @Column(name = "dia_vencimiento_mes", nullable = false)
    private Integer diaVencimientoMes;

    @Column(name = "monto_senia", precision = 15, scale = 2)
    private BigDecimal montoSenia;

    @Column(name = "fecha_limite_senia")
    private LocalDate fechaLimiteSenia;

    @Column(name = "motivo_cancelacion", length = 500)
    private String motivoCancelacion;

    @Column(name = "senia_retenida")
    private Boolean seniaRetenida;

    @Column(name = "penalizacion_rescision", precision = 15, scale = 2)
    private BigDecimal penalizacionRescision;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    @Builder.Default
    private EstadoContratoEnum estado = EstadoContratoEnum.CREADO;

    // Optimistic locking: evita transiciones concurrentes contradictorias sobre el mismo contrato
    @Version
    @Setter(AccessLevel.NONE)
    private Long version;

    @CreationTimestamp
    @Setter(AccessLevel.NONE)
    @Column(name = "fecha_alta", nullable = false, updatable = false)
    private LocalDateTime fechaAlta;

    @UpdateTimestamp
    @Setter(AccessLevel.NONE)
    @Column(name = "fecha_modificacion")
    private LocalDateTime fechaModificacion;

    @OneToMany(mappedBy = "contrato", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("fechaAplicacion ASC")
    @Builder.Default
    private List<Ajuste> ajustes = new ArrayList<>();

    @OneToMany(mappedBy = "contrato", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("nroCuota ASC")
    @Builder.Default
    private List<Cuota> cuotas = new ArrayList<>();

    @PrePersist
    void inicializarValoresPorDefecto() {
        if (nroContrato == null || nroContrato.isBlank()) {
            nroContrato = PREFIJO_CODIGO + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        }
        if (estado == null) {
            estado = EstadoContratoEnum.CREADO;
        }
        if (tipoIndice == null) {
            tipoIndice = TipoIndiceEnum.ICL;
        }
        if (montoActual == null && montoInicial != null) {
            montoActual = montoInicial;
        }
    }

    public void agregarAjuste(Ajuste ajuste) {
        ajustes.add(ajuste);
        ajuste.setContrato(this);
    }

    public void agregarCuota(Cuota cuota) {
        cuotas.add(cuota);
        cuota.setContrato(this);
    }
}
