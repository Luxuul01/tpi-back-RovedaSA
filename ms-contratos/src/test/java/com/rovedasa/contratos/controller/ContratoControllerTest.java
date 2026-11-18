package com.rovedasa.contratos.controller;

import com.rovedasa.commons.security.KeycloakRoleConverter;
import com.rovedasa.contratos.client.CatalogoConflictoException;
import com.rovedasa.contratos.client.CatalogoIntegracionException;
import com.rovedasa.contratos.config.SecurityConfig;
import com.rovedasa.contratos.dto.AplicarAjusteRequest;
import com.rovedasa.contratos.dto.ContratoResponse;
import com.rovedasa.contratos.dto.ResumenAjustePeriodicoResponse;
import com.rovedasa.contratos.service.AjusteProgramadoService;
import com.rovedasa.contratos.service.ContratoService;
import com.rovedasa.contratos.service.CuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContratoController.class)
@Import({SecurityConfig.class, KeycloakRoleConverter.class})
class ContratoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContratoService contratoService;

    @MockBean
    private CuotaService cuotaService;

    @MockBean
    private AjusteProgramadoService ajusteProgramadoService;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("ROLE_ADMINISTRADOR");

    @Test
    void unConflictoDelCatalogoResponde409ConApiError() throws Exception {
        when(contratoService.activarContrato(eq(1L), anyString()))
                .thenThrow(new CatalogoConflictoException("La propiedad está en estado DISPONIBLE y se esperaba RESERVADA"));

        mockMvc.perform(post("/api/contratos/1/activar").with(jwt().authorities(ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("La propiedad está en estado DISPONIBLE y se esperaba RESERVADA"));
    }

    @Test
    void unaCredencialRechazadaPorElCatalogoResponde502() throws Exception {
        when(contratoService.activarContrato(eq(1L), anyString()))
                .thenThrow(new CatalogoIntegracionException("El catálogo rechazó la credencial del servicio (HTTP 401)"));

        mockMvc.perform(post("/api/contratos/1/activar").with(jwt().authorities(ADMIN)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void ajusteSinBodyUsaElPeriodoDelContrato() throws Exception {
        when(contratoService.aplicarAjuste(eq(1L), isNull(), anyString()))
                .thenReturn(ContratoResponse.builder().id(1L).build());

        mockMvc.perform(post("/api/contratos/1/ajustes").with(jwt().authorities(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void ajusteConElHitoFuturoLlegaAlServicioSinErrorDeValidacionDeCampo() throws Exception {
        // Antes un "hasta" futuro cortaba con 400 "no puede ser futura" (@PastOrPresent) y el pedido nunca
        // llegaba a la regla del período, que es la que dice desde qué fecha corresponde el ajuste
        LocalDate hoy = LocalDate.now();
        LocalDate hito = hoy.plusMonths(3);
        when(contratoService.aplicarAjuste(eq(1L), any(AplicarAjusteRequest.class), anyString()))
                .thenReturn(ContratoResponse.builder().id(1L).build());

        mockMvc.perform(post("/api/contratos/1/ajustes").with(jwt().authorities(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaIndiceDesde\":\"" + hoy + "\",\"fechaIndiceHasta\":\"" + hito + "\"}"))
                .andExpect(status().isOk());

        verify(contratoService).aplicarAjuste(eq(1L),
                argThat(p -> hoy.equals(p.getFechaIndiceDesde()) && hito.equals(p.getFechaIndiceHasta())), anyString());
    }

    @Test
    void cancelarConUnMotivoDeMasDe500CaracteresDa400SinLlegarAlServicio() throws Exception {
        mockMvc.perform(post("/api/contratos/1/cancelar").with(jwt().authorities(ADMIN))
                        .param("motivo", "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validaciones.motivo")
                        .value("El motivo de cancelación admite como máximo 500 caracteres"));

        verify(contratoService, never()).cancelarContrato(any(), any(), anyBoolean(), any());
    }

    @Test
    void cancelarConUnMotivoDe500CaracteresLlegaAlServicio() throws Exception {
        String motivo = "x".repeat(500);
        when(contratoService.cancelarContrato(eq(1L), eq(motivo), eq(false), anyString()))
                .thenReturn(ContratoResponse.builder().id(1L).build());

        mockMvc.perform(post("/api/contratos/1/cancelar").with(jwt().authorities(ADMIN)).param("motivo", motivo))
                .andExpect(status().isOk());
    }

    @Test
    void ajustePeriodicoDevuelveElResumen() throws Exception {
        when(ajusteProgramadoService.ejecutarAjustePeriodico()).thenReturn(ResumenAjustePeriodicoResponse.builder()
                .contratosRevisados(3).ajustesAplicados(2)
                .pendientes(List.of(ResumenAjustePeriodicoResponse.Pendiente.builder()
                        .contratoId(9L).motivo("IPC de octubre todavía no publicado").build()))
                .build());

        mockMvc.perform(post("/api/contratos/ajuste-periodico").with(jwt().authorities(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contratosRevisados").value(3))
                .andExpect(jsonPath("$.ajustesAplicados").value(2))
                .andExpect(jsonPath("$.pendientes[0].contratoId").value(9))
                .andExpect(jsonPath("$.pendientes[0].motivo").value("IPC de octubre todavía no publicado"));
    }
}
