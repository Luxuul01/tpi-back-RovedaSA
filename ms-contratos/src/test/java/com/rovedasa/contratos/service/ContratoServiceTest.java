package com.rovedasa.contratos.service;

import com.rovedasa.contratos.model.EstadoContratoEnum;
import com.rovedasa.commons.dto.PropiedadDTO;
import com.rovedasa.commons.enums.EstadoPropiedadEnum;
import com.rovedasa.commons.exception.OperacionInvalidaException;
import com.rovedasa.commons.exception.RecursoNoEncontradoException;
import com.rovedasa.contratos.client.CatalogoClient;
import com.rovedasa.contratos.dto.ContratoResponse;
import com.rovedasa.contratos.dto.CrearContratoRequest;
import com.rovedasa.contratos.dto.PersonaResumenDTO;
import com.rovedasa.contratos.model.Contrato;
import com.rovedasa.contratos.repository.AjusteRepository;
import com.rovedasa.contratos.repository.ContratoRepository;
import com.rovedasa.contratos.state.EstadoContratoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContratoServiceTest {

    private ContratoRepository contratoRepository;
    private CuotaService cuotaService;
    private CatalogoClient catalogoClient;
    private ContratoService contratoService;

    @BeforeEach
    void setUp() {
        contratoRepository = mock(ContratoRepository.class);
        cuotaService = mock(CuotaService.class);
        catalogoClient = mock(CatalogoClient.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);
        contratoService = new ContratoService(
                contratoRepository,
                mock(AjusteRepository.class),
                cuotaService,
                catalogoClient,
                mock(EstadoContratoFactory.class),
                mock(IndiceService.class),
                clock);
        when(cuotaService.toResumenList(anyList(), any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("ADMINISTRADOR ve todos los contratos")
    void administradorVeTodosLosContratos() {
        when(contratoRepository.findAll()).thenReturn(List.of(
                crearContrato(1L, "sub-prop-1", "sub-inq-1"),
                crearContrato(2L, "sub-prop-2", "sub-inq-2")));

        List<ContratoResponse> resultado = contratoService.listarContratos("ADMINISTRADOR", "sub-admin");

        assertEquals(2, resultado.size());
        verify(contratoRepository).findAll();
        verify(contratoRepository, never()).findByPropietarioKeycloakId(anyString());
        verify(contratoRepository, never()).findByInquilinoKeycloakId(anyString());
    }

    @Test
    @DisplayName("PROPIETARIO solo ve los contratos de su usuario de Keycloak")
    void propietarioSoloVeSusContratos() {
        when(contratoRepository.findByPropietarioKeycloakId("sub-prop-1"))
                .thenReturn(List.of(crearContrato(1L, "sub-prop-1", "sub-inq-1")));

        List<ContratoResponse> resultado = contratoService.listarContratos("PROPIETARIO", "sub-prop-1");

        assertEquals(1, resultado.size());
        assertEquals("sub-prop-1", resultado.get(0).getPropietarioKeycloakId());
        verify(contratoRepository).findByPropietarioKeycloakId("sub-prop-1");
        verify(contratoRepository, never()).findAll();
    }

    @Test
    @DisplayName("INQUILINO solo ve los contratos de su usuario de Keycloak")
    void inquilinoSoloVeSusContratos() {
        when(contratoRepository.findByInquilinoKeycloakId("sub-inq-1"))
                .thenReturn(List.of(crearContrato(1L, "sub-prop-1", "sub-inq-1")));

        List<ContratoResponse> resultado = contratoService.listarContratos("INQUILINO", "sub-inq-1");

        assertEquals(1, resultado.size());
        assertEquals("sub-inq-1", resultado.get(0).getInquilinoKeycloakId());
        verify(contratoRepository).findByInquilinoKeycloakId("sub-inq-1");
        verify(contratoRepository, never()).findAll();
    }

    @Test
    @DisplayName("Un usuario no puede ver el contrato de otro (anti-IDOR)")
    void noPermiteVerContratoDeOtroUsuario() {
        when(contratoRepository.findById(1L))
                .thenReturn(Optional.of(crearContrato(1L, "sub-prop-1", "sub-inq-1")));

        assertThrows(RecursoNoEncontradoException.class,
                () -> contratoService.obtenerContrato(1L, "INQUILINO", "sub-ajeno"));
    }

    @Test
    @DisplayName("El dueño puede ver su propio contrato")
    void permiteVerContratoPropio() {
        when(contratoRepository.findById(1L))
                .thenReturn(Optional.of(crearContrato(1L, "sub-prop-1", "sub-inq-1")));

        ContratoResponse response = contratoService.obtenerContrato(1L, "PROPIETARIO", "sub-prop-1");

        assertEquals(1L, response.getId());
        assertEquals("sub-prop-1", response.getPropietarioKeycloakId());
    }

    @Test
    @DisplayName("Un rol desconocido no puede listar contratos")
    void rolDesconocidoLanzaOperacionInvalida() {
        assertThrows(OperacionInvalidaException.class,
                () -> contratoService.listarContratos("GARANTE", "sub-garante"));
    }

    @Test
    @DisplayName("No permite crear contrato si el garante coincide con el inquilino o el propietario (RN-02)")
    void crearContratoRechazaGaranteRepetidoConPartes() {
        CrearContratoRequest req = CrearContratoRequest.builder()
                .propiedadId(1L)
                .propietarioId(10L)
                .inquilinoId(20L)
                .garanteId(20L)
                .fechaInicio(LocalDate.of(2026, 8, 1))
                .fechaFin(LocalDate.of(2028, 8, 1))
                .frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.10"))
                .diaVencimientoMes(10)
                .build();

        assertThrows(OperacionInvalidaException.class, () -> contratoService.crearContrato(req));
        verify(catalogoClient, never()).obtenerPropiedad(any());
    }

    @Test
    @DisplayName("No permite crear contrato si alguna persona no posee el rol correspondiente (RN-02)")
    void crearContratoRechazaPersonaSinRolRequerido() {
        when(catalogoClient.obtenerPropiedad(1L)).thenReturn(PropiedadDTO.builder()
                .id(1L).propietarioId(10L).estado(EstadoPropiedadEnum.DISPONIBLE).build());
        when(catalogoClient.obtenerPersona(10L)).thenReturn(PersonaResumenDTO.builder()
                .id(10L).keycloakUserId("sub-prop").roles(Set.of("PROPIETARIO")).build());
        when(catalogoClient.obtenerPersona(20L)).thenReturn(PersonaResumenDTO.builder()
                .id(20L).keycloakUserId("sub-inq").roles(Set.of("INQUILINO")).build());
        when(catalogoClient.obtenerPersona(30L)).thenReturn(PersonaResumenDTO.builder()
                .id(30L).keycloakUserId("sub-otro").roles(Set.of("INQUILINO")).build());

        CrearContratoRequest req = CrearContratoRequest.builder()
                .propiedadId(1L)
                .propietarioId(10L)
                .inquilinoId(20L)
                .garanteId(30L)
                .fechaInicio(LocalDate.of(2026, 8, 1))
                .fechaFin(LocalDate.of(2028, 8, 1))
                .frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.10"))
                .diaVencimientoMes(10)
                .build();

        assertThrows(OperacionInvalidaException.class, () -> contratoService.crearContrato(req));
        verify(contratoRepository, never()).save(any());
    }

    private Contrato crearContrato(Long id, String propietarioKeycloakId, String inquilinoKeycloakId) {
        return Contrato.builder()
                .id(id)
                .nroContrato("CONT-TEST" + id)
                .propiedadId(id)
                .propietarioId(10L + id)
                .inquilinoId(20L + id)
                .garanteId(30L + id)
                .propietarioKeycloakId(propietarioKeycloakId)
                .inquilinoKeycloakId(inquilinoKeycloakId)
                .fechaInicio(LocalDate.of(2026, 1, 1))
                .fechaFin(LocalDate.of(2027, 1, 1))
                .frecuenciaAjusteMeses(3)
                .montoInicial(new BigDecimal("500000.00"))
                .montoActual(new BigDecimal("500000.00"))
                .porcentajeMoraDiaria(new BigDecimal("0.1000"))
                .diaVencimientoMes(10)
                .estado(EstadoContratoEnum.ACTIVO)
                .build();
    }
}
