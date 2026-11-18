package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.dto.CrearPersonaRequest;
import com.rovedasa.catalogo.dto.PersonaResponse;
import com.rovedasa.catalogo.exception.RecursoDuplicadoException;
import com.rovedasa.catalogo.mapper.PersonaMapper;
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.repository.PersonaRepository;
import com.rovedasa.catalogo.util.NormalizadorTexto;
import com.rovedasa.catalogo.validation.KeycloakUserIdSegunRolesValidator;
import com.rovedasa.catalogo.validation.ValidadorCuit;
import com.rovedasa.commons.exception.OperacionInvalidaException;
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
        String keycloakUserId = normalizarKeycloakUserId(solicitud.keycloakUserId());
        String email = normalizarEmail(solicitud.email());

        // B4: el mensaje de la excepción lo loguea el handler y lo ve el cliente, así que no lleva el dato;
        // el log de acá lo lleva enmascarado para poder rastrear el intento
        if (personaRepository.existsByCuitCuil(cuitCuil)) {
            log.warn("Alta rechazada: ya existe una persona con CUIT/CUIL {}", ValidadorCuit.enmascarar(cuitCuil));
            throw new RecursoDuplicadoException("Ya existe una persona con ese CUIT/CUIL");
        }
        if (keycloakUserId != null && personaRepository.existsByKeycloakUserId(keycloakUserId)) {
            throw new RecursoDuplicadoException("Ya existe una persona vinculada al usuario de Keycloak " + keycloakUserId);
        }
        if (email != null && personaRepository.existsByEmail(email)) {
            log.warn("Alta rechazada: ya existe una persona con el email {}", enmascararEmail(email));
            throw new RecursoDuplicadoException("Ya existe una persona con ese email");
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

    /**
     * Idempotente: si la persona ya tiene el rol, no hace nada y devuelve el estado actual.
     * RN-17: PROPIETARIO e INQUILINO exigen que la persona esté vinculada a su usuario de Keycloak. Si no lo
     * está (por ejemplo un GARANTE dado de alta sin usuario), el keycloakUserId se toma del body: tiene que ser
     * un UUID y no puede estar vinculado a otra persona. En cualquier otro caso el keycloakUserId del body se ignora.
     */
    @Transactional
    public PersonaResponse agregarRol(Long id, TipoPersonaEnum rol, String keycloakUserIdSolicitado) {
        Persona persona = buscar(id);
        if (persona.tieneRol(rol)) {
            log.debug("La persona ID: {} ya tenía el rol {}: no se modifica", id, rol);
            return PersonaMapper.aResponse(persona);
        }
        if (rol.requiereUsuarioKeycloak() && persona.getKeycloakUserId() == null) {
            persona.setKeycloakUserId(validarKeycloakUserIdNuevo(id, rol, keycloakUserIdSolicitado));
        }
        persona.agregarRol(rol);
        log.info("Rol {} agregado a la persona ID: {}", rol, id);
        return PersonaMapper.aResponse(persona);
    }

    private String validarKeycloakUserIdNuevo(Long id, TipoPersonaEnum rol, String keycloakUserIdSolicitado) {
        String keycloakUserId = normalizarKeycloakUserId(keycloakUserIdSolicitado);
        if (keycloakUserId == null) {
            throw new OperacionInvalidaException(String.format(
                    "No se puede agregar el rol %s a la persona ID %d: no está vinculada a un usuario de Keycloak "
                            + "(keycloakUserId) y sin él no podría ver sus propiedades ni sus contratos. Indicá el "
                            + "keycloakUserId en el body", rol, id));
        }
        if (!KeycloakUserIdSegunRolesValidator.esUuid(keycloakUserId)) {
            throw new OperacionInvalidaException(KeycloakUserIdSegunRolesValidator.MENSAJE_NO_ES_UUID);
        }
        if (personaRepository.existsByKeycloakUserId(keycloakUserId)) {
            throw new RecursoDuplicadoException("Ya existe una persona vinculada al usuario de Keycloak " + keycloakUserId);
        }
        log.info("Persona ID: {} vinculada a su usuario de Keycloak para recibir el rol {}", id, rol);
        return keycloakUserId;
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

    // El claim sub de Keycloak viene en minúscula: se guarda igual para que el usuario encuentre sus datos
    // y para que la unicidad no dependa de mayúsculas
    private static String normalizarKeycloakUserId(String keycloakUserId) {
        String limpio = NormalizadorTexto.limpiarOpcional(keycloakUserId);
        return limpio != null ? limpio.toLowerCase(Locale.ROOT) : null;
    }

    // Para logs (dato personal): "juan.perez@mail.com" -> "j***@mail.com"
    static String enmascararEmail(String email) {
        int arroba = email.indexOf('@');
        if (arroba <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(arroba);
    }
}
