package com.rovedasa.catalogo.repository;

import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonaRepository extends JpaRepository<Persona, Long> {

    boolean existsByCuitCuil(String cuitCuil);

    boolean existsByKeycloakUserId(String keycloakUserId);

    boolean existsByEmail(String email);

    Page<Persona> findByRolesContaining(TipoPersonaEnum rol, Pageable pageable);
}
