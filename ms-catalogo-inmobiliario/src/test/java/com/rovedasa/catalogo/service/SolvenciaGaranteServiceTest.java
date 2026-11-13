package com.rovedasa.catalogo.service;

import com.rovedasa.catalogo.client.bcra.BcraCentralDeudoresClient;
import com.rovedasa.catalogo.client.bcra.dto.BcraCausalDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraChequeDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraChequesResultadoDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraDeudorDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraEntidadChequesDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraEntidadDeudaDTO;
import com.rovedasa.catalogo.client.bcra.dto.BcraPeriodoDTO;
import com.rovedasa.catalogo.exception.BcraNoDisponibleException;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SolvenciaGaranteServiceTest {

    private static final String CUIT = "20123456789";
    private static final BigDecimal INGRESOS = new BigDecimal("1500000");

    private BcraCentralDeudoresClient bcraClient;
    private SolvenciaGaranteService service;

    @BeforeEach
    void setUp() {
        bcraClient = mock(BcraCentralDeudoresClient.class);
        service = new SolvenciaGaranteService(bcraClient, new EvaluadorSolvencia(2, 0));
    }

    private static BcraDeudorDTO deudor(String periodo, int... situaciones) {
        List<BcraEntidadDeudaDTO> entidades = java.util.Arrays.stream(situaciones)
                .mapToObj(s -> BcraEntidadDeudaDTO.builder().entidad("BANCO").situacion(s).build())
                .toList();
        return BcraDeudorDTO.builder().denominacion("PEREZ JUAN")
                .periodos(List.of(BcraPeriodoDTO.builder().periodo(periodo).entidades(entidades).build()))
                .build();
    }

    @Test
    @DisplayName("Sin deudas ni cheques (404 en ambos): situación 1 y solvente")
    void sinRegistros() {
        when(bcraClient.obtenerDeudas(CUIT)).thenReturn(Optional.empty());
        when(bcraClient.obtenerChequesRechazados(CUIT)).thenReturn(Optional.empty());

        ValidacionGaranteDTO r = service.evaluar(CUIT, INGRESOS);

        assertAll(
                () -> assertTrue(r.getEsSolvente()),
                () -> assertEquals(1, r.getSituacionCrediticiaBcra()),
                () -> assertEquals(0, r.getChequesRechazados()));
    }

    @Test
    @DisplayName("Se toma la peor situación del período más reciente")
    void peorSituacion() {
        when(bcraClient.obtenerDeudas(CUIT)).thenReturn(Optional.of(deudor("202608", 1, 3, 2)));
        when(bcraClient.obtenerChequesRechazados(CUIT)).thenReturn(Optional.empty());

        ValidacionGaranteDTO r = service.evaluar("20-12345678-9", INGRESOS);

        assertAll(
                () -> assertEquals(3, r.getSituacionCrediticiaBcra()),
                () -> assertFalse(r.getEsSolvente()),
                () -> assertEquals("PEREZ JUAN", r.getNombreCompleto()));
    }

    @Test
    @DisplayName("Cuenta todos los cheques rechazados informados")
    void cuentaCheques() {
        BcraChequesResultadoDTO cheques = BcraChequesResultadoDTO.builder()
                .causales(List.of(BcraCausalDTO.builder().causal("SIN FONDOS")
                        .entidades(List.of(BcraEntidadChequesDTO.builder().entidad(11)
                                .detalle(List.of(new BcraChequeDTO(), new BcraChequeDTO())).build()))
                        .build()))
                .build();
        when(bcraClient.obtenerDeudas(CUIT)).thenReturn(Optional.empty());
        when(bcraClient.obtenerChequesRechazados(CUIT)).thenReturn(Optional.of(cheques));

        ValidacionGaranteDTO r = service.evaluar(CUIT, INGRESOS);

        assertAll(
                () -> assertEquals(2, r.getChequesRechazados()),
                () -> assertFalse(r.getEsSolvente()));
    }

    @Test
    @DisplayName("BCRA caído: BcraNoDisponibleException (503)")
    void bcraCaido() {
        when(bcraClient.obtenerDeudas(CUIT)).thenThrow(new ResourceAccessException("Read timed out"));
        assertThrows(BcraNoDisponibleException.class, () -> service.evaluar(CUIT, INGRESOS));
    }

    @Test
    @DisplayName("CUIT con formato inválido: 400")
    void cuitInvalido() {
        assertThrows(OperacionInvalidaException.class, () -> service.evaluar("123", INGRESOS));
    }
}
