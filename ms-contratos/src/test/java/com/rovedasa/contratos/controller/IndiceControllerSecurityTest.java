package com.rovedasa.contratos.controller;

import com.rovedasa.commons.security.KeycloakRoleConverter;
import com.rovedasa.contratos.config.SecurityConfig;
import com.rovedasa.contratos.dto.OrigenDatoEnum;
import com.rovedasa.contratos.dto.ValorIndiceDTO;
import com.rovedasa.contratos.model.TipoIndiceEnum;
import com.rovedasa.contratos.service.IndiceService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IndiceController.class)
@Import({SecurityConfig.class, KeycloakRoleConverter.class})
class IndiceControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IndiceService indiceService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void obtenerValor_sinTokenResponde401() throws Exception {
        mockMvc.perform(get("/api/indices/ICL/valor").param("fecha", "2026-10-26"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(indiceService);
    }

    @Test
    void obtenerValor_conRolNoAutorizadoResponde403PorPreAuthorize() throws Exception {
        mockMvc.perform(get("/api/indices/ICL/valor")
                        .param("fecha", "2026-10-26")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INVITADO"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(indiceService);
    }

    @Test
    void obtenerValor_conRolInquilinoResponde200() throws Exception {
        when(indiceService.obtenerValor(any(), any())).thenReturn(
                ValorIndiceDTO.builder()
                        .tipoIndice(TipoIndiceEnum.ICL)
                        .fechaSolicitada(LocalDate.of(2026, 10, 26))
                        .fechaIndice(LocalDate.of(2026, 10, 26))
                        .valor(new BigDecimal("37.11"))
                        .origen(OrigenDatoEnum.BCRA)
                        .build());

        mockMvc.perform(get("/api/indices/ICL/valor")
                        .param("fecha", "2026-10-26")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INQUILINO"))))
                .andExpect(status().isOk());
    }
}
