package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.config.SecurityConfig;
import com.rovedasa.catalogo.dto.CrearPersonaRequest;
import com.rovedasa.catalogo.dto.PersonaResponse;
import com.rovedasa.catalogo.exception.RecursoDuplicadoException;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.service.EvaluacionGaranteService;
import com.rovedasa.catalogo.service.PersonaService;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alta, consulta y roles de PersonaController (F-02, F-03) con la SecurityConfig real. La evaluación de
 * solvencia está en PersonaControllerTest.
 */
@WebMvcTest(PersonaController.class)
@Import(SecurityConfig.class)
class PersonaControllerAbmTest {

    private static final String KC_PROPIETARIO = "00000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PersonaService personaService;
    @MockBean
    private EvaluacionGaranteService evaluacionGaranteService;

    // --- POST /api/personas (F-02, RN-01) ---

    @Test
    void postConAdministradorDevuelve201ConLocation() throws Exception {
        when(personaService.registrar(any(CrearPersonaRequest.class)))
                .thenReturn(persona(7L, KC_PROPIETARIO, TipoPersonaEnum.PROPIETARIO));

        mockMvc.perform(postPersona(cuerpo("20-12345678-6", "\"" + KC_PROPIETARIO + "\"", "PROPIETARIO"))
                        .with(conRol("ADMINISTRADOR")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/personas/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.roles[0]").value("PROPIETARIO"));

        ArgumentCaptor<CrearPersonaRequest> solicitud = ArgumentCaptor.forClass(CrearPersonaRequest.class);
        verify(personaService).registrar(solicitud.capture());
        assertEquals(KC_PROPIETARIO, solicitud.getValue().keycloakUserId());
    }

    @Test
    void postDeGaranteSinKeycloakUserIdDevuelve201() throws Exception {
        when(personaService.registrar(any(CrearPersonaRequest.class)))
                .thenReturn(persona(8L, null, TipoPersonaEnum.GARANTE));

        mockMvc.perform(postPersona(cuerpo("20123456786", null, "GARANTE")).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"20-12345678-7", "20-ABC12345678-6", "20.12345678.6", "2012345678"})
    void postConCuitInvalidoDevuelve400(String cuit) throws Exception {
        mockMvc.perform(postPersona(cuerpo(cuit, null, "GARANTE")).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.cuitCuil").value("CUIT/CUIL inválido"));

        verifyNoInteractions(personaService);
    }

    // Los @NotBlank sin mensaje propio usaban el idioma de la JVM ("must not be blank"): el locale es fijo (es)
    @Test
    void postSinCuitDevuelve400ConElMensajeEnEspanolAunqueElClientePidaIngles() throws Exception {
        mockMvc.perform(postPersona(cuerpo("", null, "GARANTE")).with(conRol("ADMINISTRADOR"))
                        .header("Accept-Language", "en-US"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.cuitCuil").value("no debe estar vacío"));

        verifyNoInteractions(personaService);
    }

    // M3 / RN-17: sin keycloakUserId un PROPIETARIO o INQUILINO vería [] en silencio
    @ParameterizedTest
    @ValueSource(strings = {"PROPIETARIO", "INQUILINO"})
    void postDePropietarioOInquilinoSinKeycloakUserIdDevuelve400(String rol) throws Exception {
        mockMvc.perform(postPersona(cuerpo("20123456786", null, rol)).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.keycloakUserId",
                        containsString("obligatorio para los roles PROPIETARIO e INQUILINO")));

        verifyNoInteractions(personaService);
    }

    @Test
    void postConVariosRolesQueIncluyenInquilinoTambienExigeKeycloakUserId() throws Exception {
        mockMvc.perform(postPersona(cuerpo("20123456786", "\"   \"", "GARANTE\", \"INQUILINO"))
                        .with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.keycloakUserId").exists());

        verifyNoInteractions(personaService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kc-propietario-1", "00000000-0000-0000-0000-00000000000", "no es un uuid"})
    void postConKeycloakUserIdQueNoEsUuidDevuelve400(String keycloakUserId) throws Exception {
        mockMvc.perform(postPersona(cuerpo("20123456786", "\"" + keycloakUserId + "\"", "GARANTE"))
                        .with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.keycloakUserId", containsString("UUID")));

        verifyNoInteractions(personaService);
    }

    @Test
    void postConCuitDuplicadoDevuelve409SinElCuitEnElMensaje() throws Exception {
        when(personaService.registrar(any(CrearPersonaRequest.class)))
                .thenThrow(new RecursoDuplicadoException("Ya existe una persona con ese CUIT/CUIL"));

        mockMvc.perform(postPersona(cuerpo("20123456786", null, "GARANTE")).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Ya existe una persona con ese CUIT/CUIL"));
    }

    @Test
    void postConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(postPersona(cuerpo("20123456786", null, "GARANTE")).with(conRol("INQUILINO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(personaService);
    }

    @Test
    void postSinTokenDevuelve401() throws Exception {
        mockMvc.perform(postPersona(cuerpo("20123456786", null, "GARANTE")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(personaService);
    }

    // --- GET /api/personas y /api/personas/{id} (F-03) ---

    @Test
    void listarConAdministradorFiltraPorRol() throws Exception {
        when(personaService.listar(eq(TipoPersonaEnum.GARANTE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(persona(8L, null, TipoPersonaEnum.GARANTE)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/personas").param("rol", "GARANTE").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(8))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void listarSinRolPasaNulo() throws Exception {
        when(personaService.listar(isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/personas").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void listarConPropietarioDevuelve403() throws Exception {
        mockMvc.perform(get("/api/personas").with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(personaService);
    }

    @Test
    void obtenerConAdministradorDevuelve200() throws Exception {
        when(personaService.obtener(7L)).thenReturn(persona(7L, KC_PROPIETARIO, TipoPersonaEnum.PROPIETARIO));

        mockMvc.perform(get("/api/personas/{id}", 7L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keycloakUserId").value(KC_PROPIETARIO));
    }

    @Test
    void obtenerInexistenteDevuelve404() throws Exception {
        when(personaService.obtener(99L)).thenThrow(new RecursoNoEncontradoException("No existe la persona con ID 99"));

        mockMvc.perform(get("/api/personas/{id}", 99L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void obtenerConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(get("/api/personas/{id}", 7L).with(conRol("INQUILINO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(personaService);
    }

    // --- PATCH /api/personas/{id}/roles (F-03) ---

    @Test
    void agregarRolConAdministradorDevuelve200() throws Exception {
        PersonaResponse conGarante = new PersonaResponse(7L, "20123456786", "Juan", "Pérez", "Juan Pérez", null, null,
                KC_PROPIETARIO, Set.of(TipoPersonaEnum.PROPIETARIO, TipoPersonaEnum.GARANTE), null, null, null, null,
                null);
        when(personaService.agregarRol(7L, TipoPersonaEnum.GARANTE)).thenReturn(conGarante);

        mockMvc.perform(patchRol(7L, "{\"rol\": \"GARANTE\"}").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));
    }

    @Test
    void agregarRolQueExigeKeycloakAUnaPersonaSinVinculoDevuelve400() throws Exception {
        String mensaje = "No se puede agregar el rol INQUILINO a la persona ID 8: no está vinculada a un usuario de "
                + "Keycloak (keycloakUserId) y sin él no podría ver sus propiedades ni sus contratos";
        when(personaService.agregarRol(8L, TipoPersonaEnum.INQUILINO)).thenThrow(new OperacionInvalidaException(mensaje));

        mockMvc.perform(patchRol(8L, "{\"rol\": \"INQUILINO\"}").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(mensaje));
    }

    @Test
    void agregarRolSinRolDevuelve400() throws Exception {
        mockMvc.perform(patchRol(7L, "{}").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.rol").exists());

        verifyNoInteractions(personaService);
    }

    @Test
    void agregarRolInexistenteDevuelve400() throws Exception {
        mockMvc.perform(patchRol(7L, "{\"rol\": \"JEFE\"}").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("JEFE")));

        verifyNoInteractions(personaService);
    }

    @Test
    void agregarRolConPropietarioDevuelve403() throws Exception {
        mockMvc.perform(patchRol(7L, "{\"rol\": \"GARANTE\"}").with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(personaService);
    }

    @Test
    void agregarRolAPersonaInexistenteDevuelve404() throws Exception {
        when(personaService.agregarRol(99L, TipoPersonaEnum.GARANTE))
                .thenThrow(new RecursoNoEncontradoException("No existe la persona con ID 99"));

        mockMvc.perform(patchRol(99L, "{\"rol\": \"GARANTE\"}").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isNotFound());
    }

    // --- Datos de prueba ---

    // keycloakUserIdJson: null para omitirlo, o el valor JSON ya entre comillas. roles: los nombres separados por "\", \""
    private static String cuerpo(String cuit, String keycloakUserIdJson, String roles) {
        return """
                {"cuitCuil": "%s", "nombre": "Juan", "apellido": "Pérez", "email": "juan@mail.com",
                 %s "roles": ["%s"]}
                """.formatted(cuit, keycloakUserIdJson == null ? "" : "\"keycloakUserId\": " + keycloakUserIdJson + ",",
                roles);
    }

    private static MockHttpServletRequestBuilder postPersona(String cuerpo) {
        return post("/api/personas").contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static MockHttpServletRequestBuilder patchRol(Long id, String cuerpo) {
        return patch("/api/personas/{id}/roles", id).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static RequestPostProcessor conRol(String rol) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    private static PersonaResponse persona(Long id, String keycloakUserId, TipoPersonaEnum rol) {
        return new PersonaResponse(id, "20123456786", "Juan", "Pérez", "Juan Pérez", null, null, keycloakUserId,
                Set.of(rol), null, null, null, null, null);
    }
}
