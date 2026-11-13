package com.rovedasa.contratos.repository;

import com.rovedasa.contratos.model.Ajuste;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AjusteRepository extends JpaRepository<Ajuste, Long> {

    List<Ajuste> findByContratoIdOrderByFechaAplicacionAsc(Long contratoId);

    Optional<Ajuste> findFirstByContratoIdOrderByFechaIndiceHastaDesc(Long contratoId);

    boolean existsByContratoIdAndFechaIndiceHasta(Long contratoId, LocalDate fechaIndiceHasta);
}
