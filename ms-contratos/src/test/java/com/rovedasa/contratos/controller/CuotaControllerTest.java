package com.rovedasa.contratos.controller;

import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.commons.security.KeycloakRoleConverter;
import com.rovedasa.contratos.config.SecurityConfig;
import com.rovedasa.contratos.dto.CuotaResumenDTO;
import com.rovedasa.contratos.model.EstadoCuotaEnum;
import com.rovedasa.contratos.service.CuotaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CuotaController.class)
@Import({SecurityConfig.class, KeycloakRoleConverter.class})
class CuotaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CuotaService cuotaService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("PATCH pago: un INQUILINO recibe 403 y no se llama al servicio")
    void pagarComoInquilinoResponde403() throws Exception {
        mockMvc.perform(patch("/api/cuotas/1/pago")
                        .param("fechaPago", "2026-07-18")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INQUILINO"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cuotaService);
    }

    @Test
    @DisplayName("PATCH pago: el ADMINISTRADOR recibe 200 con la mora fija hasta la fecha de pago")
    void pagarComoAdministradorResponde200ConLaMora() throws Exception {
        when(cuotaService.pagarCuota(1L, LocalDate.of(2026, 7, 18))).thenReturn(CuotaResumenDTO.builder()
                .id(1L)
                .estado(EstadoCuotaEnum.PAGADA)
                .fechaPago(LocalDate.of(2026, 7, 18))
                .montoBase(new BigDecimal("500000.00"))
                .montoMora(new BigDecimal("4000.00"))
                .montoTotal(new BigDecimal("504000.00"))
                .build());

        mockMvc.perform(patch("/api/cuotas/1/pago")
                        .param("fechaPago", "2026-07-18")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PAGADA"))
                .andExpect(jsonPath("$.fechaPago").value("2026-07-18"))
                .andExpect(jsonPath("$.montoMora").value(4000.00))
                .andExpect(jsonPath("$.montoTotal").value(504000.00));
    }

    @Test
    @DisplayName("PATCH pago sin fechaPago: el servicio recibe null (usa la fecha de hoy)")
    void pagarSinFechaPasaNullAlServicio() throws Exception {
        when(cuotaService.pagarCuota(eq(1L), isNull())).thenReturn(CuotaResumenDTO.builder().id(1L).build());

        mockMvc.perform(patch("/api/cuotas/1/pago")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))))
                .andExpect(status().isOk());
        verify(cuotaService).pagarCuota(eq(1L), isNull());
    }

    @Test
    @DisplayName("PATCH pago con fecha inválida para la regla (anterior al inicio) responde 400 con el mensaje")
    void pagarConFechaAnteriorAlInicioResponde400() throws Exception {
        when(cuotaService.pagarCuota(1L, LocalDate.of(1990, 1, 1))).thenThrow(new OperacionInvalidaException(
                "La fecha de pago (1990-01-01) no puede ser anterior al inicio del contrato (2026-01-01)"));

        mockMvc.perform(patch("/api/cuotas/1/pago")
                        .param("fechaPago", "1990-01-01")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("La fecha de pago (1990-01-01) no puede ser anterior al inicio del contrato (2026-01-01)"));
    }

    @Test
    @DisplayName("PATCH pago con fechaPago mal formada responde 400 sin llamar al servicio")
    void pagarConFechaMalFormadaResponde400() throws Exception {
        mockMvc.perform(patch("/api/cuotas/1/pago")
                        .param("fechaPago", "18/07/2026")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cuotaService);
    }

    @Test
    @DisplayName("GET cuota: pasa al servicio el rol y el sub del token y la fecha de referencia")
    void obtenerCuotaPasaRolSubYFechaDeReferencia() throws Exception {
        when(cuotaService.obtenerCuotaConMora(1L, LocalDate.of(2026, 7, 20), "INQUILINO", "sub-inq"))
                .thenReturn(CuotaResumenDTO.builder().id(1L).montoMora(new BigDecimal("5000.00")).build());

        mockMvc.perform(get("/api/cuotas/1")
                        .param("fechaReferencia", "2026-07-20")
                        .with(jwt().jwt(j -> j.subject("sub-inq"))
                                .authorities(new SimpleGrantedAuthority("ROLE_INQUILINO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.montoMora").value(5000.00));
    }

    @Test
    @DisplayName("GET cuota ajena responde 404 (RN-17)")
    void obtenerCuotaAjenaResponde404() throws Exception {
        when(cuotaService.obtenerCuotaConMora(eq(1L), any(), eq("PROPIETARIO"), eq("sub-otro")))
                .thenThrow(new RecursoNoEncontradoException("Cuota con ID 1 no fue encontrada"));

        mockMvc.perform(get("/api/cuotas/1")
                        .with(jwt().jwt(j -> j.subject("sub-otro"))
                                .authorities(new SimpleGrantedAuthority("ROLE_PROPIETARIO"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Cuota con ID 1 no fue encontrada"));
    }
}
