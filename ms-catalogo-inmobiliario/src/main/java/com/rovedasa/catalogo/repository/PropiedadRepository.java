package com.rovedasa.catalogo.repository;

import com.rovedasa.catalogo.model.Propiedad;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PropiedadRepository extends JpaRepository<Propiedad, Long> {

    // Detalle de una propiedad: trae las tres personas vinculadas en una sola consulta
    @EntityGraph(attributePaths = {"propietario", "inquilino", "garante"})
    Optional<Propiedad> findByCodigo(String codigo);

    // En los listados se trae el propietario (y sus roles, que son EAGER) en la misma consulta para evitar N+1
    @EntityGraph(attributePaths = {"propietario", "propietario.roles"})
    List<Propiedad> findByEstado(EstadoPropiedadEnum estado);

    // Filtro de seguridad del rol PROPIETARIO (CU-08): la identidad sale del claim sub del JWT
    @EntityGraph(attributePaths = {"propietario", "propietario.roles"})
    List<Propiedad> findByPropietario_KeycloakUserId(String keycloakUserId);

    @EntityGraph(attributePaths = {"propietario", "propietario.roles"})
    List<Propiedad> findByPropietario_Id(Long propietarioId);
}
