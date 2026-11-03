package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.config.SecurityConfig;
import com.rovedasa.catalogo.dto.PropiedadResponse;
import com.rovedasa.catalogo.service.PropiedadService;
import com.rovedasa.commons.dto.ActualizarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seguridad de PropiedadController con la SecurityConfig real. jwt() arma el token ya validado,
 * así que no hace falta Keycloak; las authorities se pasan con el prefijo ROLE_ igual que las genera
 * el KeycloakRoleConverter de commons.
 */
@WebMvcTest(PropiedadController.class)
@Import(SecurityConfig.class)
class PropiedadControllerTest {

    private static final String SUB_PROPIETARIO = "00000000-0000-0000-0000-000000000002";
    private static final String CUERPO_RESERVAR = "{\"nuevoEstado\": \"RESERVADA\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PropiedadService propiedadService;

    // --- PATCH /api/propiedades/{id}/estado ---

    @Test
    void patchEstadoConAdministradorDevuelve200() throws Exception {
        when(propiedadService.actualizarEstado(eq(10L), any(ActualizarEstadoPropiedadDTO.class)))
                .thenReturn(PropiedadDTO.builder().id(10L).estado(EstadoPropiedadEnum.RESERVADA).build());

        mockMvc.perform(patchEstado(10L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.estado").value("RESERVADA"));
    }

    @Test
    void patchEstadoConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(patchEstado(10L).with(conRol("INQUILINO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void patchEstadoSinTokenDevuelve401() throws Exception {
        mockMvc.perform(patchEstado(10L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void patchEstadoConTransicionInvalidaDevuelve400() throws Exception {
        when(propiedadService.actualizarEstado(eq(10L), any(ActualizarEstadoPropiedadDTO.class)))
                .thenThrow(new OperacionInvalidaException("No se puede pasar de ALQUILADA a RESERVADA"));

        mockMvc.perform(patchEstado(10L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No se puede pasar de ALQUILADA a RESERVADA"));
    }

    @Test
    void patchEstadoDePropiedadInexistenteDevuelve404() throws Exception {
        when(propiedadService.actualizarEstado(eq(99L), any(ActualizarEstadoPropiedadDTO.class)))
                .thenThrow(new RecursoNoEncontradoException("No existe la propiedad con ID 99"));

        mockMvc.perform(patchEstado(99L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isNotFound());
    }

    // --- GET /api/propiedades/propias ---

    @Test
    void propiasConPropietarioDevuelve200YSoloSusPropiedades() throws Exception {
        when(propiedadService.listarPropias(SUB_PROPIETARIO))
                .thenReturn(List.of(propiedad(10L, "PROP-AAAA0001"), propiedad(11L, "PROP-AAAA0002")));

        mockMvc.perform(get("/api/propiedades/propias")
                        .with(jwt().jwt(token -> token.subject(SUB_PROPIETARIO))
                                .authorities(new SimpleGrantedAuthority("ROLE_PROPIETARIO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].codigo").value("PROP-AAAA0001"))
                .andExpect(jsonPath("$[1].codigo").value("PROP-AAAA0002"));

        // La identidad sale del sub del token y de ningún otro lado
        verify(propiedadService).listarPropias(SUB_PROPIETARIO);
        verifyNoMoreInteractions(propiedadService);
    }

    @Test
    void propiasSinPropiedadesDevuelveListaVacia() throws Exception {
        when(propiedadService.listarPropias(SUB_PROPIETARIO)).thenReturn(List.of());

        mockMvc.perform(get("/api/propiedades/propias")
                        .with(jwt().jwt(token -> token.subject(SUB_PROPIETARIO))
                                .authorities(new SimpleGrantedAuthority("ROLE_PROPIETARIO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // El @PreAuthorize del método reemplaza al de la clase: ADMINISTRADOR no entra a /propias
    @Test
    void propiasConAdministradorDevuelve403() throws Exception {
        mockMvc.perform(get("/api/propiedades/propias").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void propiasSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/api/propiedades/propias"))
                .andExpect(status().isUnauthorized());
    }

    // --- Datos de prueba ---

    private static MockHttpServletRequestBuilder patchEstado(Long id) {
        return patch("/api/propiedades/{id}/estado", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_RESERVAR);
    }

    private static RequestPostProcessor conRol(String rol) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    private static PropiedadResponse propiedad(Long id, String codigo) {
        return new PropiedadResponse(id, codigo, null, null, null, null, 1L, null, EstadoPropiedadEnum.DISPONIBLE,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }
}
