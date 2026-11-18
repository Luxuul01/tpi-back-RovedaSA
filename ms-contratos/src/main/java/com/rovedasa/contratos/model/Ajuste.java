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

    /** Inicio de la ventana del ajuste: H_{k-1} (fechaInicio + (k-1) · frecuencia meses). */
    @Column(name = "fecha_indice_desde", nullable = false)
    private LocalDate fechaIndiceDesde;

    /** Fin de la ventana del ajuste: H_k. Único por contrato: un solo ajuste por período (RN-11). */
    @Column(name = "fecha_indice_hasta", nullable = false)
    private LocalDate fechaIndiceHasta;

    /**
     * Fecha del valor del índice que se usó para "desde": puede ser anterior a fechaIndiceDesde si ese día no
     * hubo publicación (ICL: último publicado anterior). En IPC, el primer mes encadenado.
     */
    @Column(name = "fecha_valor_indice_desde")
    private LocalDate fechaValorIndiceDesde;

    /** Igual que fechaValorIndiceDesde, para "hasta". En IPC, el último mes encadenado. */
    @Column(name = "fecha_valor_indice_hasta")
    private LocalDate fechaValorIndiceHasta;

    // Escala 8: en IPC el "índice" encadenado es el propio coeficiente (base 1)
    @Column(name = "valor_indice_desde", nullable = false, precision = 19, scale = 8)
    private BigDecimal valorIndiceDesde;

    @Column(name = "valor_indice_hasta", nullable = false, precision = 19, scale = 8)
    private BigDecimal valorIndiceHasta;

    // Misma escala con la que CalculadoraAjuste calcula el coeficiente (8 decimales)
    @Column(name = "coeficiente", nullable = false, precision = 19, scale = 8)
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
