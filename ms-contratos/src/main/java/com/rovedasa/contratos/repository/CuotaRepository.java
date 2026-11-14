package com.rovedasa.contratos.repository;

import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.contratos.model.Cuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CuotaRepository extends JpaRepository<Cuota, Long> {

    List<Cuota> findByContratoIdOrderByNroCuotaAsc(Long contratoId);

    List<Cuota> findByContratoIdAndEstadoOrderByNroCuotaAsc(Long contratoId, EstadoCuotaEnum estado);

    Optional<Cuota> findByContratoIdAndNroCuota(Long contratoId, Integer nroCuota);

    @Query("select c.contrato.id from Cuota c where c.id = :cuotaId")
    Optional<Long> findContratoIdByCuotaId(@Param("cuotaId") Long cuotaId);

    List<Cuota> findByContratoIdAndEstadoAndFechaVencimientoAfter(
            Long contratoId,
            EstadoCuotaEnum estado,
            LocalDate fechaCorte
    );
}
