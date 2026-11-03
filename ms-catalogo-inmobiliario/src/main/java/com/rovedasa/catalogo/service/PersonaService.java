package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.dto.CrearPersonaRequest;
import com.rovedasa.catalogo.dto.PersonaResponse;
import com.rovedasa.catalogo.exception.RecursoDuplicadoException;
import com.rovedasa.catalogo.mapper.PersonaMapper;
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.repository.PersonaRepository;
import com.rovedasa.catalogo.util.NormalizadorTexto;
import com.rovedasa.catalogo.validation.ValidadorCuit;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Alta y consulta de personas (CU-02). El mapeo a DTO se hace dentro de la transacción porque
 * los roles son LAZY y open-in-view está deshabilitado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaService {

    private final PersonaRepository personaRepository;

    @Transactional
    public PersonaResponse registrar(CrearPersonaRequest solicitud) {
        String cuitCuil = ValidadorCuit.normalizar(solicitud.cuitCuil());
        String keycloakUserId = NormalizadorTexto.limpiarOpcional(solicitud.keycloakUserId());
        String email = normalizarEmail(solicitud.email());

        if (personaRepository.existsByCuitCuil(cuitCuil)) {
            throw new RecursoDuplicadoException("Ya existe una persona con CUIT/CUIL " + cuitCuil);
        }
        if (keycloakUserId != null && personaRepository.existsByKeycloakUserId(keycloakUserId)) {
            throw new RecursoDuplicadoException("Ya existe una persona vinculada al usuario de Keycloak " + keycloakUserId);
        }
        if (email != null && personaRepository.existsByEmail(email)) {
            throw new RecursoDuplicadoException("Ya existe una persona con el email " + email);
        }

        Persona persona = Persona.builder()
                .cuitCuil(cuitCuil)
                .nombre(solicitud.nombre().trim())
                .apellido(solicitud.apellido().trim())
                .email(email)
                .telefono(NormalizadorTexto.limpiarOpcional(solicitud.telefono()))
                .keycloakUserId(keycloakUserId)
                .roles(solicitud.roles())
                .ingresosMensualesDeclarados(solicitud.ingresosMensualesDeclarados())
                .build();
        Persona guardada = personaRepository.save(persona);

        // Sin CUIT ni ingresos en el log: son datos personales
        log.info("Persona registrada con ID: {} y roles: {}", guardada.getId(), guardada.getRoles());
        return PersonaMapper.aResponse(guardada);
    }

    @Transactional(readOnly = true)
    public PersonaResponse obtener(Long id) {
        return PersonaMapper.aResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public Page<PersonaResponse> listar(TipoPersonaEnum rol, Pageable paginacion) {
        Page<Persona> personas = rol == null
                ? personaRepository.findAll(paginacion)
                : personaRepository.findByRolesContaining(rol, paginacion);
        return personas.map(PersonaMapper::aResponse);
    }

    // Idempotente: si la persona ya tiene el rol, no hace nada y devuelve el estado actual
    @Transactional
    public PersonaResponse agregarRol(Long id, TipoPersonaEnum rol) {
        Persona persona = buscar(id);
        if (persona.tieneRol(rol)) {
            log.debug("La persona ID: {} ya tenía el rol {}: no se modifica", id, rol);
        } else {
            persona.agregarRol(rol);
            log.info("Rol {} agregado a la persona ID: {}", rol, id);
        }
        return PersonaMapper.aResponse(persona);
    }

    private Persona buscar(Long id) {
        return personaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la persona con ID " + id));
    }

    // El email se guarda en minúscula para que la unicidad no dependa de mayúsculas
    private static String normalizarEmail(String email) {
        String limpio = NormalizadorTexto.limpiarOpcional(email);
        return limpio != null ? limpio.toLowerCase(Locale.ROOT) : null;
    }
}
