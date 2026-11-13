package com.rovedasa.contratos.repository;

import com.rovedasa.contratos.model.IndiceCache;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
     * Valores guardados de un índice en o antes de una fecha, del más reciente al más viejo.
     * Con PageRequest.of(0, 1) trae solo el último publicado anterior a esa fecha (Alcance, sección 14).
     */
    @Query("SELECT i FROM IndiceCache i WHERE i.tipoIndice = :tipo "
            + "AND i.fecha <= :hasta ORDER BY i.fecha DESC")
    List<IndiceCache> buscarHasta(@Param("tipo") TipoIndiceEnum tipo,
                                  @Param("hasta") LocalDate hasta,
                                  Pageable pagina);
}
