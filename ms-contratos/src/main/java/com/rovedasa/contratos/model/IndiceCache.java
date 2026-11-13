package com.rovedasa.contratos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Caché local de contingencia de los índices del BCRA (RNF-03).
 *
 * Generaliza la entidad IndiceIclCache del diagrama de clases para guardar
 * tanto el ICL como el IPC: cada valor que se obtiene del BCRA se persiste acá
 * y, si el BCRA no responde, el servicio de índices lee desde esta tabla.
 */
@Entity
@Table(name = "indices_cache",
        uniqueConstraints = @UniqueConstraint(columnNames = { "tipo_indice", "fecha" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndiceCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_indice", nullable = false, length = 10)
    private TipoIndiceEnum tipoIndice;

    @Column(name = "fecha", nullable = false)
    private LocalDate fecha;

    @Column(name = "valor", nullable = false, precision = 19, scale = 8)
    private BigDecimal valor;

    @Column(name = "fecha_consulta", nullable = false)
    private LocalDateTime fechaConsulta;
}
