package com.rovedasa.contratos.repository;

import com.rovedasa.commons.enums.EstadoContratoEnum;
import com.rovedasa.contratos.model.Contrato;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContratoRepository extends JpaRepository<Contrato, Long> {

    Optional<Contrato> findByNroContrato(String nroContrato);

    List<Contrato> findByPropiedadId(Long propiedadId);

    List<Contrato> findByPropiedadIdAndEstado(Long propiedadId, EstadoContratoEnum estado);

    boolean existsByPropiedadIdAndEstadoIn(Long propiedadId, Collection<EstadoContratoEnum> estados);

    List<Contrato> findByEstado(EstadoContratoEnum estado);

    List<Contrato> findByInquilinoId(Long inquilinoId);

    List<Contrato> findByPropietarioId(Long propietarioId);

    List<Contrato> findByInquilinoKeycloakId(String inquilinoKeycloakId);

    List<Contrato> findByPropietarioKeycloakId(String propietarioKeycloakId);
}
