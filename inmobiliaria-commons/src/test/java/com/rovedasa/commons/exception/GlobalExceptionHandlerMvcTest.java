package com.rovedasa.commons.exception;

import com.rovedasa.commons.filter.CorrelationIdFilter;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Errores que arma el propio Spring MVC (405, 406, 415, header faltante, validación de parámetros, ruta
 * inexistente), probados de punta a punta con el CorrelationIdFilter y el GlobalExceptionHandler reales.
 */
class GlobalExceptionHandlerMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ControladorDePrueba())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    @DisplayName("415: body text/plain en un endpoint que consume JSON")
    void contentTypeNoSoportadoDa415ConApiError() throws Exception {
        mockMvc.perform(post("/prueba/json").contentType(MediaType.TEXT_PLAIN).content("hola")
                        .header("X-Correlation-ID", "corr-415"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message", containsString("text/plain")))
                .andExpect(jsonPath("$.correlationId").value("corr-415"));
    }

    @Test
    @DisplayName("406: Accept text/csv en un endpoint que solo produce JSON; el ApiError sale en JSON")
    void acceptNoSatisfacibleDa406ConApiErrorEnJson() throws Exception {
        mockMvc.perform(get("/prueba/json").accept(MediaType.parseMediaType("text/csv")))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(406));
    }

    @Test
    @DisplayName("405: método no soportado, con header Allow")
    void metodoNoSoportadoDa405ConAllow() throws Exception {
        mockMvc.perform(delete("/prueba/json"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("400: falta un header obligatorio")
    void headerFaltanteDa400() throws Exception {
        mockMvc.perform(get("/prueba/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el header obligatorio 'X-Usuario'"));
    }

    @Test
    @DisplayName("400: validación de un @RequestParam (HandlerMethodValidationException) con el detalle por parámetro")
    void validacionDeParametroDa400ConValidaciones() throws Exception {
        mockMvc.perform(get("/prueba/validado").param("cantidad", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.MENSAJE_PARAMETROS_INVALIDOS))
                .andExpect(jsonPath("$.validaciones.cantidad").exists());
    }

    @Test
    @DisplayName("404: ruta inexistente con ApiError")
    void rutaInexistenteDa404ConApiError() throws Exception {
        mockMvc.perform(get("/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/no-existe"));
    }

    @Test
    @DisplayName("Un X-Correlation-ID inválido no se refleja: se genera uno nuevo y es el mismo en header y body")
    void correlationIdInvalidoSeReemplazaEnHeaderYBody() throws Exception {
        MvcResult resultado = mockMvc.perform(get("/no-existe").header("X-Correlation-ID", "<script>alert(1)</script>"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-ID", startsWith("req-")))
                .andReturn();

        String enHeader = resultado.getResponse().getHeader("X-Correlation-ID");
        assertEquals(enHeader, com.jayway.jsonpath.JsonPath.read(
                resultado.getResponse().getContentAsString(), "$.correlationId"));
    }

    @RestController
    @RequestMapping("/prueba")
    static class ControladorDePrueba {

        @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> leer() {
            return Map.of("ok", "si");
        }

        @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> crear(@RequestBody Map<String, Object> cuerpo) {
            return cuerpo;
        }

        @GetMapping("/header")
        String conHeader(@RequestHeader("X-Usuario") String usuario) {
            return usuario;
        }

        @GetMapping("/validado")
        String validado(@RequestParam("cantidad") @Min(value = 1, message = "debe ser al menos 1") int cantidad) {
            return String.valueOf(cantidad);
        }
    }
}
