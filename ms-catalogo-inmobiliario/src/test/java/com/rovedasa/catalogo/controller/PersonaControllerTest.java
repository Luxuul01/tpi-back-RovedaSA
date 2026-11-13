package com.rovedasa.catalogo.controller;

import com.rovedasa.catalogo.config.SecurityConfig;
import com.rovedasa.catalogo.exception.BcraNoDisponibleException;
import com.rovedasa.catalogo.service.EvaluacionGaranteService;
import com.rovedasa.catalogo.service.PersonaService;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seguridad y errores de PersonaController con la SecurityConfig real (mismo esquema que PropiedadControllerTest).
 */
@WebMvcTest(PersonaController.class)
@Import(SecurityConfig.class)
class PersonaControllerTest {

    // CUIT de prueba con dígito verificador válido, con DNI en el rango 80-89 millones, que hoy no está asignado
    private static final String CUIT_GARANTE = "20888888889";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PersonaService personaService;
    @MockBean
    private EvaluacionGaranteService evaluacionGaranteService;

    // --- POST /api/personas/{id}/evaluacion-solvencia (F-08) ---

    @Test
    void evaluarSolvenciaConAdministradorDevuelve200ConLaEvaluacion() throws Exception {
        when(evaluacionGaranteService.evaluar(10L)).thenReturn(ValidacionGaranteDTO.builder()
                .personaId(10L)
                .cuitCuil(CUIT_GARANTE)
                .nombreCompleto("DIAZ MARCOS")
                .situacionCrediticiaBcra(1)
                .chequesRechazados(0)
                .ingresosDeclarados(new BigDecimal("2500000"))
                .esSolvente(true)
                .motivoEvaluacion("Califica: situación 1, 0 cheques rechazados, ingresos no evaluados (no se informó canon)")
                .build());

        mockMvc.perform(evaluarSolvencia(10L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personaId").value(10))
                .andExpect(jsonPath("$.cuitCuil").value(CUIT_GARANTE))
                .andExpect(jsonPath("$.situacionCrediticiaBcra").value(1))
                .andExpect(jsonPath("$.chequesRechazados").value(0))
                .andExpect(jsonPath("$.esSolvente").value(true));
    }

    @Test
    void evaluarSolvenciaConPropietarioDevuelve403ConApiError() throws Exception {
        mockMvc.perform(evaluarSolvencia(10L).with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(evaluacionGaranteService);
    }

    @Test
    void evaluarSolvenciaConInquilinoDevuelve403() throws Exception {
        mockMvc.perform(evaluarSolvencia(10L).with(conRol("INQUILINO")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(evaluacionGaranteService);
    }

    @Test
    void evaluarSolvenciaSinTokenDevuelve401ConApiError() throws Exception {
        mockMvc.perform(evaluarSolvencia(10L).header("X-Correlation-ID", "corr-solvencia-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Se requiere un token de acceso válido para acceder a este recurso"))
                .andExpect(jsonPath("$.correlationId").value("corr-solvencia-401"));

        verifyNoInteractions(evaluacionGaranteService);
    }

    @Test
    void evaluarSolvenciaConBcraCaidoDevuelve503ConApiError() throws Exception {
        String mensaje = "La Central de Deudores del BCRA no está disponible en este momento. Reintente más tarde.";
        when(evaluacionGaranteService.evaluar(10L)).thenThrow(new BcraNoDisponibleException(mensaje, null));

        mockMvc.perform(evaluarSolvencia(10L).with(conRol("ADMINISTRADOR")).header("X-Correlation-ID", "corr-bcra"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value(mensaje))
                .andExpect(jsonPath("$.path").value("/api/personas/10/evaluacion-solvencia"))
                .andExpect(jsonPath("$.correlationId").value("corr-bcra"));
    }

    @Test
    void evaluarSolvenciaDePersonaSinRolGaranteDevuelve400() throws Exception {
        when(evaluacionGaranteService.evaluar(10L)).thenThrow(new OperacionInvalidaException(
                "La persona Lucía Gómez (ID 10) no tiene el rol GARANTE; agregáselo antes de evaluar su solvencia"));

        mockMvc.perform(evaluarSolvencia(10L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "La persona Lucía Gómez (ID 10) no tiene el rol GARANTE; agregáselo antes de evaluar su solvencia"));
    }

    @Test
    void evaluarSolvenciaDePersonaInexistenteDevuelve404() throws Exception {
        when(evaluacionGaranteService.evaluar(99L))
                .thenThrow(new RecursoNoEncontradoException("No existe la persona con ID 99"));

        mockMvc.perform(evaluarSolvencia(99L).with(conRol("ADMINISTRADOR")))
                .andExpect(status().isNotFound());
    }

    // --- Datos de prueba ---

    private static MockHttpServletRequestBuilder evaluarSolvencia(Long id) {
        return post("/api/personas/{id}/evaluacion-solvencia", id);
    }

    private static RequestPostProcessor conRol(String rol) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }
}
