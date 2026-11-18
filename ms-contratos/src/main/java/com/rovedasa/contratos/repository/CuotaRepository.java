package com.rovedasa.contratos.repository;

import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.contratos.model.Cuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CuotaRepository extends JpaRepository<Cuota, Long> {

    List<Cuota> findByContratoIdOrderByNroCuotaAsc(Long contratoId);

    List<Cuota> findByContratoIdAndEstadoOrderByNroCuotaAsc(Long contratoId, EstadoCuotaEnum estado);

    Optional<Cuota> findByContratoIdAndNroCuota(Long contratoId, Integer nroCuota);

}
