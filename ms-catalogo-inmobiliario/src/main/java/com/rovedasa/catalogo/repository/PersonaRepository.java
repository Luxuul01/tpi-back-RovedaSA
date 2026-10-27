package com.rovedasa.catalogo.repository;

import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonaRepository extends JpaRepository<Persona, Long> {

    Optional<Persona> findByCuitCuil(String cuitCuil);

    boolean existsByCuitCuil(String cuitCuil);

    Optional<Persona> findByKeycloakUserId(String keycloakUserId);

    List<Persona> findByRolesContaining(TipoPersonaEnum rol);
}
