package com.rovedasa.contratos.repository;

import com.rovedasa.contratos.model.IndiceCache;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IndiceCacheRepository extends JpaRepository<IndiceCache, Long> {

    Optional<IndiceCache> findByTipoIndiceAndFecha(TipoIndiceEnum tipoIndice, LocalDate fecha);

    /**
     * Valores guardados de un índice dentro de un rango de fechas, del más reciente al más viejo
     * (mismo orden en que los devuelve el BCRA).
     */
    @Query("SELECT i FROM IndiceCache i WHERE i.tipoIndice = :tipo "
            + "AND i.fecha BETWEEN :desde AND :hasta ORDER BY i.fecha DESC")
    List<IndiceCache> buscarEntreFechas(@Param("tipo") TipoIndiceEnum tipo,
                                        @Param("desde") LocalDate desde,
                                        @Param("hasta") LocalDate hasta);

    /**
     * Valores guardados de un índice entre 'desde' y 'hasta', del más reciente al más viejo.
     * Con PageRequest.of(0, 1) trae solo el último publicado anterior a 'hasta', pero nunca uno
     * anterior a 'desde': así el caché de contingencia tiene un límite de antigüedad.
     */
    @Query("SELECT i FROM IndiceCache i WHERE i.tipoIndice = :tipo "
            + "AND i.fecha BETWEEN :desde AND :hasta ORDER BY i.fecha DESC")
    List<IndiceCache> buscarUltimoEntre(@Param("tipo") TipoIndiceEnum tipo,
                                        @Param("desde") LocalDate desde,
                                        @Param("hasta") LocalDate hasta,
                                        Pageable pagina);

    /** Actualiza el valor de un (tipo, fecha) que ya estaba guardado. Devuelve cuántas filas cambió (0 o 1). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE IndiceCache i SET i.valor = :valor, i.fechaConsulta = :fechaConsulta "
            + "WHERE i.tipoIndice = :tipo AND i.fecha = :fecha")
    int actualizarValor(@Param("tipo") TipoIndiceEnum tipo,
                        @Param("fecha") LocalDate fecha,
                        @Param("valor") BigDecimal valor,
                        @Param("fechaConsulta") LocalDateTime fechaConsulta);

    /**
     * Inserta un valor si todavía no existe ese (tipo_indice, fecha). SQL nativo (Apunte 16): si otra
     * transacción lo insertó al mismo tiempo, ON CONFLICT DO NOTHING lo ignora en vez de fallar por la
     * clave única. Sin destino de conflicto porque así lo aceptan PostgreSQL y H2 (modo PostgreSQL).
     * Devuelve cuántas filas insertó (0 o 1).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO indices_cache (tipo_indice, fecha, valor, fecha_consulta) "
            + "VALUES (:tipo, :fecha, :valor, :fechaConsulta) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertarSiNoExiste(@Param("tipo") String tipo,
                           @Param("fecha") LocalDate fecha,
                           @Param("valor") BigDecimal valor,
                           @Param("fechaConsulta") LocalDateTime fechaConsulta);
}
