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
import com.rovedasa.catalogo.model.Persona;
import com.rovedasa.catalogo.model.enums.TipoPersonaEnum;
import com.rovedasa.catalogo.repository.PersonaRepository;
import com.rovedasa.commons.dto.ValidacionGaranteDTO;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Usa el SolvenciaGaranteService y el EvaluadorSolvencia reales (con los valores de RN-07) y simula solo el
 * cliente HTTP del BCRA: así cada caso verifica de punta a punta la regla de solvencia que se aplica.
 */
@ExtendWith(MockitoExtension.class)
class EvaluacionGaranteServiceTest {

    // CUIT ficticio con dígito verificador válido (no corresponde a una persona real)
    private static final String CUIT_GARANTE = "20123456700";
    private static final Long PERSONA_ID = 10L;
    // 13:30 UTC = 10:30 en Argentina
    private static final Clock RELOJ_FIJO = Clock.fixed(Instant.parse("2026-11-12T13:30:00Z"),
            ZoneId.of("America/Argentina/Cordoba"));
    private static final LocalDateTime FECHA_ESPERADA = LocalDateTime.of(2026, 11, 12, 10, 30);

    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private BcraCentralDeudoresClient bcraClient;
    // Mock: TransactionTemplate abre y confirma "transacciones" sobre él, y permite contarlas
    @Mock
    private PlatformTransactionManager transactionManager;

    private EvaluacionGaranteService servicio;

    @BeforeEach
    void prepararServicio() {
        // Valores por defecto de application.yml: situación máxima 2, 0 cheques, múltiplo 3 (no se usa: canon null)
        SolvenciaGaranteService solvencia = new SolvenciaGaranteService(bcraClient,
                new EvaluadorSolvencia(2, 0, new BigDecimal("3")));
        servicio = new EvaluacionGaranteService(personaRepository, solvencia, RELOJ_FIJO, transactionManager);
    }

    @Test
    void garanteSolventeSituacion1SinChequesRegistraLaEvaluacionConLaFechaDelReloj() {
        Persona garante = persona(TipoPersonaEnum.GARANTE, new BigDecimal("2500000"));
        when(bcraClient.obtenerDeudas(CUIT_GARANTE)).thenReturn(Optional.of(deudorConSituacion(1)));
        when(bcraClient.obtenerChequesRechazados(CUIT_GARANTE)).thenReturn(Optional.empty());

        ValidacionGaranteDTO resultado = servicio.evaluar(PERSONA_ID);

        assertTrue(resultado.getEsSolvente());
        assertEquals(PERSONA_ID, resultado.getPersonaId());
        assertEquals(CUIT_GARANTE, resultado.getCuitCuil());
        assertEquals(1, resultado.getSituacionCrediticiaBcra());
        assertEquals(0, resultado.getChequesRechazados());
        assertEquals(new BigDecimal("2500000"), resultado.getIngresosDeclarados());
        assertEquals("DIAZ MARCOS", resultado.getNombreCompleto());
        // Persistido en la persona con la fecha del Clock fijo
        assertEquals(1, garante.getSituacionCrediticiaBcra());
        assertEquals(0, garante.getChequesRechazados());
        assertEquals(FECHA_ESPERADA, garante.getFechaConsultaBcra());
        // Dos transacciones cortas (lectura y escritura); el BCRA queda afuera de ambas
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(2)).commit(any());
    }

    // canonMensual en null: la regla de ingresos no se aplica y la evaluación es exactamente RN-07
    @Test
    void sinIngresosDeclaradosIgualEsSolventePorqueNoSeEvaluanIngresos() {
        persona(TipoPersonaEnum.GARANTE, null);
        when(bcraClient.obtenerDeudas(CUIT_GARANTE)).thenReturn(Optional.empty());
        when(bcraClient.obtenerChequesRechazados(CUIT_GARANTE)).thenReturn(Optional.empty());

        ValidacionGaranteDTO resultado = servicio.evaluar(PERSONA_ID);

        assertTrue(resultado.getEsSolvente());
        assertTrue(resultado.getMotivoEvaluacion().contains("ingresos no evaluados"));
        // Sin registros en el BCRA no hay denominación: se usa el nombre del catálogo
        assertEquals("Marcos Díaz", resultado.getNombreCompleto());
    }

    @Test
    void noSolventePorSituacion3RegistraIgualLaEvaluacion() {
        Persona garante = persona(TipoPersonaEnum.GARANTE, new BigDecimal("2500000"));
        when(bcraClient.obtenerDeudas(CUIT_GARANTE)).thenReturn(Optional.of(deudorConSituacion(3)));
        when(bcraClient.obtenerChequesRechazados(CUIT_GARANTE)).thenReturn(Optional.empty());

        ValidacionGaranteDTO resultado = servicio.evaluar(PERSONA_ID);

        assertFalse(resultado.getEsSolvente());
        assertTrue(resultado.getMotivoEvaluacion().contains("situación crediticia 3"));
        assertEquals(3, garante.getSituacionCrediticiaBcra());
        assertEquals(FECHA_ESPERADA, garante.getFechaConsultaBcra());
    }

    @Test
    void noSolventePorChequesRechazadosRegistraIgualLaEvaluacion() {
        Persona garante = persona(TipoPersonaEnum.GARANTE, new BigDecimal("2500000"));
        when(bcraClient.obtenerDeudas(CUIT_GARANTE)).thenReturn(Optional.of(deudorConSituacion(1)));
        when(bcraClient.obtenerChequesRechazados(CUIT_GARANTE)).thenReturn(Optional.of(chequesRechazados(1)));

        ValidacionGaranteDTO resultado = servicio.evaluar(PERSONA_ID);

        assertFalse(resultado.getEsSolvente());
        assertTrue(resultado.getMotivoEvaluacion().contains("1 cheque(s) rechazado(s)"));
        assertEquals(1, garante.getSituacionCrediticiaBcra());
        assertEquals(1, garante.getChequesRechazados());
    }

    @Test
    void personaInexistenteLanzaRecursoNoEncontradoSinConsultarAlBcra() {
        when(personaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class, () -> servicio.evaluar(99L));

        verifyNoInteractions(bcraClient);
    }

    @Test
    void personaSinRolGaranteLanzaOperacionInvalidaSinConsultarAlBcra() {
        Persona inquilino = persona(TipoPersonaEnum.INQUILINO, new BigDecimal("950000"));

        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class,
                () -> servicio.evaluar(PERSONA_ID));

        assertTrue(ex.getMessage().contains("no tiene el rol GARANTE"));
        verifyNoInteractions(bcraClient);
        assertNull(inquilino.getFechaConsultaBcra());
    }

    @Test
    void bcraCaidoPropagaBcraNoDisponibleYNoGuardaNada() {
        Persona garante = persona(TipoPersonaEnum.GARANTE, new BigDecimal("2500000"));
        when(bcraClient.obtenerDeudas(CUIT_GARANTE)).thenThrow(new ResourceAccessException("Read timed out"));

        assertThrows(BcraNoDisponibleException.class, () -> servicio.evaluar(PERSONA_ID));

        assertNull(garante.getSituacionCrediticiaBcra());
        assertNull(garante.getChequesRechazados());
        assertNull(garante.getFechaConsultaBcra());
        // Solo se abrió la transacción de lectura: la de escritura nunca empezó
        verify(transactionManager, times(1)).getTransaction(any());
        verify(personaRepository, times(1)).findById(PERSONA_ID);
    }

    // --- Datos de prueba ---

    private Persona persona(TipoPersonaEnum rol, BigDecimal ingresos) {
        Persona persona = Persona.builder()
                .cuitCuil(CUIT_GARANTE)
                .nombre("Marcos")
                .apellido("Díaz")
                .roles(Set.of(rol))
                .ingresosMensualesDeclarados(ingresos)
                .build();
        ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));
        return persona;
    }

    private static BcraDeudorDTO deudorConSituacion(int situacion) {
        return BcraDeudorDTO.builder()
                .denominacion("DIAZ MARCOS")
                .periodos(List.of(BcraPeriodoDTO.builder()
                        .periodo("202610")
                        .entidades(List.of(BcraEntidadDeudaDTO.builder().entidad("BANCO").situacion(situacion).build()))
                        .build()))
                .build();
    }

    private static BcraChequesResultadoDTO chequesRechazados(int cantidad) {
        List<BcraChequeDTO> detalle = java.util.stream.IntStream.range(0, cantidad)
                .mapToObj(i -> new BcraChequeDTO())
                .toList();
        return BcraChequesResultadoDTO.builder()
                .causales(List.of(BcraCausalDTO.builder().causal("SIN FONDOS")
                        .entidades(List.of(BcraEntidadChequesDTO.builder().entidad(11).detalle(detalle).build()))
                        .build()))
                .build();
    }
}
