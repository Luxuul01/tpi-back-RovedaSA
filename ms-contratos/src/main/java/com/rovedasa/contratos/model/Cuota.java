package com.rovedasa.contratos.model;

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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "cuotas",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cuota_contrato_nro",
                columnNames = { "contrato_id", "nro_cuota" }
        ))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Cuota {

    private static final BigDecimal CIEN = new BigDecimal("100");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contrato_id", nullable = false)
    private Contrato contrato;

    // Nulo para las cuotas del primer tramo (originadas en la activación del contrato)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ajuste_id")
    private Ajuste ajuste;

    @Column(name = "nro_cuota", nullable = false)
    private Integer nroCuota;

    @Column(name = "periodo_mes", nullable = false)
    private Integer periodoMes;

    @Column(name = "periodo_anio", nullable = false)
    private Integer periodoAnio;

    @Column(name = "fecha_vencimiento", nullable = false)
    private LocalDate fechaVencimiento;

    @Column(name = "monto_base", nullable = false, precision = 15, scale = 2)
    private BigDecimal montoBase;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    @Builder.Default
    private EstadoCuotaEnum estado = EstadoCuotaEnum.PENDIENTE;

    @Column(name = "fecha_pago")
    private LocalDate fechaPago;

    /**
     * Indica si la cuota está vencida en la fecha de referencia.
     * Solo las cuotas PENDIENTE generan mora: las PAGADAS y ANULADAS no
     * acumulan recargo (RN-13, F-17 y C-06).
     */
    public boolean estaVencida(LocalDate fechaReferencia) {
        return estado == EstadoCuotaEnum.PENDIENTE
                && fechaReferencia != null
                && fechaReferencia.isAfter(fechaVencimiento);
    }

    /**
     * Calcula el recargo por mora diaria de forma dinámica (no persistida):
     * Mora = montoBase * (porcentajeDiario / 100) * diasAtraso
     */
    public BigDecimal calcularMora(LocalDate fechaReferencia, BigDecimal porcentajeDiario) {
        if (!estaVencida(fechaReferencia) || porcentajeDiario == null || porcentajeDiario.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        long diasAtraso = ChronoUnit.DAYS.between(fechaVencimiento, fechaReferencia);
        return montoBase
                .multiply(porcentajeDiario)
                .divide(CIEN, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(diasAtraso))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calcularTotalConMora(LocalDate fechaReferencia, BigDecimal porcentajeDiario) {
        return montoBase.add(calcularMora(fechaReferencia, porcentajeDiario)).setScale(2, RoundingMode.HALF_UP);
    }

    public void anular() {
        if (this.estado == EstadoCuotaEnum.PENDIENTE) {
            this.estado = EstadoCuotaEnum.ANULADA;
        }
    }
}
