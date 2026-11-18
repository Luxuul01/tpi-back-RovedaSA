package com.rovedasa.contratos.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seguridad de ms-contratos con la aplicación completa (incluido actuator) y el perfil "local" activo, para
 * comprobar que ese perfil ya no abre los endpoints. jwt() arma el token ya validado, sin Keycloak.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SecurityConfigTest {

    private static final String MENSAJE_NO_AUTENTICADO =
            "Se requiere un token de acceso válido para acceder a este recurso";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Sin token: 401 con ApiError, correlationId y WWW-Authenticate (también en el perfil local)")
    void sinTokenDa401ConApiError() throws Exception {
        mockMvc.perform(get("/api/contratos").header("X-Correlation-ID", "corr-contratos-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("X-Correlation-ID", "corr-contratos-401"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(MENSAJE_NO_AUTENTICADO))
                .andExpect(jsonPath("$.path").value("/api/contratos"))
                .andExpect(jsonPath("$.correlationId").value("corr-contratos-401"));
    }

    @Test
    @DisplayName("Token mal formado: 401 invalid_token con ApiError")
    void tokenMalFormadoDa401ConApiError() throws Exception {
        mockMvc.perform(get("/api/contratos").header("Authorization", "Bearer no-es-un-jwt")
                        .header("X-Correlation-ID", "corr-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")))
                .andExpect(jsonPath("$.message").value(MENSAJE_NO_AUTENTICADO))
                .andExpect(jsonPath("$.correlationId").value("corr-token"));
    }

    @Test
    @DisplayName("Rol sin permiso en @PreAuthorize: 403 con ApiError")
    void rolSinPermisoDa403ConApiError() throws Exception {
        mockMvc.perform(post("/api/contratos/ajuste-periodico").with(conRol("INQUILINO"))
                        .header("X-Correlation-ID", "corr-contratos-403"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("No tenés permisos para realizar esta operación"))
                .andExpect(jsonPath("$.correlationId").value("corr-contratos-403"));
    }

    @Test
    @DisplayName("/actuator/health es público")
    void healthEsPublico() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/actuator/loggers con un rol que no es ADMINISTRADOR: 403 con ApiError (lectura y escritura)")
    void loggersSinAdministradorDa403() throws Exception {
        mockMvc.perform(get("/actuator/loggers").with(conRol("PROPIETARIO")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mockMvc.perform(post("/actuator/loggers/com.rovedasa").with(conRol("INQUILINO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"configuredLevel\":\"TRACE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/actuator/loggers sin token: 401")
    void loggersSinTokenDa401() throws Exception {
        mockMvc.perform(get("/actuator/loggers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/actuator/loggers con ADMINISTRADOR: 200")
    void loggersConAdministradorDa200() throws Exception {
        mockMvc.perform(get("/actuator/loggers").with(conRol("ADMINISTRADOR")))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor conRol(String rol) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }
}
