package com.rovedasa.contratos.model;

import com.rovedasa.contratos.dto.OrigenDatoEnum;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "ajustes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ajuste_contrato_fecha_hasta",
                columnNames = { "contrato_id", "fecha_indice_hasta" }
        ))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Ajuste {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contrato_id", nullable = false)
    private Contrato contrato;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_indice", nullable = false, length = 10)
    @Builder.Default
    private TipoIndiceEnum tipoIndice = TipoIndiceEnum.ICL;

    @Column(name = "fecha_aplicacion", nullable = false)
    private LocalDate fechaAplicacion;

    @Column(name = "fecha_indice_desde", nullable = false)
    private LocalDate fechaIndiceDesde;

    @Column(name = "fecha_indice_hasta", nullable = false)
    private LocalDate fechaIndiceHasta;

    @Column(name = "valor_indice_desde", nullable = false, precision = 19, scale = 6)
    private BigDecimal valorIndiceDesde;

    @Column(name = "valor_indice_hasta", nullable = false, precision = 19, scale = 6)
    private BigDecimal valorIndiceHasta;

    @Column(name = "coeficiente", nullable = false, precision = 19, scale = 6)
    private BigDecimal coeficiente;

    @Column(name = "monto_anterior", nullable = false, precision = 15, scale = 2)
    private BigDecimal montoAnterior;

    @Column(name = "monto_nuevo", nullable = false, precision = 15, scale = 2)
    private BigDecimal montoNuevo;

    @Column(name = "porcentaje_variacion", nullable = false, precision = 9, scale = 4)
    private BigDecimal porcentajeVariacion;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", length = 30)
    @Builder.Default
    private OrigenDatoEnum origen = OrigenDatoEnum.BCRA;
}
