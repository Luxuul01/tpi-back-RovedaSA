package com.rovedasa.catalogo.mapper;

import com.rovedasa.catalogo.dto.PersonaResponse;
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;

import java.util.EnumSet;
import java.util.Set;

/**
 * Conversión manual de Persona a DTO. Métodos estáticos: es una función pura, sin dependencias.
 * Los roles son LAZY: hay que llamarlo dentro de una transacción.
 */
public final class PersonaMapper {

    private PersonaMapper() {
    }

    public static PersonaResponse aResponse(Persona persona) {
        return new PersonaResponse(
                persona.getId(),
                persona.getCuitCuil(),
                persona.getNombre(),
                persona.getApellido(),
                persona.getNombreCompleto(),
                persona.getEmail(),
                persona.getTelefono(),
                persona.getKeycloakUserId(),
                copiarRoles(persona.getRoles()),
                persona.getIngresosMensualesDeclarados(),
                persona.getSituacionCrediticiaBcra(),
                persona.getChequesRechazados(),
                persona.getFechaConsultaBcra(),
                persona.getFechaAlta());
    }

    // Copia en un EnumSet: inicializa la colección LAZY y deja los roles en orden de declaración
    private static Set<TipoPersonaEnum> copiarRoles(Set<TipoPersonaEnum> roles) {
        Set<TipoPersonaEnum> copia = EnumSet.noneOf(TipoPersonaEnum.class);
        copia.addAll(roles);
        return copia;
    }
}
