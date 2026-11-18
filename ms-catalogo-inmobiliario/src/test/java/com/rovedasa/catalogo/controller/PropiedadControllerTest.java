package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.config.SecurityConfig;
import com.rovedasa.catalogo.dto.CrearPropiedadRequest;
import com.rovedasa.catalogo.dto.ModificarPropiedadRequest;
import com.rovedasa.catalogo.dto.PropiedadResponse;
import com.rovedasa.catalogo.exception.EstadoPropiedadInesperadoException;
import com.rovedasa.catalogo.exception.ServicioExternoNoDisponibleException;
import com.rovedasa.catalogo.service.PropiedadService;
import com.rovedasa.commons.dto.ActualizarEstadoPropiedadDTO;
import com.rovedasa.commons.dto.CompensarEstadoPropiedadDTO;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private static final String CUERPO_COMPENSAR_ACTIVACION =
            "{\"estadoActual\": \"ALQUILADA\", \"estadoAnterior\": \"RESERVADA\"}";
    private static final String CUERPO_ALTA = """
            {"tipologia": "DEPARTAMENTO", "calle": "independencia", "numero": "900", "ciudad": "Córdoba",
             "provincia": "Córdoba", "superficieTotalM2": 55, "superficieCubiertaM2": 50, "propietarioId": 1}
            """;
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

    // Contrato 1 con ms-contratos: estadoEsperado llega al servicio
    @Test
    void patchEstadoConEstadoEsperadoLoPasaAlServicio() throws Exception {
        when(propiedadService.actualizarEstado(eq(10L), any(ActualizarEstadoPropiedadDTO.class)))
                .thenReturn(PropiedadDTO.builder().id(10L).estado(EstadoPropiedadEnum.ALQUILADA).build());

        mockMvc.perform(patch("/api/propiedades/{id}/estado", 10L).with(conRol("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevoEstado\": \"ALQUILADA\", \"estadoEsperado\": \"RESERVADA\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ActualizarEstadoPropiedadDTO> solicitud =
                ArgumentCaptor.forClass(ActualizarEstadoPropiedadDTO.class);
        verify(propiedadService).actualizarEstado(eq(10L), solicitud.capture());
        assertEquals(EstadoPropiedadEnum.ALQUILADA, solicitud.getValue().getNuevoEstado());
        assertEquals(EstadoPropiedadEnum.RESERVADA, solicitud.getValue().getEstadoEsperado());
    }

    @Test
    void patchEstadoConEstadoEsperadoDistintoDevuelve409ConElMensaje() throws Exception {
        when(propiedadService.actualizarEstado(eq(10L), any(ActualizarEstadoPropiedadDTO.class)))
                .thenThrow(new EstadoPropiedadInesperadoException(EstadoPropiedadEnum.RESERVADA,
                        EstadoPropiedadEnum.DISPONIBLE));

        mockMvc.perform(patchEstado(10L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("La propiedad está en estado RESERVADA y se esperaba DISPONIBLE"));
    }

    @Test
    void patchEstadoSinNuevoEstadoDevuelve400() throws Exception {
        mockMvc.perform(patch("/api/propiedades/{id}/estado", 10L).with(conRol("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estadoEsperado\": \"DISPONIBLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.nuevoEstado").exists());

        verifyNoInteractions(propiedadService);
    }

    // --- PATCH /api/propiedades/{id}/estado/compensacion (contrato 2 con ms-contratos) ---

    @Test
    void compensacionConAdministradorDevuelve200ConLaPropiedad() throws Exception {
        when(propiedadService.compensarEstado(eq(10L), any(CompensarEstadoPropiedadDTO.class)))
                .thenReturn(PropiedadDTO.builder().id(10L).estado(EstadoPropiedadEnum.RESERVADA).build());

        mockMvc.perform(patchCompensacion(10L, CUERPO_COMPENSAR_ACTIVACION).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.estado").value("RESERVADA"));

        ArgumentCaptor<CompensarEstadoPropiedadDTO> solicitud =
                ArgumentCaptor.forClass(CompensarEstadoPropiedadDTO.class);
        verify(propiedadService).compensarEstado(eq(10L), solicitud.capture());
        assertEquals(EstadoPropiedadEnum.ALQUILADA, solicitud.getValue().getEstadoActual());
        assertEquals(EstadoPropiedadEnum.RESERVADA, solicitud.getValue().getEstadoAnterior());
    }

    @Test
    void compensacionConPropietarioDevuelve403() throws Exception {
        mockMvc.perform(patchCompensacion(10L, CUERPO_COMPENSAR_ACTIVACION).with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void compensacionConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(patchCompensacion(10L, CUERPO_COMPENSAR_ACTIVACION).with(conRol("INQUILINO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void compensacionSinTokenDevuelve401() throws Exception {
        mockMvc.perform(patchCompensacion(10L, CUERPO_COMPENSAR_ACTIVACION))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void compensacionSinEstadosDevuelve400ConLosCampos() throws Exception {
        mockMvc.perform(patchCompensacion(10L, "{}").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.estadoActual").exists())
                .andExpect(jsonPath("$.validaciones.estadoAnterior").exists());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void compensacionDeUnParInvalidoDevuelve400() throws Exception {
        String mensaje = "No se puede compensar de ALQUILADA a DISPONIBLE: DISPONIBLE -> ALQUILADA no es una "
                + "transición válida que se pueda deshacer";
        when(propiedadService.compensarEstado(eq(10L), any(CompensarEstadoPropiedadDTO.class)))
                .thenThrow(new OperacionInvalidaException(mensaje));

        mockMvc.perform(patchCompensacion(10L, "{\"estadoActual\": \"ALQUILADA\", \"estadoAnterior\": \"DISPONIBLE\"}")
                        .with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(mensaje));
    }

    @Test
    void compensacionConEstadoActualDistintoDevuelve409() throws Exception {
        when(propiedadService.compensarEstado(eq(10L), any(CompensarEstadoPropiedadDTO.class)))
                .thenThrow(new EstadoPropiedadInesperadoException(EstadoPropiedadEnum.DISPONIBLE,
                        EstadoPropiedadEnum.ALQUILADA));

        mockMvc.perform(patchCompensacion(10L, CUERPO_COMPENSAR_ACTIVACION).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La propiedad está en estado DISPONIBLE y se esperaba ALQUILADA"));
    }

    @Test
    void compensacionDePropiedadInexistenteDevuelve404() throws Exception {
        when(propiedadService.compensarEstado(eq(99L), any(CompensarEstadoPropiedadDTO.class)))
                .thenThrow(new RecursoNoEncontradoException("No existe la propiedad con ID 99"));

        mockMvc.perform(patchCompensacion(99L, CUERPO_COMPENSAR_ACTIVACION).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isNotFound());
    }

    // --- POST /api/propiedades (F-04) ---

    @Test
    void postConAdministradorDevuelve201ConLocationRelativa() throws Exception {
        when(propiedadService.registrar(any(CrearPropiedadRequest.class))).thenReturn(propiedad(15L, "PROP-AAAA0015"));

        mockMvc.perform(post("/api/propiedades").with(conRol("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_ALTA))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/propiedades/15"))
                .andExpect(jsonPath("$.id").value(15))
                .andExpect(jsonPath("$.estado").value("DISPONIBLE"));

        ArgumentCaptor<CrearPropiedadRequest> solicitud = ArgumentCaptor.forClass(CrearPropiedadRequest.class);
        verify(propiedadService).registrar(solicitud.capture());
        assertEquals(1L, solicitud.getValue().propietarioId());
        assertEquals(new BigDecimal("55"), solicitud.getValue().superficieTotalM2());
    }

    @Test
    void postConCuerpoInvalidoDevuelve400ConLosCamposRechazados() throws Exception {
        // Sin calle, sin propietario y con más de dos decimales
        String cuerpoInvalido = """
                {"tipologia": "DEPARTAMENTO", "numero": "900", "ciudad": "Córdoba", "provincia": "Córdoba",
                 "superficieTotalM2": 55.123}
                """;

        mockMvc.perform(post("/api/propiedades").with(conRol("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpoInvalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validaciones.calle").exists())
                .andExpect(jsonPath("$.validaciones.propietarioId").exists())
                .andExpect(jsonPath("$.validaciones.superficieTotalM2").exists());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void postConPropietarioDevuelve403() throws Exception {
        mockMvc.perform(post("/api/propiedades").with(conRol("PROPIETARIO"))
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_ALTA))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void postConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(post("/api/propiedades").with(conRol("INQUILINO"))
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_ALTA))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void postConDireccionQueGeoRefNoEncuentraDevuelve400() throws Exception {
        when(propiedadService.registrar(any(CrearPropiedadRequest.class)))
                .thenThrow(new OperacionInvalidaException("No se pudo normalizar la dirección"));

        mockMvc.perform(post("/api/propiedades").with(conRol("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_ALTA))
                .andExpect(status().isBadRequest());
    }

    // --- GET /api/propiedades y /api/propiedades/{id} (F-05) ---

    @Test
    void listarConAdministradorDevuelvePaginaFiltradaPorEstado() throws Exception {
        when(propiedadService.listar(eq(EstadoPropiedadEnum.DISPONIBLE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(propiedad(10L, "PROP-AAAA0001")), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/propiedades").param("estado", "DISPONIBLE").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].codigo").value("PROP-AAAA0001"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void listarSinFiltroPasaEstadoNulo() throws Exception {
        when(propiedadService.listar(isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/propiedades").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // C-08 (d): el GET general con propietario1 da 403 en el servicio
    @Test
    void listarConPropietarioDevuelve403() throws Exception {
        mockMvc.perform(get("/api/propiedades").with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(propiedadService);
    }

    @Test
    void listarConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(get("/api/propiedades").with(conRol("INQUILINO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void listarConEstadoInexistenteDevuelve400() throws Exception {
        mockMvc.perform(get("/api/propiedades").param("estado", "VENDIDA").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void obtenerConAdministradorDevuelve200() throws Exception {
        when(propiedadService.obtener(10L)).thenReturn(propiedad(10L, "PROP-AAAA0001"));

        mockMvc.perform(get("/api/propiedades/{id}", 10L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.codigo").value("PROP-AAAA0001"));
    }

    @Test
    void obtenerConPropietarioDevuelve403() throws Exception {
        mockMvc.perform(get("/api/propiedades/{id}", 10L).with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(propiedadService);
    }

    @Test
    void obtenerInexistenteDevuelve404() throws Exception {
        when(propiedadService.obtener(99L)).thenThrow(new RecursoNoEncontradoException("No existe la propiedad con ID 99"));

        mockMvc.perform(get("/api/propiedades/{id}", 99L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No existe la propiedad con ID 99"));
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

    // M1: Actuator (salvo health) es solo para ADMINISTRADOR. Antes un INQUILINO podía cambiar el nivel de log
    // con POST /actuator/loggers/ROOT pegándole directo al 8081.
    @Test
    void actuatorConRolNoAdministradorDevuelve403() throws Exception {
        for (String rol : List.of("INQUILINO", "PROPIETARIO")) {
            mockMvc.perform(post("/actuator/loggers/ROOT").with(conRol(rol))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"configuredLevel\": \"TRACE\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/actuator/loggers").with(conRol(rol)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/actuator/metrics").with(conRol(rol)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/actuator/info").with(conRol(rol)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void actuatorSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/actuator/loggers"))
                .andExpect(status().isUnauthorized());
    }

    // En @WebMvcTest no está Actuator (da 404): se verifica que la seguridad deja pasar al ADMINISTRADOR
    @Test
    void actuatorConAdministradorNoLoFrenaLaSeguridad() throws Exception {
        for (String ruta : List.of("/actuator/loggers", "/actuator/metrics", "/actuator/info")) {
            int status = mockMvc.perform(get(ruta).with(conRol("ADMINISTRADOR"))).andReturn().getResponse().getStatus();
            assertNotEquals(401, status, ruta);
            assertNotEquals(403, status, ruta);
        }
    }

    @Test
    void healthSigueSiendoPublicoConCualquierRol() throws Exception {
        for (String ruta : List.of("/actuator/health", "/actuator/health/liveness")) {
            int status = mockMvc.perform(get(ruta).with(conRol("INQUILINO"))).andReturn().getResponse().getStatus();
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

    private static MockHttpServletRequestBuilder patchCompensacion(Long id, String cuerpo) {
        return patch("/api/propiedades/{id}/estado/compensacion", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo);
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
