package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.config.SecurityConfig;
import com.rovedasa.catalogo.dto.ModificarPropiedadRequest;
import com.rovedasa.catalogo.dto.PropiedadResponse;
import com.rovedasa.catalogo.exception.ServicioExternoNoDisponibleException;
import com.rovedasa.catalogo.service.PropiedadService;
import com.rovedasa.commons.dto.ActualizarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private static final String MENSAJE_NO_AUTENTICADO =
            "Se requiere un token de acceso válido para acceder a este recurso";
    // Cuerpo válido: los 403 tienen que salir por el rol, no por la validación
    private static final String CUERPO_MODIFICAR = """
            {"tipologia": "CASA", "calle": "Independencia", "numero": "900", "ciudad": "Córdoba",
             "provincia": "Córdoba", "superficieTotalM2": 80, "superficieCubiertaM2": 60}
            """;

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

    // --- PUT /api/propiedades/{id} (F-06) ---

    @Test
    void putConAdministradorDevuelve200() throws Exception {
        when(propiedadService.modificar(eq(10L), any(ModificarPropiedadRequest.class)))
                .thenReturn(propiedad(10L, "PROP-AAAA0001"));

        mockMvc.perform(put(10L, CUERPO_MODIFICAR).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.codigo").value("PROP-AAAA0001"));

        ArgumentCaptor<ModificarPropiedadRequest> solicitud = ArgumentCaptor.forClass(ModificarPropiedadRequest.class);
        verify(propiedadService).modificar(eq(10L), solicitud.capture());
        assertEquals("Independencia", solicitud.getValue().calle());
        assertEquals(new BigDecimal("80"), solicitud.getValue().superficieTotalM2());
    }

    @Test
    void putConPropietarioDevuelve403() throws Exception {
        mockMvc.perform(put(10L, CUERPO_MODIFICAR).with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void putConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(put(10L, CUERPO_MODIFICAR).with(conRol("INQUILINO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void putSinTokenDevuelve401() throws Exception {
        mockMvc.perform(put(10L, CUERPO_MODIFICAR))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void putConCuerpoInvalidoDevuelve400ConLosCamposRechazados() throws Exception {
        // Sin calle y con superficie negativa
        String cuerpoInvalido = """
                {"tipologia": "CASA", "numero": "900", "ciudad": "Córdoba", "provincia": "Córdoba",
                 "superficieTotalM2": -5}
                """;

        mockMvc.perform(put(10L, cuerpoInvalido).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.calle").exists())
                .andExpect(jsonPath("$.validaciones.superficieTotalM2").exists());

        verifyNoInteractions(propiedadService);
    }

    // La regla por URL de SecurityConfig corta antes de que Spring MVC valide el body
    @Test
    void putConInquilinoYCuerpoInvalidoDevuelve403YNo400() throws Exception {
        String cuerpoInvalido = """
                {"tipologia": "CASA", "numero": "900", "ciudad": "Córdoba", "provincia": "Córdoba",
                 "superficieTotalM2": -5}
                """;

        mockMvc.perform(put(10L, cuerpoInvalido).with(conRol("INQUILINO")))
                .andExpect(status().isForbidden())
                // Mismo ApiError que el 403 de un @PreAuthorize, sin detalle de validación
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("No tenés permisos para realizar esta operación"))
                .andExpect(jsonPath("$.validaciones").doesNotExist());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void putSobrePropiedadReservadaDevuelve400ConElMensajeDelServicio() throws Exception {
        String mensaje = "No se puede modificar la propiedad PROP-AAAA0001: está RESERVADA y hay un contrato "
                + "que la referencia. Solo se pueden modificar propiedades DISPONIBLE o INACTIVA";
        when(propiedadService.modificar(eq(10L), any(ModificarPropiedadRequest.class)))
                .thenThrow(new OperacionInvalidaException(mensaje));

        mockMvc.perform(put(10L, CUERPO_MODIFICAR).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(mensaje));
    }

    @Test
    void putConGeoRefCaidoDevuelve503() throws Exception {
        when(propiedadService.modificar(eq(10L), any(ModificarPropiedadRequest.class)))
                .thenThrow(new ServicioExternoNoDisponibleException("GeoRef no disponible", null));

        mockMvc.perform(put(10L, CUERPO_MODIFICAR).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isServiceUnavailable());
    }

    // --- PATCH /api/propiedades/{id}/baja (F-06) ---

    @Test
    void patchBajaConAdministradorDevuelve200ConLaPropiedadInactiva() throws Exception {
        when(propiedadService.darDeBaja(10L)).thenReturn(propiedad(10L, "PROP-AAAA0001", EstadoPropiedadEnum.INACTIVA));

        mockMvc.perform(patch("/api/propiedades/{id}/baja", 10L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.estado").value("INACTIVA"));
    }

    @Test
    void patchBajaConPropietarioDevuelve403() throws Exception {
        mockMvc.perform(patch("/api/propiedades/{id}/baja", 10L).with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void patchBajaConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(patch("/api/propiedades/{id}/baja", 10L).with(conRol("INQUILINO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void patchBajaSinTokenDevuelve401() throws Exception {
        mockMvc.perform(patch("/api/propiedades/{id}/baja", 10L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void patchBajaDePropiedadNoDisponibleDevuelve400() throws Exception {
        when(propiedadService.darDeBaja(10L)).thenThrow(new OperacionInvalidaException(
                "No se puede inactivar la propiedad PROP-AAAA0001: su estado actual es ALQUILADA y la operación requiere DISPONIBLE"));

        mockMvc.perform(patch("/api/propiedades/{id}/baja", 10L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchBajaDePropiedadInexistenteDevuelve404() throws Exception {
        when(propiedadService.darDeBaja(99L))
                .thenThrow(new RecursoNoEncontradoException("No existe la propiedad con ID 99"));

        mockMvc.perform(patch("/api/propiedades/{id}/baja", 99L).with(conRol("ADMINISTRADOR")))
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

    // --- 401 con formato ApiError ---

    // Sin token: lo corta el ExceptionTranslationFilter y el entry point lo deriva a CatalogoExceptionHandler
    @Test
    void sinTokenDevuelve401ConApiError() throws Exception {
        mockMvc.perform(get("/api/propiedades/{id}", 10L).header("X-Correlation-ID", "corr-test-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value(MENSAJE_NO_AUTENTICADO))
                .andExpect(jsonPath("$.path").value("/api/propiedades/10"))
                .andExpect(jsonPath("$.correlationId").value("corr-test-401"));

        verifyNoInteractions(propiedadService);
    }

    // Token mal formado: lo corta el filtro de bearer token (otro entry point) antes de buscar las claves de Keycloak
    @Test
    void tokenMalFormadoDevuelve401ConApiError() throws Exception {
        mockMvc.perform(get("/api/propiedades/{id}", 10L)
                        .header("Authorization", "Bearer esto-no-es-un-jwt")
                        .header("X-Correlation-ID", "corr-test-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(MENSAJE_NO_AUTENTICADO))
                .andExpect(jsonPath("$.correlationId").value("corr-test-token"));

        verifyNoInteractions(propiedadService);
    }

    // --- Rutas públicas ---

    // En @WebMvcTest no están Swagger ni Actuator (darían 404): lo que se verifica es que la seguridad no las frena
    @Test
    void swaggerYHealthNoExigenToken() throws Exception {
        for (String ruta : List.of("/v3/api-docs", "/swagger-ui/index.html", "/actuator/health")) {
            int status = mockMvc.perform(get(ruta)).andReturn().getResponse().getStatus();
            assertNotEquals(401, status, ruta);
            assertNotEquals(403, status, ruta);
        }
    }

    // --- Datos de prueba ---

    private static MockHttpServletRequestBuilder patchEstado(Long id) {
        return patch("/api/propiedades/{id}/estado", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_RESERVAR);
    }

    private static MockHttpServletRequestBuilder put(Long id, String cuerpo) {
        return MockMvcRequestBuilders.put("/api/propiedades/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo);
    }

    private static RequestPostProcessor conRol(String rol) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    private static PropiedadResponse propiedad(Long id, String codigo) {
        return propiedad(id, codigo, EstadoPropiedadEnum.DISPONIBLE);
    }

    private static PropiedadResponse propiedad(Long id, String codigo, EstadoPropiedadEnum estado) {
        return new PropiedadResponse(id, codigo, null, null, null, null, 1L, null, estado,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }
}
