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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

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

    // Fecha (del Clock) en que se generó la cuota. Si se emite después de su vencimiento (contrato que se pone
    // al día, ajuste demorado por el índice) la mora corre desde acá y no desde el vencimiento.
    // Nullable: las cuotas generadas antes de agregar la columna no la tienen y corren desde el vencimiento.
    @Column(name = "fecha_emision")
    private LocalDate fechaEmision;

    // Optimistic locking: dos pagos concurrentes de la misma cuota no pueden confirmarse los dos.
    // El default 0 permite que ddl-auto=update agregue la columna a una tabla con filas.
    @Version
    @Setter(AccessLevel.NONE)
    @ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Indica si la cuota está impaga y vencida en la fecha de referencia.
     * Solo una cuota PENDIENTE puede estar vencida: las PAGADAS y ANULADAS no.
     */
    public boolean estaVencida(LocalDate fechaReferencia) {
        return estado == EstadoCuotaEnum.PENDIENTE
                && fechaReferencia != null
                && fechaReferencia.isAfter(fechaVencimiento);
    }

    /**
     * Fecha hasta la que corre la mora (RN-13 y RN-18):
     * PENDIENTE hasta la fecha de referencia; PAGADA hasta la fecha de pago (queda fija),
     * o hasta la fecha de referencia si es anterior al pago; ANULADA no genera mora (null).
     */
    LocalDate fechaCorteMora(LocalDate fechaReferencia) {
        if (fechaReferencia == null || estado == null) {
            return null;
        }
        return switch (estado) {
            case PENDIENTE -> fechaReferencia;
            case PAGADA -> fechaPago == null || fechaReferencia.isBefore(fechaPago) ? fechaReferencia : fechaPago;
            case ANULADA -> null;
        };
    }

    /**
     * Fecha desde la que corre la mora: max(fechaVencimiento, fechaEmision). Una cuota emitida tarde no nace
     * con mora retroactiva; sin fecha de emisión (datos viejos) corre desde el vencimiento.
     */
    public LocalDate fechaInicioMora() {
        return fechaEmision != null && fechaEmision.isAfter(fechaVencimiento) ? fechaEmision : fechaVencimiento;
    }

    /**
     * Días de atraso a la fecha de referencia: desde {@link #fechaInicioMora()} hasta
     * {@link #fechaCorteMora(LocalDate)}. Nunca negativo.
     */
    public long calcularDiasAtraso(LocalDate fechaReferencia) {
        LocalDate corte = fechaCorteMora(fechaReferencia);
        LocalDate inicio = fechaInicioMora();
        if (corte == null || !corte.isAfter(inicio)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(inicio, corte);
    }

    /**
     * Calcula el recargo por mora diaria de forma dinámica (no persistida):
     * Mora = montoBase * (porcentajeDiario / 100) * diasAtraso, redondeada HALF_UP a 2 decimales
     * una sola vez al final (sin redondeos intermedios).
     */
    public BigDecimal calcularMora(LocalDate fechaReferencia, BigDecimal porcentajeDiario) {
        long diasAtraso = calcularDiasAtraso(fechaReferencia);
        if (diasAtraso == 0 || porcentajeDiario == null || porcentajeDiario.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return montoBase
                .multiply(porcentajeDiario)
                .multiply(BigDecimal.valueOf(diasAtraso))
                .divide(CIEN, 2, RoundingMode.HALF_UP);
    }

    public BigDecimal calcularTotalConMora(LocalDate fechaReferencia, BigDecimal porcentajeDiario) {
        return montoBase.add(calcularMora(fechaReferencia, porcentajeDiario)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Registra el pago (RN-18): PENDIENTE -> PAGADA con su fecha de pago.
     * Las validaciones de estado y de fecha las hace CuotaService antes de llamar.
     */
    public void registrarPago(LocalDate fechaPago) {
        this.estado = EstadoCuotaEnum.PAGADA;
        this.fechaPago = fechaPago;
    }

    public void anular() {
        if (this.estado == EstadoCuotaEnum.PENDIENTE) {
            this.estado = EstadoCuotaEnum.ANULADA;
        }
    }
}
