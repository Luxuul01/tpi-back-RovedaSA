package com.rovedasa.contratos.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * DTO reducido para obtener los datos de una persona desde ms-catalogo-inmobiliario.
 * Se usa para extraer el keycloakUserId (claim sub) y validar roles al crear contratos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonaResumenDTO {
    private Long id;
    private String cuitCuil;
    private String nombre;
    private String apellido;
    private String keycloakUserId;
    private Set<String> roles;
}
